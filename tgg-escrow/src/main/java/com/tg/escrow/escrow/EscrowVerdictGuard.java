package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 裁决守卫——把「收签名 → 逐个验签 → 判定规则」这条链闭合起来。
 *
 * <p>{@link EscrowMultiSigRule} 判定的是<b>已验签通过</b>的签名方集合。若没有本类，
 * 那句"已验签通过"就只是一个前提假设：任何调用方都可能把没验过的（或验失败的）
 * 签名直接塞进集合，"裁决必含联邦"于是成为一句口号。
 *
 * <h2>两条 fail-closed 纪律</h2>
 * <ol>
 *   <li><b>验签失败方不入集合</b>——不是"计入后再剔除"，而是根本不进入。</li>
 *   <li><b>验签器自身抛异常 → 视为不通过</b>：伪造的签名可能触发实现库的解析异常，
 *       把它向上抛会让调用方被迫写 catch，而 catch 里最容易发生的事就是"吞掉后放行"。
 *       这里直接降级为"不通过"，让失败方向永远是<b>拒绝</b>。</li>
 * </ol>
 *
 * <h2>本类不判的事</h2>
 * <p>不判签名真伪的实现细节（由 {@link PartySignatureVerifier} 负责），
 * 也不判业务上该不该裁决（例如订单是否处于 DISPUTED）——那是服务编排层的职责。
 */
public final class EscrowVerdictGuard {

    private EscrowVerdictGuard() {
    }

    /**
     * 逐个验签，返回<b>通过验签</b>的参与方集合。
     *
     * @param verdict    裁决单（其规范字节即签名载荷）
     * @param signatures 参与方到签名值的映射；空或 {@code null} 表示无人签名
     * @param verifier   单方验签器
     * @return 验签通过的参与方（不可变集合，可能为空）
     */
    public static Set<Party> verifiedParties(EscrowVerdict verdict,
                                             Map<Party, byte[]> signatures,
                                             PartySignatureVerifier verifier) {
        if (verdict == null) {
            throw new EscrowException("裁决守卫缺少裁决单");
        }
        if (verifier == null) {
            throw new EscrowException("裁决守卫缺少验签器");
        }
        if (signatures == null || signatures.isEmpty()) {
            return Set.of();
        }

        byte[] payload = verdict.canonicalBytes();
        EnumSet<Party> verified = EnumSet.noneOf(Party.class);

        for (Map.Entry<Party, byte[]> entry : signatures.entrySet()) {
            Party party = entry.getKey();
            byte[] signature = entry.getValue();
            if (party == null || signature == null || signature.length == 0) {
                // 空手冒充：没有签名值就不算签名方
                continue;
            }
            try {
                if (verifier.verify(party, payload, signature)) {
                    verified.add(party);
                }
            } catch (RuntimeException ex) {
                // fail-closed：验签器出错 → 该方不通过，且不向上抛（见类注释第 2 条）
                // 刻意不记日志：本类是纯逻辑，日志由调用方在编排层统一处理，避免重复。
            }
        }

        return Collections.unmodifiableSet(verified);
    }

    /**
     * 裁决是否可执行：验签通过者是否满足多签规则。
     *
     * <p>便捷入口；需要知道"具体哪几方通过了"时用 {@link #verifiedParties}。
     */
    public static boolean isExecutable(EscrowVerdict verdict,
                                       Map<Party, byte[]> signatures,
                                       PartySignatureVerifier verifier) {
        return EscrowMultiSigRule.satisfies(verifiedParties(verdict, signatures, verifier));
    }
}
