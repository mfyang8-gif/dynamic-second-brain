package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.ai.rag.SourceReference;
import com.yangmf.mini_nodepad.pojo.dto.ChatMessageQueryDTO;
import com.yangmf.mini_nodepad.pojo.dto.RegenerateDTO;
import com.yangmf.mini_nodepad.pojo.dto.SessionCreateDTO;
import com.yangmf.mini_nodepad.pojo.vo.ChatMessageVO;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.pojo.vo.RegenerateVO;
import com.yangmf.mini_nodepad.result.PageResult;

import java.util.List;

public interface ChatSessionService {

    String createSession(SessionCreateDTO dto);

    List<ChatSessionVO> listSessionsByBookId(String bookId);

    ChatSessionVO getSessionById(String sessionId);

    void deleteSession(String sessionId);

    /**
     * 分页拉取某会话的历史消息（按时间正序）。
     *
     * 四条红线：
     *   1. 鉴权：userId 从 BaseContext 取 + 会话归属校验（子查询 book.user_id），
     *      DTO 里根本没有 userId 字段，杜绝横向越权；
     *   2. 强制分页：MP Page + PaginationInnerInterceptor，pageSize 上限 100（PageQueryDTO @Max）；
     *   3. 出入参分离：返回 ChatMessageVO，不把 Entity 直接抛给前端；
     *   4. 字段剥离：.select(...) 显式圈定列，排除 deleted 等内部字段。
     */
    PageResult<ChatMessageVO> listMessages(String sessionId, ChatMessageQueryDTO dto);

    /**
     * 根据 sessionId 查出 bookId（供 ChatStreamService 调用）
     */
    String getBookIdBySessionId(String sessionId);

    /**
     * 持久化一条对话消息。
     *
     * @return 落库后的消息主键（ASSIGN_UUID 在 insert 时回填到实体，这里透传出去）。
     *         recommendations 类字段的「异步回写」需要它：suggestions 在 SSE done 之后
     *         才由 LLM 生成，届时按主键 update，而不是重新查一遍最新行。
     */
    String saveMessage(String sessionId, String role, String content, List<String> sourcePageIds,
                       List<SourceReference> sourceReferences, List<String> suggestions);

    /**
     * 推荐追问生成完毕后回写到已落库的 assistant 行。
     * 幂等无害：多写一次也只是同值覆盖。行不存在（被 regenerate 截断等）静默忽略。
     */
    void attachSuggestions(String messageId, List<String> suggestions);

    void updateTitle(String sessionId, String title);

    /**
     * 重新生成：软删除截断目标 Assistant 消息及其后所有消息，同步重建 Redis ChatMemory
     * 防止 LLM 仍"记得"被删回答；无条件签发一次性 Token（内含可选修正指令 + 首尾掐断的
     * 被否决上一版负例，TTL 5 分钟），前端必须在随后的 /chat/stream 请求中携带，
     * 杜绝无指令重发时二次回答雷同；本接口不吐 SSE 流。
     */
    RegenerateVO regenerate(String sessionId, String messageId, RegenerateDTO dto);
}