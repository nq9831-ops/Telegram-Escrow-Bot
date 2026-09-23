package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

import java.util.EnumMap;
import java.util.Map;

/**
 * 入群风险评分器（原文档 G28/G29）。
 *
 * <p>把若干弱信号累加成一个可执行的档位判断：放行 / 人工复核 / 直接拒绝。
 * 机制全部来自 {@link RiskPolicy}（配置），本类不内置任何数值判断。
 *
 * <h2>三处刻意的行为选择</h2>
 * <ol>
 *   <li><b>权重表中没有的因子不计入</b>（而不是"按默认权重计"）：这让因子可以逐个启用，
 *       也让"暂时不想要某个因子"不需要改代码。</li>
 *   <li><b>语言未知（{@code null}/空白）时不判违规</b>：Telegram 不保证提供语言，
 *       把"拿不到"当作"可疑"会让评分在部分客户端上系统性偏高——那是数据问题，
 *       不是用户问题。</li>
 *   <li><b>{@code signals} 为 {@code null} 时抛异常</b>，不做"无信号即放行"的默认：
 *       评分器的失败方向应当是拒绝，而非放行。</li>
 * </ol>
 *
 * <h2>阈值比较用「达到即算」</h2>
 * <p>{@code score >= threshold} 而非 {@code >}。原因很实际：配置里写 20 的人期望的是
 * "到 20 就复核"，而不是"到 21 才复核"——后者会让人反复核对配置和日志却找不出差异。
 */
public final class JoinRiskScorer {

    private final RiskPolicy policy;

    public JoinRiskScorer(RiskPolicy policy) {
        if (policy == null) {
            throw new TggException("未提供风险策略");
        }
        this.policy = policy;
    }

    /**
     * 评估一次入群申请。
     *
     * @param signals 申请时可获得的信号
     * @return 分数、档位与命中明细
     */
    public RiskAssessment assess(JoinSignals signals) {
        if (signals == null) {
            throw new TggException("未提供入群信号——不做「无信号即放行」的默认");
        }

        Map<RiskFactor, Integer> hits = new EnumMap<>(RiskFactor.class);

        if (!signals.hasAvatar()) {
            hit(hits, RiskFactor.NO_AVATAR);
        }
        if (!signals.hasUsername()) {
            hit(hits, RiskFactor.NO_USERNAME);
        }
        if (signals.accountAgeDays() < policy.newAccountDays()) {
            hit(hits, RiskFactor.NEW_ACCOUNT);
        }
        if (languageNotAllowed(signals.languageCode())) {
            hit(hits, RiskFactor.LANGUAGE_NOT_ALLOWED);
        }

        int score = hits.values().stream().mapToInt(Integer::intValue).sum();

        RiskAssessment.Verdict verdict;
        if (score >= policy.rejectThreshold()) {
            verdict = RiskAssessment.Verdict.REJECT;
        } else if (score >= policy.reviewThreshold()) {
            verdict = RiskAssessment.Verdict.REVIEW;
        } else {
            verdict = RiskAssessment.Verdict.ALLOW;
        }

        return new RiskAssessment(score, verdict, hits);
    }

    private void hit(Map<RiskFactor, Integer> hits, RiskFactor factor) {
        Integer weight = policy.weights().get(factor);
        if (weight != null && weight > 0) {
            hits.put(factor, weight);
        }
    }

    private boolean languageNotAllowed(String languageCode) {
        if (!policy.languageCheckEnabled()) {
            return false;
        }
        if (languageCode == null || languageCode.isBlank()) {
            // 语言未知不臆断为违规：那是采集问题，不是用户问题
            return false;
        }
        String normalized = languageCode.trim();
        return policy.allowedLanguages().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(normalized));
    }
}
