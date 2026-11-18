package com.jim.wms.inventory.converter;

import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.inventory.entity.Inventory;

/**
 * 库存实体 -> InventoryView（字段与 common DTO 一致）。
 */
public final class InventoryViewConverter {

    private InventoryViewConverter() {
    }

    public static InventoryView toView(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryView view = new InventoryView();
        view.setWarehouseCode(inventory.getWarehouseCode());
        view.setLocationCode(inventory.getLocationCode());
        view.setSkuCode(inventory.getSkuCode());
        view.setBatchNo(inventory.getBatchNo());
        view.setExpireDate(inventory.getExpireDate());
        view.setSnCode(inventory.getSnCode());
        view.setQty(inventory.getQty());
        view.setFreezeQty(inventory.getFreezeQty());
        view.setStockStatus(inventory.getStockStatus());
        return view;
    }
}
