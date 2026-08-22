package com.lion.agent.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应结构
 *
 * @param <T> 数据类型
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页 */
    private long pageNum;

    /** 每页条数 */
    private long pageSize;

    /** 总条数 */
    private long total;

    /** 总页数 */
    private long pages;

    /** 当前页数据 */
    private List<T> list;

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> list) {
        PageResult<T> result = new PageResult<>();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setTotal(total);
        result.setPages(total == 0 ? 0 : (total + pageSize - 1) / pageSize);
        result.setList(list);
        return result;
    }
}
