package com.jim.wms.common.api;

import com.jim.wms.common.dto.FreezeCmd;
import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存服务内部契约。库存实物唯一写者是 wms-inventory-service，其他服务一律通过本接口 Feign 调用。
 */
@FeignClient(name = "wms-inventory-service", path = "/internal/inventory")
public interface InventoryApi {

    /** 库存变动（加锁 -> 扣加台账 -> 写流水），requestId 幂等 */
    @PostMapping("/change-stock")
    Result<InventoryView> changeStock(@RequestBody StockChangeCmd cmd);

    /** 冻结 / 解冻 */
    @PostMapping("/freeze")
    Result<Void> freeze(@RequestBody FreezeCmd cmd);

    /** 按仓库 + SKU 查询库存台账 */
    @GetMapping("/query")
    Result<List<InventoryView>> query(@RequestParam("warehouseCode") String warehouseCode,
                                    @RequestParam("skuCode") String skuCode);

    /** 仓库 + SKU 实物在库总量 */
    @GetMapping("/total-on-hand")
    Result<BigDecimal> totalOnHand(@RequestParam("warehouseCode") String warehouseCode,
                                   @RequestParam("skuCode") String skuCode);

    /** 按仓库列出全部库存（报表聚合用），warehouseCode 可空表示全仓 */
    @GetMapping("/list")
    Result<List<InventoryView>> list(@RequestParam(value = "warehouseCode", required = false) String warehouseCode);
}
