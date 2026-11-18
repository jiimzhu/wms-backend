package com.jim.wms.inbound.service;

import com.jim.wms.inbound.entity.InboundOrder;

import java.math.BigDecimal;

public interface InboundService {

    /** 建入库单（采购 / 调拨 / 退货），自动生成单号 */
    InboundOrder create(InboundOrder order);

    /** PDA 收货，累加实收数量 */
    void receive(String inboundNo, String skuCode, BigDecimal qty);

    /** 质检结论：PASS / REJECT */
    void qc(String inboundNo, String skuCode, String result);

    /** 上架，Feign 加实物库存并推送库存事件 */
    void putaway(String inboundNo, String skuCode, String locationCode, BigDecimal qty);
}
