package com.jim.wms.stocktake.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.common.api.InventoryApi;
import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.result.Result;
import com.jim.wms.common.util.IdGenerator;
import com.jim.wms.stocktake.entity.StocktakeItem;
import com.jim.wms.stocktake.entity.StocktakeOrder;
import com.jim.wms.stocktake.mapper.StocktakeItemMapper;
import com.jim.wms.stocktake.mapper.StocktakeOrderMapper;
import com.jim.wms.stocktake.service.StocktakeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StocktakeServiceImpl implements StocktakeService {

    private final StocktakeOrderMapper orderMapper;
    private final StocktakeItemMapper itemMapper;
    private final InventoryApi inventoryApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StocktakeOrder create(StocktakeOrder order) {
        order.setStocktakeNo(IdGenerator.nextNo("ST"));
        order.setStatus("CREATED");
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        // 切断旧单体对库存台账 Mapper 的跨库直连：改为 Feign 按仓拉取库存台账生成盘点行
        List<InventoryView> inventories = listInventoryOrThrow(order.getWarehouseCode());

        orderMapper.insert(order);
        for (InventoryView inventory : inventories) {
            StocktakeItem item = new StocktakeItem();
            item.setStocktakeNo(order.getStocktakeNo());
            item.setWarehouseCode(inventory.getWarehouseCode());
            item.setLocationCode(inventory.getLocationCode());
            item.setSkuCode(inventory.getSkuCode());
            item.setBatchNo(inventory.getBatchNo());
            item.setBookQty(inventory.getQty());
            itemMapper.insert(item);
        }
        log.info("盘点任务 {} 生成盘点行 {} 条", order.getStocktakeNo(), inventories.size());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(String stocktakeNo, String locationCode, String skuCode, BigDecimal actualQty) {
        StocktakeItem item = itemMapper.selectOne(Wrappers.<StocktakeItem>lambdaQuery()
                .eq(StocktakeItem::getStocktakeNo, stocktakeNo)
                .eq(StocktakeItem::getLocationCode, locationCode)
                .eq(StocktakeItem::getSkuCode, skuCode));
        if (item == null) {
            throw new BusinessException(404, "盘点行不存在: " + skuCode + "@" + locationCode);
        }
        item.setActualQty(actualQty);
        item.setDiffQty(actualQty.subtract(item.getBookQty() == null ? BigDecimal.ZERO : item.getBookQty()));
        itemMapper.updateById(item);

        StocktakeOrder order = orderMapper.selectOne(Wrappers.<StocktakeOrder>lambdaQuery()
                .eq(StocktakeOrder::getStocktakeNo, stocktakeNo));
        order.setStatus("COUNTING");
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(String stocktakeNo) {
        StocktakeOrder order = orderMapper.selectOne(Wrappers.<StocktakeOrder>lambdaQuery()
                .eq(StocktakeOrder::getStocktakeNo, stocktakeNo));
        if (order == null) {
            throw new BusinessException(404, "盘点单不存在: " + stocktakeNo);
        }
        List<StocktakeItem> items = itemMapper.selectList(Wrappers.<StocktakeItem>lambdaQuery()
                .eq(StocktakeItem::getStocktakeNo, stocktakeNo));
        for (StocktakeItem item : items) {
            BigDecimal diff = item.getDiffQty() == null ? BigDecimal.ZERO : item.getDiffQty();
            if (diff.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            String requestId = "ST:" + stocktakeNo + ":" + item.getSkuCode() + ":" + item.getLocationCode()
                    + ":" + safeBatch(item.getBatchNo());
            StockChangeCmd cmd = StockChangeCmd.builder()
                    .warehouseCode(item.getWarehouseCode())
                    .locationCode(item.getLocationCode())
                    .skuCode(item.getSkuCode())
                    .batchNo(item.getBatchNo())
                    .changeQty(diff)
                    .businessType("STOCKTAKE_ADJ")
                    .businessNo(stocktakeNo)
                    .remark("盘点差异调整，账面=" + item.getBookQty() + "，实盘=" + item.getActualQty())
                    .requestId(requestId)
                    .build();
            changeStockOrThrow(cmd);
        }
        order.setStatus("ADJUSTED");
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    /**
     * Feign 按仓拉取台账；库存服务不可用或返回业务失败时，记录单号/仓/原因并抛出明确业务错误，
     * 不允许「本地盘点单已生成、台账数据静默丢失」。
     */
    private List<InventoryView> listInventoryOrThrow(String warehouseCode) {
        Result<List<InventoryView>> result;
        try {
            result = inventoryApi.list(warehouseCode);
        } catch (Exception e) {
            log.error("库存服务调用异常，盘点建单拉取台账失败 warehouseCode={}", warehouseCode, e);
            throw new BusinessException(500, "库存服务调用失败，无法生成盘点行: " + e.getMessage());
        }
        if (result == null || result.getCode() == null || result.getCode() != 200) {
            String msg = result == null ? "库存服务无响应" : result.getMsg();
            log.error("库存服务拉取台账失败 warehouseCode={}, code={}, msg={}", warehouseCode,
                    result == null ? null : result.getCode(), msg);
            throw new BusinessException(result == null || result.getCode() == null ? 500 : result.getCode(),
                    "库存台账拉取失败: " + msg);
        }
        return result.getData() == null ? List.of() : result.getData();
    }

    /**
     * 库存变动结果按 body.code 判定（不依赖 HTTP 状态码）；失败时记录单号/SKU/库位/批次/数量并抛出，
     * 本地单据状态不置 ADJUSTED。重试入口：对同一 stocktakeNo 重新调用 /api/stocktake/approve，
     * requestId 由单号+SKU+库位+批次确定性生成，库存侧按 biz_key 幂等，重复提交不重复调整。
     */
    private void changeStockOrThrow(StockChangeCmd cmd) {
        Result<InventoryView> result;
        try {
            result = inventoryApi.changeStock(cmd);
        } catch (Exception e) {
            log.error("库存变动调用异常 businessNo={}, sku={}, location={}, batch={}, qty={}",
                    cmd.getBusinessNo(), cmd.getSkuCode(), cmd.getLocationCode(), cmd.getBatchNo(),
                    cmd.getChangeQty(), e);
            throw new BusinessException(500, "库存服务调用失败，盘点差异未调整: " + e.getMessage());
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

    private String safeBatch(String batchNo) {
        return batchNo == null || batchNo.isBlank() ? "NOBATCH" : batchNo;
    }
}
