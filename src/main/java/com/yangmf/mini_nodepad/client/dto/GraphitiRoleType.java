package com.yangmf.mini_nodepad.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Graphiti 消息角色类型。
 *
 * <p>服务端 pydantic 用 {@code Literal['user','assistant','system']} 约束该字段，
 * 越界即 422（附录 B 陷阱 3）。用枚举把约束前移到<b>编译期</b> ——
 * 字符串常量拼错要到运行期才暴露，而这里写错直接编译不过。
 *
 * <p>注意：Java 侧枚举名是大写 {@code USER}，线上字面量必须是<b>小写</b> {@code user}，
 * 因此必须靠 {@link JsonValue} 显式下发，不能依赖 Jackson 默认的枚举名序列化。
 */
public enum GraphitiRoleType {

    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    /** 服务端字面量 */
    private final String wireValue;

    GraphitiRoleType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 序列化为小写字面量。 */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** 反序列化（大小写不敏感），非法取值直接抛错而不是静默降级。 */
    @JsonCreator
    public static GraphitiRoleType fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (GraphitiRoleType type : values()) {
            if (type.wireValue.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "非法的 role_type: " + value + "（只允许 user / assistant / system）");
    }
}
