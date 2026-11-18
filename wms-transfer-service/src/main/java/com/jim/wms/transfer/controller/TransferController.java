package com.jim.wms.transfer.controller;

import com.jim.wms.common.result.Result;
import com.jim.wms.transfer.entity.TransferOrder;
import com.jim.wms.transfer.service.TransferService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping("/order")
    public Result<TransferOrder> create(@RequestBody TransferOrder order) {
        return Result.success(transferService.create(order));
    }

    @PostMapping("/outbound")
    public Result<Void> outbound(@RequestBody TransferNoRequest request) {
        transferService.markOutbound(request.getTransferNo());
        return Result.success();
    }

    @PostMapping("/received")
    public Result<Void> received(@RequestBody TransferNoRequest request) {
        transferService.markReceived(request.getTransferNo());
        return Result.success();
    }

    @Data
    public static class TransferNoRequest {
        private String transferNo;
    }
}
