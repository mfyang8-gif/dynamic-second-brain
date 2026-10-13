package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.dto.FeedbackDTO;
import com.yangmf.mini_nodepad.pojo.vo.FeedbackVO;

public interface MessageFeedbackService {

    /**
     * 提交/更新/取消 Assistant 消息反馈。
     * 归属校验走 message → session → book 链路，不存在/越权统一 403；
     * type=none 物理删除反馈记录；其余走 uk_message_id 幂等 Upsert，返回最终生效状态。
     */
    FeedbackVO submitFeedback(String messageId, FeedbackDTO dto);
}
