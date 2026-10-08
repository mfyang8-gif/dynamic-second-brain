package com.yangmf.mini_nodepad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.enums.studio.ReportFormatEnum;
import com.yangmf.mini_nodepad.enums.studio.WidgetStatusEnum;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ArtifactPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ReportPayload;
import com.yangmf.mini_nodepad.properties.PandocProperties;
import com.yangmf.mini_nodepad.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
public class PandocServiceTest {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private PandocProperties pandocProperties;

    @Autowired
    private AliOssUtil aliOssUtil;

    @Autowired
    private ObjectMapper objectMapper;

    private static final Pattern WIDGET_PATTERN = Pattern.compile(
            "\\[WIDGET:(?<type>\\w+),\\s*id=\"(?<id>[^\"]+)\",\\s*title=\"(?<title>[^\"]+)\",\\s*prompt=\"(?<prompt>[^\"]+)\"\\]"
    );

    // ======================== 1. Pandoc 微服务连通性测试 ========================

    @Test
    void testPandocApiConvert() {
        String markdown = """
                # 测试报告
                
                ## 摘要
                这是一份用于测试 Pandoc 微服务渲染能力的 Markdown 文档。
                
                ## 正文
                - **核心概念**：Spring Boot 自动装配机制
                - **关键注解**：`@SpringBootApplication`、`@EnableAutoConfiguration`
                
                ## 总结
                Spring Boot 通过约定优于配置的理念大幅简化了 Java 应用的开发。
                """;

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("markdown", markdown);

        byte[] docxBytes = restTemplate.postForObject(
                pandocProperties.getApiUrl(),
                requestBody,
                byte[].class
        );

        assertNotNull(docxBytes, "Pandoc 返回的 DOCX 字节数组不应为 null");
        assertTrue(docxBytes.length > 0, "DOCX 文件不应为空，实际长度: " + docxBytes.length);

        log.info("Pandoc 渲染成功 | 输入Markdown长度={}, 输出DOCX大小={}bytes",
                markdown.length(), docxBytes.length);
    }

    @Test
    void testPandocApiConvertAndUploadOss() {
        String markdown = """
                # 完整链路测试
                
                ## 引言
                本测试验证 Pandoc 渲染 → OSS 上传的完整闭环。
                
                ## 核心内容
                1. RAG 检索增强生成
                2. 向量数据库 Qdrant
                3. LangChain4j AI 框架
                
                ## 结论
                以上技术栈构成了现代 AI 应用的基础设施层。
                """;

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("markdown", markdown);

        byte[] docxBytes = restTemplate.postForObject(
                pandocProperties.getApiUrl(),
                requestBody,
                byte[].class
        );
        assertNotNull(docxBytes);
        assertTrue(docxBytes.length > 0);

        String objectName = aliOssUtil.generateFileName("test-report.docx");
        String fileUrl = aliOssUtil.upload(docxBytes, "studio/reports/test/" + objectName);

        assertNotNull(fileUrl);
        assertTrue(fileUrl.startsWith("https://"));
        log.info("完整链路测试成功 | fileUrl={}", fileUrl);

        aliOssUtil.deleteFile("studio/reports/test/" + objectName);
        log.info("测试文件已清理");
    }

    // ======================== 2. ReportPayload 序列化/反序列化验证 ========================

    @Test
    void testReportPayloadSerialization_DocumentMode() throws JsonProcessingException {
        ReportPayload payload = new ReportPayload();
        payload.setFormat(ReportFormatEnum.DOCUMENT);
        payload.setMarkdown("# 静态报告\n\n这是纯文档模式的内容。");
        payload.setFileUrl("https://oss.example.com/report.docx");
        payload.setWidgetStates(null);

        String json = objectMapper.writeValueAsString(payload);
        log.info("DOCUMENT 模式序列化结果:\n{}", json);

        assertTrue(json.contains("\"artifactType\":\"REPORT\""));
        assertTrue(json.contains("\"format\":\"DOCUMENT\""));
        assertTrue(json.contains("\"fileUrl\":\"https://oss.example.com/report.docx\""));
        assertFalse(json.contains("\"code\""));
        assertFalse(json.contains("\"description\""));

        ArtifactPayload deserialized = objectMapper.readValue(json, ArtifactPayload.class);
        assertInstanceOf(ReportPayload.class, deserialized);

        ReportPayload restored = (ReportPayload) deserialized;
        assertEquals(ReportFormatEnum.DOCUMENT, restored.getFormat());
        assertEquals("# 静态报告\n\n这是纯文档模式的内容。", restored.getMarkdown());
        assertEquals("https://oss.example.com/report.docx", restored.getFileUrl());
        assertNull(restored.getWidgetStates());
    }

