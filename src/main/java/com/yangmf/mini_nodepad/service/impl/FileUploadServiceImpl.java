package com.yangmf.mini_nodepad.service.impl;

import com.yangmf.mini_nodepad.enums.UploadModule;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.pojo.vo.UploadResultVO;
import com.yangmf.mini_nodepad.service.FileUploadService;
import com.yangmf.mini_nodepad.utils.AliOssUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl implements FileUploadService {

    private final AliOssUtil aliOssUtil;

    @Override
    public UploadResultVO upload(MultipartFile file, UploadModule module, Long userId) {
        // 1. 类型白名单校验
        String contentType = file.getContentType();
        if (contentType == null || !module.getAllowedContentTypes().contains(contentType)) {
            throw new ValidationException("不支持的文件类型: " + contentType
                    + "，该模块仅允许 " + module.getAllowedContentTypes());
        }
        // 2. 业务层大小校验（容器上限 25MB 刻意大于业务最大 20MB，保证能走到这里优雅报错）
        if (file.getSize() > module.getMaxFileSize()) {
            throw new ValidationException("文件超过大小限制: "
                    + module.getMaxFileSize() / 1024 / 1024 + "MB");
        }

        // 3. 扩展名由服务端根据 Content-Type 反推，杜绝客户端伪造文件名注入；路径按用户隔离
        String objectName = "%s/%d/%s%s".formatted(
                module.getDir(), userId,
                UUID.randomUUID().toString().replace("-", ""),
                module.resolveExtension(contentType));

        try {
            String url = aliOssUtil.upload(file.getBytes(), objectName);
            log.info("文件上传成功 | module={}, userId={}, objectName={}", module, userId, objectName);

            // 4. 知识源文件属私密对象：换成 2 小时限时签名 URL，杜绝永久公共直链泄露；
            //    头像/通用附件本就对外展示，维持永久直链
            if (module == UploadModule.SOURCE) {
                url = aliOssUtil.generatePresignedUrl(objectName);
            }

            return UploadResultVO.builder()
                    .url(url)
                    .objectName(objectName)
                    .size(file.getSize())
                    .build();
        } catch (IOException e) {
            throw new ValidationException("读取上传文件失败", e);
        }
    }
}