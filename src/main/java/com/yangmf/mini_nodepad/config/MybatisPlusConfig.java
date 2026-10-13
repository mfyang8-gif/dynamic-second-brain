package com.yangmf.mini_nodepad.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 核心配置类
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * ⚠️ PaginationInnerInterceptor 必须注册，否则 Page 对象形同虚设：
     * selectPage 不会拼 LIMIT，mapper 会把全表拉回内存（OOM 防线从这里开始）。
     * 显式指定 DbType.MYSQL，避免运行时再去探测数据源（首次探测有额外开销）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        // MVP 阶段采用不可变数据策略（只增只删不改），乐观锁暂不需要
        // 未来如果开放编辑功能，取消下面注释即可恢复乐观锁机制
        // interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }
}
