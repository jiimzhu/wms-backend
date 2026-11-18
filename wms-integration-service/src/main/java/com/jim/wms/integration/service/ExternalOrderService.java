package com.jim.wms.integration.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.integration.entity.ExternalOrder;
import com.jim.wms.integration.mapper.ExternalOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 外部单据幂等登记。同一外部单号重复推送直接拒绝，避免重复建单、重复出库。
 *
 * <p>状态机：RECEIVED（已登记，待出库建单）→ 建单成功后由出库链路状态回写；
 * 建单 Feign 失败回写 FAILED。FAILED 记录允许同一单号合法重试（重置为 RECEIVED 后继续），
 * RECEIVED 及终态记录重复推送仍抛 409。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalOrderService {

    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_FAILED = "FAILED";

    private final ExternalOrderMapper externalOrderMapper;

    public void register(String sourceSystem, String externalOrderNo, String businessType,
                         String warehouseCode, String rawJson) {
        ExternalOrder existing = externalOrderMapper.selectOne(Wrappers.<ExternalOrder>lambdaQuery()
                .eq(ExternalOrder::getSourceSystem, sourceSystem)
                .eq(ExternalOrder::getExternalOrderNo, externalOrderNo));

        if (existing != null) {
            if (STATUS_FAILED.equals(existing.getStatus())) {
                // 上一次登记后出库建单失败（本地未产生出库单），允许同单号重试：重置后继续流程
                log.info("单据上次建单失败，允许重试: {}#{}", sourceSystem, externalOrderNo);
                existing.setBusinessType(businessType);
                existing.setWarehouseCode(warehouseCode);
                existing.setStatus(STATUS_RECEIVED);
                existing.setRawJson(rawJson);
                existing.setUpdateTime(LocalDateTime.now());
                externalOrderMapper.updateById(existing);
                return;
            }
            throw new BusinessException(409, "单据已接收，请勿重复推送: " + externalOrderNo);
        }

        ExternalOrder order = new ExternalOrder();
        order.setSourceSystem(sourceSystem);
        order.setExternalOrderNo(externalOrderNo);
        order.setBusinessType(businessType);
        order.setWarehouseCode(warehouseCode);
        order.setStatus(STATUS_RECEIVED);
        order.setRawJson(rawJson);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        try {
            externalOrderMapper.insert(order);
        } catch (DuplicateKeyException dup) {
            // 并发重复推送兜底：唯一索引 uk_external_order 冲突按 409 处理
            throw new BusinessException(409, "单据已接收，请勿重复推送: " + externalOrderNo);
        }
    }

    /**
     * 建单失败回写 FAILED。登记记录不存在时只记日志，不抛出异常，避免掩盖原始建单错误。
     */
    public void markFailed(String sourceSystem, String externalOrderNo) {
        ExternalOrder order = externalOrderMapper.selectOne(Wrappers.<ExternalOrder>lambdaQuery()
                .eq(ExternalOrder::getSourceSystem, sourceSystem)
                .eq(ExternalOrder::getExternalOrderNo, externalOrderNo));
        if (order == null) {
            log.warn("回写 FAILED 时未找到登记记录: {}#{}", sourceSystem, externalOrderNo);
            return;
        }
        order.setStatus(STATUS_FAILED);
        order.setUpdateTime(LocalDateTime.now());
        externalOrderMapper.updateById(order);
    }

    public void updateStatus(String sourceSystem, String externalOrderNo, String status) {
        ExternalOrder order = externalOrderMapper.selectOne(Wrappers.<ExternalOrder>lambdaQuery()
                .eq(ExternalOrder::getSourceSystem, sourceSystem)
                .eq(ExternalOrder::getExternalOrderNo, externalOrderNo));
        if (order == null) {
            throw new BusinessException(404, "外部单据不存在: " + externalOrderNo);
        }
        order.setStatus(status);
        order.setUpdateTime(LocalDateTime.now());
        externalOrderMapper.updateById(order);
    }

    public ExternalOrder get(String externalOrderNo) {
        return externalOrderMapper.selectOne(Wrappers.<ExternalOrder>lambdaQuery()
                .eq(ExternalOrder::getExternalOrderNo, externalOrderNo));
    }

    /** 按来源系统 + 外部单号查询登记记录（OMS 取消反查出库单号用），不存在返回 null */
    public ExternalOrder findBySource(String sourceSystem, String externalOrderNo) {
        return externalOrderMapper.selectOne(Wrappers.<ExternalOrder>lambdaQuery()
                .eq(ExternalOrder::getSourceSystem, sourceSystem)
                .eq(ExternalOrder::getExternalOrderNo, externalOrderNo));
    }

    /** 建单成功后回填 WMS 出库单号；记录不存在只记日志，不抛异常 */
    public void markOutboundNo(String sourceSystem, String externalOrderNo, String outboundNo) {
        ExternalOrder order = findBySource(sourceSystem, externalOrderNo);
        if (order == null) {
            log.warn("回填出库单号时未找到登记记录: {}#{}", sourceSystem, externalOrderNo);
            return;
        }
        order.setOutboundNo(outboundNo);
        order.setUpdateTime(LocalDateTime.now());
        externalOrderMapper.updateById(order);
    }
}
