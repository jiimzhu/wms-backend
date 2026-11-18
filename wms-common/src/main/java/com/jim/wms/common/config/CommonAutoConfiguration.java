package com.jim.wms.common.config;

import com.jim.wms.common.exception.GlobalExceptionHandler;
import com.jim.wms.common.lock.LockProperties;
import com.jim.wms.common.lock.RedisLockUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * wms-common 自动装配入口。
 *
 * <p>common 是 jar，包名 com.jim.wms.common 不在各服务的组件扫描范围内，
 * 因此通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册本类，让每个服务自动获得：统一异常处理、MyBatis-Plus 分页/乐观锁、CORS、RedisLockUtil。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(LockProperties.class)
@Import({MybatisPlusConfig.class, WebConfig.class, GlobalExceptionHandler.class})
public class CommonAutoConfiguration {

    @Bean
    public RedisLockUtil redisLockUtil(StringRedisTemplate stringRedisTemplate,
                                       LockProperties lockProperties,
                                       RedisProperties redisProperties,
                                       Environment environment) {
        String instanceId = environment.getProperty("spring.application.name", "unknown")
                + ":" + environment.getProperty("server.port", "unknown");
        return new RedisLockUtil(stringRedisTemplate, lockProperties, redisProperties, instanceId);
    }
}
