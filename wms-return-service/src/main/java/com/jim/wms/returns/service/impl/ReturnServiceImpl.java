package com.jim.wms.returns.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.common.api.InventoryApi;
import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.result.Result;
import com.jim.wms.common.util.IdGenerator;
import com.jim.wms.returns.entity.ReturnOrder;
import com.jim.wms.returns.mapper.ReturnOrderMapper;
import com.jim.wms.returns.service.ReturnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnServiceImpl implements ReturnService {

    private final ReturnOrderMapper orderMapper;
    private final InventoryApi inventoryApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnOrder create(ReturnOrder order) {
        order.setReturnNo(IdGenerator.nextNo("RT"));
        order.setStatus("CREATED");
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inspect(String returnNo, String disposition) {
        List<String> allowed = List.of("SALEABLE", "DEFECT", "SCRAP");
        if (!allowed.contains(disposition)) {
            throw new BusinessException(400, "不支持的退货处置类型: " + disposition);
        }
        ReturnOrder order = require(returnNo);
        order.setDisposition(disposition);
        order.setStatus("INSPECTED");
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void putaway(String returnNo, String locationCode) {
        ReturnOrder order = require(returnNo);
        if (!"INSPECTED".equals(order.getStatus())) {
            throw new BusinessException(400, "请先完成退货质检");
        }
        if ("SCRAP".equals(order.getDisposition())) {
            order.setStatus("SCRAPPED");
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
            return;
        }
        // 沿用旧单体口径：SALEABLE 与 DEFECT 两个分支 businessType 同为 RETURN_IN（基线 T-8b，未擅自改为残次不入账）
        String businessType = "RETURN_IN";
        String requestId = "RT:" + returnNo + ":" + order.getSkuCode() + ":" + locationCode
                + ":" + safeBatch(order.getBatchNo());
        StockChangeCmd cmd = StockChangeCmd.builder()
                .warehouseCode(order.getWarehouseCode())
                .locationCode(locationCode)
                .skuCode(order.getSkuCode())
                .batchNo(order.getBatchNo())
                .changeQty(order.getQty())
                .businessType(businessType)
                .businessNo(returnNo)
                .remark("退货入库，处置=" + order.getDisposition())
                .requestId(requestId)
                .build();
        changeStockOrThrow(cmd);
        order.setStatus("IN_STOCK");
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    /**
     * 库存变动结果按 body.code 判定（不依赖 HTTP 状态码）；失败时记录单号/SKU/库位/批次/数量并抛出，
     * 本地单据状态不置 IN_STOCK。重试入口：对同一 returnNo 重新调用 /api/return/putaway，
     * requestId 由单号+SKU+库位+批次确定性生成，库存侧按 biz_key 幂等，重复提交不重复入库。
     */
    private void changeStockOrThrow(StockChangeCmd cmd) {
        Result<InventoryView> result;
        try {
            result = inventoryApi.changeStock(cmd);
        } catch (Exception e) {
            log.error("库存变动调用异常 businessNo={}, sku={}, location={}, batch={}, qty={}",
                    cmd.getBusinessNo(), cmd.getSkuCode(), cmd.getLocationCode(), cmd.getBatchNo(),
                    cmd.getChangeQty(), e);
            throw new BusinessException(500, "库存服务调用失败，退货未回流入库: " + e.getMessage());
        }
        if (result == null || result.getCode() == null || result.getCode() != 200) {
            String msg = result == null ? "库存服务无响应" : result.getMsg();
            log.error("库存变动业务失败 businessNo={}, sku={}, location={}, batch={}, qty={}, code={}, msg={}",
                    cmd.getBusinessNo(), cmd.getSkuCode(), cmd.getLocationCode(), cmd.getBatchNo(),
                    cmd.getChangeQty(), result == null ? null : result.getCode(), msg);
            throw new BusinessException(result == null || result.getCode() == null ? 500 : result.getCode(),
                    "库存变动失败: " + msg);
        }
    }

    private ReturnOrder require(String returnNo) {
        ReturnOrder order = orderMapper.selectOne(Wrappers.<ReturnOrder>lambdaQuery()
                .eq(ReturnOrder::getReturnNo, returnNo));
        if (order == null) {
            throw new BusinessException(404, "退货单不存在: " + returnNo);
        }
        return order;
    }

    private String safeBatch(String batchNo) {
        return batchNo == null || batchNo.isBlank() ? "NOBATCH" : batchNo;
    }
}
