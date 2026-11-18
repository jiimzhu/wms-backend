package com.jim.wms.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jim.wms.common.api.OutboundApi;
import com.jim.wms.common.dto.CancelOutboundCmd;
import com.jim.wms.common.dto.ReplenishOrderCmd;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.result.Result;
import com.jim.wms.integration.client.CtmCallbackClient;
import com.jim.wms.integration.dto.CtmReplenishOrderDTO;
import com.jim.wms.integration.service.ExternalOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CTM 对接入口：接收补货 / 调拨申请单，落 WMS 出库单。
 * WMS 不做逻辑预占，CTM 已预占；WMS 只负责实物作业。
 *
 * <p>路径、请求字段、返回 {@code Result<String>}（data 为 WMS 出库单号）、幂等 409 语义
 * 全部沿用旧单体 CtmReplenishController；拆分后本入口归 wms-integration-service 承载。</p>
 */
@Slf4j
@RestController
@RequestMapping("/integration/ctm")
@RequiredArgsConstructor
public class CtmReplenishController {

    private static final String SOURCE_SYSTEM = "CTM";

    private final ExternalOrderService externalOrderService;
    private final OutboundApi outboundApi;
    private final ObjectMapper objectMapper;

    @PostMapping("/replenish/order")
    public Result<String> receiveReplenish(@Valid @RequestBody CtmReplenishOrderDTO dto) throws Exception {
        // 1. 幂等登记，重复推送直接拒绝（FAILED 记录允许重试，见 ExternalOrderService）
        String rawJson = objectMapper.writeValueAsString(dto);
        externalOrderService.register(SOURCE_SYSTEM, dto.getCtmOrderNo(), dto.getOrderType(),
                dto.getSourceWarehouseCode(), rawJson);

        // 2. 组装内部建单指令：orderType 原样透传外部值（REPLENISH / TRANSFER）。
        //    类型映射（TRANSFER → TRANSFER_OUT，其余 → REPLENISH）统一收敛到 outbound 一处，
        //    本服务不再二次转换，避免 TRANSFER_OUT 被 outbound 二次判成 REPLENISH。
        List<ReplenishOrderCmd.Item> items = dto.getItems().stream()
                .map(src -> ReplenishOrderCmd.Item.builder()
                        .skuCode(src.getSkuCode())
                        .batchNo(src.getBatchNo())
                        .qty(src.getQty())
                        .build())
                .toList();
        ReplenishOrderCmd cmd = ReplenishOrderCmd.builder()
                .ctmOrderNo(dto.getCtmOrderNo())
                .orderType(dto.getOrderType())
                .sourceWarehouseCode(dto.getSourceWarehouseCode())
                .targetWarehouseCode(dto.getTargetWarehouseCode())
                .priority(dto.getPriority())
                .items(items)
                .build();
        log.info("接收 CTM 单据 {}，透传 orderType={}，sourceWarehouseCode={}",
                dto.getCtmOrderNo(), dto.getOrderType(), dto.getSourceWarehouseCode());

        // 3. Feign 调出库服务建单；登记与建单不在同一事务，建单失败回写 FAILED 避免假成功
        Result<String> resp;
        try {
            resp = outboundApi.replenishOrder(cmd);
        } catch (Exception e) {
            log.error("Feign 调用出库服务建单失败, ctmOrderNo={}", dto.getCtmOrderNo(), e);
            externalOrderService.markFailed(SOURCE_SYSTEM, dto.getCtmOrderNo());
            throw new BusinessException(503, "出库服务暂时不可用，单据已登记为 FAILED，可稍后重试: "
                    + e.getClass().getSimpleName());
        }
        if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
            externalOrderService.markFailed(SOURCE_SYSTEM, dto.getCtmOrderNo());
            int code = resp == null || resp.getCode() == null ? 503 : resp.getCode();
            String msg = resp == null || resp.getMsg() == null ? "出库服务无响应" : resp.getMsg();
            throw new BusinessException(code, "出库服务建单失败: " + msg);
        }

        // 4. 回填 WMS 出库单号并返回（与 OMS 路径一致）
        String wmsOutboundNo = resp.getData();
        externalOrderService.markOutboundNo(SOURCE_SYSTEM, dto.getCtmOrderNo(), wmsOutboundNo);
        log.info("接收 CTM 单据 {}，生成 WMS 出库单 {}", dto.getCtmOrderNo(), wmsOutboundNo);
        return Result.success(wmsOutboundNo);
    }

    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestBody CancelRequest request) {
        if (request.getWmsOutboundNo() == null || request.getWmsOutboundNo().isBlank()) {
            throw new BusinessException(400, "wmsOutboundNo 不能为空");
        }
        CancelOutboundCmd cmd = CancelOutboundCmd.builder()
                .ctmOrderNo(request.getCtmOrderNo())
                .wmsOutboundNo(request.getWmsOutboundNo())
                .build();
        Result<Void> resp;
        try {
            resp = outboundApi.cancel(cmd);
        } catch (Exception e) {
            log.error("Feign 调用出库服务取消失败, wmsOutboundNo={}", request.getWmsOutboundNo(), e);
            throw new BusinessException(503, "出库服务暂时不可用: " + e.getClass().getSimpleName());
        }
        if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
            int code = resp == null || resp.getCode() == null ? 503 : resp.getCode();
            String msg = resp == null || resp.getMsg() == null ? "出库服务无响应" : resp.getMsg();
            throw new BusinessException(code, "出库取消失败: " + msg);
        }
        return Result.success();
    }

    @lombok.Data
    public static class CancelRequest {
        private String ctmOrderNo;
        private String wmsOutboundNo;
    }
}