    @Test
    void testReportPayloadSerialization_InteractiveMode() throws JsonProcessingException {
        ReportPayload payload = new ReportPayload();
        payload.setFormat(ReportFormatEnum.INTERACTIVE);
        payload.setMarkdown("# 互动报告\n\n正文\n[WIDGET:QUIZ, id=\"w_1\", title=\"测验\", prompt=\"生成测验\"]");
        payload.setFileUrl(null);

        Map<String, ReportPayload.WidgetState> widgetStates = new LinkedHashMap<>();
        widgetStates.put("w_1", ReportPayload.WidgetState.builder()
                .status(WidgetStatusEnum.RECOMMENDED)
                .targetArtifactId(null)
                .build());
        widgetStates.put("w_2", ReportPayload.WidgetState.builder()
                .status(WidgetStatusEnum.GENERATED_SHOW)
                .targetArtifactId("quiz-artifact-id-123")
                .build());
        payload.setWidgetStates(widgetStates);

        String json = objectMapper.writeValueAsString(payload);
        log.info("INTERACTIVE 模式序列化结果:\n{}", json);

        assertTrue(json.contains("\"format\":\"INTERACTIVE\""));
        assertTrue(json.contains("\"w_1\""));
        assertTrue(json.contains("\"RECOMMENDED\""));
        assertTrue(json.contains("\"GENERATED_SHOW\""));
        assertTrue(json.contains("\"quiz-artifact-id-123\""));

        ArtifactPayload deserialized = objectMapper.readValue(json, ArtifactPayload.class);
        assertInstanceOf(ReportPayload.class, deserialized);

        ReportPayload restored = (ReportPayload) deserialized;
        assertEquals(ReportFormatEnum.INTERACTIVE, restored.getFormat());
        assertNotNull(restored.getWidgetStates());
        assertEquals(2, restored.getWidgetStates().size());
        assertEquals(WidgetStatusEnum.RECOMMENDED, restored.getWidgetStates().get("w_1").getStatus());
        assertNull(restored.getWidgetStates().get("w_1").getTargetArtifactId());
        assertEquals(WidgetStatusEnum.GENERATED_SHOW, restored.getWidgetStates().get("w_2").getStatus());
        assertEquals("quiz-artifact-id-123", restored.getWidgetStates().get("w_2").getTargetArtifactId());
    }

    // ======================== 3. Widget 正则解析验证 ========================

    @Test
    void testWidgetPatternParsing() {
        String markdown = """
                # 知识报告
                
                ## 第一章：核心概念
                
                这里是第一章的正文内容...
                
                [WIDGET:QUIZ, id="w_1", title="核心概念测验", prompt="基于第一章内容生成5道选择题"]
                
                ## 第二章：框架设计
                
                这里是第二章的正文内容...
                
                [WIDGET:MINDMAP, id="w_2", title="框架设计导图", prompt="梳理第二章的架构层级关系"]
                
                ## 第三章：术语表
                
                这里罗列了大量专业术语...
                
                [WIDGET:FLASHCARD, id="w_3", title="术语速记卡", prompt="提取第三章所有专业名词生成卡片"]
                
                ## 总结
                以上是全部知识点的总结。
                """;

        Matcher matcher = WIDGET_PATTERN.matcher(markdown);
        Map<String, String> foundWidgets = new LinkedHashMap<>();
        while (matcher.find()) {
            foundWidgets.put(matcher.group("id"), matcher.group("type"));
        }

        assertEquals(3, foundWidgets.size());
        assertEquals("QUIZ", foundWidgets.get("w_1"));
        assertEquals("MINDMAP", foundWidgets.get("w_2"));
        assertEquals("FLASHCARD", foundWidgets.get("w_3"));

        log.info("正则解析成功 | 找到 {} 个 Widget: {}", foundWidgets.size(), foundWidgets);
    }

    @Test
    void testWidgetPatternNoMatch() {
        String cleanMarkdown = "# 纯文档\n\n这是一份没有 Widget 占位符的报告。\n\n## 结论\n内容完整，无需互动组件。";

        Matcher matcher = WIDGET_PATTERN.matcher(cleanMarkdown);
        assertFalse(matcher.find(), "纯净 Markdown 不应匹配到任何 Widget 占位符");
    }

    // ======================== 4. 导出清洗逻辑验证 ========================

