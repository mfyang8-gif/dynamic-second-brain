package com.yangmf.mini_nodepad.ai.aiservice;

import com.yangmf.mini_nodepad.pojo.dto.ExtractedHabitList;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 用户习惯结构化提取器（LangChain4j AiServices JSON 结构化输出）。
 * 绑定专用 qwen-turbo 实例（temperature 0.1，见 AssistantConfig.habitExtractor），
 * 输出为不可信数据，消费方必须做白名单解析 + PromptSanitizer 消毒后才能落库。
 * <p>
 * ⚠️ 返回类型必须是 POJO（{@link ExtractedHabitList}），**不能**声明为
 * {@code List<ExtractedHabit>}：Collection 返回类型会被 LangChain4j 路由到
 * {@code PojoCollectionOutputParser}，其 {@code formatInstructions()} 故意抛异常，
 * 而 QwenChatModel 不支持原生 JSON-Schema，必然走到该方法 —— 修复前每次调用都
 * 在发请求前抛 IllegalStateException，导致语义记忆链路静默全断。
 */
public interface HabitExtractor {

    @SystemMessage("""
    你是一个用户习惯分析引擎。请分析用户最近的对话记录，判断用户是否展现出了明确的跨会话习惯。
    如果用户表达了正向偏好（如"我习惯用 Vue3"），标注为 PREFERENCE；
    如果表达了负向禁忌（如"绝不要写注释"），标注为 CONSTRAINT。
    如果没有明确全局习惯，返回空列表。
    警告：绝不能把单次任务的临时指令当成全局习惯！

    判定标准（必须同时满足才提取）：
    1. 表述具有全局性和持续性，出现"我习惯/我一般/以后都/每次都/绝不要/不要再"等泛化句式；
    2. 是针对所有对话的长期设定，而非当前任务的局部要求。

    反例（禁止提取）：
    - "这次帮我把变量名改成驼峰" → 单次任务临时指令，不提取；
    - "上面的回答再详细一点" → 针对单条回答的修正，不提取；
    - "帮我写一个 Python 脚本" → 普通任务请求，不提取。

    输出格式要求：
    - 每条 fact 必须是标准化第三人称短语，以"用户"开头，不超过 50 字（如"用户偏好使用 Vue3 进行前端开发"）；
    - ruleType 只允许 PREFERENCE 或 CONSTRAINT 两个大写枚举值；
    - 同一会话内重复表达的相同习惯只输出一条；
    - 只输出 JSON 对象 {"habits":[{"fact":"...","ruleType":"..."}]}，无习惯时输出 {"habits":[]}，绝不输出解释文本。
    """)
    @UserMessage("以下是用户最近的对话记录（仅用户发言，每行一条）：\n{{dialogue}}")
    ExtractedHabitList extract(String dialogue);
}
