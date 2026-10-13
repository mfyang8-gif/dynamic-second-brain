package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "重新生成请求")
public class RegenerateDTO {

    @Size(max = 500, message = "修正指令不能超过500字")
    @Schema(description = "可选修正指令，例如：请用Python重写；严禁携带 userId，服务端身份一律取自登录上下文")
    private String instruction;
}
