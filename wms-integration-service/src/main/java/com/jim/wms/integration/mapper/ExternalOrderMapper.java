package com.jim.wms.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.wms.integration.entity.ExternalOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExternalOrderMapper extends BaseMapper<ExternalOrder> {
}
