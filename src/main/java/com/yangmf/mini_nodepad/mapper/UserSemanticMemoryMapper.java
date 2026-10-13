package com.yangmf.mini_nodepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yangmf.mini_nodepad.enums.SemanticRuleType;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticMemory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 正式语义记忆 Mapper：晋升写入依赖 uk_user_fact + INSERT IGNORE 阻断并发重复晋升竞态
 * （已存在含 deleted=1 的行同样静默跳过——用户主动删除的习惯不自动复活）。
 */
@Mapper
public interface UserSemanticMemoryMapper extends BaseMapper<UserSemanticMemory> {

    @Insert("INSERT IGNORE INTO user_semantic_memory "
            + "(user_id, fact_hash, fact, rule_type, last_reinforced_at, created_at, updated_at, deleted) "
            + "VALUES (#{userId}, #{factHash}, #{fact}, #{ruleType}, NOW(3), NOW(3), NOW(3), 0)")
    int insertIgnorePromoted(@Param("userId") Long userId,
                             @Param("factHash") String factHash,
                             @Param("fact") String fact,
                             @Param("ruleType") SemanticRuleType ruleType);
}
