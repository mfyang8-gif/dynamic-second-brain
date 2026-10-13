package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.mapper.ChatMessageMapper;
import com.yangmf.mini_nodepad.mapper.MessageFeedbackMapper;
import com.yangmf.mini_nodepad.pojo.dto.FeedbackDTO;
import com.yangmf.mini_nodepad.pojo.entity.ChatMessage;
import com.yangmf.mini_nodepad.pojo.entity.MessageFeedback;
import com.yangmf.mini_nodepad.pojo.vo.FeedbackVO;
import com.yangmf.mini_nodepad.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageFeedbackServiceImpl implements MessageFeedbackService {

    private final ChatMessageMapper chatMessageMapper;
    private final MessageFeedbackMapper messageFeedbackMapper;

    // 消息归属链：message → session → book.user_id；@TableLogic 已自动过滤消息本身 deleted=1
    private static final String ASSISTANT_OWNED_BY_CURRENT_USER =
            "session_id IN (SELECT s.id FROM chat_session s " +
            "JOIN book b ON s.book_id = b.id " +
            "WHERE b.user_id = {0} AND s.deleted = 0 AND b.deleted = 0)";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO submitFeedback(String messageId, FeedbackDTO dto) {
        Long currentUserId = BaseContext.getCurrentId();

        // 红线1&2：userId 只从上下文取；消息不存在/非本人/非 Assistant 回复统一 403，防资源探测
        ChatMessage message = chatMessageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessage>()
                        .select(ChatMessage::getId, ChatMessage::getRole)
                        .eq(ChatMessage::getId, messageId)
                        .eq(ChatMessage::getRole, "assistant")
                        .apply(ASSISTANT_OWNED_BY_CURRENT_USER, String.valueOf(currentUserId))
        );
        if (message == null) {
            throw new ForbiddenException("消息不存在或无权访问");
        }

        // none = 取消反馈：独立轻量表采用物理删除，天然幂等
        if ("none".equals(dto.getType())) {
            messageFeedbackMapper.delete(
                    new LambdaQueryWrapper<MessageFeedback>()
                            .eq(MessageFeedback::getMessageId, messageId)
            );
            log.info("取消消息反馈 | messageId={}, userId={}", messageId, currentUserId);
            return FeedbackVO.builder().messageId(messageId).type("none").content(null).build();
        }

        if ("like".equals(dto.getType()) && dto.getContent() != null && !dto.getContent().isBlank()) {
            throw new ValidationException("反馈文本仅在点踩时提供");
        }

        // 幂等 Upsert：依赖 uk_message_id 唯一索引
        messageFeedbackMapper.upsert(messageId, currentUserId, dto.getType(), dto.getContent());

        MessageFeedback latest = messageFeedbackMapper.selectOne(
                new LambdaQueryWrapper<MessageFeedback>()
                        .eq(MessageFeedback::getMessageId, messageId)
        );
        log.info("消息反馈生效 | messageId={}, type={}, userId={}", messageId, dto.getType(), currentUserId);
        return toVO(latest);
    }

    private FeedbackVO toVO(MessageFeedback feedback) {
        return FeedbackVO.builder()
                .messageId(feedback.getMessageId())
                .type(feedback.getType())
                .content(feedback.getContent())
                .build();
    }
}
