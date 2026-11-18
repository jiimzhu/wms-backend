package com.jim.wms.integration.controller;

import com.jim.wms.common.api.IntegrationApi;
import com.jim.wms.common.dto.CtmCallbackCmd;
import com.jim.wms.common.dto.ExternalOrderRegisterCmd;
import com.jim.wms.common.dto.OmsStatusNotifyCmd;
import com.jim.wms.common.result.Result;
import com.jim.wms.integration.client.CtmCallbackClient;
import com.jim.wms.integration.client.OmsCallbackClient;
import com.jim.wms.integration.service.ExternalOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口实现，严格对齐 wms-common 的 {@link IntegrationApi}：
 * 外部单据幂等登记、WMS 主动回调 CTM 单据状态。供其他服务 Feign 调用。
 *
 * <p>注意：Spring MVC 不会从接口继承 {@code @RequestMapping} 映射注解，
 * 因此这里在控制器类与方法上显式重复声明路径，与 {@link IntegrationApi} 保持一致。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/integration")
@RequiredArgsConstructor
public class InternalIntegrationController implements IntegrationApi {

    private final ExternalOrderService externalOrderService;
    private final CtmCallbackClient ctmCallbackClient;
    private final OmsCallbackClient omsCallbackClient;

    @Override
    @PostMapping("/external-order/register")
    public Result<Void> registerExternalOrder(@RequestBody ExternalOrderRegisterCmd cmd) {
        externalOrderService.register(cmd.getSourceSystem(), cmd.getExternalOrderNo(),
                cmd.getBusinessType(), cmd.getWarehouseCode(), cmd.getRawJson());
        return Result.success();
    }

    @Override
    @PostMapping("/ctm/callback")
    public Result<Void> ctmCallback(@RequestBody CtmCallbackCmd cmd) {
        // 转换为旧 CtmCallbackClient 的调用语义：orderNo / status / message 出站回调 CTM
        ctmCallbackClient.callbackOrderStatus(cmd.getOrderNo(), cmd.getStatus(), cmd.getMessage());
        return Result.success();
    }

    @Override
    @PostMapping("/oms/status")
    public Result<Void> notifyOmsStatus(@RequestBody OmsStatusNotifyCmd cmd) {
        // 出站通知 OMS；未配置回调地址时客户端只记 INFO 跳过，失败只记 WARN，均返回成功不阻断上游
        omsCallbackClient.notifyStatus(cmd.getOmsOrderNo(), cmd.getOrderNo(), cmd.getStatus(), cmd.getMessage());
        return Result.success();
    }
}
