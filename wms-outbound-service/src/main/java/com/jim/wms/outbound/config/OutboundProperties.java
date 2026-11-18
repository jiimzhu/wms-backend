package com.jim.wms.outbound.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出库补偿任务与单据补扫配置（规格 REFACTOR-SPEC-2026-11-18 第 4/8 节）。
 *
 * <p>取值依据：单次远程最坏耗时 = 分布式锁等待 2s + Feign 读超时 10s ≈ 12s，
 * 租约取其约 5 倍（60s）；扫描间隔 30s 显著小于租约，避免任务在租约内被误回收。</p>
 */
@Data
@ConfigurationProperties(prefix = "wms.outbound")
public class OutboundProperties {

    /** 认领租约时长（秒）：最坏耗时（锁等待 2s + Feign 读超时 10s）约 12s，取 ~5 倍 = 60s。 */
    private long leaseSeconds = 60L;

    /** 补偿任务扫描间隔（毫秒），必须显著小于租约；默认 30000。 */
    private long scanIntervalMs = 30000L;

    /** 插入 PENDING 任务时 next_retry_time = 创建时间 + 该阈值（毫秒），PENDING 超时纳入扫描。 */
    private long pendingThresholdMs = 10000L;

    /** 单据停在 CONFIRMING 超过该阈值（毫秒）触发单据维度补扫；默认 120000。 */
    private long docReconcileThresholdMs = 120000L;

    /** 任务最大重试次数，达到后不再自动认领。 */
    private int maxRetry = 5;

    /** 长耗时步骤（Feign 前后）续租间隔（毫秒），仅 owner 匹配才续。 */
    private long leaseRenewIntervalMs = 20000L;
}
