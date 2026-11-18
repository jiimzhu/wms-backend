package com.jim.wms.common.api;

import com.jim.wms.common.result.Result;
import com.jim.wms.common.dto.LocationView;
import com.jim.wms.common.dto.SkuView;
import com.jim.wms.common.dto.WarehouseView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 主数据服务内部契约。
 */
@FeignClient(name = "wms-admin-service", path = "/internal/admin")
public interface AdminApi {

    @GetMapping("/warehouse/{code}")
    Result<WarehouseView> getWarehouse(@PathVariable("code") String code);

    @GetMapping("/location/{code}")
    Result<LocationView> getLocation(@PathVariable("code") String code,
                                     @RequestParam(value = "warehouseCode", required = false) String warehouseCode);

    @GetMapping("/sku/{skuCode}")
    Result<SkuView> getSku(@PathVariable("skuCode") String skuCode);
}
