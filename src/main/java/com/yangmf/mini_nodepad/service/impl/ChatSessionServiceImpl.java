package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.mapper.ChatMessageMapper;
import com.yangmf.mini_nodepad.mapper.ChatSessionMapper;
import com.yangmf.mini_nodepad.pojo.dto.SessionCreateDTO;
import com.yangmf.mini_nodepad.pojo.entity.ChatMessage;
import com.yangmf.mini_nodepad.pojo.entity.ChatSession;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.service.BookService;
import com.yangmf.mini_nodepad.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final BookService bookService;

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
    public String getBookIdBySessionId(String sessionId) {
        ChatSession session = getByIdWithOwnershipCheck(sessionId);
        return session.getBookId();
    }

    @Override
    public void saveMessage(String sessionId, String role, String content, List<String> sourcePageIds) {
        ChatMessage message = ChatMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .sourcePageIds(sourcePageIds)
                .build();
        chatMessageMapper.insert(message);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        ChatSession session = getByIdWithOwnershipCheck(sessionId);
        session.setTitle(title);
        chatSessionMapper.updateById(session);
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