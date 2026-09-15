package com.yangmf.mini_nodepad.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@EnableAsync //  开启全局异步支持
@Configuration
public class ThreadPoolConfig {

    public static final String AI_TASK_EXECUTOR = "aiTaskExecutor";

    @Bean(AI_TASK_EXECUTOR)
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：日常保持几个线程随时待命 (建议 5)
        executor.setCorePoolSize(5);
        // 最大线程数：遇到大流量最多开几个线程并调大模型 (结合你大模型的并发限流，建议 10-20)
        executor.setMaxPoolSize(15);
        // 队列容量：线程满了之后，允许多少个任务在队列里排队 (建议 100)
        executor.setQueueCapacity(100);
        // 线程名前缀：排查日志时一目了然
        executor.setThreadNamePrefix("AI-Wash-");
        
        //  拒绝策略：如果队列也满了，再来的任务怎么处理？
        // CallerRunsPolicy: 谁提交的任务谁自己去执行 (主线程会被阻塞，变相实现限流)
        // AbortPolicy: 直接抛异常 (前端会提示服务器繁忙)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.initialize();
        log.info(" AI 专属有界线程池 [{}] 初始化完成", AI_TASK_EXECUTOR);
        return executor;
    }
}