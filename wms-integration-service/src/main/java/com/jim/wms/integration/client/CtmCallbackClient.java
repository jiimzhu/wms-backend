package com.jim.wms.integration.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * WMS 主动回调 CTM：单据状态（已接收、拣货中、部分完成、全部完成、取消、失败）
 * 实现方式沿用旧单体：内部 new RestTemplate()，地址取 wms.ctm.base-url；
 * 回调失败只记 ERROR 日志，由后续重试 / 对账补偿，不阻断主流程。
 */
@Slf4j
@Component
public class CtmCallbackClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${wms.ctm.base-url:http://127.0.0.1:9000}")
    private String ctmBaseUrl;

    public void callbackOrderStatus(String orderNo, String status, String message) {
        String url = ctmBaseUrl + "/ctm/wms/order/status";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "orderNo", orderNo,
                "status", status,
                "message", message == null ? "" : message);
        try {
            restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
            log.info("回调 CTM 单据状态成功, orderNo={}, status={}", orderNo, status);
        } catch (Exception e) {
            log.error("回调 CTM 失败, 需由重试任务补偿, orderNo={}, status={}", orderNo, status, e);
        }
    }
}
