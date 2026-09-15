package com.yangmf.mini_nodepad.enums;


public enum SourceTypeEnum {
    /**
     * 手动输入
     */
    MANUAL("MANUAL", "手动录入"),
    /**
     * 网页
     */
    WEB("WEB", "网页"),
    /**
     * 混合
     */
    HYBRID("HYBRID", "混合");
    private String code;
    private String description;
    SourceTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
