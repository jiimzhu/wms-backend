package com.jim.wms.inventory.service;

import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.inventory.entity.Inventory;

import java.math.BigDecimal;
import java.util.List;

public interface InventoryService {

    /**
     * 库存变动唯一入口：加锁 -> 幂等判定 -> 扣加台账 -> 写流水 -> 推送 Kafka 事件。
     * requestId 非空时按 biz_key 幂等，重复请求直接返回当前库存，不重复扣减。
     */
    Inventory changeStock(StockChangeCmd cmd);

    /**
     * 冻结 / 解冻（CTM 计算 ATP 时可参考冻结量，但冻结逻辑仍只在 WMS 内）
     */
    void freeze(String warehouseCode, String locationCode, String skuCode, String batchNo, BigDecimal qty);

    List<Inventory> queryByWarehouseAndSku(String warehouseCode, String skuCode);

    BigDecimal totalOnHand(String warehouseCode, String skuCode);

    /** 按仓库列出全部库存，warehouseCode 可空表示全仓 */
    List<Inventory> list(String warehouseCode);
}
