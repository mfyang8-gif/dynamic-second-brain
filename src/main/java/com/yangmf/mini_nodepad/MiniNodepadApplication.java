package com.yangmf.mini_nodepad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@SpringBootApplication
@EnableRetry
// Phase 4 · B1：GraphitiBackpressureGuard / GraphitiConfirmJob / GraphitiReplayJob
// 三个 @Scheduled 任务依赖它。⚠️ 缺了它不会报错，只会「定时任务静默不执行」——
// 而其中两个是对账与补投：少了它们，失败的消息既不会被确认、也不会被补投，
// 表现为「记忆偶尔丢一批，且日志里什么都看不到」。
@EnableScheduling
public class MiniNodepadApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniNodepadApplication.class, args);
    }

}
