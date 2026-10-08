package com.yangmf.mini_nodepad.pojo.dto.auth;

import com.yangmf.mini_nodepad.pojo.vo.UserVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "认证响应")
public class AuthResponse {

    @Schema(description = "JWT 访问令牌")
    private String token;

    @Schema(description = "令牌有效期（秒）")
    private Long expiresIn;

    @Schema(description = "用户信息")
    private UserVO user;
}