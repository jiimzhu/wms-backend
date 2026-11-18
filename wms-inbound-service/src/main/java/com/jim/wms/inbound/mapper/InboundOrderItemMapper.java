package com.jim.wms.inbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.wms.inbound.entity.InboundOrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InboundOrderItemMapper extends BaseMapper<InboundOrderItem> {
}
