// config/AliOssConfig.java
package com.yangmf.mini_nodepad.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.yangmf.mini_nodepad.properties.AliOssProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AliOssConfig {

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(AliOssProperties properties) {
        log.info("初始化阿里云 OSS 客户端 | endpoint={}", properties.getEndpoint());
        return new OSSClientBuilder().build(
                properties.getEndpoint(),
                properties.getAccessKeyId(),
                properties.getAccessKeySecret()
        );
    }
}