package com.jim.wms.report.controller;

import com.jim.wms.common.api.InventoryApi;
import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.result.Result;
import com.jim.wms.report.vo.StockSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础报表：库存汇总。CTM 对账也可复用该口径。
 *
 * <p>拆分后本服务不持有任何库存表 / Mapper，库存台账全部通过 Feign 调
 * {@link InventoryApi#list(String)} 取数，在内存中按 仓库|SKU 聚合，口径与旧单体一致：
 * onHandQty=在库 qty 求和，freezeQty=冻结 freezeQty 求和，availableQty=onHandQty-freezeQty。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final InventoryApi inventoryApi;

    @GetMapping("/stock-summary")
    public Result<List<StockSummaryVO>> stockSummary(@RequestParam(required = false) String warehouseCode) {
        Result<List<InventoryView>> resp;
        try {
            resp = inventoryApi.list(warehouseCode);
        } catch (Exception e) {
            // 库存服务未注册 / 网络不通 / 负载均衡无实例：明确报错，不静默返回空汇总
            log.error("Feign 调用库存服务失败, warehouseCode={}", warehouseCode, e);
            throw new BusinessException(503, "库存服务暂时不可用，请稍后重试: " + e.getClass().getSimpleName());
        }
        if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
            int code = resp == null || resp.getCode() == null ? 503 : resp.getCode();
            String msg = resp == null || resp.getMsg() == null ? "库存服务无响应" : resp.getMsg();
            throw new BusinessException(code, "库存服务返回异常: " + msg);
        }

        List<InventoryView> list = resp.getData() == null ? Collections.emptyList() : resp.getData();

        Map<String, StockSummaryVO> map = new LinkedHashMap<>();
        for (InventoryView inventory : list) {
            String key = inventory.getWarehouseCode() + "|" + inventory.getSkuCode();
            StockSummaryVO vo = map.computeIfAbsent(key, k -> {
                StockSummaryVO summary = new StockSummaryVO();
                summary.setWarehouseCode(inventory.getWarehouseCode());
                summary.setSkuCode(inventory.getSkuCode());
                summary.setOnHandQty(BigDecimal.ZERO);
                summary.setFreezeQty(BigDecimal.ZERO);
                return summary;
            });
            vo.setOnHandQty(vo.getOnHandQty().add(nvl(inventory.getQty())));
            vo.setFreezeQty(vo.getFreezeQty().add(nvl(inventory.getFreezeQty())));
        }
        List<StockSummaryVO> result = new ArrayList<>(map.values());
        result.forEach(vo -> vo.setAvailableQty(vo.getOnHandQty().subtract(vo.getFreezeQty())));
        return Result.success(result);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
