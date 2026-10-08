// controller/FileController.java
package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.enums.UploadModule;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.pojo.vo.UploadResultVO;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.FileUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Tag(name = "文件上传", description = "通用文件上传（头像/知识源文件/附件），按模块校验类型与大小")
public class FileController {

    private final FileUploadService fileUploadService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件",
            description = "multipart/form-data 提交。module: avatar-头像(图片≤5MB) | source-知识源文件(≤20MB) | general-通用附件(≤10MB)")
    public Result<UploadResultVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "module", defaultValue = "general") String module) {
        if (file.isEmpty()) {
            throw new ValidationException("上传文件不能为空");
        }
        UploadModule uploadModule;
        try {
            uploadModule = UploadModule.valueOf(module.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("无效的上传模块: " + module);
        }
        return Result.success(fileUploadService.upload(file, uploadModule, BaseContext.getCurrentId()));
    }
}