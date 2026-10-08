// src/test/java/com/yangmf/mini_nodepad/AliOssUtilTest.java
package com.yangmf.mini_nodepad;

import com.yangmf.mini_nodepad.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
public class AliOssUtilTest {

    @Autowired
    private AliOssUtil aliOssUtil;

    @Test
    void testUploadAndDelete() {
        byte[] content = "Hello OSS Test - 测试中文内容".getBytes(StandardCharsets.UTF_8);
        String objectName = "test/" + aliOssUtil.generateFileName("test.txt");

        String url = aliOssUtil.upload(content, objectName);
        log.info("上传返回 URL: {}", url);

        assertNotNull(url);
        assertTrue(url.contains(objectName));
        assertTrue(url.startsWith("https://"));

        assertDoesNotThrow(() -> aliOssUtil.deleteFile(objectName));
    }

    @Test
    void testGenerateFileName() {
        String name1 = aliOssUtil.generateFileName("photo.jpg");
        String name2 = aliOssUtil.generateFileName("photo.jpg");

        assertNotEquals(name1, name2);
        assertTrue(name1.endsWith(".jpg"));
        assertEquals(32 + 4, name1.length());

        String noExt = aliOssUtil.generateFileName("noext");
        assertFalse(noExt.contains("."));
        assertEquals(32, noExt.length());

        String nullName = aliOssUtil.generateFileName(null);
        assertEquals(32, nullName.length());
    }

    @Test
    void testUploadInvalidBucket() {
        assertThrows(Exception.class, () ->
                aliOssUtil.upload("test".getBytes(), "test/fail.txt")
        );
    }
}