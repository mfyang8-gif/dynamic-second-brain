package com.yangmf.mini_nodepad.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mfyang.alioss")
public class AliOssProperties {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

    /** 私有文件签名 URL 有效期（小时），默认 2 小时 */
    private Integer presignExpireHours = 2;
}