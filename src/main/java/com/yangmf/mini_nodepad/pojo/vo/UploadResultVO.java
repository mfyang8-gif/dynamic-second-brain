// pojo/vo/UploadResultVO.java
package com.yangmf.mini_nodepad.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResultVO {

    /** 公网访问 URL（前端直接用于 <img src> 等） */
    private String url;

    /** OSS 对象键（如需持久化，删除文件时必须依赖它，建议前端表单同时回传） */
    private String objectName;

    /** 文件大小（字节） */
    private Long size;
}