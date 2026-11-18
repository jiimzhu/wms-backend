package com.jim.wms.stocktake.service;

import com.jim.wms.stocktake.entity.StocktakeOrder;

public interface StocktakeService {

    /** 生成盘点任务，自动带出账面数量 */
    StocktakeOrder create(StocktakeOrder order);

    /** PDA 录入实盘数量 */
    void record(String stocktakeNo, String locationCode, String skuCode, java.math.BigDecimal actualQty);

    /** 审核通过：按差异调整实物库存 */
    void approve(String stocktakeNo);
}
