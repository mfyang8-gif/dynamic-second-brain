package com.yangmf.mini_nodepad.ai.component;

import com.yangmf.mini_nodepad.ai.aiservice.IntentRouter;
import com.yangmf.mini_nodepad.enums.UserIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 意图路由中枢门面：三级级联降级策略。
 * <p>
 * Step 1 毫秒级正则拦截：寒暄精确匹配表 → 高置信关键词双向扫描（守卫疑问词跳过）；
 * Step 2 LLM 动态研判：正则未命中/双命中时交 qwen-turbo 语义仲裁，3s 硬上限；
 * Step 3 超时/异常兜底：默认 HYBRID 走 RAG 主链路，保障主流程 TTFT 不被路由节点拖挂。
 * <p>
 * 超时执行点说明：dashscope starter 属性与 QwenChatModel.Builder 均无 timeout 字段，
 * SDK 全局静态超时会污染共享 qwen-turbo Bean 的其他 6 个 AiServices，故 3s 预算在门面层
 * 用 Future 截止强制；超时即放弃（底层请求可能短暂后台完成，但主流程不再等待）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRoutingService {

    private final IntentRouter intentRouter;

    /** 意图研判硬预算：TTFT 优先，超时立刻走 HYBRID 兜底 */
    private static final long INTENT_LLM_TIMEOUT_MS = 3_000L;

    /** 虚拟线程执行器：每次研判独立起线程，超时后 cancel 不打断主流程 */
    private final ExecutorService intentCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 寒暄精确匹配表（<b>归一化形态</b>：小写、已剥标点 / emoji / 空白）。
     *
     * <p>2026-10-12 升级：旧表是拿原始输入与字面量做 {@code Set.contains} 精确匹配，
     * 「你好啊朋友」「你好。」「hi!」全部漏到 LLM 去 —— 一次小模型往返换一个"这是闲聊"的结论，
     * 在双路重构把答案首字压到 1~2s 之后，这 1s 就成了最刺眼的浪费。
     * 现在统一先归一化再比对，覆盖大小写、标点、emoji、语气词尾缀。
     */
    private static final Set<String> SMALLTALK_EXACT = Set.of(
            "你好", "您好", "嗨", "hi", "hello", "hey", "哈喽",
            "谢谢", "感谢", "多谢", "谢了", "thanks", "thankyou",
            "再见", "拜拜", "bye", "晚安", "早安", "早上好", "下午好", "晚上好",
            "goodmorning", "goodevening", "goodnight",
            "嗯嗯", "嗯", "好的", "ok", "okay", "收到",
            "哈哈", "呵呵", "嘻嘻", "嘿嘿",
            "在吗", "在不在", "在么", "在嘛",
            "你好吗", "您好吗",
            "最近怎么样", "最近好吗", "最近咋样", "最近如何", "怎么样", "咋样",
            "干嘛呢", "在干嘛", "忙吗", "吃了吗"
    );

    /**
     * 寒暄前缀表：识别「<b>寒暄 + 实质提问</b>」的混合句。
     *
     * <p>这类句子是静态加速最容易翻车的地方 ——
     * <pre>
     *   「你好啊，我想问你明天的天气」   ← 只看开头就判闲聊，用户的真问题被静默吞掉
     *   「哈喽，请问 Vue 的响应式原理是什么」
     * </pre>
     * 所以前缀命中<b>不等于</b> shortcut，必须再走 {@link #containsSubstantive} 二次判定。
     */
    private static final List<String> SMALLTALK_PREFIXES = List.of(
            "你好", "您好", "哈喽", "嗨", "hello", "hi", "hey",
            "在吗", "在不在", "在么",
            "谢谢", "感谢", "多谢", "thanks", "thankyou",
            "再见", "拜拜", "bye", "晚安", "早安", "早上好", "下午好", "晚上好",
            "ok", "okay", "好的", "嗯嗯", "哈哈", "呵呵", "嘻嘻", "嘿嘿"
    );

    /**
     * 实义标记（实义动词 + 疑问词）—— 防误杀补丁的核心判据。
     *
     * <p>用户 2026-10-12 明确要求：去掉寒暄词后若<b>剩余字符数 &gt; 0 且含实义动词或疑问词</b>，
     * 绝对不能短路为 GENERAL_CHAT，必须送 qwen-turbo 研判。
     *
     * <p>这张表刻意收得<b>宽</b>：多收一个词最多让一句闲聊多走一次 LLM（约 1s），
     * 漏收一个词则会把真问题静默判成闲聊 —— 两种错误的代价完全不对称。
     */
    private static final List<String> SUBSTANTIVE_MARKERS = List.of(
            // 实义动词
            "问", "想", "要", "帮", "看", "写", "说", "讲", "解释", "介绍", "推荐", "给", "找",
            "查", "搜", "做", "用", "学", "教", "改", "翻", "算", "评", "比", "分析", "总结",
            "生成", "告诉", "聊聊", "讨论", "分享", "说明", "列举", "区分", "区别", "实现",
            "配置", "安装", "部署", "设计", "优化", "测试", "翻译", "计算", "统计", "整理", "总结",
            // 疑问词 / 疑问标记
            "吗", "呢", "什么", "怎么", "如何", "为什么", "哪", "谁", "几", "多少",
            "是否", "能否", "是不是", "有没有", "多久", "多大", "多远", "干嘛", "干什么", "怎么办"
    );

    /** 短句启发式长度上限：这个长度以内且无实义标记，才允许零 LLM 判闲聊 */
    private static final int SHORT_SENTENCE_MAX_LEN = 6;

    /** KNOWLEDGE_SEARCH 高置信关键词 */
    private static final List<String> KNOWLEDGE_KEYWORDS = List.of(
            "文档里", "文档中", "根据资料", "资料里", "根据笔记",
            "刚才那份报告", "知识库"
    );

    /** EPISODIC_MEMORY 高置信关键词 */
    private static final List<String> EPISODIC_KEYWORDS = List.of(
            "刚才说的", "上面提到的", "继续上一个",
            "之前说的", "上次写的", "上次说的"
    );

    /**
     * WEB_SEARCH 高置信关键词（2026-10-12 轨一）。
     * 只收录【用户显式点名联网】的说法 —— 像"最新""今天"这类时效词故意不收，
     * 它们误伤面太大（"最新的研究表明"其实是知识问题），交给 LLM 语义研判。
     */
    private static final List<String> WEB_EXPLICIT_KEYWORDS = List.of(
            "联网搜", "联网查", "上网搜", "上网查", "网上搜一下", "网上查一下",
            "百度一下", "谷歌一下", "google一下", "帮我搜一下", "实时搜索"
    );

    /**
     * 护栏疑问词：只要句子中包含疑问词，说明语境可能是在询问系统本身（如"知识库怎么用"），
     * 直接跳过关键词正则，交 LLM 语义研判（准确率优先）。
     * 注：中文语序灵活、主语常在前，句首 startsWith 会被"知识库怎么用？"绕过，故用全句 contains。
     */
    private static final List<String> META_QUESTION_KEYWORDS = List.of(
            "怎么", "如何", "什么是", "为什么", "能否", "能不能", "是否", "可以", "支持"
    );

    public UserIntent determineIntent(String userMessage) {
        return determineIntent(userMessage, "");
    }

    /**
     * 带会话上下文的意图研判（用户 2026-10-12 拍板）。
     * <p>
     * 缺陷还原：「再给我推荐几个学习方向」孤立看像闲聊，实际是依赖上文的追问 ——
     * 旧版只喂孤立单句，qwen-turbo 只能瞎猜，实测稳定误判 GENERAL_CHAT，
     * 导致闲聊模型吐「您可以针对知识库中的具体内容向我提问」客服话术。
     * 修复：把最近几轮对话原文作为【对话背景】注入研判输入，追问/指代类即可正确判 HYBRID。
     *
     * @param conversationContext 最近对话原文（buildConversationContext 产物，可为空）
     */
    public UserIntent determineIntent(String userMessage, String conversationContext) {
        if (userMessage == null || userMessage.isBlank()) {
            return UserIntent.GENERAL_CHAT;
        }
        String trimmed = userMessage.trim();
        String normalized = normalizeForRoute(trimmed);

        // Step 1a：寒暄（归一化后精确匹配）→ GENERAL_CHAT，零 LLM 成本。
        // 归一化吃掉大小写、标点、emoji 与空白，所以「你好。」「hi!」「哈喽😀」都进得来。
        if (SMALLTALK_EXACT.contains(normalized)) {
            log.info("意图快速路径 | intent=GENERAL_CHAT, rule=smalltalk_exact, query={}", truncate(trimmed));
            return UserIntent.GENERAL_CHAT;
        }

        // Step 1a-2 ★ 防误杀补丁（用户 2026-10-12 明确要求）
        // 「寒暄 + 实质提问」的混合句是静态加速最容易翻车的地方：
        //   「你好啊，我想问你明天的天气」→ 只看开头就判闲聊，用户的真问题被静默吞掉。
        // 规则：剥掉开头的寒暄词后，若剩余字符数 > 0 且含实义动词/疑问词，绝不能短路为闲聊。
        String residue = stripSmalltalkPrefix(normalized);
        if (residue != null) {
            if (!containsSubstantive(residue)) {
                log.info("意图快速路径 | intent=GENERAL_CHAT, rule=smalltalk_prefix, residue={}", residue);
                return UserIntent.GENERAL_CHAT;
            }
            log.info("寒暄前缀后仍有实质内容，拒绝短路闲聊，继续研判 | query={}, residue={}",
                    truncate(trimmed), residue);
        }

        // Step 1c：显式联网请求 → WEB_SEARCH。
        // 刻意放在 1b 之前 —— 1b 的守卫是「含疑问词就跳过正则」，
        // 而「能不能联网搜一下…」这类句子里正好带疑问词，放后面会被守卫吃掉。
        if (containsAny(trimmed, WEB_EXPLICIT_KEYWORDS)) {
            log.info("意图快速路径 | intent=WEB_SEARCH, rule=explicit_web, query={}", truncate(trimmed));
            return UserIntent.WEB_SEARCH;
        }

        // Step 1b：关键词双向扫描；守卫疑问词命中则跳过（护栏机制）
        if (!containsAny(trimmed, META_QUESTION_KEYWORDS)) {
            boolean hitKnowledge = containsAny(trimmed, KNOWLEDGE_KEYWORDS);
            boolean hitEpisodic = containsAny(trimmed, EPISODIC_KEYWORDS);
            if (hitKnowledge && !hitEpisodic) {
                log.info("意图快速路径 | intent=KNOWLEDGE_SEARCH, rule=keyword, query={}", truncate(trimmed));
                return UserIntent.KNOWLEDGE_SEARCH;
            }
            if (hitEpisodic && !hitKnowledge) {
                log.info("意图快速路径 | intent=EPISODIC_MEMORY, rule=keyword, query={}", truncate(trimmed));
                return UserIntent.EPISODIC_MEMORY;
            }
            // 双命中 = 语境复杂，正则不做粗暴仲裁，fall through 交 LLM 语义研判（准 > 快）
        }

        // Step 1d：短句启发式 —— 极短且不含任何实义标记 → 判定闲聊，零 LLM。
        // 兜底链：像「好嘞」「嗯呐」这类不在词表里的短寒暄，靠这条收掉。
        // 排除词表由 SUBSTANTIVE_MARKERS 兼任：「怎么办」「为什么」「多少钱」「是什么」
        // 全部含疑问词，会在上一步就被判定为"有实质内容"，绝不会被这条误杀。
        if (normalized.length() <= SHORT_SENTENCE_MAX_LEN && !containsSubstantive(normalized)) {
            log.info("意图快速路径 | intent=GENERAL_CHAT, rule=short_sentence, query={}", truncate(trimmed));
            return UserIntent.GENERAL_CHAT;
        }

        // Step 2：LLM 动态研判（3s 硬上限）；Step 3：超时/解析异常兜底 HYBRID
        try {
            CompletableFuture<UserIntent> future = CompletableFuture.supplyAsync(
                    () -> intentRouter.route(composeRouteInput(userMessage, conversationContext)),
                    intentCallExecutor);
            try {
                UserIntent intent = future.get(INTENT_LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.info("意图 LLM 研判完成 | intent={}, query={}", intent, truncate(userMessage));
                return intent;
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("意图研判超时 {}ms，兜底 HYBRID | query={}", INTENT_LLM_TIMEOUT_MS, truncate(userMessage));
                return UserIntent.HYBRID;
            }
        } catch (Exception e) {
            log.warn("意图研判解析失败，兜底 HYBRID | query={}", truncate(userMessage), e);
            return UserIntent.HYBRID;
        }
    }

    /**
     * 组装研判输入：上下文在前、当前句在后并显式标注，杜绝模型把上一轮内容当成当前提问。
     */
    private String composeRouteInput(String userMessage, String conversationContext) {
        if (conversationContext == null || conversationContext.isBlank()) {
            return userMessage;
        }
        log.info("意图研判注入上下文 | chars={}", conversationContext.length());
        return "【对话背景（仅供理解指代与追问，不要对其分类）】\n"
                + conversationContext
                + "\n【当前用户输入（对这一句分类）】\n" + userMessage;
    }

    /**
     * 归一化：小写 + 剥标点 / emoji / 空白。
     *
     * <p>{@code \p{S}} 覆盖 emoji 与数学符号，{@code \p{P}} 覆盖中英文标点，
     * 一起剥掉后「你好。」「hi!」「哈喽😀」会收敛到同一形态。
     */
    private String normalizeForRoute(String text) {
        return text.toLowerCase()
                .replaceAll("[\\p{P}\\p{S}\\p{Z}\\s]", "");
    }

    /**
     * 剥掉开头的寒暄词后返回剩余内容。
     *
     * <p>返回 {@code null} 表示"没有命中任何寒暄前缀" ——
     * 与"命中了但剩余为空串"是两种完全不同的情况，调用方靠这个区分做不同决策。
     * 前缀表按声明顺序匹配，长的写在前面（如「你好吗」优先于「你好」）。
     */
    private String stripSmalltalkPrefix(String normalized) {
        for (String prefix : SMALLTALK_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return normalized.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * 剩余内容里是否含实义动词或疑问词 —— 防误杀补丁的核心判据。
     *
     * <p>空串（纯寒暄）返回 false，即"没有实质内容"，可以安全短路为闲聊。
     */
    private boolean containsSubstantive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return SUBSTANTIVE_MARKERS.stream().anyMatch(text::contains);
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private String truncate(String text) {
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
