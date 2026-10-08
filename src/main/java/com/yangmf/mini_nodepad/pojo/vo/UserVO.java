package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "用户信息（安全，不含密码）")
public class UserVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像地址")
    private String avatarUrl;

    @Schema(description = "注册时间")
    private LocalDateTime createdAt;
}