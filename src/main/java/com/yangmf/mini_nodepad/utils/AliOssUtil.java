package com.yangmf.mini_nodepad.utils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ClientException;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.properties.AliOssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AliOssUtil {

    private final OSS ossClient;
    private final AliOssProperties aliOssProperties;

    public String upload(byte[] bytes, String objectName) {
        return upload(new ByteArrayInputStream(bytes), objectName);
    }

    public String upload(InputStream inputStream, String objectName) {
        String bucketName = aliOssProperties.getBucketName();
        try {
            ossClient.putObject(bucketName, objectName, inputStream);
            String url = buildAccessUrl(bucketName, objectName);
            log.info("文件上传成功 | objectName={}, url={}", objectName, url);
            return url;
        } catch (OSSException oe) {
            log.error("OSS 服务端异常 | objectName={}, code={}, msg={}",
                    objectName, oe.getErrorCode(), oe.getErrorMessage());
            throw new BusinessException("文件上传失败: " + oe.getErrorMessage(), oe);
        } catch (ClientException ce) {
            log.error("OSS 客户端异常 | objectName={}, msg={}", objectName, ce.getMessage());
            throw new BusinessException("文件上传失败: 网络异常", ce);
        }
    }

    public String generateFileName(String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        return UUID.randomUUID().toString().replace("-", "") + extension;
    }

    public void deleteFile(String objectName) {
        String bucketName = aliOssProperties.getBucketName();
        try {
            ossClient.deleteObject(bucketName, objectName);
            log.info("文件删除成功 | objectName={}", objectName);
        } catch (OSSException oe) {
            log.error("OSS 删除失败 | objectName={}, code={}, msg={}",
                    objectName, oe.getErrorCode(), oe.getErrorMessage());
            throw new BusinessException("文件删除失败: " + oe.getErrorMessage(), oe);
        } catch (ClientException ce) {
            log.error("OSS 客户端异常 | objectName={}, msg={}", objectName, ce.getMessage());
            throw new BusinessException("文件删除失败: 网络异常", ce);
        }
    }

    private String buildAccessUrl(String bucketName, String objectName) {
        String endpoint = aliOssProperties.getEndpoint();
        String host = endpoint.startsWith("http") ? endpoint : "https://" + endpoint;
        host = host.replace("://", "://" + bucketName + ".");
        return host + "/" + objectName;
    }
}