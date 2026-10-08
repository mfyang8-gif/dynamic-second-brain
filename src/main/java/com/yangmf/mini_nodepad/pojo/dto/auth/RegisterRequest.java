package com.yangmf.mini_nodepad.pojo.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "注册请求")
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度须在 3~20 个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    @Schema(description = "用户名")
    private String username;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱")
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 个字符之间")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$", message = "密码必须包含至少一个字母和一个数字")
    @Schema(description = "密码（至少8位，含字母+数字）")
    private String password;

    @Size(max = 30, message = "昵称最长30个字符")
    @Schema(description = "昵称（可选，默认同用户名）")
    private String nickname;
}