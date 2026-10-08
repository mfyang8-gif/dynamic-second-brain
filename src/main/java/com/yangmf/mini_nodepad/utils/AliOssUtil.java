package com.yangmf.mini_nodepad.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.properties.AliOssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;
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

    /**
     * 生成带签名的临时访问 URL（有效期取 mfyang.alioss.presign-expire-hours，默认 2 小时）。
     * 用于知识源文件等私密对象：链接到期自动失效，规避公共读直链的永久泄露风险。
     */
    public String generatePresignedUrl(String objectName) {
        Integer hours = aliOssProperties.getPresignExpireHours();
        return generatePresignedUrl(objectName, hours == null ? 2 : hours);
    }

    public String generatePresignedUrl(String objectName, int expireHours) {
        String bucketName = aliOssProperties.getBucketName();
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.HOUR_OF_DAY, expireHours);
            Date expiration = calendar.getTime();
            URL url = ossClient.generatePresignedUrl(bucketName, objectName, expiration, HttpMethod.GET);
            log.info("签名 URL 生成成功 | objectName={}, 有效期至={}", objectName, expiration);
            return url.toString();
        } catch (OSSException | ClientException e) {
            log.error("生成签名 URL 失败 | objectName={}, msg={}", objectName, e.getMessage());
            throw new BusinessException("生成文件访问链接失败: " + e.getMessage(), e);
        }
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