package com.jim.wms.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.common.event.WmsEventTopics;
import com.jim.wms.common.event.WmsStockEvent;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.lock.RedisLockUtil;
import com.jim.wms.common.outbox.OutboxWriter;
import com.jim.wms.common.util.IdGenerator;
import com.jim.wms.inventory.entity.Inventory;
import com.jim.wms.inventory.entity.InventoryLog;
import com.jim.wms.inventory.mapper.InventoryLogMapper;
import com.jim.wms.inventory.mapper.InventoryMapper;
import com.jim.wms.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryMapper inventoryMapper;
    private final InventoryLogMapper inventoryLogMapper;
    private final RedisLockUtil redisLockUtil;
    private final OutboxWriter outboxWriter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Inventory changeStock(StockChangeCmd cmd) {
        if (cmd.getChangeQty() == null || cmd.getChangeQty().compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException(400, "变动数量不能为 0");
        }
        // 空批次语义统一为 NULL：null / "" / 全空格一律归一为 null，
        // 保证查询条件（isNull）与落库值口径一致，避免同一维度重复建行。
        cmd.setBatchNo(normalizeBatch(cmd.getBatchNo()));
        String lockKey = "wms:stock:" + cmd.getWarehouseCode() + ":" + cmd.getLocationCode()
                + ":" + cmd.getSkuCode() + ":" + safe(cmd.getBatchNo());
        // 有界等待获取 Redis 锁；超时由锁工具抛 429，Redis 不可用抛 503。
        // 存在活动事务时在 afterCompletion 释放，无事务时在 finally 释放，句柄保证只释放一次。
        RedisLockUtil.LockHandle lockHandle = redisLockUtil.acquire(lockKey);
        try {
            String requestId = cmd.getRequestId();
            boolean hasRequestId = requestId != null && !requestId.isBlank();
            if (hasRequestId) {
                // 幂等判定：同一 biz_key 已存在流水，直接返回当前库存，不再扣减、不再写流水、不再发事件
                long hit = inventoryLogMapper.selectCount(
                        Wrappers.<InventoryLog>lambdaQuery().eq(InventoryLog::getBizKey, requestId));
                if (hit > 0) {
                    log.info("库存变动命中幂等，直接返回当前库存，requestId={}, warehouseCode={}, locationCode={}, skuCode={}, batchNo={}",
                            requestId, cmd.getWarehouseCode(), cmd.getLocationCode(), cmd.getSkuCode(), cmd.getBatchNo());
                    return lockInventory(cmd);
                }
            } else {
                log.warn("库存变动未携带 requestId，跳过幂等校验，businessType={}, businessNo={}",
                        cmd.getBusinessType(), cmd.getBusinessNo());
            }

            Inventory inventory = lockInventory(cmd);
            BigDecimal before = inventory == null ? BigDecimal.ZERO : inventory.getQty();
            BigDecimal after = before.add(cmd.getChangeQty());
            if (after.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(400, "库存不足, skuCode=" + cmd.getSkuCode()
                        + ", 现有=" + before + ", 需要=" + cmd.getChangeQty().abs());
            }
            if (inventory == null) {
                inventory = new Inventory();
                inventory.setWarehouseCode(cmd.getWarehouseCode());
                inventory.setLocationCode(cmd.getLocationCode());
                inventory.setSkuCode(cmd.getSkuCode());
                inventory.setBatchNo(cmd.getBatchNo());
                inventory.setQty(after);
                inventory.setFreezeQty(BigDecimal.ZERO);
                inventory.setStockStatus(1);
                inventory.setVersion(0);
                inventory.setCreateTime(LocalDateTime.now());
                inventory.setUpdateTime(LocalDateTime.now());
                inventoryMapper.insert(inventory);
            } else {
                inventory.setQty(after);
                inventory.setUpdateTime(LocalDateTime.now());
                inventoryMapper.updateById(inventory);
            }

            writeLog(cmd, before, after, hasRequestId ? requestId : null);
            publishEvent(cmd, before, after);
            return inventory;
        } finally {
            lockHandle.release();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void freeze(String warehouseCode, String locationCode, String skuCode, String batchNo, BigDecimal qty) {
        batchNo = normalizeBatch(batchNo);
        String lockKey = "wms:stock:" + warehouseCode + ":" + locationCode + ":" + skuCode + ":" + safe(batchNo);
        RedisLockUtil.LockHandle lockHandle = redisLockUtil.acquire(lockKey,
                "库存正在变更，请稍后重试: " + lockKey);
        try {
            Inventory inventory = lockInventory(StockChangeCmd.builder()
                    .warehouseCode(warehouseCode)
                    .locationCode(locationCode)
                    .skuCode(skuCode)
                    .batchNo(batchNo)
                    .changeQty(BigDecimal.ZERO)
                    .businessType("FREEZE")
                    .businessNo(IdGenerator.nextNo("FZ"))
                    .build());
            if (inventory == null) {
                throw new BusinessException(404, "库存不存在，无法冻结");
            }
            BigDecimal freeze = inventory.getFreezeQty() == null ? BigDecimal.ZERO : inventory.getFreezeQty();
            BigDecimal available = inventory.getQty().subtract(freeze);
            if (available.compareTo(qty) < 0) {
                throw new BusinessException(400, "可冻库存不足，可冻=" + available);
            }
            inventory.setFreezeQty(freeze.add(qty));
            inventory.setUpdateTime(LocalDateTime.now());
            inventoryMapper.updateById(inventory);
        } finally {
            lockHandle.release();
        }
    }

    @Override
    public List<Inventory> queryByWarehouseAndSku(String warehouseCode, String skuCode) {
        return inventoryMapper.selectList(Wrappers.<Inventory>lambdaQuery()
                .eq(Inventory::getWarehouseCode, warehouseCode)
                .eq(Inventory::getSkuCode, skuCode));
    }

    @Override
    public BigDecimal totalOnHand(String warehouseCode, String skuCode) {
        return queryByWarehouseAndSku(warehouseCode, skuCode).stream()
                .map(Inventory::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public List<Inventory> list(String warehouseCode) {
        LambdaQueryWrapper<Inventory> wrapper = Wrappers.lambdaQuery();
        if (warehouseCode != null && !warehouseCode.isBlank()) {
            wrapper.eq(Inventory::getWarehouseCode, warehouseCode);
        }
        return inventoryMapper.selectList(wrapper);
    }

    private Inventory lockInventory(StockChangeCmd cmd) {
        LambdaQueryWrapper<Inventory> wrapper = Wrappers.<Inventory>lambdaQuery()
                .eq(Inventory::getWarehouseCode, cmd.getWarehouseCode())
                .eq(Inventory::getLocationCode, cmd.getLocationCode())
                .eq(Inventory::getSkuCode, cmd.getSkuCode());
        if (cmd.getBatchNo() == null || cmd.getBatchNo().isBlank()) {
            wrapper.isNull(Inventory::getBatchNo);
        } else {
            wrapper.eq(Inventory::getBatchNo, cmd.getBatchNo());
        }
        wrapper.last("for update");
        return inventoryMapper.selectOne(wrapper);
    }

    /**
     * 流水只新增：本方法只执行 insert，全工程不存在对 wms_inventory_log 的 update/delete。
     */
    private void writeLog(StockChangeCmd cmd, BigDecimal before, BigDecimal after, String bizKey) {
        InventoryLog stockLog = new InventoryLog();
        stockLog.setWarehouseCode(cmd.getWarehouseCode());
        stockLog.setLocationCode(cmd.getLocationCode());
        stockLog.setSkuCode(cmd.getSkuCode());
        stockLog.setBatchNo(cmd.getBatchNo());
        stockLog.setBusinessType(cmd.getBusinessType());
        stockLog.setBusinessNo(cmd.getBusinessNo());
        stockLog.setChangeQty(cmd.getChangeQty());
        stockLog.setBeforeQty(before);
        stockLog.setAfterQty(after);
        stockLog.setRemark(cmd.getRemark());
        stockLog.setBizKey(bizKey);
        stockLog.setCreateTime(LocalDateTime.now());
        inventoryLogMapper.insert(stockLog);
    }

    private void publishEvent(StockChangeCmd cmd, BigDecimal before, BigDecimal after) {
        WmsStockEvent event = new WmsStockEvent();
        event.setEventId(IdGenerator.nextIdStr());
        event.setEventType(mapEventType(cmd.getBusinessType()));
        event.setWarehouseCode(cmd.getWarehouseCode());
        event.setLocationCode(cmd.getLocationCode());
        event.setSkuCode(cmd.getSkuCode());
        event.setBatchNo(cmd.getBatchNo());
        event.setBusinessNo(cmd.getBusinessNo());
        event.setChangeQty(cmd.getChangeQty());
        event.setBeforeQty(before);
        event.setAfterQty(after);
        event.setEventTime(LocalDateTime.now());
        // 业务事务内只写发件箱 PENDING 记录，真正的 Kafka 投递由 OutboxRelay 在事务外完成；
        // 本方法不调用任何 Kafka API。
        outboxWriter.write(WmsEventTopics.TOPIC_STOCK_EVENT,
                event.getWarehouseCode() + ":" + event.getSkuCode(), event);
    }

    private String mapEventType(String businessType) {
        return switch (businessType) {
            case "PURCHASE_IN", "RETURN_IN" -> "INBOUND";
            case "SALE_OUT" -> "OUTBOUND";
            case "TRANSFER_IN" -> "TRANSFER_IN";
            case "TRANSFER_OUT" -> "TRANSFER_OUT";
            case "STOCKTAKE_ADJ" -> "STOCKTAKE_ADJ";
            default -> businessType;
        };
    }

    private String safe(String value) {
        return value == null ? "NO_BATCH" : value;
    }

    /** 空批次归一：null / "" / 全空格 → null；其余原样保留。 */
    private String normalizeBatch(String batchNo) {
        return batchNo == null || batchNo.isBlank() ? null : batchNo;
    }
}
