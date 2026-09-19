package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.dto.SessionCreateDTO;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;

import java.util.List;

public interface ChatSessionService {

    String createSession(SessionCreateDTO dto);

    List<ChatSessionVO> listSessionsByBookId(String bookId);

    ChatSessionVO getSessionById(String sessionId);

    void deleteSession(String sessionId);

    /**
     * 根据 sessionId 查出 bookId（供 ChatStreamService 调用）
     */
    String getBookIdBySessionId(String sessionId);

    /**
     * 持久化一条对话消息
     */
    void saveMessage(String sessionId, String role, String content, List<String> sourcePageIds);

    void updateTitle(String sessionId, String title);
}