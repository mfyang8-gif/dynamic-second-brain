package com.yangmf.mini_nodepad.ai.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 重新生成上下文的一次性暂存仓库（Token 模式，方案 B：无条件发 Token + 负例锚定）。
 *
 * 写入侧：regenerate 接口把 {instruction, rejected_draft} 打包为 JSON，
 * 以随机 token 为 key 存入 Redis（TTL 5 分钟）——即使用户没填指令，
 * 被否决的上一版回答（负例）也必然随 token 下发，杜绝二次回答雷同的"UX 假死"；
 * 消费侧：/chat/stream 请求携带 regenerateToken，getAndDelete 一次性取删，
 * 未携带 token 的普通提问完全不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegenerateInstructionStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "chat:regenerate:instruction:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final String FIELD_INSTRUCTION = "instruction";
    private static final String FIELD_REJECTED_DRAFT = "rejected_draft";

    /**
     * 消费产物：修正指令（可为空串） + 被否决的上一版回答（首尾掐断后的文本，可为空串）
     */
    public record RegeneratePayload(String instruction, String rejectedDraft) {
    }

    /**
     * 暂存重新生成上下文，永远返回一次性凭证 token
     */
    public String save(String instruction, String rejectedDraft) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    FIELD_INSTRUCTION, instruction == null ? "" : instruction,
                    FIELD_REJECTED_DRAFT, rejectedDraft == null ? "" : rejectedDraft));
        } catch (JsonProcessingException e) {
            log.error("重新生成上下文序列化失败", e);
            throw new BusinessException("重新生成指令暂存失败，请稍后重试", e);
        }
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + token, payload, TTL);
        log.debug("重新生成上下文已暂存 | token={}, instructionLength={}, draftLength={}",
                token, instruction == null ? 0 : instruction.length(),
                rejectedDraft == null ? 0 : rejectedDraft.length());
        return token;
    }

    /**
     * 一次性消费：取回并删除。token 为空、已过期、已被消费或反序列化失败均返回 null（降级为普通对话）
     */
    public RegeneratePayload consume(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String raw = stringRedisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token);
        if (raw == null) {
            log.warn("重新生成上下文 Token 无效、已过期或已被消费 | token={}", token);
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(raw, Map.class);
            String instruction = map.get(FIELD_INSTRUCTION) instanceof String s ? s : "";
            String rejectedDraft = map.get(FIELD_REJECTED_DRAFT) instanceof String s ? s : "";
            return new RegeneratePayload(instruction, rejectedDraft);
        } catch (Exception e) {
            log.error("重新生成上下文反序列化失败，降级为空 | token={}", token, e);
            return null;
        }
    }
}
