package com.yangmf.mini_nodepad.ai.aiservice;

import com.yangmf.mini_nodepad.pojo.dto.HabitVerdict;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 习惯去重仲裁器（方案 B 第二级判定）：向量只负责「宽松召回」，真正的是否同一由本判定器裁决。
 * <p>
 * 为什么必须有这一级：向量余弦衡量的是**句子形式相似度**而非**事实同一性**。实测
 * 「用中文交流 vs 用英文交流」= 0.9330、「偏好 Vue3 vs 偏好 React」= 0.8473，
 * 而「不要在代码里写多余的注释 vs 禁止在代码中添加注释」仅 0.7483 ——
 * 「该合并的最低分」低于「绝不该合并的最高分」，不存在可用的相似度阈值。
 * <p>
 * 输出契约与 {@link #judge} 的 prompt 严格对应：{@code {"isSame":bool,"matchedHash":str}}。
 * 返回值属不可信数据，调用方必须对 matchedHash 做「必须命中召回集合」的校验后才可使用。
 */
public interface HabitJudge {

    @SystemMessage("""
            你是一个极度严谨的逻辑比对引擎。你的任务是判断「新习惯」是否与候选列表中的某一条在逻辑和事实上完全等价。

            基础规则：
            1. 若候选列表为空，直接判定为不相同；
            2. matchedHash 只能从候选列表给出的 hash 中挑选，绝对禁止编造、拼接或修改 hash。

            判定为「相同」的情形：
            - 两条指向同一对象、同一方向（同为偏好或同为禁止），只是措辞、句式或详略程度不同。
              例如「不要在代码里写多余的注释」与「禁止在代码中添加注释」是同一条习惯的两种说法，应判定为相同。

            判定为「不相同」的情形（警告）：
            - 两条描述的是同一类事物的不同选项，例如「偏好 Vue3」与「偏好 React」是同一类别（前端框架）
              的不同取值，「用中文输出」与「用英文输出」是同一类别（输出语言）的不同取值 ——
              它们是相互冲突的新习惯，绝对不能判定为相同；
            - 约束或偏好的对象不同，例如「禁止写注释」与「禁止写日志」；
            - 语义相反，例如「写详细注释」与「禁止写注释」；
            - 你无法百分之百确定二者是同一事实。

            若在「同义复述」与「不同选项」之间摇摆，一律判定为不相同。

            输出格式要求：只输出 JSON 对象，形如
            {"isSame": true, "matchedHash": "命中的 fact_hash"}
            或
            {"isSame": false, "matchedHash": ""}
            除 JSON 外绝不输出任何解释文字。
            """)
    @UserMessage("""
            新习惯：
            {{newFact}}

            候选列表（每行一条，格式为 [序号] hash=... | 习惯原文）：
            {{candidates}}
            """)
    HabitVerdict judge(@V("newFact") String newFact, @V("candidates") String candidates);
}
