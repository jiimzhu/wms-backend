package com.jim.wms.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事务性发件箱写入器。
 *
 * <p>业务事务内只写一条待发记录，不直接调用 Kafka：台账变更与事件记录在同一个本地事务里提交，
 * 保证「台账变了事件不会永久丢」。真正的 Kafka 发送由 {@link OutboxRelay} 在事务之外完成。</p>
 *
 * <p>写发件箱失败必须抛出运行时异常，让业务事务一起回滚，不允许吞掉。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxWriter {

    private static final String INSERT_SQL = "INSERT INTO wms_event_outbox "
            + "(event_id, topic, event_key, payload, status, retry_count, next_retry_time, create_time, update_time) "
            + "VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 在当前事务内写一条待发记录。
     *
     * @param topic    Kafka 主题（不得改名）
     * @param eventKey Kafka key，同维度有序（仓库:SKU 或单号）
     * @param payload  事件对象，序列化为 JSON
     */
    public void write(String topic, String eventKey, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败，业务事务将回滚: " + e.getMessage(), e);
        }
        LocalDateTime now = LocalDateTime.now();
        Timestamp ts = Timestamp.valueOf(now);
        try {
            jdbcTemplate.update(INSERT_SQL,
                    UUID.randomUUID().toString(), topic, eventKey, json, ts, ts, ts);
        } catch (DataAccessException e) {
            throw new IllegalStateException("写发件箱失败，业务事务将回滚: " + e.getMessage(), e);
        }
        log.info("发件箱已登记 topic={}, eventKey={}", topic, eventKey);
    }
}
