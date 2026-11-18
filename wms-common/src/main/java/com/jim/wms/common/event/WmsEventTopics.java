package com.jim.wms.common.event;

/**
 * Kafka topic 常量，topic 名不可更改。
 */
public final class WmsEventTopics {

    private WmsEventTopics() {
    }

    /** 库存变动事件 topic */
    public static final String TOPIC_STOCK_EVENT = "wms-stock-event";

    /** 单据状态事件 topic */
    public static final String TOPIC_ORDER_STATUS = "wms-order-status";
}
