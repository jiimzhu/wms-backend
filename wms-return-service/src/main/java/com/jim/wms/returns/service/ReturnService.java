package com.jim.wms.returns.service;

import com.jim.wms.returns.entity.ReturnOrder;

public interface ReturnService {

    ReturnOrder create(ReturnOrder order);

    /** 质检分流：SALEABLE / DEFECT / SCRAP */
    void inspect(String returnNo, String disposition);

    /** 可售回流入库（报废不入可售库存） */
    void putaway(String returnNo, String locationCode);
}
