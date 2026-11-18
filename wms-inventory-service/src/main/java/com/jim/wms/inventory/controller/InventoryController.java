package com.jim.wms.inventory.controller;

import com.jim.wms.common.result.Result;
import com.jim.wms.inventory.entity.Inventory;
import com.jim.wms.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存查询与对账接口。CTM 定时调用 /on-hand 拉取实物库存校准镜像。
 * 对外路径与返回结构沿用旧单体。
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/query")
    public Result<List<Inventory>> query(@RequestParam String warehouseCode,
                                         @RequestParam String skuCode) {
        return Result.success(inventoryService.queryByWarehouseAndSku(warehouseCode, skuCode));
    }

    /**
     * 对账口径：仓库 + SKU 的实物在库总量
     */
    @GetMapping("/on-hand")
    public Result<BigDecimal> onHand(@RequestParam String warehouseCode,
                                     @RequestParam String skuCode) {
        return Result.success(inventoryService.totalOnHand(warehouseCode, skuCode));
    }

    @PostMapping("/freeze")
    public Result<Void> freeze(@RequestBody FreezeRequest request) {
        inventoryService.freeze(request.getWarehouseCode(), request.getLocationCode(),
                request.getSkuCode(), request.getBatchNo(), request.getQty());
        return Result.success();
    }

    @lombok.Data
    public static class FreezeRequest {
        private String warehouseCode;
        private String locationCode;
        private String skuCode;
        private String batchNo;
        private BigDecimal qty;
    }
}
