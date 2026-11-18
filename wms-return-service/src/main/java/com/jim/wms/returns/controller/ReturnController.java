package com.jim.wms.returns.controller;

import com.jim.wms.common.result.Result;
import com.jim.wms.returns.entity.ReturnOrder;
import com.jim.wms.returns.service.ReturnService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/return")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnService returnService;

    @PostMapping("/order")
    public Result<ReturnOrder> create(@RequestBody ReturnOrder order) {
        return Result.success(returnService.create(order));
    }

    @PostMapping("/inspect")
    public Result<Void> inspect(@RequestBody InspectRequest request) {
        returnService.inspect(request.getReturnNo(), request.getDisposition());
        return Result.success();
    }

    @PostMapping("/putaway")
    public Result<Void> putaway(@RequestBody PutawayRequest request) {
        returnService.putaway(request.getReturnNo(), request.getLocationCode());
        return Result.success();
    }

    @Data
    public static class InspectRequest {
        private String returnNo;
        private String disposition;
    }

    @Data
    public static class PutawayRequest {
        private String returnNo;
        private String locationCode;
    }
}
