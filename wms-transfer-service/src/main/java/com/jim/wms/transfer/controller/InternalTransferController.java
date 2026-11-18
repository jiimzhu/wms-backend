package com.jim.wms.transfer.controller;

import com.jim.wms.common.dto.TransferInTransitCmd;
import com.jim.wms.common.dto.TransferReceiveCmd;
import com.jim.wms.common.dto.TransferView;
import com.jim.wms.common.result.Result;
import com.jim.wms.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调拨服务内部契约（对齐 wms-common 的 TransferApi），供 wms-outbound-service 等 Feign 调用。
 *
 * <p>controller 仅做入参校验与返回包装；匹配顺序、幂等与状态机非法流转防护均在服务层。</p>
 */
@RestController
@RequestMapping("/internal/transfer")
@RequiredArgsConstructor
public class InternalTransferController {

    private final TransferService transferService;

    @PostMapping("/in-transit")
    public Result<TransferView> markInTransit(@RequestBody TransferInTransitCmd cmd) {
        if (cmd == null || cmd.getTransferNo() == null || cmd.getTransferNo().isBlank()) {
            return Result.fail(400, "调拨单号不能为空");
        }
        return Result.success(transferService.markInTransit(cmd.getTransferNo().trim(), cmd.getOutboundNo()));
    }

    @PostMapping("/receive")
    public Result<TransferView> receive(@RequestBody TransferReceiveCmd cmd) {
        if (cmd == null || cmd.getTransferNo() == null || cmd.getTransferNo().isBlank()) {
            return Result.fail(400, "调拨单号不能为空");
        }
        return Result.success(transferService.receive(cmd.getTransferNo().trim()));
    }
}
