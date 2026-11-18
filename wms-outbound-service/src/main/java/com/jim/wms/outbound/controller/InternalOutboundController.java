package com.jim.wms.outbound.controller;

import com.jim.wms.common.dto.CancelOutboundCmd;
import com.jim.wms.common.dto.OmsOutboundOrderCmd;
import com.jim.wms.common.dto.ReplenishOrderCmd;
import com.jim.wms.common.result.Result;
import com.jim.wms.outbound.service.OutboundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出库服务内部契约（对齐 wms-common 的 OutboundApi），供 wms-integration-service Feign 调用。
 *
 * <p>注意：对外的 /integration/ctm/replenish/order 与 /integration/ctm/cancel 路径由
 * wms-integration-service 承接，本服务不再暴露同名路径，避免路由冲突。</p>
 */
@RestController
@RequestMapping("/internal/outbound")
@RequiredArgsConstructor
public class InternalOutboundController {

    private final OutboundService outboundService;

    @PostMapping("/replenish-order")
    public Result<String> replenishOrder(@Valid @RequestBody ReplenishOrderCmd cmd) {
        return Result.success(outboundService.replenishOrder(cmd));
    }

    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestBody CancelOutboundCmd cmd) {
        outboundService.cancelByInternal(cmd);
        return Result.success();
    }

    @PostMapping("/sales-order")
    public Result<String> createSalesOrder(@Valid @RequestBody OmsOutboundOrderCmd cmd) {
        return Result.success(outboundService.createSalesOrder(cmd));
    }
}
