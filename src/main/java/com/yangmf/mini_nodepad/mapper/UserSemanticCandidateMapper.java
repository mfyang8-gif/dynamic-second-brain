package com.yangmf.mini_nodepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yangmf.mini_nodepad.enums.SemanticRuleType;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticCandidate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 习惯候选池 Mapper：并发累加依赖 MySQL 原生 ON DUPLICATE KEY UPDATE
 * （uk_user_fact 阻断并发幻读行，参照 MessageFeedbackMapper.upsert 先例）。
 */
@Mapper
public interface UserSemanticCandidateMapper extends BaseMapper<UserSemanticCandidate> {

    /**
     * 频次累加：新记录 hit_count=1；已存在则 +1 并刷新 last_hit_at。
     * 不更新 fact 文本本体（同 hash 视为同一事实，语义合并由后续 Phase 处理）。
     * ruleType 为强类型枚举：MyBatis 默认 EnumTypeHandler 按 name() 存取（本项目 code == name）。
     */
    @Insert("INSERT INTO user_semantic_candidate "
            + "(user_id, fact_hash, fact, rule_type, hit_count, promoted, last_hit_at, created_at, updated_at) "
            + "VALUES (#{userId}, #{factHash}, #{fact}, #{ruleType}, 1, 0, NOW(3), NOW(3), NOW(3)) "
            + "ON DUPLICATE KEY UPDATE hit_count = hit_count + 1, last_hit_at = NOW(3), updated_at = NOW(3)")
    int upsertHit(@Param("userId") Long userId,
                  @Param("factHash") String factHash,
                  @Param("fact") String fact,
                  @Param("ruleType") SemanticRuleType ruleType);

    /** 晋升完成后打标，防重复扫描晋升 */
    @Update("UPDATE user_semantic_candidate SET promoted = 1, updated_at = NOW(3) WHERE id = #{id} AND promoted = 0")
    int markPromoted(@Param("id") Long id);
}
