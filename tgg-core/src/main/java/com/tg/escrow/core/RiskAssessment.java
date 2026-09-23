package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

import java.util.Map;

/**
 * 风险评估结论——<b>携带命中的因子明细，而非只有一个分数</b>。
 *
 * <p>为什么分数不够：用户申诉"为什么拒绝我"时，"总分 55"无法回答任何问题，
 * 而"命中：新账号(30) + 语言不在允许列表(25)"既能回答用户，也能让运营者复核
 * 该策略是否过严。评分系统一旦无法解释，就会被当成黑箱并在申诉中失效。
 *
 * @param score   总分（各命中因子权重之和）
 * @param verdict 裁决档位
 * @param hits    命中的因子及其贡献分
 */
public record RiskAssessment(int score, Verdict verdict, Map<RiskFactor, Integer> hits) {

    /** 裁决档位。<b>三档互斥完备</b>：任何分数必落入其中一档。 */
    public enum Verdict {
        /** 放行。 */
        ALLOW,
        /** 进入人工复核。 */
        REVIEW,
        /** 直接拒绝。 */
        REJECT
    }

    public RiskAssessment {
        if (verdict == null) {
            throw new TggException("风险评估缺少裁决档位");
        }
        if (hits == null) {
            throw new TggException("风险评估缺少命中明细");
        }
        hits = Map.copyOf(hits);
    }

    /** 是否命中某因子。 */
    public boolean hit(RiskFactor factor) {
        return hits.containsKey(factor);
    }
}
