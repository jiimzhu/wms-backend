package com.jim.wms.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jim.wms.common.api.AdminApi;
import com.jim.wms.common.api.OutboundApi;
import com.jim.wms.common.dto.CancelOutboundCmd;
import com.jim.wms.common.dto.LocationView;
import com.jim.wms.common.dto.OmsOutboundCancelCmd;
import com.jim.wms.common.dto.OmsOutboundOrderCmd;
import com.jim.wms.common.dto.SkuView;
import com.jim.wms.common.dto.WarehouseView;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.result.Result;
import com.jim.wms.integration.entity.ExternalOrder;
import com.jim.wms.integration.service.ExternalOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OMS 对接入口：接收销售出库单 / 取消指令，落 WMS 出库单。
 * 流程风格对齐 {@link CtmReplenishController}：先做主数据校验，再幂等登记
 * {@code wms_external_order}，登记成功后 Feign 调出库服务建单并回填 WMS 单号。
 */
@Slf4j
@RestController
@RequestMapping("/integration/oms")
@RequiredArgsConstructor
public class OmsOutboundController {

    private static final String SOURCE_SYSTEM = "OMS";
    private static final String BUSINESS_TYPE = "SALE_OUT";

    private final ExternalOrderService externalOrderService;
    private final OutboundApi outboundApi;
    private final AdminApi adminApi;
    private final ObjectMapper objectMapper;

    @PostMapping("/outbound/order")
    public Result<String> receiveSalesOrder(@Valid @RequestBody OmsOutboundOrderCmd cmd) throws Exception {
        // 1. 主数据校验：仓库、每个明细的库位（含归属仓库）与商品必须存在，任一不存在返回 400 并指明编码
        validateMasterData(cmd);

        // 2. 幂等登记；重复推送由唯一键 uk_external_order 兜底，命中冲突抛 409
        String rawJson = objectMapper.writeValueAsString(cmd);
        externalOrderService.register(SOURCE_SYSTEM, cmd.getOmsOrderNo(), BUSINESS_TYPE,
                cmd.getWarehouseCode(), rawJson);

        // 3. Feign 调出库服务建单；登记与建单不在同一事务，建单失败回写 FAILED 避免假成功
        Result<String> resp;
        try {
            resp = outboundApi.createSalesOrder(cmd);
        } catch (Exception e) {
            log.error("Feign 调用出库服务建单失败, omsOrderNo={}", cmd.getOmsOrderNo(), e);
            externalOrderService.markFailed(SOURCE_SYSTEM, cmd.getOmsOrderNo());
            throw new BusinessException(503, "出库服务暂时不可用，单据已登记为 FAILED，可稍后重试: "
                    + e.getClass().getSimpleName());
        }
        if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
            externalOrderService.markFailed(SOURCE_SYSTEM, cmd.getOmsOrderNo());
            int code = resp == null || resp.getCode() == null ? 503 : resp.getCode();
            String msg = resp == null || resp.getMsg() == null ? "出库服务无响应" : resp.getMsg();
            throw new BusinessException(code, "出库服务建单失败: " + msg);
        }

        // 4. 回填 WMS 出库单号并返回
        String wmsOutboundNo = resp.getData();
        externalOrderService.markOutboundNo(SOURCE_SYSTEM, cmd.getOmsOrderNo(), wmsOutboundNo);
        log.info("接收 OMS 单据 {}, 生成 WMS 出库单 {}", cmd.getOmsOrderNo(), wmsOutboundNo);
        return Result.success(wmsOutboundNo);
    }

    @PostMapping("/outbound/cancel")
    public Result<Void> cancel(@RequestBody OmsOutboundCancelCmd cmd) {
        String wmsOutboundNo = resolveOutboundNo(cmd);

        CancelOutboundCmd cancelCmd = CancelOutboundCmd.builder()
                .ctmOrderNo(cmd.getOmsOrderNo())
                .wmsOutboundNo(wmsOutboundNo)
                .build();
        Result<Void> resp;
        try {
            resp = outboundApi.cancel(cancelCmd);
        } catch (Exception e) {
            log.error("Feign 调用出库服务取消失败, wmsOutboundNo={}", wmsOutboundNo, e);
            throw new BusinessException(503, "出库服务暂时不可用: " + e.getClass().getSimpleName());
        }
        // outbound 返回的 code/msg 原样透传（PICKED 的 409 文案必须落到调用方），不归一成 502
        if (resp == null || resp.getCode() == null) {
            throw new BusinessException(503, "出库服务无响应");
        }
        if (resp.getCode() != 200) {
            throw new BusinessException(resp.getCode(), resp.getMsg() == null ? "出库取消失败" : resp.getMsg());
        }
        return Result.success();
    }

    /** 主数据校验：仓库存在；每个明细库位存在且归属该仓库；商品存在。 */
    private void validateMasterData(OmsOutboundOrderCmd cmd) {
        Result<WarehouseView> whResp = adminApi.getWarehouse(cmd.getWarehouseCode());
        if (whResp == null || !Integer.valueOf(200).equals(whResp.getCode()) || whResp.getData() == null) {
            throw new BusinessException(400, "仓库不存在: " + cmd.getWarehouseCode());
        }
        for (OmsOutboundOrderCmd.Item item : cmd.getItems()) {
            Result<LocationView> locResp = adminApi.getLocation(item.getLocationCode(), cmd.getWarehouseCode());
            if (locResp == null || !Integer.valueOf(200).equals(locResp.getCode()) || locResp.getData() == null) {
                throw new BusinessException(400, "库位不存在: " + item.getLocationCode());
            }
            LocationView location = locResp.getData();
            if (location.getWarehouseCode() != null
                    && !location.getWarehouseCode().equals(cmd.getWarehouseCode())) {
                throw new BusinessException(400, "库位不属于该仓库: " + item.getLocationCode());
            }
            Result<SkuView> skuResp = adminApi.getSku(item.getSkuCode());
            if (skuResp == null || !Integer.valueOf(200).equals(skuResp.getCode()) || skuResp.getData() == null) {
                throw new BusinessException(400, "商品不存在: " + item.getSkuCode());
            }
        }
    }

    /** 解析出库单号：优先 wmsOutboundNo；否则按 omsOrderNo 查登记记录回填的 outbound_no。 */
    private String resolveOutboundNo(OmsOutboundCancelCmd cmd) {
        boolean noNo = cmd.getWmsOutboundNo() == null || cmd.getWmsOutboundNo().isBlank();
        boolean noOms = cmd.getOmsOrderNo() == null || cmd.getOmsOrderNo().isBlank();
        if (noNo && noOms) {
            throw new BusinessException(400, "omsOrderNo 与 wmsOutboundNo 不能同时为空");
        }
        if (!noNo) {
            return cmd.getWmsOutboundNo().trim();
        }
        ExternalOrder order = externalOrderService.findBySource(SOURCE_SYSTEM, cmd.getOmsOrderNo().trim());
        if (order == null) {
            throw new BusinessException(404, "外部单据不存在: " + cmd.getOmsOrderNo());
        }
        if (order.getOutboundNo() == null || order.getOutboundNo().isBlank()) {
            throw new BusinessException(400, "单据尚未生成出库单号: " + cmd.getOmsOrderNo());
        }
        return order.getOutboundNo();
    }
}
