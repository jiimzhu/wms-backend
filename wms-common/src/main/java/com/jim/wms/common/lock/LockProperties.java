package com.jim.wms.common.lock;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 分布式锁配置，前缀 {@code wms.lock}。
 *
 * <p>统一在 wms-common 绑定，9 个业务服务的 application.yml 保持完全一致。</p>
 */
@Data
@ConfigurationProperties(prefix = "wms.lock")
public class LockProperties {

    /**
     * 获取锁的有界等待时长；在该时长内按 {@link #retryInterval} 重试，超时抛 429。
     * 默认 2000ms。
     */
    private Duration waitTimeout = Duration.ofMillis(2000);

    /**
     * 锁租约时长（Redis key 的 PX 过期时间）。
     *
     * <p>租约必须大于最长业务耗时：若业务执行时间超过 leaseTime，锁会提前失效，
     * 存在其他线程/实例并发进入临界区的窗口。当前业务最长耗时按 30s 以内评估，
     * 故默认 30s；如后续业务耗时变长，必须同步上调本值。</p>
     */
    private Duration leaseTime = Duration.ofSeconds(30);

    /** 未抢到锁时的重试间隔；默认 50ms。 */
    private Duration retryInterval = Duration.ofMillis(50);

    /**
     * 是否允许在 Redis 不可用时回退进程内锁兜底。
     *
     * <p>默认 false：Redis 连接失败/超时等异常直接抛 503 快速失败，不静默降级、不静默放行。
     * 仅显式置 true 供「确实无 Redis 的单机开发」使用；多实例与生产环境禁止开启，
     * 否则多实例下退化为单机锁，互斥语义失效。</p>
     */
    private boolean allowLocalFallback = false;
}
