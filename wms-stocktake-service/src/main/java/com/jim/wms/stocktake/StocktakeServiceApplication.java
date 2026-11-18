package com.jim.wms.stocktake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;


@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.jim.wms.common.api")
@MapperScan("com.jim.wms.stocktake.mapper")

public class StocktakeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StocktakeServiceApplication.class, args);
    }
}