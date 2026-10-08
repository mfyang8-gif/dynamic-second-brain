// pojo/dto/studio/BaseStudioRequest.java
package com.yangmf.mini_nodepad.pojo.dto.studio.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public abstract class BaseStudioRequest {

    @NotBlank(message = "bookId 不能为空")
    private String bookId;

    private List<String> sourcePageIds;

    private String topic;

    private String language = "zh-CN";
}