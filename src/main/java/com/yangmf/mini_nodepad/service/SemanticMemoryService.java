package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.dto.ExtractedHabit;
import com.yangmf.mini_nodepad.pojo.vo.SemanticHabitVO;

import java.util.List;
import java.util.Map;

/**
 * 全局语义记忆晋升中枢（Phase 3 终极版）。
 * userId 一律由调用方显式传入（MQ 消费线程无 HTTP 上下文，禁止触碰 BaseContext）。
 */
public interface SemanticMemoryService {

    /**
     * 向量语义查重 + 候选累加 + 晋升检查：
     * 白名单过滤 → 消毒 → 批内 hash 精确合并 → Qdrant dense 相似度查重（>阈值命中旧习惯
     * 按旧 hash 累加，miss 落新 hash 并回写向量索引）→ ON DUPLICATE KEY 累加
     * → hit_count>=3 晋升正式库（INSERT IGNORE 防竞态）→ promoted 打标。
     */
    void processAndPromote(Long userId, List<ExtractedHabit> habits);

    /**
     * 查询用户正式记忆（注入用），按 rule_type 分组、每类按 last_reinforced_at 倒序 Top-10 截断。
     * key = PREFERENCE / CONSTRAINT，value = fact 列表；无记忆返回空 Map（Prompt 渲染为空零污染）。
     */
    Map<String, List<String>> getUserGlobalMemory(Long userId);

    /** 习惯可视化（缺口 1）：列出当前用户全部已晋升习惯，按最近强化时间倒序 */
    List<SemanticHabitVO> listHabits(Long userId);

    /** 遗忘权（缺口 1）：逻辑删除 deleted=1；不存在或非本人一律 ForbiddenException 防探测 */
    void deleteHabit(Long userId, Long id);
}
