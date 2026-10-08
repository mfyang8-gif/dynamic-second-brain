package com.yangmf.mini_nodepad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;

@EnableCaching
@SpringBootApplication
@EnableRetry
public class MiniNodepadApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniNodepadApplication.class, args);
    }

}
