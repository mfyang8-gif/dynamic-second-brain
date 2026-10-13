package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class DocumentParsingService {

    /** 单文件最大 10MB（业务红线，容器上限 25MB 仅兜底防恶意大包） */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".pdf", ".docx", ".doc", ".txt", ".md", ".markdown");

    /** 后缀 → 允许 MIME 集合（双重核对防伪，octet-stream 作为浏览器通用兜底） */
    private static final Map<String, Set<String>> EXTENSION_MIME_WHITELIST = Map.ofEntries(
            Map.entry(".pdf", Set.of("application/pdf", "application/octet-stream")),
            Map.entry(".doc", Set.of("application/msword", "application/vnd.ms-word", "application/octet-stream")),
            Map.entry(".docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")),
            Map.entry(".txt", Set.of("text/plain", "application/octet-stream")),
            Map.entry(".md", Set.of("text/markdown", "text/plain", "text/x-markdown", "application/octet-stream")),
            Map.entry(".markdown", Set.of("text/markdown", "text/plain", "application/octet-stream"))
    );

    private static final String REJECT_MSG = "不支持的文件格式或文件大小超过10MB限制";

    /**
     * 校验并提取文档【完整】纯文本，不截断（切片交给异步 DocumentSplitter）。
     * 任何格式/大小/MIME 不符抛出 ValidationException，由全局异常处理器返回 HTTP 400。
     */
    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException(REJECT_MSG);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("文件超过10MB限制 | name={}, size={}", file.getOriginalFilename(), file.getSize());
            throw new ValidationException(REJECT_MSG);
        }

        String ext = resolveExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            log.warn("文件后缀不在白名单 | name={}, ext={}", file.getOriginalFilename(), ext);
            throw new ValidationException(REJECT_MSG);
        }

        String contentType = normalizeMimeType(file.getContentType());
        Set<String> allowedMimes = EXTENSION_MIME_WHITELIST.get(ext);
        if (contentType == null || allowedMimes == null || !allowedMimes.contains(contentType)) {
            log.warn("MIME 与后缀不匹配 | name={}, ext={}, contentType={}",
                    file.getOriginalFilename(), ext, file.getContentType());
            throw new ValidationException(REJECT_MSG);
        }

        try (InputStream in = file.getInputStream()) {
            String text = switch (ext) {
                case ".pdf" -> new ApachePdfBoxDocumentParser().parse(in).text();
                case ".doc", ".docx" -> new ApachePoiDocumentParser().parse(in).text();
                case ".txt", ".md", ".markdown" -> new String(in.readAllBytes(), StandardCharsets.UTF_8);
                default -> throw new ValidationException(REJECT_MSG);
            };
            log.info("文档解析完成 | name={}, ext={}, rawLength={}",
                    file.getOriginalFilename(), ext, text == null ? 0 : text.length());
            return text;
        } catch (ValidationException e) {
            throw e;
        } catch (IOException e) {
            log.error("文件流读取失败 | name={}", file.getOriginalFilename(), e);
            throw new BusinessException("文件解析失败，请检查文件是否损坏", e);
        }
    }

    private String resolveExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String normalizeMimeType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int idx = contentType.indexOf(';');
        String base = idx >= 0 ? contentType.substring(0, idx) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}