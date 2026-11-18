package com.jim.wms.common.api;

import com.jim.wms.common.dto.CancelOutboundCmd;
import com.jim.wms.common.dto.OmsOutboundOrderCmd;
import com.jim.wms.common.dto.ReplenishOrderCmd;
import com.jim.wms.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 出库服务内部契约。
 */
@FeignClient(name = "wms-outbound-service", path = "/internal/outbound")
public interface OutboundApi {

    /** 按 CTM 补货 / 调拨申请单创建出库单，返回 WMS 出库单号 wmsOutboundNo */
    @PostMapping("/replenish-order")
    Result<String> replenishOrder(@RequestBody ReplenishOrderCmd cmd);

    /** 取消出库单 */
    @PostMapping("/cancel")
    Result<Void> cancel(@RequestBody CancelOutboundCmd cmd);

    /** 按 OMS 销售出库指令创建出库单，返回 WMS 出库单号 wmsOutboundNo */
    @PostMapping("/sales-order")
    Result<String> createSalesOrder(@RequestBody OmsOutboundOrderCmd cmd);
}
