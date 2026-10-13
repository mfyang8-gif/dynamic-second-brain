package com.yangmf.mini_nodepad.client.dto;

/**
 * {@code POST /messages} 的响应体（服务端 {@code AddMessagesResponse}）。
 *
 * <p>⚠️ 这是本 Phase 最需要警惕的一条契约：
 * <b>202 ≠ 成功</b>（§3.6）。该接口是异步的，返回 202 只代表「任务已入队」，
 * 真正的落库发生在后台 job 里，可能 15~45s 之后才可见，且<b>可能失败</b>
 * （例如误传 uuid 导致的 {@code NodeNotFoundError}，见陷阱 31 —— 后台静默失败、
 * HTTP 照样 202）。因此：
 *
 * <ul>
 *   <li>调用方（B1 的写消费者）拿到本对象后<b>只应做投递 ack</b>，绝不可当成落库成功；</li>
 *   <li>落库确认必须靠异步对账（{@code GraphitiConfirmJob} 按 {@code name} 锚点比对），
 *       且确认动作<b>不能阻塞消费者</b>（陷阱 16）。</li>
 * </ul>
 */
public record GraphitiWriteAck(

        /** 服务端返回的提示文本（如 "Messages sent" 之类）。 */
        String message,

        /** 是否受理。⚠️ 仅表示「已入队」，不代表落库成功。 */
        Boolean success) {

    /** 是否已受理入队（不等于落库成功，见类注释）。 */
    public boolean accepted() {
        return Boolean.TRUE.equals(success);
    }
}
