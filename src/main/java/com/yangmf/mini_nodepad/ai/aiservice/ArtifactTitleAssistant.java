package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 工件两阶段命名助手。
 *
 * <p>同一个接口服务两个阶段，靠 stage 入参区分（避免为一次短调用建两个 Bean）：
 * <ul>
 *   <li>{@code DRAFT}：主生成启动的同时并行调用，手里只有用户诉求，产出"场景化暂定名"，
 *       供前端加载态占位（如「关于群体心理学的思维导图」）。</li>
 *   <li>{@code FINAL}：主生成结束后在【主线程】调用，手握完整成品内容节选，
 *       产出具象标题（如「狂热与博弈：群体心理的思维脉络」）并随 SUCCESS 一起落库。</li>
 * </ul>
 *
 * <p>调用方（StudioServiceImpl）对两个阶段都做了 Best-Effort 收敛，
 * 这里的任何异常都不会影响工件生成本身。
 */
public interface ArtifactTitleAssistant {

    @SystemMessage("""
    你是资深内容编辑，负责为知识库衍生工件命名。

    【DRAFT 阶段：只拿到用户诉求】
    输出一个"场景化暂定名"，形如「关于群体心理学的思维导图」。
    要求平实、具体、一眼能认出在做什么，此时不追求文采。

    【FINAL 阶段：已拿到成品内容】
    输出一个具象、有辨识度的正式标题，形如「狂热与博弈：群体心理的思维脉络」。
    允许使用主副标题（中间用中文冒号分隔），但必须有信息量，
    禁止「XX报告」「XX测验」这类类型名加主题的空壳命名。

    【硬性规则】
    1. 只输出标题本身，禁止任何解释、序号、引号、书名号、冒号前缀等附加文字
    2. 长度控制在 6 到 24 个汉字
    3. 语言与用户诉求保持一致
    4. 不得臆造内容节选中不存在的结论或实体
    5. 若信息不足以命名，就退回到「关于（主题）的（工件类型）」这种稳妥表达
    """)
    @UserMessage("""
    【阶段】{{stage}}
    【工件类型】{{typeName}}
    【知识库名称】{{bookName}}
    【用户诉求】{{topic}}
    【内容节选】{{digest}}
    """)
    String nameIt(@V("stage") String stage,
                  @V("typeName") String typeName,
                  @V("bookName") String bookName,
                  @V("topic") String topic,
                  @V("digest") String digest);
}
