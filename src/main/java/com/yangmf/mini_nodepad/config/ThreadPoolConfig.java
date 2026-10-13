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

    public static final String STUDIO_TASK_EXECUTOR = "studioTaskExecutor";

    public static final String TITLE_TASK_EXECUTOR = "titleTaskExecutor";

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


    /**
     * 工件"暂定名"专用池。
     *
     * <p>与生成池（STUDIO_TASK_EXECUTOR）分开的理由：生成池 core 只有 3，
     * 暂定名若抢它的名额，3 个生成任务占满核心线程后命名任务会排到队列，
     * 甚至触发 CallerRunsPolicy 回落到调用方线程，反而拖慢主生成。
     *
     * <p>与通用 AI 池（AI_TASK_EXECUTOR）分开的理由：通用池是 CallerRunsPolicy，
     * 拒绝时【不抛异常】而是内联执行 —— 而暂定名是在 HTTP 请求线程里派发的，
     * 一旦内联就会把 LLM 调用压在接口线程上。
     * 这里刻意用 AbortPolicy：拒绝即抛 RejectedExecutionException，
     * 调用方 catch 后静默降级（前端回退显示 topic），绝不阻塞任何线程。
     */
    @Bean(TITLE_TASK_EXECUTOR)
    public Executor titleTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("Studio-Title-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("工件暂定名线程池 [{}] 初始化完成", TITLE_TASK_EXECUTOR);
        return executor;
    }

    @Bean(STUDIO_TASK_EXECUTOR)
    public Executor studioTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("Studio-Gen-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Studio 生成任务线程池 [{}] 初始化完成", STUDIO_TASK_EXECUTOR);
        return executor;
    }
}