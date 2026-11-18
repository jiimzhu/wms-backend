package com.jim.wms.common.api;

import com.jim.wms.common.dto.CtmCallbackCmd;
import com.jim.wms.common.dto.ExternalOrderRegisterCmd;
import com.jim.wms.common.dto.OmsStatusNotifyCmd;
import com.jim.wms.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 集成服务内部契约：外部单据幂等登记、CTM 状态回调。
 */
@FeignClient(name = "wms-integration-service", path = "/internal/integration")
public interface IntegrationApi {

    /** 外部单据幂等登记（CTM / OMS / ERP 单据号唯一） */
    @PostMapping("/external-order/register")
    Result<Void> registerExternalOrder(@RequestBody ExternalOrderRegisterCmd cmd);

    /** WMS 主动回调 CTM 单据状态 */
    @PostMapping("/ctm/callback")
    Result<Void> ctmCallback(@RequestBody CtmCallbackCmd cmd);

    /** WMS 主动通知 OMS 单据状态 */
    @PostMapping("/oms/status")
    Result<Void> notifyOmsStatus(@RequestBody OmsStatusNotifyCmd cmd);
}
