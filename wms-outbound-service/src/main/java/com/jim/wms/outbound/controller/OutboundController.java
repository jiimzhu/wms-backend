package com.jim.wms.outbound.controller;

import com.jim.wms.common.result.Result;
import com.jim.wms.outbound.entity.OutboundOrder;
import com.jim.wms.outbound.service.OutboundService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 出库作业接口（PDA / OMS）
 */
@RestController
@RequestMapping("/api/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;

    @PostMapping("/order")
    public Result<OutboundOrder> create(@RequestBody OutboundOrder order) {
        return Result.success(outboundService.create(order));
    }

    @PostMapping("/pick")
    public Result<Void> pick(@RequestBody PickRequest request) {
        outboundService.pick(request.getOutboundNo(), request.getSkuCode(),
                request.getLocationCode(), request.getBatchNo(), request.getQty());
        return Result.success();
    }

    @PostMapping("/confirm")
    public Result<Void> confirm(@RequestBody OrderNoRequest request) {
        outboundService.confirm(request.getOutboundNo());
        return Result.success();
    }

    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestBody CancelRequest request) {
        outboundService.cancel(request.getOutboundNo(), request.getReason());
        return Result.success();
    }

    @Data
    public static class PickRequest {
        private String outboundNo;
        private String skuCode;
        private String locationCode;
        private String batchNo;
        private BigDecimal qty;
    }

    @Data
    public static class OrderNoRequest {
        private String outboundNo;
    }

    @Data
    public static class CancelRequest {
        private String outboundNo;
        private String reason;
    }
}
