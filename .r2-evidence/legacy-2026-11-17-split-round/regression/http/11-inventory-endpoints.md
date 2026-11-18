# wms-inventory-service 实际端点清单（源码核对 + 运行探测）

## 对外 /api/inventory（对齐 MONOLITH-BASELINE 端点 6/7/8）
- GET  /api/inventory/query?warehouseCode=&skuCode=   Result<List<Inventory>>
- GET  /api/inventory/on-hand?warehouseCode=&skuCode= Result<BigDecimal>
- POST /api/inventory/freeze                          Result<Void>

## 内部 /internal/inventory（Feign 契约，对齐 wms-common InventoryApi）
- POST   /internal/inventory/change-stock
- POST   /internal/inventory/freeze
- GET    /internal/inventory/query?warehouseCode=&skuCode=
- GET    /internal/inventory/total-on-hand?warehouseCode=&skuCode=
- GET    /internal/inventory/list?warehouseCode=

## 核对结论
基线（MONOLITH-BASELINE.md 2.2 节）对外仅 query/on-hand/freeze 三个端点，
源码 Grep stock-history / stockHistory 为 0 命中；无 /api/inventory/stock-history 端点。
新工程对外端点集合与基线逐条一致，无缺失、无多余对外端点。