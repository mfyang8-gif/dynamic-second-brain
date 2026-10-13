package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 高危词表（B1，纯规则，可配置）。
 *
 * <p>它解决的问题是「时效价值不对称」：健康 / 禁忌 / 硬约束类事实（过敏、疾病、明确的禁止项）
 * 若等到会话静默 5 分钟才沉淀，用户下一句可能已经需要它了。
 * 因此命中的轮次<b>绕过 5 分钟延时队列、立即投递</b>，并以 {@code priority = 10} 插队。
 *
 * <p>三条定位说明：
 * <ol>
 *   <li>它是<b>召回侧粗筛</b>，不是判定替代品：命中只代表「优先投递」，不代表「一定入图」——
 *       入图与否仍由 {@code MemorySignalJudge} 决定。</li>
 *   <li>它是<b>纯规则、零 LLM 成本</b>，因此可以在落库后的主链路里同步调用。</li>
 *   <li>它<b>不做分词</b>：直接子串匹配。中文短词表场景下，子串匹配的召回率高于分词，
 *       而误报的代价仅为「多一次 immediate 投递」（后续仍要过判定器），可接受。</li>
 * </ol>
 *
 * <p>⚠️ 匹配是<b>大小写不敏感</b>的（两边都转小写），词表为空时 {@link #hit} 恒为 false ——
 * 这是刻意的：默认空词表意味着「关闭快通道」，而不是「命中一切」。
 */
@Slf4j
@Component
public class HighRiskLexicon {

    /** 原词（用于日志与 source_description 展示，保留使用者书写的大小写）。 */
    private final List<String> words;

    /** 小写词（匹配用）。 */
    private final List<String> lowered;

    public HighRiskLexicon(GraphitiProperties properties) {
        List<String> raw = properties.getWrite().getHighRiskLexicon();
        if (raw == null || raw.isEmpty()) {
            this.words = List.of();
            this.lowered = List.of();
            log.info("高危词表为空：快通道关闭（所有轮次走 5 分钟静默期常规投递）");
            return;
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String word : raw) {
            if (word != null && !word.isBlank()) {
                distinct.add(word.trim());
            }
        }
        this.words = List.copyOf(distinct);
        List<String> lower = new ArrayList<>(this.words.size());
        for (String word : this.words) {
            lower.add(word.toLowerCase(Locale.ROOT));
        }
        this.lowered = Collections.unmodifiableList(lower);
        log.info("高危词表已加载 | 词数={} | 词={}", this.words.size(), this.words);
    }

    /** 是否命中高危词（词表为空时恒 false）。 */
    public boolean hit(String text) {
        return !matched(text).isEmpty();
    }

    /**
     * 命中的词（去重、保持词表顺序）。
     *
     * <p>返回值用于写进 {@code source_description} 的「人话式来源说明」，
     * 顺带记录「是哪个词命中的」—— 保留全部审计能力，且不污染语义。
     */
    public List<String> matched(String text) {
        if (text == null || text.isBlank() || lowered.isEmpty()) {
            return List.of();
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>(2);
        for (int i = 0; i < lowered.size(); i++) {
            if (lowerText.contains(lowered.get(i))) {
                hits.add(words.get(i));
            }
        }
        return hits;
    }

    /** 词表是否为空（= 快通道关闭）。 */
    public boolean isEmpty() {
        return words.isEmpty();
    }
}
