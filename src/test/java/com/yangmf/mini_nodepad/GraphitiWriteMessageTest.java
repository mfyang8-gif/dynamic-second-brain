package com.yangmf.mini_nodepad;

import com.yangmf.mini_nodepad.client.GraphitiErrorClassifier;
import com.yangmf.mini_nodepad.mq.GraphitiWriteMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphitiWriteMessage} 单测 —— 投递信封的不变量。
 *
 * <p>为什么要测一个「数据类」：它是穿越 RabbitMQ 序列化边界的载体。
 * 构造器里的 4 条校验不是防御式编程，而是<b>把静默失败变成快速失败</b>：
 * 缺 {@code messageId} → 确认锚点不存在 → 消息永远确认不了；
 * 缺 {@code createdAtIso} → 每条历史对话都被盖上「处理时刻」→ 图谱时间线被污染。
 * 这两项在运行期都不会报错，只会让结果悄悄变错。
 */
@DisplayName("L0-2 GraphitiWriteMessage —— 投递信封的不变量")
class GraphitiWriteMessageTest {

    private static final String MID = "9000000000000000000000000000001";
    private static final String SID = "9000000000000000000000000000002";
    private static final String UID = "999999";
    private static final String CONTENT = "我对花生过敏";
    private static final String ISO = "2026-10-13T10:00:00";

    // ==================== ① 构造器校验：静默失败 → 快速失败 ====================

    @Nested
    @DisplayName("① 必填字段缺失必须快速失败（否则会变成静默错误，不是报错）")
    class RequiredFields {

