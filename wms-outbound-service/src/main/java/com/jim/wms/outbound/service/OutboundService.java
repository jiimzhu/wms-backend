package com.jim.wms.outbound.service;

import com.jim.wms.common.dto.CancelOutboundCmd;
import com.jim.wms.common.dto.OmsOutboundOrderCmd;
import com.jim.wms.common.dto.ReplenishOrderCmd;
import com.jim.wms.outbound.entity.OutboundOrder;

import java.math.BigDecimal;

public interface OutboundService {

    /** 建出库单（OMS 销售出库 / CTM 补货、调拨出库） */
    OutboundOrder create(OutboundOrder order);

    /** 拣货：按 SKU+库位+批次定位唯一明细行，登记实际拣货数量 */
    void pick(String outboundNo, String skuCode, String locationCode, String batchNo, BigDecimal qty);

    /** 出库确认：落补偿任务后逐条 Feign 扣减库存，全部成功再置终态、推事件、回调来源系统 */
    void confirm(String outboundNo);

    /** 取消：未执行完的单据终止，未成功任务置 CANCELLED，通知来源系统 */
    void cancel(String outboundNo, String reason);

    /** 内部接口：按 CTM 补货 / 调拨申请单创建出库单，返回 WMS 出库单号 */
    String replenishOrder(ReplenishOrderCmd cmd);

    /** 内部接口：按 OMS 销售出库指令创建出库单，返回 WMS 出库单号 */
    String createSalesOrder(OmsOutboundOrderCmd cmd);

    /** 内部接口：按取消指令转调本地取消逻辑 */
    void cancelByInternal(CancelOutboundCmd cmd);
}
