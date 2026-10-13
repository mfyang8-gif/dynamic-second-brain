package com.yangmf.mini_nodepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yangmf.mini_nodepad.pojo.entity.MessageFeedback;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MessageFeedbackMapper extends BaseMapper<MessageFeedback> {

    /**
     * 幂等 Upsert：依赖 uk_message_id 唯一索引，重复提交就地覆盖类型与文本
     */
    @Insert("INSERT INTO chat_message_feedback (message_id, user_id, type, content) " +
            "VALUES (#{messageId}, #{userId}, #{type}, #{content}) " +
            "ON DUPLICATE KEY UPDATE type = VALUES(type), content = VALUES(content), user_id = VALUES(user_id)")
    int upsert(@Param("messageId") String messageId,
               @Param("userId") Long userId,
               @Param("type") String type,
               @Param("content") String content);
}
