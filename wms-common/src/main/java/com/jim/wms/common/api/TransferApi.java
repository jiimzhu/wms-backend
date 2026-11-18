package com.jim.wms.common.api;

import com.jim.wms.common.dto.TransferInTransitCmd;
import com.jim.wms.common.dto.TransferReceiveCmd;
import com.jim.wms.common.dto.TransferView;
import com.jim.wms.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调拨服务内部契约。
 *
 * <p>调用方：wms-outbound-service。TRANSFER_OUT 出库单全部扣减成功后调用
 * {@link #markInTransit(TransferInTransitCmd)} 把调拨单置 IN_TRANSIT；
 * 调入仓收货完成后由收货方（本轮为内部接口直接调用）调用 {@link #receive(TransferReceiveCmd)} 置 FINISHED。</p>
 */
@FeignClient(name = "wms-transfer-service", path = "/internal/transfer")
public interface TransferApi {

    /** 出库完成 → 调拨在途（幂等：已是 IN_TRANSIT / FINISHED 时重复调用返回当前状态） */
    @PostMapping("/in-transit")
    Result<TransferView> markInTransit(@RequestBody TransferInTransitCmd cmd);

    /** 调入仓收货 → FINISHED（幂等：已 FINISHED 重复调用返回当前状态） */
    @PostMapping("/receive")
    Result<TransferView> receive(@RequestBody TransferReceiveCmd cmd);
}
