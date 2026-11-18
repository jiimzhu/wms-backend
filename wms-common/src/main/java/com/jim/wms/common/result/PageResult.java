package com.jim.wms.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页返回体（结构沿用旧单体）
 */
@Data
public class PageResult<T> implements Serializable {

    private Long total;
    private Long pageNum;
    private Long pageSize;
    private List<T> records;

    public static <T> PageResult<T> of(Long total, Long pageNum, Long pageSize, List<T> records) {
        PageResult<T> page = new PageResult<>();
        page.setTotal(total);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        page.setRecords(records);
        return page;
    }
}
