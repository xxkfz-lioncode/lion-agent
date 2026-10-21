package com.lion.agent.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置
 *
 * <p>核心是分页拦截器：没有它，{@code mapper.selectPage(page, wrapper)} 只会执行主查询，
 * 不会执行 COUNT(*) 统计，导致 {@code page.getTotal()} 恒为 0（列表有数据但 total=0、
 * 分页条不显示）。所有返回 {@link com.lion.agent.common.result.PageResult} 的接口都依赖此配置。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页拦截器：自动改写 SQL 为 MySQL 方言并回填 total / pages。
     *
     * @return MyBatis-Plus 插件链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大条数上限，防止前端传超大 pageSize 拖垮数据库
        pagination.setMaxLimit(500L);
        // 页码超出总页数时不自动回到首页，交由前端/业务自行处理
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