        @ParameterizedTest(name = "messageId=[{0}] → 拒绝")
        @NullSource
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("★ messageId 为空 → 拒绝（它是确认锚点 cm_&lt;id&gt; 的唯一来源）")
        void messageIdRequired(String messageId) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GraphitiWriteMessage(messageId, SID, UID, CONTENT, ISO, "s", 0, 1),
                    "缺 messageId = 确认锚点不存在 = 该消息永远无法被确认落库");
        }

        @ParameterizedTest(name = "userId=[{0}] → 拒绝")
        @NullSource
        @ValueSource(strings = {"", "  "})
        @DisplayName("★ userId 为空 → 拒绝（group_id 只能由它推导，缺了就是跨租户风险）")
        void userIdRequired(String userId) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GraphitiWriteMessage(MID, SID, userId, CONTENT, ISO, "s", 0, 1));
        }

        @ParameterizedTest(name = "content=[{0}] → 拒绝")
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("content 为空 → 拒绝")
        void contentRequired(String content) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GraphitiWriteMessage(MID, SID, UID, content, ISO, "s", 0, 1));
        }

        @ParameterizedTest(name = "createdAtIso=[{0}] → 拒绝")
        @NullSource
        @ValueSource(strings = {"", "  "})
        @DisplayName("★ createdAtIso 为空 → 拒绝（缺了会让历史对话被盖上处理时刻 —— 陷阱 4）")
        void createdAtIsoRequired(String iso) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GraphitiWriteMessage(MID, SID, UID, CONTENT, iso, "s", 0, 1),
                    "缺业务时刻 = 图谱把整段历史对话钉在「投递那一刻」，时间线全错");
        }

        @Test
        @DisplayName("sessionId / sourceDescription 可以为空（它们只用于日志与展示，不参与正确性）")
        void optionalFieldsMayBeBlank() {
            GraphitiWriteMessage msg = new GraphitiWriteMessage(MID, null, UID, CONTENT, ISO, null, 0, 1);
            assertEquals(MID, msg.messageId());
            assertTrue(msg.sessionId() == null);
        }
    }

    // ==================== ② 优先级 ====================

    @Nested
    @DisplayName("② 优先级：常规 1 / 高危 10，且非法值必须被兜住")
    class Priority {

        @Test
        @DisplayName("highRisk 工厂 → 10；normal 工厂 → 1")
        void factoriesSetExpectedPriority() {
            assertEquals(GraphitiWriteMessage.PRIORITY_HIGH_RISK,
                    GraphitiWriteMessage.highRisk(MID, SID, UID, CONTENT, ISO, "s").priority());
            assertEquals(GraphitiWriteMessage.PRIORITY_NORMAL,
                    GraphitiWriteMessage.normal(MID, SID, UID, CONTENT, ISO, "s").priority());
        }

        @Test
        @DisplayName("★ 优先级上限必须等于队列的 x-max-priority（10），否则插队无效")
        void highRiskPriorityMatchesQueueCapability() {
            assertEquals(10, GraphitiWriteMessage.PRIORITY_HIGH_RISK,
                    """
                    高危优先级与 graphiti.write.queue 的 x-max-priority 必须一致。
                    队列声明为 10，而消息给 20 时 broker 会把它截到 10（不报错）；
                    消息给 10 而队列声明成 5 时，10 会被截成 5 —— 两种情况都静默，插队形同虚设。""");
        }

        @ParameterizedTest(name = "priority={0} → 归一化为 {1}")
        @org.junit.jupiter.params.provider.CsvSource({
                "0,  1",
                "-1, 1",
                "-99, 1",
                "1,  1",
                "10, 10",
                "7,  7",
        })
        @DisplayName("priority ≤ 0 归一到常规档（负数会被 broker 拒收或当 0，绝不能透传）")
        void nonPositivePriorityIsNormalized(int given, int expected) {
            GraphitiWriteMessage msg = new GraphitiWriteMessage(MID, SID, UID, CONTENT, ISO, "s", 0, given);
            assertEquals(expected, msg.priority());
        }
    }

    // ==================== ③ nextAttempt：两个动作必须成对 ====================

    @Nested
    @DisplayName("③ nextAttempt()：attempt 递增与优先级保留必须成对发生")
    class NextAttempt {

        @Test
        @DisplayName("attempt 递增")
        void incrementsAttempt() {
            GraphitiWriteMessage m0 = GraphitiWriteMessage.normal(MID, SID, UID, CONTENT, ISO, "s");
            assertEquals(0, m0.attempt());
            assertEquals(1, m0.nextAttempt().attempt());
            assertEquals(2, m0.nextAttempt().nextAttempt().attempt());
        }

        @Test
        @DisplayName("★ 高危消息重投一次不得降级为平民（漏保 priority = 优先级队列形同虚设）")
        void preservesPriorityAcrossRetries() {
            GraphitiWriteMessage high = GraphitiWriteMessage.highRisk(MID, SID, UID, CONTENT, ISO, "s");
            GraphitiWriteMessage retried = high;
            for (int i = 0; i < GraphitiErrorClassifier.MAX_ATTEMPT; i++) {
                retried = retried.nextAttempt();
                // 循环内被重新赋值的变量不是 effectively-final，断言消息里要用副本
                final int round = retried.attempt();
                assertEquals(GraphitiWriteMessage.PRIORITY_HIGH_RISK, retried.priority(),
                        () -> "第 " + round + " 次重投后优先级被降级 —— 高危消息失败一次就排到队尾");
            }
        }

        @Test
        @DisplayName("★ 重投不得丢字段（内容/锚点/时刻在往返 MQ 后必须逐字段一致）")
        void retryKeepsAllFields() {
            GraphitiWriteMessage original =
                    GraphitiWriteMessage.normal(MID, SID, UID, CONTENT, ISO, "用户消息（命中高危词：过敏）");
            GraphitiWriteMessage next = original.nextAttempt();

            assertEquals(original.messageId(), next.messageId());
            assertEquals(original.sessionId(), next.sessionId());
            assertEquals(original.userId(), next.userId());
            assertEquals(original.content(), next.content());
            assertEquals(original.createdAtIso(), next.createdAtIso());
            assertEquals(original.sourceDescription(), next.sourceDescription());
            assertEquals(original.priority(), next.priority());
            assertEquals(original.attempt() + 1, next.attempt());
        }

        @Test
        @DisplayName("isExhausted 与 GraphitiErrorClassifier.MAX_ATTEMPT 同源（不得各写一个数）")
        void exhaustedIsSourcedFromClassifier() {
            assertFalse(GraphitiWriteMessage.normal(MID, SID, UID, CONTENT, ISO, "s").isExhausted());
            GraphitiWriteMessage atLimit = new GraphitiWriteMessage(MID, SID, UID, CONTENT, ISO, "s",
                    GraphitiErrorClassifier.MAX_ATTEMPT, 1);
            assertTrue(atLimit.isExhausted(),
                    "达到上限却不判死 → 会无限重投，把配额窗口填满");
        }
    }

    // ==================== ④ 字符预算常量 ====================

    @Test
    @DisplayName("★ CONTENT_CHAR_BUDGET 必须显著小于 GraphitiMessage.MAX_CONTENT_LENGTH（8000）")
    void contentBudgetStaysUnderServerLimit() {
        assertTrue(GraphitiWriteMessage.CONTENT_CHAR_BUDGET < 8000,
                "预算若 ≥ 服务端上限，超长内容会在 Graphiti 侧被拒（pydantic 422）→ 走 TOXIC 永久丢弃");
        assertEquals(4000, GraphitiWriteMessage.CONTENT_CHAR_BUDGET,
                "该值被扫描层 / 补投层 / 快通道三处引用，改动前先确认三处行为一致（见 GraphitiStaticGuardTest）");
    }

    // ==================== ⑤ 序列化边界（它要真的穿过 RabbitMQ）====================

    @Test
    @DisplayName("★ 可被 Java 原生序列化往返且字段等价（Spring AMQP 默认走 SimpleMessageConverter）")
    void roundTripsThroughJavaSerialization() throws Exception {
        GraphitiWriteMessage original = GraphitiWriteMessage.highRisk(
                MID, SID, UID, CONTENT, ISO, "用户消息（命中高危词：过敏，提前投递）");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        GraphitiWriteMessage restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (GraphitiWriteMessage) in.readObject();
        }

        assertEquals(original, restored,
                """
                record 是 final 且字段全 final，序列化往返必须等价。
                不等价说明有人加了不可序列化的字段（如非 transient 的 lambda / 外部句柄）——
                那会在**消费侧**才炸，且只在真投递路径上暴露。""");
    }
}
