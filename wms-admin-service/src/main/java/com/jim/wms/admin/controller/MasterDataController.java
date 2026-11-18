package com.jim.wms.admin.controller;

import com.jim.wms.admin.entity.SkuItem;
import com.jim.wms.admin.entity.Warehouse;
import com.jim.wms.admin.entity.WarehouseArea;
import com.jim.wms.admin.entity.WarehouseLocation;
import com.jim.wms.admin.service.MasterDataService;
import com.jim.wms.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主数据维护
 */
@RestController
@RequestMapping("/api/master")
@RequiredArgsConstructor
public class MasterDataController {

    private final MasterDataService masterDataService;

    @GetMapping("/warehouses")
    public Result<List<Warehouse>> warehouses() {
        return Result.success(masterDataService.listWarehouses());
    }

    @PostMapping("/warehouses")
    public Result<Warehouse> createWarehouse(@RequestBody Warehouse warehouse) {
        return Result.success(masterDataService.createWarehouse(warehouse));
    }

    @GetMapping("/areas")
    public Result<List<WarehouseArea>> areas(@RequestParam String warehouseCode) {
        return Result.success(masterDataService.listAreas(warehouseCode));
    }

    @GetMapping("/locations")
    public Result<List<WarehouseLocation>> locations(@RequestParam String warehouseCode) {
        return Result.success(masterDataService.listLocations(warehouseCode));
    }

    @GetMapping("/skus")
    public Result<List<SkuItem>> skus() {
        return Result.success(masterDataService.listSkus());
    }
}
