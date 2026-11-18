package com.jim.wms.outbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.wms.outbound.entity.OutboundOrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OutboundOrderItemMapper extends BaseMapper<OutboundOrderItem> {
}
