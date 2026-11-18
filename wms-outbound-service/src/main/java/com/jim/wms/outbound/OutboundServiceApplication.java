package com.jim.wms.outbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.jim.wms.outbound.config.OutboundProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.jim.wms.common.api")
@MapperScan("com.jim.wms.outbound.mapper")
@EnableScheduling
@EnableConfigurationProperties(OutboundProperties.class)
public class OutboundServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboundServiceApplication.class, args);
    }
}