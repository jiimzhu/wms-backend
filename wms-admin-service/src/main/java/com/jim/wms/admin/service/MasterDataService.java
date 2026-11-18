package com.jim.wms.admin.service;

import com.jim.wms.admin.entity.SkuItem;
import com.jim.wms.admin.entity.Warehouse;
import com.jim.wms.admin.entity.WarehouseArea;
import com.jim.wms.admin.entity.WarehouseLocation;

import java.util.List;

public interface MasterDataService {

    List<Warehouse> listWarehouses();

    Warehouse createWarehouse(Warehouse warehouse);

    List<WarehouseArea> listAreas(String warehouseCode);

    List<WarehouseLocation> listLocations(String warehouseCode);

    List<SkuItem> listSkus();

    SkuItem getSku(String skuCode);

    Warehouse getWarehouse(String code);

    WarehouseLocation getLocation(String code, String warehouseCode);
}
