package com.jim.wms.transfer.service;

import com.jim.wms.common.dto.TransferView;
import com.jim.wms.transfer.entity.TransferOrder;

public interface TransferService {

    TransferOrder create(TransferOrder order);

    /** 调出仓出库完成，进入调拨在途 */
    void markOutbound(String transferNo);

    /** 调入仓收货完成 */
    void markReceived(String transferNo);

    /**
     * 内部契约：出库完成通知调拨单进入在途。
     * 匹配顺序：先按 transfer_no 精确匹配，未命中再按 source_no 匹配。
     * 幂等：已是 IN_TRANSIT / FINISHED 时不回退状态，仅在 outbound_no 为空时补齐。
     */
    TransferView markInTransit(String transferNo, String outboundNo);

    /**
     * 内部契约：调入仓收货完成，置 FINISHED。
     * 幂等：已 FINISHED 重复调用返回当前状态。
     */
    TransferView receive(String transferNo);
}
