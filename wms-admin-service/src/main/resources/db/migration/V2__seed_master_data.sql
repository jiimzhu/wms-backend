-- 本地联调用基础主数据（仅 admin 归属的 4 张表，字段值与旧单体种子一致）
INSERT INTO wms_warehouse (warehouse_code, warehouse_name, warehouse_type, enable)
VALUES ('DC01', '上海主仓', 1, 1),
       ('DC02', '广州主仓', 1, 1);

INSERT INTO wms_area (warehouse_code, area_code, area_name, area_type)
VALUES ('DC01', 'RCV', '收货区', 1),
       ('DC01', 'STO', '存储区', 2),
       ('DC01', 'PICK', '拣货区', 3),
       ('DC01', 'NG', '不合格区', 4);

INSERT INTO wms_location (warehouse_code, area_code, location_code, location_type, capacity, enable)
VALUES ('DC01', 'RCV', 'DC01-RCV-01', 1, 9999, 1),
       ('DC01', 'STO', 'DC01-A-01-01', 2, 500, 1),
       ('DC01', 'STO', 'DC01-A-01-02', 2, 500, 1),
       ('DC01', 'PICK', 'DC01-P-01', 3, 200, 1),
       ('DC01', 'NG', 'DC01-NG-01', 4, 500, 1);

INSERT INTO wms_sku (sku_code, sku_name, unit, batch_manage, expire_manage, sn_manage, enable)
VALUES ('SKU-001', '测试商品A', 'EA', 1, 1, 0, 1),
       ('SKU-002', '测试商品B', 'EA', 0, 0, 0, 1);
