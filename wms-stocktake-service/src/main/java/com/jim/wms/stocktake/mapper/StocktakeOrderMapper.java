package com.jim.wms.stocktake.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.wms.stocktake.entity.StocktakeOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StocktakeOrderMapper extends BaseMapper<StocktakeOrder> {
}
