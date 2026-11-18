package com.jim.wms.transfer.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.wms.common.dto.TransferView;
import com.jim.wms.common.exception.BusinessException;
import com.jim.wms.common.util.IdGenerator;
import com.jim.wms.transfer.entity.TransferOrder;
import com.jim.wms.transfer.mapper.TransferOrderMapper;
import com.jim.wms.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    private static final String STATUS_FINISHED = "FINISHED";

    private final TransferOrderMapper transferOrderMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferOrder create(TransferOrder order) {
        if (order.getFromWarehouseCode() == null || order.getToWarehouseCode() == null) {
            throw new BusinessException(400, "调出仓 / 调入仓不能为空");
        }
        order.setTransferNo(IdGenerator.nextNo("TR"));
        order.setStatus(STATUS_CREATED);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        transferOrderMapper.insert(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markOutbound(String transferNo) {
        TransferOrder order = require(transferNo);
        if (!STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(400, "当前状态不允许调拨出库: " + order.getStatus());
        }
        order.setStatus(STATUS_IN_TRANSIT);
        order.setUpdateTime(LocalDateTime.now());
        transferOrderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReceived(String transferNo) {
        TransferOrder order = require(transferNo);
        if (!STATUS_IN_TRANSIT.equals(order.getStatus())) {
            throw new BusinessException(400, "调拨单不在在途状态，无法收货确认");
        }
        order.setStatus(STATUS_FINISHED);
        order.setUpdateTime(LocalDateTime.now());
        transferOrderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferView markInTransit(String transferNo, String outboundNo) {
        TransferOrder order = resolve(transferNo);
        if (order == null) {
            throw new BusinessException(404, "调拨单不存在: " + transferNo);
        }
        boolean changed = false;

        // 状态机：仅 CREATED -> IN_TRANSIT；IN_TRANSIT / FINISHED 幂等，不允许回退。
        if (STATUS_CREATED.equals(order.getStatus())) {
            order.setStatus(STATUS_IN_TRANSIT);
            changed = true;
        }
        // 回填出库单号：仅当入参非空且库内为空时补齐。
        if (outboundNo != null && !outboundNo.isBlank()
                && (order.getOutboundNo() == null || order.getOutboundNo().isBlank())) {
            order.setOutboundNo(outboundNo.trim());
            changed = true;
        }
        if (changed) {
            order.setUpdateTime(LocalDateTime.now());
            transferOrderMapper.updateById(order);
        }
        return toView(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferView receive(String transferNo) {
        TransferOrder order = resolve(transferNo);
        if (order == null) {
            throw new BusinessException(404, "调拨单不存在: " + transferNo);
        }
        if (STATUS_FINISHED.equals(order.getStatus())) {
            // 幂等：已 FINISHED 重复收货直接返回当前状态。
            return toView(order);
        }
        if (!STATUS_IN_TRANSIT.equals(order.getStatus())) {
            throw new BusinessException(400, "调拨单不在在途状态，无法收货确认: " + order.getStatus());
        }
        order.setStatus(STATUS_FINISHED);
        order.setUpdateTime(LocalDateTime.now());
        transferOrderMapper.updateById(order);
        return toView(order);
    }

    /** 先按 transfer_no 精确匹配，未命中再按 source_no 匹配（CTM 单号兜底）。 */
    private TransferOrder resolve(String transferNo) {
        TransferOrder order = transferOrderMapper.selectOne(Wrappers.<TransferOrder>lambdaQuery()
                .eq(TransferOrder::getTransferNo, transferNo));
        if (order != null) {
            return order;
        }
        return transferOrderMapper.selectOne(Wrappers.<TransferOrder>lambdaQuery()
                .eq(TransferOrder::getSourceNo, transferNo));
    }

    private TransferOrder require(String transferNo) {
        TransferOrder order = transferOrderMapper.selectOne(Wrappers.<TransferOrder>lambdaQuery()
                .eq(TransferOrder::getTransferNo, transferNo));
        if (order == null) {
            throw new BusinessException(404, "调拨单不存在: " + transferNo);
        }
        return order;
    }

    private TransferView toView(TransferOrder order) {
        TransferView view = new TransferView();
        view.setTransferNo(order.getTransferNo());
        view.setFromWarehouseCode(order.getFromWarehouseCode());
        view.setToWarehouseCode(order.getToWarehouseCode());
        view.setSkuCode(order.getSkuCode());
        view.setBatchNo(order.getBatchNo());
        view.setQty(order.getQty());
        view.setStatus(order.getStatus());
        return view;
    }
}
