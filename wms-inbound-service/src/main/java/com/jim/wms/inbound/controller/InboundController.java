package com.jim.wms.inbound.controller;

import com.jim.wms.common.result.Result;
import com.jim.wms.inbound.entity.InboundOrder;
import com.jim.wms.inbound.service.InboundService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 入库作业接口（PDA 与 ERP / CTM 调拨入库共用）
 */
@RestController
@RequestMapping("/api/inbound")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService inboundService;

    @PostMapping("/order")
    public Result<InboundOrder> create(@RequestBody InboundOrder order) {
        return Result.success(inboundService.create(order));
    }

    @PostMapping("/receive")
    public Result<Void> receive(@RequestBody ReceiveRequest request) {
        inboundService.receive(request.getInboundNo(), request.getSkuCode(), request.getQty());
        return Result.success();
    }

    @PostMapping("/qc")
    public Result<Void> qc(@RequestBody QcRequest request) {
        inboundService.qc(request.getInboundNo(), request.getSkuCode(), request.getResult());
        return Result.success();
    }

    @PostMapping("/putaway")
    public Result<Void> putaway(@RequestBody PutawayRequest request) {
        inboundService.putaway(request.getInboundNo(), request.getSkuCode(),
                request.getLocationCode(), request.getQty());
        return Result.success();
    }

    @Data
    public static class ReceiveRequest {
        private String inboundNo;
        private String skuCode;
        private BigDecimal qty;
    }

    @Data
    public static class QcRequest {
        private String inboundNo;
        private String skuCode;
        private String result;
    }

    @Data
    public static class PutawayRequest {
        private String inboundNo;
        private String skuCode;
        private String locationCode;
        private BigDecimal qty;
    }
}
