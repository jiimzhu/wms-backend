package com.jim.wms.integration.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WMS 主动回调 OMS：单据状态通知。
 * 回调地址取 {@code wms.integration.oms.callback-url}，默认空；未配置时只记 INFO 跳过。
 * 已配置则 RestTemplate（连接 / 读超时各 3s）POST JSON；失败只记 WARN，返回成功，不阻断上游。
 */
@Slf4j
@Component
public class OmsCallbackClient {

    private final RestTemplate restTemplate;

    @Value("${wms.integration.oms.callback-url:}")
    private String callbackUrl;

    public OmsCallbackClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void notifyStatus(String omsOrderNo, String orderNo, String status, String message) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.info("OMS 回调未配置，跳过, omsOrderNo={}, orderNo={}, status={}", omsOrderNo, orderNo, status);
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("omsOrderNo", omsOrderNo);
        body.put("orderNo", orderNo);
        body.put("status", status);
        body.put("message", message == null ? "" : message);
        try {
            restTemplate.postForObject(callbackUrl, new HttpEntity<>(body, headers), Map.class);
            log.info("回调 OMS 单据状态成功, omsOrderNo={}, orderNo={}, status={}", omsOrderNo, orderNo, status);
        } catch (Exception e) {
            log.warn("回调 OMS 失败（不阻断上游）, omsOrderNo={}, orderNo={}, status={}, ex={}: {}",
                    omsOrderNo, orderNo, status, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
