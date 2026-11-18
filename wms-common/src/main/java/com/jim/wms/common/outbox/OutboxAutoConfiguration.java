package com.jim.wms.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 发件箱自动装配：仅当服务显式配置 {@code wms.outbox.enabled=true} 时生效。
 *
 * <p>事件生产方（wms-inventory-service、wms-outbound-service）打开；
 * 其余不生产事件的服务保持默认关闭，不会因为缺少 wms_event_outbox 表而启动失败。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@ConditionalOnProperty(prefix = "wms.outbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new OutboxWriter(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate,
                                   ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
                                   OutboxProperties outboxProperties,
                                   Environment environment) {
        String instanceId = environment.getProperty("spring.application.name", "unknown")
                + ":" + environment.getProperty("server.port", "unknown");
        return new OutboxRelay(jdbcTemplate, kafkaTemplateProvider, outboxProperties, instanceId);
    }
}
