package com.yangmf.mini_nodepad.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "studio.pandoc")
public class PandocProperties {
    private String apiUrl;
}