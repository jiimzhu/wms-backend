package com.jim.wms.admin.controller;

import com.jim.wms.admin.entity.SkuItem;
import com.jim.wms.admin.entity.Warehouse;
import com.jim.wms.admin.entity.WarehouseLocation;
import com.jim.wms.admin.service.MasterDataService;
import com.jim.wms.common.dto.LocationView;
import com.jim.wms.common.dto.SkuView;
import com.jim.wms.common.dto.WarehouseView;
import com.jim.wms.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主数据内部契约（对齐 wms-common 的 AdminApi），供其他服务 Feign 调用。
 * 不存在时抛 BusinessException(404, ...)，由全局异常处理器转成 body.code=404。
 */
@RestController
@RequestMapping("/internal/admin")
@RequiredArgsConstructor
public class InternalAdminController {

    private final MasterDataService masterDataService;

    @GetMapping("/warehouse/{code}")
    public Result<WarehouseView> getWarehouse(@PathVariable("code") String code) {
        Warehouse warehouse = masterDataService.getWarehouse(code);
        WarehouseView view = new WarehouseView();
        view.setWarehouseCode(warehouse.getWarehouseCode());
        view.setWarehouseName(warehouse.getWarehouseName());
        view.setWarehouseType(warehouse.getWarehouseType());
        view.setEnable(warehouse.getEnable());
        return Result.success(view);
    }

    @GetMapping("/location/{code}")
    public Result<LocationView> getLocation(@PathVariable("code") String code,
                                            @RequestParam(value = "warehouseCode", required = false) String warehouseCode) {
        WarehouseLocation location = masterDataService.getLocation(code, warehouseCode);
        LocationView view = new LocationView();
        view.setWarehouseCode(location.getWarehouseCode());
        view.setAreaCode(location.getAreaCode());
        view.setLocationCode(location.getLocationCode());
        view.setLocationType(location.getLocationType());
        view.setCapacity(location.getCapacity());
        view.setEnable(location.getEnable());
        return Result.success(view);
    }

    @GetMapping("/sku/{skuCode}")
    public Result<SkuView> getSku(@PathVariable("skuCode") String skuCode) {
        SkuItem sku = masterDataService.getSku(skuCode);
        SkuView view = new SkuView();
        view.setSkuCode(sku.getSkuCode());
        view.setSkuName(sku.getSkuName());
        view.setUnit(sku.getUnit());
        view.setBatchManage(sku.getBatchManage());
        view.setExpireManage(sku.getExpireManage());
        view.setSnManage(sku.getSnManage());
        view.setEnable(sku.getEnable());
        return Result.success(view);
    }
}
