package com.jim.wms.stocktake.controller;

import com.jim.wms.common.result.Result;
import com.jim.wms.stocktake.entity.StocktakeOrder;
import com.jim.wms.stocktake.service.StocktakeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/stocktake")
@RequiredArgsConstructor
public class StocktakeController {

    private final StocktakeService stocktakeService;

    @PostMapping("/order")
    public Result<StocktakeOrder> create(@RequestBody StocktakeOrder order) {
        return Result.success(stocktakeService.create(order));
    }

    @PostMapping("/record")
    public Result<Void> record(@RequestBody RecordRequest request) {
        stocktakeService.record(request.getStocktakeNo(), request.getLocationCode(),
                request.getSkuCode(), request.getActualQty());
        return Result.success();
    }

    @PostMapping("/approve")
    public Result<Void> approve(@RequestBody StocktakeNoRequest request) {
        stocktakeService.approve(request.getStocktakeNo());
        return Result.success();
    }

    @Data
    public static class RecordRequest {
        private String stocktakeNo;
        private String locationCode;
        private String skuCode;
        private BigDecimal actualQty;
    }

    @Data
    public static class StocktakeNoRequest {
        private String stocktakeNo;
    }
}
