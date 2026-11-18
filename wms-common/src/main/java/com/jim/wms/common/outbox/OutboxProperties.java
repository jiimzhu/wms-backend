package com.jim.wms.common.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事务性发件箱配置。
 *
 * <p>发件箱只在需要发 Kafka 事件的服务内启用（{@code wms.outbox.enabled=true}），
 * 其余服务保持关闭；表 wms_event_outbox 由各服务自己的 Flyway 迁移创建。</p>
 */
@Data
@ConfigurationProperties(prefix = "wms.outbox")
public class OutboxProperties {

    /** 是否启用发件箱与投递线程，默认关闭；仅事件生产方（inventory / outbound）打开。 */
    private boolean enabled = false;

    /** 投递线程扫描间隔（毫秒）。 */
    private long scanIntervalMs = 1000L;

    /** 单次扫描最多处理的记录数。 */
    private int batchSize = 50;

    /** 最大重试次数，达到后置 FAILED 且不再自动重试。 */
    private int maxRetry = 20;

    /** 单条 Kafka 发送的同步等待上限（毫秒）。 */
    private long sendTimeoutMs = 5000L;

    /** 失败重试退避基准（毫秒），每次失败按该值顺延 next_retry_time。 */
    private long backoffMs = 5000L;

    /** SENDING 记录的租约（毫秒），超时未回写视为投递线程已崩溃，重置为 PENDING 重新投递。 */
    private long sendingLeaseMs = 60000L;
}