    @Test
    void testCleanWidgetsForExport() {
        String markdown = """
                # 报告标题
                
                ## 第一章
                
                第一章正文。
                
                [WIDGET:QUIZ, id="w_1", title="测验卡片", prompt="生成测验"]
                
                ## 第二章
                
                第二章正文。
                
                [WIDGET:MINDMAP, id="w_2", title="思维导图", prompt="生成导图"]
                
                ## 第三章
                
                第三章正文。
                
                [WIDGET:FLASHCARD, id="w_3", title="记忆卡片", prompt="生成卡片"]
                """;

        ReportPayload payload = new ReportPayload();
        payload.setMarkdown(markdown);
        payload.setFormat(ReportFormatEnum.INTERACTIVE);

        Map<String, ReportPayload.WidgetState> states = new LinkedHashMap<>();
        states.put("w_1", ReportPayload.WidgetState.builder()
                .status(WidgetStatusEnum.DISMISSED)
                .build());
        states.put("w_2", ReportPayload.WidgetState.builder()
                .status(WidgetStatusEnum.RECOMMENDED)
                .build());
        states.put("w_3", ReportPayload.WidgetState.builder()
                .status(WidgetStatusEnum.GENERATED_SHOW)
                .targetArtifactId("flashcard-123")
                .build());
        payload.setWidgetStates(states);

        String cleaned = cleanWidgetsForExport(payload);
        log.info("清洗后 Markdown:\n{}", cleaned);

        assertFalse(cleaned.contains("[WIDGET:"), "清洗后不应残留原始占位符");
        assertFalse(cleaned.contains("测验卡片"), "DISMISSED 的 w_1 应被完全删除");
        assertTrue(cleaned.contains("互动提示"), "RECOMMENDED 的 w_2 应替换为引用提示");
        assertTrue(cleaned.contains("思维导图"), "RECOMMENDED 态应保留标题信息");
        assertTrue(cleaned.contains("互动组件"), "GENERATED_SHOW 的 w_3 应替换为已生成标记");
        assertTrue(cleaned.contains("记忆卡片"), "GENERATED 态应保留标题信息");
    }

    @Test
    void testCleanWidgetsForExport_ThenPandoc() {
        String markdown = """
                # 互动报告导出测试
                
                ## 核心知识
                
                Spring Boot 是基于 Spring 框架的快速开发脚手架。
                
                [WIDGET:QUIZ, id="w_1", title="Spring Boot 测验", prompt="生成 Spring Boot 相关测验"]
                
                ## 总结
                
                以上为全部内容。
                """;

        ReportPayload payload = new ReportPayload();
        payload.setMarkdown(markdown);
        payload.setFormat(ReportFormatEnum.INTERACTIVE);

        Map<String, ReportPayload.WidgetState> states = new LinkedHashMap<>();
        states.put("w_1", ReportPayload.WidgetState.builder()
                .status(WidgetStatusEnum.RECOMMENDED)
                .build());
        payload.setWidgetStates(states);

        String cleaned = cleanWidgetsForExport(payload);
        log.info("清洗后:\n{}", cleaned);

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("markdown", cleaned);

        byte[] docxBytes = restTemplate.postForObject(
                pandocProperties.getApiUrl(),
                requestBody,
                byte[].class
        );

        assertNotNull(docxBytes, "Pandoc 应返回非空 DOCX");
        assertTrue(docxBytes.length > 0, "DOCX 文件大小应 > 0");
        log.info("互动报告 → 清洗 → Pandoc → DOCX 成功 | 大小={}bytes", docxBytes.length);
    }

    // ======================== 辅助方法（复制自 StudioServiceImpl） ========================

    private String cleanWidgetsForExport(ReportPayload reportPayload) {
        String markdown = reportPayload.getMarkdown();
        if (markdown == null) return "";

        Map<String, ReportPayload.WidgetState> widgetStates = reportPayload.getWidgetStates();
        Matcher matcher = WIDGET_PATTERN.matcher(markdown);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String widgetId = matcher.group("id");
            String title = matcher.group("title");
            ReportPayload.WidgetState state = (widgetStates != null) ? widgetStates.get(widgetId) : null;
            String replacement;

            if (state == null || state.getStatus() == WidgetStatusEnum.DISMISSED) {
                replacement = "";
            } else if (state.getStatus() == WidgetStatusEnum.RECOMMENDED) {
                replacement = "> **互动提示**：Web端包含对应的沉浸式互动学习组件（" + title + "）。";
            } else if (state.getStatus() == WidgetStatusEnum.GENERATED_SHOW
                    || state.getStatus() == WidgetStatusEnum.GENERATED_HIDE) {
                replacement = "> **互动组件**：" + title + "（已生成）";
            } else {
                replacement = "> **互动提示**：" + title;
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}