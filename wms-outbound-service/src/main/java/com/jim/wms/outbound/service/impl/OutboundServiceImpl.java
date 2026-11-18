package com.jim.wms.outbound.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.common.api.IntegrationApi;
import com.jim.wms.common.api.InventoryApi;
import com.jim.wms.common.api.TransferApi;
import com.jim.wms.common.dto.CancelOutboundCmd;
import com.jim.wms.common.dto.CtmCallbackCmd;
import com.jim.wms.common.dto.InventoryView;
import com.jim.wms.common.dto.OmsOutboundOrderCmd;
import com.jim.wms.common.dto.OmsStatusNotifyCmd;
import com.jim.wms.common.dto.ReplenishOrderCmd;
import com.jim.wms.common.dto.StockChangeCmd;
import com.jim.wms.common.dto.TransferInTransitCmd;
import com.jim.wms.common.dto.TransferView;
import com.jim.wms.common.event.WmsEventTopics;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.outbox.OutboxWriter;
import com.jim.wms.common.result.Result;
import com.jim.wms.common.util.IdGenerator;
import com.jim.wms.outbound.config.OutboundProperties;
import com.jim.wms.outbound.entity.OutboundOrder;
import com.jim.wms.outbound.entity.OutboundOrderItem;
import com.jim.wms.outbound.entity.OutboundStockTask;
import com.jim.wms.outbound.mapper.OutboundOrderItemMapper;
import com.jim.wms.outbound.mapper.OutboundOrderMapper;
import com.jim.wms.outbound.mapper.OutboundStockTaskMapper;
import com.jim.wms.outbound.service.OutboundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService {

    /** 失败任务下次重试间隔（分钟） */
    private static final long RETRY_DELAY_MINUTES = 1L;

    /** 批次为空时 requestId 占位（沿用 NOBATCH 字面，与库存侧 biz_key 幂等口径一致） */
    private static final String NO_BATCH = "NOBATCH";

    /** dispatch 结果：未抢到 / 在途（跳过，单据保持 CONFIRMING） */
    private static final int DISPATCH_NOT_CLAIMED = 0;
    /** dispatch 结果：远程扣减成功（任务已置 SUCCESS） */
    private static final int DISPATCH_SUCCESS = 1;
    /** dispatch 结果：远程失败（任务已置 FAILED + last_error + next_retry_time，待补偿） */
    private static final int DISPATCH_REMOTE_FAILED = 2;

    private final OutboundOrderMapper orderMapper;
    private final OutboundOrderItemMapper itemMapper;
    private final OutboundStockTaskMapper taskMapper;
    private final InventoryApi inventoryApi;
    private final IntegrationApi integrationApi;
    private final TransferApi transferApi;
    private final OutboxWriter outboxWriter;
    private final OutboundProperties properties;
    private final PlatformTransactionManager transactionManager;
    private final Environment environment;

    /** 普通业务事务 */
    private TransactionTemplate txTemplate;
    /** 认领独立事务：认领提交后再做 Feign 远程调用，绝不能把远程调用包进同一事务 */
    private TransactionTemplate claimTemplate;

    @jakarta.annotation.PostConstruct
    void initTxTemplate() {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.claimTemplate = new TransactionTemplate(transactionManager);
        this.claimTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundOrder create(OutboundOrder order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            throw new BusinessException(400, "出库单明细不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setOutboundNo(IdGenerator.nextNo("OUT"));
        order.setStatus("CREATED");
        order.setCreateTime(now);
        order.setUpdateTime(now);
        orderMapper.insert(order);
        for (OutboundOrderItem item : order.getItems()) {
            item.setOutboundNo(order.getOutboundNo());
            item.setPickedQty(BigDecimal.ZERO);
            itemMapper.insert(item);
        }
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pick(String outboundNo, String skuCode, String locationCode, String batchNo, BigDecimal qty) {
        OutboundOrder order = requireOrder(outboundNo);
        if ("FINISHED".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus())) {
            throw new BusinessException(400, "单据已结束，无法继续拣货");
        }
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "拣货数量必须大于 0");
        }
        // 按「SKU + 库位 + 批次」定位明细行；库位/批次入参为空时按 SKU（+ 批次，若给了）宽松匹配
        OutboundOrderItem item = resolvePickItem(outboundNo, skuCode, locationCode, batchNo);
        BigDecimal picked = nvl(item.getPickedQty()).add(qty);
        if (picked.compareTo(nvl(item.getPlanQty())) > 0) {
            throw new BusinessException(400, "拣货数量超过计划数量");
        }
        // 回写实际拣货维度：入参给了就用入参；未给则保留明细原有维度；
        // 明细本身也无库位（CTM 补货单）时，按当前可用库存唯一库位回填，保证确认扣减落对库位。
        if (!isBlank(locationCode)) {
            item.setLocationCode(locationCode);
        } else if (isBlank(item.getLocationCode())) {
            item.setLocationCode(resolveLocationFromStock(order, item));
        }
        if (!isBlank(batchNo)) {
            item.setBatchNo(batchNo);
        }
        item.setPickedQty(picked);
        itemMapper.updateById(item);

        order.setStatus("PICKING");
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    @Override
    public void confirm(String outboundNo) {
        OutboundOrder order = requireOrder(outboundNo);
        if (!"CREATED".equals(order.getStatus()) && !"PICKING".equals(order.getStatus())) {
            throw new BusinessException(400, "当前状态不允许出库确认: " + order.getStatus());
        }
        List<OutboundOrderItem> items = listItems(outboundNo);
        List<OutboundOrderItem> picked = items.stream()
                .filter(i -> nvl(i.getPickedQty()).compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
        if (picked.isEmpty()) {
            throw new BusinessException(400, "尚未拣货，无法出库确认");
        }

        // 第一步：本地事务置 CONFIRMING，并为每个已拣货明细落一条 PENDING 补偿任务；事务提交后再做远程扣减
        txTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            order.setStatus("CONFIRMING");
            order.setUpdateTime(now);
            orderMapper.updateById(order);
            for (OutboundOrderItem item : picked) {
                ensurePendingTask(order, item, now);
            }
        });

        // 第二步：首次同步认领（claimFirstAttempt，不判 next_retry_time 到期）后逐条 Feign 扣减；Feign 在事务外
        List<OutboundStockTask> tasks = taskMapper.selectList(
                Wrappers.<OutboundStockTask>lambdaQuery().eq(OutboundStockTask::getOutboundNo, outboundNo));
        for (OutboundStockTask task : tasks) {
            if ("SUCCESS".equals(task.getStatus())) {
                continue;
            }
            dispatchFirstAttempt(task);
        }

        // 第三步：结束后重新查该单全部任务再判定（不采信单次 dispatch 返回值）：
        // 全部 SUCCESS → 推终态；存在 FAILED → CONFIRM_FAILED 等补偿；仅 PENDING/PROCESSING 在途或被别实例抢走 → 保持 CONFIRMING
        List<OutboundStockTask> latest = taskMapper.selectList(
                Wrappers.<OutboundStockTask>lambdaQuery().eq(OutboundStockTask::getOutboundNo, outboundNo));
        boolean allSuccess = latest.stream().allMatch(t -> "SUCCESS".equals(t.getStatus()));
        boolean anyFailed = latest.stream().anyMatch(t -> "FAILED".equals(t.getStatus()));
        if (allSuccess) {
            finalizeIfAllSuccess(order);
        } else if (anyFailed) {
            markOrderConfirmFailed(outboundNo);
            log.error("出库确认存在失败任务, 整单置 CONFIRM_FAILED, outboundNo={}", outboundNo);
        } else {
            log.info("任务在途或已被其它实例认领, 单据保持 CONFIRMING, outboundNo={}", outboundNo);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String outboundNo, String reason) {
        OutboundOrder order = requireOrder(outboundNo);
        if ("FINISHED".equals(order.getStatus())) {
            throw new BusinessException(400, "已完成的单据不能取消");
        }
        List<OutboundOrderItem> items = listItems(outboundNo);
        boolean anyPicked = items.stream()
                .anyMatch(i -> nvl(i.getPickedQty()).compareTo(BigDecimal.ZERO) > 0);
        // 规格第 6 节：PICKED 且存在已拣货明细 → 409，必须走反向入库回补库存，禁止直接取消造成「单已取消、库存未回滚」
        if ("PICKED".equals(order.getStatus()) && anyPicked) {
            throw new BusinessException(409, "已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存");
        }

        txTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            order.setStatus("CANCELLED");
            order.setUpdateTime(now);
            orderMapper.updateById(order);

            // 未成功（PENDING / FAILED / PROCESSING）的任务置 CANCELLED，避免定时重试把已取消单据又扣一次库存
            List<OutboundStockTask> tasks = taskMapper.selectList(
                    Wrappers.<OutboundStockTask>lambdaQuery().eq(OutboundStockTask::getOutboundNo, outboundNo));
            for (OutboundStockTask task : tasks) {
                if (!"SUCCESS".equals(task.getStatus())) {
                    taskMapper.markCancelledTerminal(task.getId(), now);
                }
            }
            // 单据状态事件与单据状态在同一业务事务内写发件箱
            outboxWriter.write(WmsEventTopics.TOPIC_ORDER_STATUS, outboundNo,
                    buildOrderStatusPayload(outboundNo, "CANCELLED", reason));
        });

        notifySource(order, "CANCELLED", reason);
    }

    @Override
    public String replenishOrder(ReplenishOrderCmd cmd) {
        OutboundOrder order = new OutboundOrder();
        // 幂等映射：integration 侧可能预映射成 TRANSFER_OUT，也可能透传原始 TRANSFER，两种写法都认；其余（含 REPLENISH、空值）落 REPLENISH
        String rawOrderType = cmd.getOrderType();
        boolean transfer = rawOrderType != null
                && ("TRANSFER".equalsIgnoreCase(rawOrderType) || "TRANSFER_OUT".equalsIgnoreCase(rawOrderType));
        order.setOrderType(transfer ? "TRANSFER_OUT" : "REPLENISH");
        order.setSourceSystem("CTM");
        order.setSourceNo(cmd.getCtmOrderNo());
        order.setWarehouseCode(cmd.getSourceWarehouseCode());
        order.setTargetWarehouseCode(cmd.getTargetWarehouseCode());

        List<OutboundOrderItem> items = new ArrayList<>();
        for (ReplenishOrderCmd.Item src : cmd.getItems()) {
            OutboundOrderItem item = new OutboundOrderItem();
            item.setSkuCode(src.getSkuCode());
            item.setBatchNo(src.getBatchNo());
            item.setPlanQty(src.getQty());
            items.add(item);
        }
        order.setItems(items);

        OutboundOrder created = create(order);
        log.info("接收 CTM 单据 {}, 生成 WMS 出库单 {}", cmd.getCtmOrderNo(), created.getOutboundNo());
        return created.getOutboundNo();
    }

    @Override
    public String createSalesOrder(OmsOutboundOrderCmd cmd) {
        OutboundOrder order = new OutboundOrder();
        // 订单类型服务端固定 SALE_OUT，不接受外部传入；来源系统 OMS，sourceNo 为 OMS 单号（幂等键）
        order.setOrderType("SALE_OUT");
        order.setSourceSystem("OMS");
        order.setSourceNo(cmd.getOmsOrderNo());
        order.setWarehouseCode(cmd.getWarehouseCode());

        // 逐行建明细：同一 SKU 不同库位必须各占一行，重复维度由唯一键 uk_item_dim 兜底
        List<OutboundOrderItem> items = new ArrayList<>();
        for (OmsOutboundOrderCmd.Item src : cmd.getItems()) {
            OutboundOrderItem item = new OutboundOrderItem();
            item.setSkuCode(src.getSkuCode());
            item.setLocationCode(src.getLocationCode());
            item.setBatchNo(src.getBatchNo());
            item.setPlanQty(src.getQty());
            items.add(item);
        }
        order.setItems(items);

        OutboundOrder created = create(order);
        log.info("接收 OMS 单据 {}, 生成 WMS 出库单 {}", cmd.getOmsOrderNo(), created.getOutboundNo());
        return created.getOutboundNo();
    }

    @Override
    public void cancelByInternal(CancelOutboundCmd cmd) {
        String wmsOutboundNo = cmd.getWmsOutboundNo();
        if (wmsOutboundNo == null || wmsOutboundNo.isBlank()) {
            throw new BusinessException(400, "wmsOutboundNo 不能为空");
        }
        cancel(wmsOutboundNo, "CTM 取消单据: " + cmd.getCtmOrderNo());
    }

    /**
     * 定时补偿：扫描 PENDING/FAILED 到期任务与租约过期的 PROCESSING 任务，条件 UPDATE 认领后逐条扣减。
     * 每轮打一行候选/认领/回写计数便于取证。
     */
    @Scheduled(fixedDelayString = "${wms.outbound.scan-interval-ms:30000}",
            initialDelayString = "${wms.outbound.scan-interval-ms:30000}")
    public void sweepCompensation() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboundStockTask> candidates = taskMapper.selectCandidates(now, properties.getMaxRetry());
        if (candidates.isEmpty()) {
            return;
        }
        int claimed = 0;
        int writtenBack = 0;
        log.info("出库补偿扫描: 候选任务数={}", candidates.size());
        for (OutboundStockTask task : candidates) {
            try {
                int result = dispatchTask(task);
                if (result != DISPATCH_NOT_CLAIMED) {
                    claimed++;
                    writtenBack++;
                }
            } catch (Exception e) {
                log.error("补偿任务处理异常, taskId={}, requestId={}", task.getId(), task.getRequestId(), e);
            }
        }
        log.info("出库补偿扫描本轮结束: 候选={}, 认领={}, 回写={}", candidates.size(), claimed, writtenBack);
    }

    /**
     * 单据维度补扫（规格第 8 节）：扫描停在 CONFIRMING 超过阈值的单据，不论任务状态重跑「查任务、补缺口、推终态」。
     * 幂等：任务按 requestId 唯一键兜底，补建不会产生重复扣减；本方法不直接调 Feign，扣减仍由任务级扫描完成。
     */
    @Scheduled(fixedDelayString = "${wms.outbound.scan-interval-ms:30000}",
            initialDelayString = "${wms.outbound.scan-interval-ms:30000}")
    public void reconcileConfirmingOrders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusNanos(properties.getDocReconcileThresholdMs() * 1_000_000L);
        List<OutboundOrder> stuckOrders = orderMapper.selectList(Wrappers.<OutboundOrder>lambdaQuery()
                .eq(OutboundOrder::getStatus, "CONFIRMING")
                .lt(OutboundOrder::getUpdateTime, threshold));
        if (stuckOrders.isEmpty()) {
            return;
        }
        log.info("单据补扫: 停留在 CONFIRMING 超阈值单据数={}", stuckOrders.size());
        for (OutboundOrder order : stuckOrders) {
            try {
                reconcileOne(order);
            } catch (Exception e) {
                log.error("单据补扫异常, outboundNo={}", order.getOutboundNo(), e);
            }
        }
    }

    /**
     * 补扫单个单据：补建缺失任务 → 全部 SUCCESS 推 FINISHED/PICKED 并发事件；仍有未成功任务则回 CONFIRM_FAILED。
     */
    private void reconcileOne(OutboundOrder order) {
        List<OutboundOrderItem> picked = listItems(order.getOutboundNo()).stream()
                .filter(i -> nvl(i.getPickedQty()).compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
        List<OutboundStockTask> existing = taskMapper.selectList(
                Wrappers.<OutboundStockTask>lambdaQuery().eq(OutboundStockTask::getOutboundNo, order.getOutboundNo()));
        Set<String> existingRequestIds = existing.stream()
                .map(OutboundStockTask::getRequestId).collect(Collectors.toSet());

        // 事务内补缺口；requestId 唯一键兜底，并发/重复扫描不重复建任务
        txTemplate.executeWithoutResult(s -> {
            LocalDateTime now = LocalDateTime.now();
            for (OutboundOrderItem item : picked) {
                String requestId = buildRequestId(order.getOutboundNo(), item);
                if (existingRequestIds.contains(requestId)) {
                    continue;
                }
                try {
                    insertPendingTask(order, item, now);
                    existingRequestIds.add(requestId);
                } catch (DuplicateKeyException dup) {
                    log.warn("补扫任务 requestId 已存在, 跳过, requestId={}", requestId);
                }
            }
        });

        List<OutboundStockTask> latest = taskMapper.selectList(
                Wrappers.<OutboundStockTask>lambdaQuery().eq(OutboundStockTask::getOutboundNo, order.getOutboundNo()));
        if (latest.isEmpty()) {
            return;
        }
        boolean allSuccess = latest.stream().allMatch(t -> "SUCCESS".equals(t.getStatus()));
        if (allSuccess) {
            finalizeIfAllSuccess(order);
        } else {
            markOrderConfirmFailed(order.getOutboundNo());
            log.warn("单据补扫: 仍有未成功任务, 回 CONFIRM_FAILED 交任务级重试, outboundNo={}", order.getOutboundNo());
        }
    }

    /**
     * 扫描器认领并执行一条任务：claimNew（PENDING/FAILED 到期）→ claimExpired（回收过期 PROCESSING）。
     * 认领在独立事务内提交，Feign 远程调用留在事务外。
     *
     * @return {@link #DISPATCH_SUCCESS} / {@link #DISPATCH_REMOTE_FAILED} / {@link #DISPATCH_NOT_CLAIMED}
     */
    private int dispatchTask(OutboundStockTask task) {
        OutboundOrder order = requireOrderOrNull(task);
        if (order == null) {
            return DISPATCH_NOT_CLAIMED;
        }
        if ("CANCELLED".equals(order.getStatus())) {
            taskMapper.markCancelledTerminal(task.getId(), LocalDateTime.now());
            return DISPATCH_NOT_CLAIMED;
        }

        String owner = buildOwner();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusSeconds(properties.getLeaseSeconds());

        // 认领：独立事务提交；先认 PENDING/FAILED，认不到再回收租约过期的 PROCESSING。影响 1 行才算抢到。
        Integer claimed = claimTemplate.execute(status -> {
            int rows = taskMapper.claimNew(task.getId(), owner, leaseUntil, now, properties.getMaxRetry());
            if (rows == 0) {
                rows = taskMapper.claimExpired(task.getId(), owner, leaseUntil, now, properties.getMaxRetry());
            }
            return rows;
        });
        if (claimed == null || claimed != 1) {
            log.info("任务未抢到（并发竞争/已终态/超上限）, taskId={}, requestId={}, owner={}",
                    task.getId(), task.getRequestId(), owner);
            return DISPATCH_NOT_CLAIMED;
        }
        return executeClaimed(task, order, owner);
    }

    /**
     * confirm 首次同步认领：claimFirstAttempt 仅认 PENDING 且 retry_count=0 的全新任务，不判 next_retry_time 到期。
     * 并发下另一实例抢到后返回 {@link #DISPATCH_NOT_CLAIMED}，本实例不得重复扣减。
     */
    private int dispatchFirstAttempt(OutboundStockTask task) {
        OutboundOrder order = requireOrderOrNull(task);
        if (order == null) {
            return DISPATCH_NOT_CLAIMED;
        }
        if ("CANCELLED".equals(order.getStatus())) {
            taskMapper.markCancelledTerminal(task.getId(), LocalDateTime.now());
            return DISPATCH_NOT_CLAIMED;
        }

        String owner = buildOwner();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusSeconds(properties.getLeaseSeconds());

        Integer claimed = claimTemplate.execute(status ->
                taskMapper.claimFirstAttempt(task.getId(), owner, leaseUntil, now));
        if (claimed == null || claimed != 1) {
            log.info("首次认领未抢到（已被别实例认领/非全新 PENDING）, taskId={}, requestId={}, owner={}",
                    task.getId(), task.getRequestId(), owner);
            return DISPATCH_NOT_CLAIMED;
        }
        return executeClaimed(task, order, owner);
    }

    /**
     * 抢到认领后的公共步骤：复核 owner → 续租 → Feign 扣减 → 终态回写。
     */
    private int executeClaimed(OutboundStockTask task, OutboundOrder order, String owner) {
        OutboundStockTask latest = taskMapper.selectById(task.getId());
        if (latest == null || !owner.equals(latest.getOwner())) {
            return DISPATCH_NOT_CLAIMED;
        }

        renewLease(latest, owner);
        String businessType = "TRANSFER_OUT".equals(order.getOrderType()) ? "TRANSFER_OUT" : "SALE_OUT";
        boolean ok;
        try {
            ok = deductStock(order, latest, businessType);
        } catch (Exception e) {
            recordFailure(latest, "Feign 调用异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return DISPATCH_REMOTE_FAILED;
        }
        renewLease(latest, owner);

        if (ok) {
            finalizeIfAllSuccess(order);
            return DISPATCH_SUCCESS;
        }
        return DISPATCH_REMOTE_FAILED;
    }

    private OutboundOrder requireOrderOrNull(OutboundStockTask task) {
        try {
            return requireOrder(task.getOutboundNo());
        } catch (BusinessException e) {
            log.error("任务对应单据不存在, taskId={}, requestId={}", task.getId(), task.getRequestId());
            return null;
        }
    }

    /**
     * 逐条调库存服务扣减；成功置 SUCCESS 终态，失败置 FAILED 并回写错误与重试信息。
     *
     * @return true 扣减成功（含库存侧幂等命中）；false 失败，待补偿
     */
    private boolean deductStock(OutboundOrder order, OutboundStockTask task, String businessType) {
        StockChangeCmd cmd = StockChangeCmd.builder()
                .warehouseCode(order.getWarehouseCode())
                .locationCode(task.getLocationCode())
                .skuCode(task.getSkuCode())
                .batchNo(task.getBatchNo())
                .changeQty(task.getChangeQty())
                .businessType(businessType)
                .businessNo(order.getOutboundNo())
                .remark("出库确认")
                .requestId(task.getRequestId())
                .build();
        Result<InventoryView> result;
        try {
            result = inventoryApi.changeStock(cmd);
        } catch (Exception e) {
            recordFailure(task, "Feign 调用异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
        if (result != null && Integer.valueOf(200).equals(result.getCode())) {
            taskMapper.markSuccessTerminal(task.getId(), LocalDateTime.now());
            return true;
        }
        String err = result == null ? "库存服务无响应" : ("code=" + result.getCode() + ", msg=" + result.getMsg());
        recordFailure(task, err);
        return false;
    }

    private void recordFailure(OutboundStockTask task, String error) {
        String truncated = error == null ? "" : (error.length() > 500 ? error.substring(0, 500) : error);
        LocalDateTime now = LocalDateTime.now();
        // 认领时 retry_count 已 +1，失败回写不重复累计；顺延 next_retry_time 交下一轮扫描
        int retryCount = nvlInt(task.getRetryCount());
        LocalDateTime nextRetryTime = now.plusMinutes(RETRY_DELAY_MINUTES);
        taskMapper.markFailedTerminal(task.getId(), truncated, retryCount, nextRetryTime, now);
        log.warn("扣减失败置 FAILED, taskId={}, requestId={}, retry={}, nextRetryAt={}, lastError={}",
                task.getId(), task.getRequestId(), retryCount, nextRetryTime, truncated);
    }

    private void markOrderConfirmFailed(String outboundNo) {
        OutboundOrder order = requireOrder(outboundNo);
        if (!"CONFIRM_FAILED".equals(order.getStatus()) && !"CANCELLED".equals(order.getStatus())) {
            order.setStatus("CONFIRM_FAILED");
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
        }
    }

    /**
     * 该单任务全部 SUCCESS 后，按旧规则判定 FINISHED / PICKED，单据状态与发件箱事件在同一事务提交，
     * 事务提交后再回调来源系统、通知调拨在途。
     *
     * @return true 已置终态；false 仍有未成功任务
     */
    private boolean finalizeIfAllSuccess(OutboundOrder order) {
        OutboundOrder fresh = requireOrder(order.getOutboundNo());
        // 幂等守卫：单据已在终态（FINISHED/PICKED）则不再重复写单据、发件箱事件与回调
        if ("FINISHED".equals(fresh.getStatus()) || "PICKED".equals(fresh.getStatus())) {
            return true;
        }
        List<OutboundStockTask> tasks = taskMapper.selectList(
                Wrappers.<OutboundStockTask>lambdaQuery().eq(OutboundStockTask::getOutboundNo, fresh.getOutboundNo()));
        if (tasks.isEmpty()) {
            return false;
        }
        boolean allSuccess = tasks.stream().allMatch(t -> "SUCCESS".equals(t.getStatus()));
        if (!allSuccess) {
            return false;
        }

        List<OutboundOrderItem> items = listItems(fresh.getOutboundNo());
        boolean complete = items.stream()
                .allMatch(i -> nvl(i.getPickedQty()).compareTo(nvl(i.getPlanQty())) >= 0);
        String status = complete ? "FINISHED" : "PICKED";
        String message = complete ? "出库完成" : "部分出库";

        txTemplate.executeWithoutResult(s -> {
            LocalDateTime now = LocalDateTime.now();
            fresh.setStatus(status);
            fresh.setLastError(null);
            fresh.setUpdateTime(now);
            orderMapper.updateById(fresh);
            // 业务事务内写发件箱 PENDING 记录，真正 Kafka 投递由 OutboxRelay 事务外完成
            outboxWriter.write(WmsEventTopics.TOPIC_ORDER_STATUS, fresh.getOutboundNo(),
                    buildOrderStatusPayload(fresh.getOutboundNo(), status, message));
        });

        notifySource(fresh, status, message);
        // 规格第 7 节：TRANSFER_OUT 全部扣减成功并置 FINISHED 后，通知调拨服务在途；调用在事务之外，独立失败处理
        if ("TRANSFER_OUT".equals(fresh.getOrderType()) && "FINISHED".equals(status)) {
            notifyTransferInTransit(fresh);
        }
        log.info("出库单终态确认, outboundNo={}, status={}", fresh.getOutboundNo(), status);
        return true;
    }

    /**
     * 通知调拨服务「调拨单已出库、进入在途」。404 / 异常只记 WARN + 单据级 last_error，不得让出库单据失败。
     */
    private void notifyTransferInTransit(OutboundOrder order) {
        TransferInTransitCmd cmd = new TransferInTransitCmd();
        cmd.setTransferNo(order.getSourceNo());
        cmd.setOutboundNo(order.getOutboundNo());
        try {
            Result<TransferView> result = transferApi.markInTransit(cmd);
            if (result == null || !Integer.valueOf(200).equals(result.getCode())) {
                String code = result == null ? "null" : String.valueOf(result.getCode());
                String msg = result == null ? "调拨服务无响应" : result.getMsg();
                log.warn("调拨在途通知未成功（不阻断出库单据）, outboundNo={}, transferNo={}, code={}, msg={}",
                        order.getOutboundNo(), order.getSourceNo(), code, msg);
                recordOrderLastError(order.getOutboundNo(), "markInTransit code=" + code + ", msg=" + msg);
            }
        } catch (Exception e) {
            log.warn("调拨在途通知异常（不阻断出库单据）, outboundNo={}, transferNo={}, ex={}: {}",
                    order.getOutboundNo(), order.getSourceNo(), e.getClass().getSimpleName(), e.getMessage());
            recordOrderLastError(order.getOutboundNo(),
                    "markInTransit 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 单据级 last_error 落库（独立短事务），供补扫排查；不改变单据状态。 */
    private void recordOrderLastError(String outboundNo, String error) {
        String truncated = error == null ? "" : (error.length() > 500 ? error.substring(0, 500) : error);
        txTemplate.executeWithoutResult(s -> {
            OutboundOrder order = requireOrder(outboundNo);
            order.setLastError(truncated);
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
        });
    }

    /**
     * 回调 CTM：sourceSystem=CTM 时通过 Feign 调 IntegrationApi.ctmCallback，
     * orderNo 用单据 sourceNo；调用失败只记 ERROR + 单据号，不阻断出库主流程（与旧回调语义一致）。
     */
    private void notifySource(OutboundOrder order, String status, String message) {
        if ("CTM".equals(order.getSourceSystem())) {
            CtmCallbackCmd callbackCmd = CtmCallbackCmd.builder()
                    .orderNo(order.getSourceNo())
                    .status(status)
                    .message(message == null ? "" : message)
                    .build();
            try {
                Result<Void> result = integrationApi.ctmCallback(callbackCmd);
                if (result == null || !Integer.valueOf(200).equals(result.getCode())) {
                    log.error("回调 CTM 失败, 需由重试任务补偿, orderNo={}, status={}, result={}",
                            order.getSourceNo(), status, result == null ? null : result.getMsg());
                }
            } catch (Exception e) {
                log.error("回调 CTM 失败, 需由重试任务补偿, orderNo={}, status={}", order.getSourceNo(), status, e);
            }
            return;
        }
        if ("OMS".equals(order.getSourceSystem())) {
            notifyOms(order, status, message);
        }
    }

    /**
     * 通知 OMS：sourceSystem=OMS 时通过 Feign 调 IntegrationApi.notifyOmsStatus。
     * 失败只记 WARN + 单据级 last_error，绝不阻断单据终态（与调拨在途通知同一容错口径）。
     */
    private void notifyOms(OutboundOrder order, String status, String message) {
        OmsStatusNotifyCmd notifyCmd = OmsStatusNotifyCmd.builder()
                .omsOrderNo(order.getSourceNo())
                .orderNo(order.getOutboundNo())
                .status(status)
                .message(message == null ? "" : message)
                .build();
        try {
            Result<Void> result = integrationApi.notifyOmsStatus(notifyCmd);
            if (result == null || !Integer.valueOf(200).equals(result.getCode())) {
                String code = result == null ? "null" : String.valueOf(result.getCode());
                String msg = result == null ? "集成服务无响应" : result.getMsg();
                log.warn("通知 OMS 状态失败（不阻断出库单据）, outboundNo={}, omsOrderNo={}, status={}, code={}, msg={}",
                        order.getOutboundNo(), order.getSourceNo(), status, code, msg);
                recordOrderLastError(order.getOutboundNo(), "notifyOmsStatus code=" + code + ", msg=" + msg);
            }
        } catch (Exception e) {
            log.warn("通知 OMS 状态异常（不阻断出库单据）, outboundNo={}, omsOrderNo={}, status={}, ex={}: {}",
                    order.getOutboundNo(), order.getSourceNo(), status, e.getClass().getSimpleName(), e.getMessage());
            recordOrderLastError(order.getOutboundNo(),
                    "notifyOmsStatus 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 事务内确保明细对应一条 PENDING 任务；已存在（按 requestId）则跳过，唯一键兜底幂等。 */
    private void ensurePendingTask(OutboundOrder order, OutboundOrderItem item, LocalDateTime now) {
        String requestId = buildRequestId(order.getOutboundNo(), item);
        Long exists = taskMapper.selectCount(Wrappers.<OutboundStockTask>lambdaQuery()
                .eq(OutboundStockTask::getRequestId, requestId));
        if (exists != null && exists > 0) {
            return;
        }
        insertPendingTask(order, item, now);
    }

    private void insertPendingTask(OutboundOrder order, OutboundOrderItem item, LocalDateTime now) {
        OutboundStockTask task = new OutboundStockTask();
        task.setOutboundNo(order.getOutboundNo());
        task.setSkuCode(item.getSkuCode());
        task.setLocationCode(item.getLocationCode());
        task.setBatchNo(item.getBatchNo());
        task.setChangeQty(nvl(item.getPickedQty()).negate());
        task.setRequestId(buildRequestId(order.getOutboundNo(), item));
        task.setStatus("PENDING");
        task.setRetryCount(0);
        // PENDING 超时纳入扫描：next_retry_time = 创建时间 + pending-threshold-ms；所有时间字段统一应用时钟
        task.setNextRetryTime(now.plusNanos(properties.getPendingThresholdMs() * 1_000_000L));
        task.setOwner(null);
        task.setLeaseUntil(null);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        taskMapper.insert(task);
    }

    /** 续租：仅 owner 匹配才续；影响 0 行说明租约已被回收或 owner 不符。 */
    private void renewLease(OutboundStockTask task, String owner) {
        LocalDateTime now = LocalDateTime.now();
        int rows = taskMapper.renewLease(task.getId(), owner,
                now.plusSeconds(properties.getLeaseSeconds()), now);
        if (rows == 0) {
            log.warn("续租未命中（已被回收或 owner 不符）, taskId={}, requestId={}", task.getId(), task.getRequestId());
        }
    }

    private OutboundOrder requireOrder(String outboundNo) {
        OutboundOrder order = orderMapper.selectOne(
                Wrappers.<OutboundOrder>lambdaQuery().eq(OutboundOrder::getOutboundNo, outboundNo));
        if (order == null) {
            throw new BusinessException(404, "出库单不存在: " + outboundNo);
        }
        return order;
    }

    /**
     * 定位拣货明细行。
     * <ul>
     *   <li>指定 locationCode：按 SKU + 库位 + 批次（批次为空按 NULL）等值匹配，沿用原唯一行口径；</li>
     *   <li>locationCode 为空：按 SKU（+ 批次，若给了）宽松匹配；唯一一行直接拣货，多行返回 400 并附候选库位/批次。</li>
     * </ul>
     */
    private OutboundOrderItem resolvePickItem(String outboundNo, String skuCode,
                                              String locationCode, String batchNo) {
        String loc = blankToNull(locationCode);
        String batch = blankToNull(batchNo);
        var wrapper = Wrappers.<OutboundOrderItem>lambdaQuery()
                .eq(OutboundOrderItem::getOutboundNo, outboundNo)
                .eq(OutboundOrderItem::getSkuCode, skuCode);
        if (loc != null) {
            wrapper.eq(OutboundOrderItem::getLocationCode, loc);
        }
        if (batch != null) {
            wrapper.eq(OutboundOrderItem::getBatchNo, batch);
        } else if (loc != null) {
            // 指定库位但未给批次：空批次明细按 NULL 匹配（沿用原口径）
            wrapper.isNull(OutboundOrderItem::getBatchNo);
        }
        List<OutboundOrderItem> matches = itemMapper.selectList(wrapper);
        if (matches.isEmpty()) {
            throw new BusinessException(404, "出库明细不存在: " + outboundNo + "/" + skuCode + "/" + locationCode + "/" + batchNo);
        }
        if (loc != null) {
            // 指定库位：唯一键 uk_item_dim 兜底，多行属于数据异常，按不存在处理
            if (matches.size() > 1) {
                throw new BusinessException(404, "出库明细不存在: " + outboundNo + "/" + skuCode + "/" + locationCode + "/" + batchNo);
            }
            return matches.get(0);
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        // 多行同 SKU（跨库位/跨批次）：要求调用方指定库位与批次，附候选便于人工选择
        String candidates = matches.stream()
                .map(i -> "库位=" + dimText(i.getLocationCode()) + ", 批次=" + dimText(i.getBatchNo()))
                .collect(Collectors.joining("; "));
        throw new BusinessException(400, "该 SKU 存在多个可拣货明细，请指定 locationCode 与 batchNo 后重试；候选: " + candidates);
    }

    /**
     * 明细无库位（CTM 补货单）时，按当前可用库存回填唯一库位；
     * 无可用库存或跨多库位/批次无法判定时返回 400，提示指定 locationCode 与 batchNo。
     */
    private String resolveLocationFromStock(OutboundOrder order, OutboundOrderItem item) {
        Result<List<InventoryView>> result = inventoryApi.query(order.getWarehouseCode(), item.getSkuCode());
        if (result == null || !Integer.valueOf(200).equals(result.getCode()) || result.getData() == null) {
            throw new BusinessException(400, "无法解析拣货库位，请指定 locationCode: " + item.getSkuCode());
        }
        boolean itemBatchNull = isBlank(item.getBatchNo());
        List<InventoryView> stocked = result.getData().stream()
                .filter(v -> nvl(v.getQty()).compareTo(BigDecimal.ZERO) > 0)
                .filter(v -> itemBatchNull ? isBlank(v.getBatchNo()) : item.getBatchNo().equals(v.getBatchNo()))
                .collect(Collectors.toList());
        List<String> locations = stocked.stream()
                .map(InventoryView::getLocationCode).distinct().collect(Collectors.toList());
        if (locations.isEmpty()) {
            throw new BusinessException(400, "无可用库存库位，请指定 locationCode: " + item.getSkuCode());
        }
        if (locations.size() > 1) {
            String candidates = stocked.stream()
                    .map(v -> "库位=" + dimText(v.getLocationCode()) + ", 批次=" + dimText(v.getBatchNo()))
                    .collect(Collectors.joining("; "));
            throw new BusinessException(400, "该 SKU 存在多个可用库位，请指定 locationCode 与 batchNo 后重试；候选: " + candidates);
        }
        return locations.get(0);
    }

    private List<OutboundOrderItem> listItems(String outboundNo) {
        return itemMapper.selectList(
                Wrappers.<OutboundOrderItem>lambdaQuery().eq(OutboundOrderItem::getOutboundNo, outboundNo));
    }

    /**
     * 幂等键（biz_key）：OUT:{outboundNo}:{skuCode}:{locationCode}:{batchNo或NOBATCH}
     * 同一单据同一 SKU 不同库位/批次生成不同 requestId。
     */
    private String buildRequestId(String outboundNo, OutboundOrderItem item) {
        String batch = item.getBatchNo() == null || item.getBatchNo().isBlank() ? NO_BATCH : item.getBatchNo();
        return "OUT:" + outboundNo + ":" + item.getSkuCode() + ":" + item.getLocationCode() + ":" + batch;
    }

    /** 单据状态事件 payload，字段名与旧单体一致：orderNo / status / message。 */
    private Map<String, Object> buildOrderStatusPayload(String orderNo, String status, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", orderNo);
        payload.put("status", status);
        payload.put("message", message == null ? "" : message);
        return payload;
    }

    /** 认领者标识：应用名:端口:线程名。 */
    private String buildOwner() {
        String app = environment.getProperty("spring.application.name", "unknown");
        String port = environment.getProperty("server.port", "unknown");
        return app + ":" + port + ":" + Thread.currentThread().getName();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int nvlInt(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String dimText(String value) {
        return isBlank(value) ? "(空)" : value;
    }
}
