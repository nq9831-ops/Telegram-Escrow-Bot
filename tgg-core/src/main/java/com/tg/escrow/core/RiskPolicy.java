package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

import java.util.Map;
import java.util.Set;

/**
 * 风险评分策略——<b>所有数值判断集中在这里，且全部由配置提供</b>。
 *
 * <p>代码只实现机制（累加、比较阈值），不实现判断（多少分算坏、哪些语言不允许）。
 * 这样同一份代码可以在不同辖区、不同群规模下调整，而不必改代码重新发版；
 * 更重要的是：**判断的责任留在配置它的那一方**，而不是隐藏在实现里。
 *
 * <h2>构造期的 fail-fast</h2>
 * <p>四类错误一律在构造期抛出，不让它们变成运行时的奇怪行为：
 * <ul>
 *   <li><b>负权重</b>：等于给风险减分——只可能是配置写错，而且会让攻击者能"刷低"分数；</li>
 *   <li><b>阈值顺序颠倒</b>（{@code reject ≤ review}）：{@code REVIEW} 档将永远不可达，
 *       拒绝会变得比预期激进得多，且不会有任何报错；</li>
 *   <li><b>负的账号年龄门槛</b>；</li>
 *   <li><b>权重表为 {@code null}</b>。</li>
 * </ul>
 *
 * @param newAccountDays  账号年龄低于此值（天）视为新账号
 * @param allowedLanguages 允许的语言；<b>空集或 {@code null} 表示不检查语言</b>（不是"都不允许"）
 * @param weights         各因子的权重；<b>未出现在表中的因子不会被计入</b>，便于按需逐步启用
 * @param reviewThreshold 达到此分进入人工复核
 * @param rejectThreshold 达到此分直接拒绝（必须大于 reviewThreshold）
 */
public record RiskPolicy(int newAccountDays, Set<String> allowedLanguages,
                         Map<RiskFactor, Integer> weights,
                         int reviewThreshold, int rejectThreshold) {

    public RiskPolicy {
        if (newAccountDays < 0) {
            throw new TggException("账号年龄门槛为负（" + newAccountDays + "）");
        }
        if (weights == null) {
            throw new TggException("风险权重表未提供");
        }
        for (Map.Entry<RiskFactor, Integer> entry : weights.entrySet()) {
            if (entry.getKey() == null) {
                throw new TggException("风险权重表含空因子");
            }
            Integer weight = entry.getValue();
            if (weight == null || weight < 0) {
                throw new TggException("风险因子权重非法（" + entry.getKey() + "=" + weight
                        + "）——负权重等于给风险减分，会让评分可被刷低");
            }
        }
        if (reviewThreshold < 0 || rejectThreshold < 0) {
            throw new TggException("风险阈值为负（review=" + reviewThreshold
                    + ", reject=" + rejectThreshold + "）");
        }
        if (rejectThreshold <= reviewThreshold) {
            throw new TggException("风险阈值顺序颠倒：reject(" + rejectThreshold
                    + ") 必须大于 review(" + reviewThreshold + ")，否则复核档永远不可达");
        }
        weights = Map.copyOf(weights);
        allowedLanguages = allowedLanguages == null ? Set.of() : Set.copyOf(allowedLanguages);
    }

    /** 是否启用了语言检查（列表非空即启用）。 */
    public boolean languageCheckEnabled() {
        return !allowedLanguages.isEmpty();
    }
}
