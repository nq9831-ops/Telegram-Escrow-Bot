package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 裁决单——裁决内容的<b>规范字节表示</b>的载体。
 *
 * <h2>为什么需要"规范"序列化</h2>
 * <p>签名是对<b>字节</b>签的，不是对"语义"签的。若同一份裁决有两种字节表示
 * （例如理由中含换行导致字段边界漂移），一方按 A 表示签、另一方按 B 表示验，
 * 结果就是"签名不通过"——而双方意见其实一致。这类故障表现为<b>随机的验签失败</b>，
 * 极难定位，所以格式必须唯一且被测试钉死。
 *
 * <h2>格式（v1）</h2>
 * <pre>
 * escrow-verdict/v1\n
 * &lt;orderId&gt;\n
 * &lt;OUTCOME&gt;\n
 * &lt;理由的 UTF-8 字节数&gt;:&lt;理由&gt;\n
 * &lt;签发时刻 epochSecond&gt;\n
 * </pre>
 *
 * <p>理由用<b>长度前缀</b>定界而非依赖分隔符：这样理由里出现任何字符（换行、冒号、
 * 甚至内容恰好形如后续字段）都不会造成歧义。
 *
 * <p>时刻截断到<b>秒</b>：毫秒/纳秒差异会让双方对同一裁决算出不同字节。
 */
public final class EscrowVerdict {

    /** 裁决结果。 */
    public enum Outcome {
        /** 放款给卖方。 */
        RELEASE,
        /** 退款给买方。 */
        REFUND
    }

    /** 规范格式的版本前缀。格式变更时递增，使错配可见而非静默失败。 */
    private static final String FORMAT_PREFIX = "escrow-verdict/v1\n";

    private final long orderId;
    private final Outcome outcome;
    private final String reason;
    private final Instant issuedAt;

    public EscrowVerdict(long orderId, Outcome outcome, String reason, Instant issuedAt) {
        if (orderId <= 0) {
            throw new EscrowException("裁决单的订单号必须为正数，实为 " + orderId);
        }
        if (outcome == null) {
            throw new EscrowException("裁决单缺少裁决结果");
        }
        if (reason == null) {
            // 刻意不把 null 当空串：两者意图不同（"未填写" vs "明确无理由"），
            // 混同会让审计日志失真。
            throw new EscrowException("裁决单理由未提供（无理由请显式传空串）");
        }
        if (issuedAt == null) {
            throw new EscrowException("裁决单缺少签发时刻");
        }
        this.orderId = orderId;
        this.outcome = outcome;
        this.reason = reason;
        this.issuedAt = issuedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * 规范字节表示。同一个裁决永远产出同一串字节；不同裁决永不撞字节。
     *
     * <p>调用方对这份字节签名 / 验签——签名与内容因此绑定：换一份裁决，旧签名必然失效。
     */
    public byte[] canonicalBytes() {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(FORMAT_PREFIX.length() + 64 + reasonBytes.length);
        sb.append(FORMAT_PREFIX);
        sb.append(orderId).append('\n');
        sb.append(outcome.name()).append('\n');
        sb.append(reasonBytes.length).append(':').append(reason).append('\n');
        sb.append(issuedAt.getEpochSecond()).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public long getOrderId() {
        return orderId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    /** 签发时刻（已截断到秒）。 */
    public Instant getIssuedAt() {
        return issuedAt;
    }

    @Override
    public String toString() {
        return "EscrowVerdict{order=" + orderId + ", outcome=" + outcome
                + ", issuedAt=" + issuedAt + "}";
    }
}
