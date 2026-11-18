package com.jim.wms.inventory.controller;

import com.jim.wms.common.dto.FreezeCmd;
import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.common.result.Result;
import com.jim.wms.inventory.converter.InventoryViewConverter;
import com.jim.wms.inventory.entity.Inventory;
import com.jim.wms.inventory.service.InventoryService;
import jakarta.validation.Valid;
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
 * 库存内部契约（对齐 wms-common 的 InventoryApi），供其他服务 Feign 调用。
 * 库存实物唯一写者是本服务，其他服务一律通过本接口扣加库存。
 */
@RestController
@RequestMapping("/internal/inventory")
@RequiredArgsConstructor
public class InternalInventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/change-stock")
    public Result<InventoryView> changeStock(@Valid @RequestBody StockChangeCmd cmd) {
        Inventory inventory = inventoryService.changeStock(cmd);
        return Result.success(InventoryViewConverter.toView(inventory));
    }

    @PostMapping("/freeze")
    public Result<Void> freeze(@Valid @RequestBody FreezeCmd cmd) {
        inventoryService.freeze(cmd.getWarehouseCode(), cmd.getLocationCode(),
                cmd.getSkuCode(), cmd.getBatchNo(), cmd.getQty());
        return Result.success();
    }

    @GetMapping("/query")
    public Result<List<InventoryView>> query(@RequestParam("warehouseCode") String warehouseCode,
                                              @RequestParam("skuCode") String skuCode) {
        List<InventoryView> views = inventoryService.queryByWarehouseAndSku(warehouseCode, skuCode).stream()
                .map(InventoryViewConverter::toView)
                .toList();
        return Result.success(views);
    }

    @GetMapping("/total-on-hand")
    public Result<BigDecimal> totalOnHand(@RequestParam("warehouseCode") String warehouseCode,
                                          @RequestParam("skuCode") String skuCode) {
        return Result.success(inventoryService.totalOnHand(warehouseCode, skuCode));
    }

    @GetMapping("/list")
    public Result<List<InventoryView>> list(@RequestParam(value = "warehouseCode", required = false) String warehouseCode) {
        List<InventoryView> views = inventoryService.list(warehouseCode).stream()
                .map(InventoryViewConverter::toView)
                .toList();
        return Result.success(views);
    }
}
