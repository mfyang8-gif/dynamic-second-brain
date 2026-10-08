// enums/UploadModule.java
package com.yangmf.mini_nodepad.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Set;

/**
 * 文件上传模块枚举：集中定义各业务管线的类型白名单与大小上限。
 */
@Getter
@RequiredArgsConstructor
public enum UploadModule {

    /** 用户头像 */
    AVATAR("avatar", Set.of("image/png", "image/jpeg", "image/webp", "image/gif"), 5L * 1024 * 1024),

    /** 知识源文件（待解析入库的文档） */
    SOURCE("source", Set.of(
            "text/plain", "text/markdown", "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            20L * 1024 * 1024),

    /** 通用附件 */
    GENERAL("general", Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif",
            "application/pdf", "text/plain", "text/markdown"),
            10L * 1024 * 1024);

    /** OSS 存储目录前缀 */
    private final String dir;

    /** 允许的 Content-Type 白名单 */
    private final Set<String> allowedContentTypes;

    /** 单文件大小上限（字节） */
    private final long maxFileSize;

    /** Content-Type → 文件扩展名（由服务端反推，不信任客户端文件名） */
    private static final Map<String, String> EXTENSION_MAP = Map.ofEntries(
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/gif", ".gif"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/markdown", ".md"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx")
    );

    public String resolveExtension(String contentType) {
        return EXTENSION_MAP.getOrDefault(contentType, "");
    }
}