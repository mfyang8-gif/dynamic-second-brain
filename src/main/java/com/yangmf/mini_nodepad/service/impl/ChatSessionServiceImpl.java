package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yangmf.mini_nodepad.ai.component.RegenerateInstructionStore;
import com.yangmf.mini_nodepad.ai.rag.SourceReference;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.mapper.ChatMessageMapper;
import com.yangmf.mini_nodepad.mapper.ChatSessionMapper;
import com.yangmf.mini_nodepad.pojo.dto.ChatMessageQueryDTO;
import com.yangmf.mini_nodepad.pojo.dto.RegenerateDTO;
import com.yangmf.mini_nodepad.pojo.dto.SessionCreateDTO;
import com.yangmf.mini_nodepad.pojo.entity.ChatMessage;
import com.yangmf.mini_nodepad.pojo.entity.ChatSession;
import com.yangmf.mini_nodepad.pojo.vo.ChatMessageVO;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.pojo.vo.RegenerateVO;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.service.BookService;
import com.yangmf.mini_nodepad.service.ChatSessionService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final BookService bookService;
    private final RegenerateInstructionStore regenerateInstructionStore;
    private final ChatMemoryProvider chatMemoryProvider;

    private static final String OWNED_BOOK_SUB_QUERY =
            "book_id IN (SELECT id FROM book WHERE user_id = {0} AND deleted = 0)";

    @Override
    public String createSession(SessionCreateDTO dto) {
        String currentUserId = getCurrentUserId();
        bookService.getBookById(dto.getBookId());

        ChatSession session = new ChatSession();
        session.setBookId(dto.getBookId());
        session.setTitle(dto.getTitle() != null && !dto.getTitle().isBlank() ? dto.getTitle() : "新对话");

        chatSessionMapper.insert(session);
        log.info("创建对话会话 | sessionId={}, bookId={}, userId={}", session.getId(), dto.getBookId(), currentUserId);
        return session.getId();
    }

    @Override
    public List<ChatSessionVO> listSessionsByBookId(String bookId) {
        String currentUserId = getCurrentUserId();
        bookService.getBookById(bookId);

        List<ChatSession> sessions = chatSessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getBookId, bookId)
                        .apply(OWNED_BOOK_SUB_QUERY, currentUserId)
                        .orderByDesc(ChatSession::getUpdatedAt)
        );

        return sessions.stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public ChatSessionVO getSessionById(String sessionId) {
        ChatSession session = getByIdWithOwnershipCheck(sessionId);
        return toVO(session);
    }

    @Override
    public void deleteSession(String sessionId) {
        String currentUserId = getCurrentUserId();
        ChatSession session = getByIdWithOwnershipCheck(sessionId);

        session.setDeletedAt(LocalDateTime.now());
        chatSessionMapper.updateById(session);
        chatSessionMapper.deleteById(sessionId);

        log.info("删除对话会话 | sessionId={}, bookId={}, userId={}", sessionId, session.getBookId(), currentUserId);
    }

    @Override
    public PageResult<ChatMessageVO> listMessages(String sessionId, ChatMessageQueryDTO dto) {
        // 归属校验：sessionId 必须属于当前登录用户名下的书 —— 不属于直接 403
        getByIdWithOwnershipCheck(sessionId);

        // 大字段说明：content 就是消息本体，历史回看必须要它，无法剥离；
        // 这里显式圈列是为了把 deleted 等内部字段挡在 SQL 层之外。
        Page<ChatMessage> page = chatMessageMapper.selectPage(
                new Page<>(dto.getPage(), dto.getPageSize()),
                new LambdaQueryWrapper<ChatMessage>()
                        .select(ChatMessage::getId,
                                ChatMessage::getRole,
                                ChatMessage::getContent,
                                ChatMessage::getSourcePageIds,
                                ChatMessage::getSourceReferences,
                                ChatMessage::getSuggestions,
                                ChatMessage::getCreatedAt)
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );

        List<ChatMessageVO> records = page.getRecords().stream()
                .map(this::toMessageVO)
                .toList();
        return PageResult.<ChatMessageVO>builder()
                .total(page.getTotal())
                .page((int) page.getCurrent())
                .pageSize((int) page.getSize())
                .records(records)
                .build();
    }

    private ChatMessageVO toMessageVO(ChatMessage message) {
        return ChatMessageVO.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .sourcePageIds(message.getSourcePageIds())
                .sourceReferences(message.getSourceReferences())
                .suggestions(message.getSuggestions())
                .createdAt(message.getCreatedAt())
                .build();
    }

    @Override
    public String getBookIdBySessionId(String sessionId) {
        ChatSession session = getByIdWithOwnershipCheck(sessionId);
        return session.getBookId();
    }

    @Override
    public String saveMessage(String sessionId, String role, String content, List<String> sourcePageIds,
                              List<SourceReference> sourceReferences, List<String> suggestions) {
        ChatMessage message = ChatMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .sourcePageIds(sourcePageIds)
                .sourceReferences(sourceReferences)
                .suggestions(suggestions)
                .build();
        chatMessageMapper.insert(message);
        // ASSIGN_UUID：insert 时由 MyBatis-Plus 生成并回填到实体，直接透传给调用方
        return message.getId();
    }

    @Override
    public void attachSuggestions(String messageId, List<String> suggestions) {
        if (messageId == null || suggestions == null || suggestions.isEmpty()) {
            return;
        }
        // updateById 默认只更新非空字段，其余列不受影响；
        // 行若已不存在（极小概率：落库后又被 regenerate 截断），更新 0 行即可，无需报错
        chatMessageMapper.updateById(ChatMessage.builder()
                .id(messageId)
                .suggestions(suggestions)
                .build());
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        ChatSession session = getByIdWithOwnershipCheck(sessionId);
        session.setTitle(title);
        chatSessionMapper.updateById(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegenerateVO regenerate(String sessionId, String messageId, RegenerateDTO dto) {
        String currentUserId = getCurrentUserId();

        // 归属校验 ①：session 必须在当前用户名下的书里（内部已实现 不存在→403 防探测）
        getByIdWithOwnershipCheck(sessionId);

        // 归属校验 ②：messageId 必须真实挂在该 session 下（跨 session 伪造直接 403）
        ChatMessage target = chatMessageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getId, messageId)
                        .eq(ChatMessage::getSessionId, sessionId)
        );
        if (target == null) {
            throw new ForbiddenException("消息不存在或无权访问");
        }
        if (!"assistant".equals(target.getRole())) {
            throw new ValidationException("仅 Assistant 回复支持重新生成");
        }

        // 软删除截断：目标消息及其后所有消息（依赖 created_at DATETIME(3) 毫秒精度，
        // 保障紧邻的 user 提问不被 >= 边界误删；@TableLogic 自动附加 deleted=0 条件）
        int truncated = chatMessageMapper.update(null,
                new LambdaUpdateWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .ge(ChatMessage::getCreatedAt, target.getCreatedAt())
                        .set(ChatMessage::getDeleted, 1)
        );

        // 关键：MySQL 截断后同步重建 Redis ChatMemory，防止 LLM 仍“记得”被删回答
        rebuildChatMemory(sessionId);

        // 方案 B：无条件签发 Token —— 指令可为空，但被否决的上一版回答（负例）必然随 token 注入，
        // 杜绝“二遍答案与第一遍雷同”的 UX 假死
        String instructionToken = regenerateInstructionStore.save(
                dto.getInstruction() == null ? "" : dto.getInstruction(),
                headTailTruncate(target.getContent()));

        log.info("重新生成截断完成 | sessionId={}, messageId={}, truncated={}, hasInstruction={}, userId={}",
                sessionId, messageId, truncated,
                dto.getInstruction() != null && !dto.getInstruction().isBlank(), currentUserId);
        return RegenerateVO.builder()
                .instructionToken(instructionToken)
                .truncatedCount(truncated)
                .build();
    }

    private static final int DRAFT_FULL_KEEP_THRESHOLD = 1000;
    private static final int DRAFT_HEAD_TAIL_LENGTH = 500;

    /**
     * 旧答案首尾掐断法（不做 Markdown 语法树提取，取最鲁棒方案）：
     * <= 1000 字全量保留；> 1000 字保留前 500 + 省略标记 + 后 500。
     * 大模型看到开头（切入角度）和结尾（结论落点）即可感知上一版的“味道”并刻意避开。
     */
    private String headTailTruncate(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() <= DRAFT_FULL_KEEP_THRESHOLD) {
            return trimmed;
        }
        return trimmed.substring(0, DRAFT_HEAD_TAIL_LENGTH)
                + "\n...(中间已省略)...\n"
                + trimmed.substring(trimmed.length() - DRAFT_HEAD_TAIL_LENGTH);
    }

    /**
     * 以截断后幸存的 DB 消息为唯一事实源，重建该会话的 LLM 记忆窗口。
     * Redis 操作不参与 DB 事务：若重建失败则事务回滚，残留旧记忆会在下次对话中
     * 被 MessageWindow 自然淘汰，不造成越权，仅影响单次体验，可接受。
     */
    private void rebuildChatMemory(String sessionId) {
        ChatMemory memory = chatMemoryProvider.get(sessionId);
        memory.clear();
        List<ChatMessage> survivors = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );
        for (ChatMessage m : survivors) {
            if ("user".equals(m.getRole())) {
                memory.add(UserMessage.from(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                memory.add(AiMessage.from(m.getContent()));
            }
        }
        log.info("ChatMemory 重建完成 | sessionId={}, survivorCount={}", sessionId, survivors.size());
    }

    // ==================== 私有方法 ====================

    private String getCurrentUserId() {
        return String.valueOf(BaseContext.getCurrentId());
    }

    private ChatSession getByIdWithOwnershipCheck(String sessionId) {
        String currentUserId = getCurrentUserId();
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getId, sessionId)
                .apply(OWNED_BOOK_SUB_QUERY, currentUserId);
        ChatSession session = chatSessionMapper.selectOne(wrapper);
        if (session == null) {
            throw new ForbiddenException("无权访问该对话会话");
        }
        return session;
    }

    private ChatSessionVO toVO(ChatSession session) {
        return ChatSessionVO.builder()
                .id(session.getId())
                .bookId(session.getBookId())
                .title(session.getTitle())
                .updatedAt(session.getUpdatedAt())
                .createdAt(session.getCreatedAt())
                .build();
    }
}