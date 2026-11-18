package com.jim.wms.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.admin.entity.SkuItem;
import com.jim.wms.admin.entity.Warehouse;
import com.jim.wms.admin.entity.WarehouseArea;
import com.jim.wms.admin.entity.WarehouseLocation;
import com.jim.wms.admin.mapper.SkuItemMapper;
import com.jim.wms.admin.mapper.WarehouseAreaMapper;
import com.jim.wms.admin.mapper.WarehouseLocationMapper;
import com.jim.wms.admin.mapper.WarehouseMapper;
import com.jim.wms.admin.service.MasterDataService;
import com.jim.wms.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MasterDataServiceImpl implements MasterDataService {

    private final WarehouseMapper warehouseMapper;
    private final WarehouseAreaMapper warehouseAreaMapper;
    private final WarehouseLocationMapper warehouseLocationMapper;
    private final SkuItemMapper skuItemMapper;

    @Override
    public List<Warehouse> listWarehouses() {
        return warehouseMapper.selectList(
                Wrappers.<Warehouse>lambdaQuery().orderByAsc(Warehouse::getWarehouseCode));
    }

    @Override
    public Warehouse createWarehouse(Warehouse warehouse) {
        if (warehouse.getWarehouseCode() == null || warehouse.getWarehouseCode().isBlank()) {
            throw new BusinessException(400, "仓库编码不能为空");
        }
        long exists = warehouseMapper.selectCount(
                Wrappers.<Warehouse>lambdaQuery().eq(Warehouse::getWarehouseCode, warehouse.getWarehouseCode()));
        if (exists > 0) {
            throw new BusinessException(409, "仓库编码已存在: " + warehouse.getWarehouseCode());
        }
        warehouse.setCreateTime(LocalDateTime.now());
        warehouse.setUpdateTime(LocalDateTime.now());
        if (warehouse.getEnable() == null) {
            warehouse.setEnable(1);
        }
        warehouseMapper.insert(warehouse);
        return warehouse;
    }

    @Override
    public List<WarehouseArea> listAreas(String warehouseCode) {
        return warehouseAreaMapper.selectList(
                Wrappers.<WarehouseArea>lambdaQuery().eq(WarehouseArea::getWarehouseCode, warehouseCode));
    }

    @Override
    public List<WarehouseLocation> listLocations(String warehouseCode) {
        return warehouseLocationMapper.selectList(
                Wrappers.<WarehouseLocation>lambdaQuery().eq(WarehouseLocation::getWarehouseCode, warehouseCode));
    }

    @Override
    public List<SkuItem> listSkus() {
        return skuItemMapper.selectList(Wrappers.<SkuItem>lambdaQuery());
    }

    @Override
    public SkuItem getSku(String skuCode) {
        SkuItem sku = skuItemMapper.selectOne(
                Wrappers.<SkuItem>lambdaQuery().eq(SkuItem::getSkuCode, skuCode));
        if (sku == null) {
            throw new BusinessException(404, "SKU 不存在: " + skuCode);
        }
        return sku;
    }

    @Override
    public Warehouse getWarehouse(String code) {
        Warehouse warehouse = warehouseMapper.selectOne(
                Wrappers.<Warehouse>lambdaQuery().eq(Warehouse::getWarehouseCode, code));
        if (warehouse == null) {
            throw new BusinessException(404, "仓库不存在: " + code);
        }
        return warehouse;
    }

    @Override
    public WarehouseLocation getLocation(String code, String warehouseCode) {
        var wrapper = Wrappers.<WarehouseLocation>lambdaQuery()
                .eq(WarehouseLocation::getLocationCode, code);
        if (warehouseCode != null && !warehouseCode.isBlank()) {
            wrapper.eq(WarehouseLocation::getWarehouseCode, warehouseCode);
        }
        WarehouseLocation location = warehouseLocationMapper.selectOne(wrapper);
        if (location == null) {
            throw new BusinessException(404, "库位不存在: " + code);
        }
        return location;
    }
}
