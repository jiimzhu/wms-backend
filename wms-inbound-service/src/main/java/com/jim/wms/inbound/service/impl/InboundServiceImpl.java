package com.jim.wms.inbound.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.common.api.InventoryApi;
import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.outbox.OutboxWriter;
import com.jim.wms.common.result.Result;
import com.jim.wms.common.util.IdGenerator;
import com.jim.wms.common.event.WmsEventTopics;
import com.jim.wms.inbound.entity.InboundOrder;
import com.jim.wms.inbound.entity.InboundOrderItem;
import com.jim.wms.inbound.mapper.InboundOrderItemMapper;
import com.jim.wms.inbound.mapper.InboundOrderMapper;
import com.jim.wms.inbound.service.InboundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InboundServiceImpl implements InboundService {

    /** 批次为空时 requestId 占位 */
    private static final String NO_BATCH = "NOBATCH";

    /** 新增错误码：下游库存服务不可用或业务拒绝（旧单体无跨服务场景，本服务新增） */
    private static final int CODE_INVENTORY_UNAVAILABLE = 502;

    private final InboundOrderMapper orderMapper;
    private final InboundOrderItemMapper itemMapper;
    private final InventoryApi inventoryApi;
    private final OutboxWriter outboxWriter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundOrder create(InboundOrder order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            throw new BusinessException(400, "入库单明细不能为空");
        }
        order.setInboundNo(IdGenerator.nextNo("IN"));
        order.setStatus("CREATED");
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);
        for (InboundOrderItem item : order.getItems()) {
            item.setInboundNo(order.getInboundNo());
            item.setReceivedQty(BigDecimal.ZERO);
            item.setPutawayQty(BigDecimal.ZERO);
            item.setQcStatus("PENDING");
            itemMapper.insert(item);
        }
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(String inboundNo, String skuCode, BigDecimal qty) {
        InboundOrder order = requireOrder(inboundNo);
        InboundOrderItem item = requireItem(inboundNo, skuCode);
        BigDecimal received = nvl(item.getReceivedQty()).add(qty);
        if (received.compareTo(nvl(item.getExpectQty())) > 0) {
            throw new BusinessException(400, "实收数量超过预期数量");
        }
        item.setReceivedQty(received);
        itemMapper.updateById(item);
        order.setStatus("RECEIVED");
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void qc(String inboundNo, String skuCode, String result) {
        InboundOrderItem item = requireItem(inboundNo, skuCode);
        if (!"PASS".equals(result) && !"REJECT".equals(result)) {
            throw new BusinessException(400, "质检结论只能是 PASS 或 REJECT");
        }
        item.setQcStatus(result);
        itemMapper.updateById(item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void putaway(String inboundNo, String skuCode, String locationCode, BigDecimal qty) {
        InboundOrder order = requireOrder(inboundNo);
        InboundOrderItem item = requireItem(inboundNo, skuCode);
        if ("REJECT".equals(item.getQcStatus())) {
            throw new BusinessException(400, "质检不合格，不允许正常上架到可售库位");
        }
        if (nvl(item.getPutawayQty()).add(qty).compareTo(nvl(item.getReceivedQty())) > 0) {
            throw new BusinessException(400, "上架数量超过实收数量");
        }

        String businessType = switch (order.getInboundType() == null ? "" : order.getInboundType()) {
            case "TRANSFER" -> "TRANSFER_IN";
            case "RETURN" -> "RETURN_IN";
            default -> "PURCHASE_IN";
        };

        // 先远程加库存（requestId 幂等，重复提交不重复入库）；失败时本地事务回滚，单据与明细保持原状态可重试
        String requestId = buildRequestId(inboundNo, skuCode, locationCode, item.getBatchNo());
        StockChangeCmd cmd = StockChangeCmd.builder()
                .warehouseCode(order.getWarehouseCode())
                .locationCode(locationCode)
                .skuCode(skuCode)
                .batchNo(item.getBatchNo())
                .changeQty(qty)
                .businessType(businessType)
                .businessNo(inboundNo)
                .remark("上架")
                .requestId(requestId)
                .build();
        Result<InventoryView> result;
        try {
            result = inventoryApi.changeStock(cmd);
        } catch (Exception e) {
            log.error("上架加库存 Feign 调用异常, inboundNo={}, skuCode={}, requestId={}", inboundNo, skuCode, requestId, e);
            throw new BusinessException(CODE_INVENTORY_UNAVAILABLE,
                    "库存服务调用失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result == null || !Integer.valueOf(200).equals(result.getCode())) {
            String msg = result == null ? "库存服务无响应" : result.getMsg();
            log.error("上架加库存业务拒绝, inboundNo={}, skuCode={}, requestId={}, code={}, msg={}",
                    inboundNo, skuCode, requestId, result == null ? null : result.getCode(), msg);
            throw new BusinessException(CODE_INVENTORY_UNAVAILABLE, "库存服务变动失败: " + msg);
        }

        item.setPutawayQty(nvl(item.getPutawayQty()).add(qty));
        item.setLocationCode(locationCode);
        itemMapper.updateById(item);
        order.setStatus("PUTAWAY");
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);

        List<InboundOrderItem> items = listItems(inboundNo);
        boolean allDone = items.stream()
                .allMatch(i -> nvl(i.getPutawayQty()).compareTo(nvl(i.getReceivedQty())) >= 0);
        if (allDone) {
            order.setStatus("FINISHED");
            orderMapper.updateById(order);
            outboxWriter.write(WmsEventTopics.TOPIC_ORDER_STATUS, inboundNo,
                    Map.of("orderNo", inboundNo, "status", "FINISHED", "message", "入库完成"));
            log.info("入库单完成，inboundNo={}", inboundNo);
        }
    }

    private InboundOrder requireOrder(String inboundNo) {
        InboundOrder order = orderMapper.selectOne(
                Wrappers.<InboundOrder>lambdaQuery().eq(InboundOrder::getInboundNo, inboundNo));
        if (order == null) {
            throw new BusinessException(404, "入库单不存在: " + inboundNo);
        }
        return order;
    }

    private InboundOrderItem requireItem(String inboundNo, String skuCode) {
        InboundOrderItem item = itemMapper.selectOne(Wrappers.<InboundOrderItem>lambdaQuery()
                .eq(InboundOrderItem::getInboundNo, inboundNo)
                .eq(InboundOrderItem::getSkuCode, skuCode));
        if (item == null) {
            throw new BusinessException(404, "入库明细不存在: " + inboundNo + "/" + skuCode);
        }
        return item;
    }

    private List<InboundOrderItem> listItems(String inboundNo) {
        return itemMapper.selectList(
                Wrappers.<InboundOrderItem>lambdaQuery().eq(InboundOrderItem::getInboundNo, inboundNo));
    }

    /**
     * 幂等键（biz_key）：IN:{inboundNo}:{skuCode}:{locationCode}:{batchNo或NOBATCH}
     */
    private String buildRequestId(String inboundNo, String skuCode, String locationCode, String batchNo) {
        String batch = batchNo == null || batchNo.isBlank() ? NO_BATCH : batchNo;
        return "IN:" + inboundNo + ":" + skuCode + ":" + locationCode + ":" + batch;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
