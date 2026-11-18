package com.jim.wms.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 发件箱投递线程：在业务事务之外把待发记录投给 Kafka。
 *
 * <p>语义：</p>
 * <ul>
 *     <li>扫描 PENDING / FAILED（next_retry_time 到期）以及 SENDING 租约过期的记录；</li>
 *     <li>先用条件 UPDATE 认领（影响 1 行才处理），避免多实例重复投递；</li>
 *     <li>发送成功置 SENT 并清空 last_error；失败累加 retry_count、记录 last_error、
 *     按 backoff 顺延 next_retry_time，达到 max-retry 后不再自动重试；</li>
 *     <li>投递线程崩溃时，SENDING 记录在租约到期后被重置回 PENDING，不会永久卡住。</li>
 * </ul>
 */
@Slf4j
public class OutboxRelay {

    private static final String SWEEP_SQL = "UPDATE wms_event_outbox SET status='PENDING', owner=NULL, "
            + "lease_until=NULL, update_time=? WHERE status='SENDING' AND lease_until IS NOT NULL AND lease_until < ?";

    private static final String SELECT_IDS_SQL = "SELECT id FROM wms_event_outbox WHERE status IN ('PENDING','FAILED') "
            + "AND retry_count < ? AND (next_retry_time IS NULL OR next_retry_time <= ?) ORDER BY id ASC LIMIT ?";

    private static final String CLAIM_SQL = "UPDATE wms_event_outbox SET status='SENDING', owner=?, lease_until=?, "
            + "update_time=? WHERE id=? AND status IN ('PENDING','FAILED')";

    private static final String SELECT_ROW_SQL = "SELECT topic, event_key, payload, retry_count FROM wms_event_outbox WHERE id=?";

    private static final String MARK_SENT_SQL = "UPDATE wms_event_outbox SET status='SENT', sent_time=?, update_time=?, "
            + "owner=NULL, lease_until=NULL, last_error=NULL WHERE id=?";

    private static final String MARK_FAILED_SQL = "UPDATE wms_event_outbox SET status=?, retry_count=?, next_retry_time=?, "
            + "last_error=?, owner=NULL, lease_until=NULL, update_time=? WHERE id=?";

    private static final RowMapper<OutboxRow> ROW_MAPPER = new RowMapper<>() {
        @Override
        public OutboxRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new OutboxRow(rs.getString("topic"), rs.getString("event_key"),
                    rs.getString("payload"), rs.getInt("retry_count"));
        }
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
    private final OutboxProperties properties;
    private final String instanceId;

    public OutboxRelay(JdbcTemplate jdbcTemplate,
                       ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
                       OutboxProperties properties,
                       String instanceId) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.properties = properties;
        this.instanceId = instanceId;
    }

    @Scheduled(fixedDelayString = "${wms.outbox.scan-interval-ms:1000}",
            initialDelayString = "${wms.outbox.scan-interval-ms:1000}")
    public void relay() {
        try {
            int swept = sweepStuckSending();
            if (swept > 0) {
                log.warn("发件箱回收租约过期的 SENDING 记录 {} 条", swept);
            }
            int handled = drain();
            if (handled > 0) {
                log.info("发件箱本轮投递处理 {} 条，owner={}", handled, instanceId);
            }
        } catch (Exception e) {
            log.error("发件箱投递线程异常，本轮跳过: {}", e.getMessage(), e);
        }
    }

    private int sweepStuckSending() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Timestamp expired = Timestamp.valueOf(LocalDateTime.now().minusNanos(properties.getSendingLeaseMs() * 1_000_000L));
        return jdbcTemplate.update(SWEEP_SQL, now, expired);
    }

    private int drain() {
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        if (kafkaTemplate == null) {
            log.warn("发件箱已启用但容器内没有 KafkaTemplate，本轮不投递");
            return 0;
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Long> ids = jdbcTemplate.queryForList(SELECT_IDS_SQL, Long.class,
                properties.getMaxRetry(), now, properties.getBatchSize());
        int handled = 0;
        for (Long id : ids) {
            handled += relayOne(kafkaTemplate, id);
        }
        return handled;
    }

    private int relayOne(KafkaTemplate<String, String> kafkaTemplate, Long id) {
        LocalDateTime claimNow = LocalDateTime.now();
        Timestamp leaseUntil = Timestamp.valueOf(claimNow.plusNanos(properties.getSendingLeaseMs() * 1_000_000L));
        int claimed = jdbcTemplate.update(CLAIM_SQL, instanceId, leaseUntil, Timestamp.valueOf(claimNow), id);
        if (claimed != 1) {
            // 已被其它实例/线程认领，跳过
            return 0;
        }
        OutboxRow row = jdbcTemplate.query(SELECT_ROW_SQL, ROW_MAPPER, id).stream().findFirst().orElse(null);
        if (row == null) {
            return 0;
        }
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            LocalDateTime sentAt = LocalDateTime.now();
            jdbcTemplate.update(MARK_SENT_SQL, Timestamp.valueOf(sentAt), Timestamp.valueOf(sentAt), id);
            log.info("发件箱投递成功 id={}, topic={}, eventKey={}", id, row.topic(), row.eventKey());
            return 1;
        } catch (Exception e) {
            markFailed(id, row.retryCount(), e);
            return 1;
        }
    }

    private void markFailed(Long id, int retryCount, Exception e) {
        int nextRetryCount = retryCount + 1;
        boolean exhausted = nextRetryCount >= properties.getMaxRetry();
        LocalDateTime now = LocalDateTime.now();
        Timestamp nextRetry = exhausted ? null
                : Timestamp.valueOf(now.plusNanos(properties.getBackoffMs() * 1_000_000L));
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (error.length() > 500) {
            error = error.substring(0, 500);
        }
        jdbcTemplate.update(MARK_FAILED_SQL, "FAILED", nextRetryCount, nextRetry, error,
                Timestamp.valueOf(now), id);
        if (exhausted) {
            log.error("发件箱投递失败且达到上限 id={}, retry={}, lastError={}", id, nextRetryCount, error);
        } else {
            log.warn("发件箱投递失败 id={}, retry={}, nextRetryAt={}, lastError={}", id, nextRetryCount, nextRetry, error);
        }
    }

    private record OutboxRow(String topic, String eventKey, String payload, int retryCount) {
    }
}
