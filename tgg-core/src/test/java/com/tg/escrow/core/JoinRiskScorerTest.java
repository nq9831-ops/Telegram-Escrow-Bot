/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 telegram-escrow-bot contributors
 *
 * This file is part of telegram-escrow-bot.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * NOTE: the SPDX identifier is AGPL-3.0-only because the LICENSE file in this
 * repository carries the plain AGPL v3 text without an "or later" grant. If you
 * intend to allow later versions, change this line to AGPL-3.0-or-later and
 * make the LICENSE wording match — the two must not disagree.
 */
package com.tg.escrow.core;

import com.tg.escrow.common.TggException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入群风险评分的行为固定测试（原文档 G28/G29）。
 *
 * <p>评分是"概率判断"，不是"事实判定"，所以本测试钉住的不是"多少分算坏"——
 * 那是业务决策——而是<b>机制</b>：哪些信号会被计入、如何累加、边界如何处理、
 * 配置缺失时是静默忽略还是报错。
 */
class JoinRiskScorerTest {

    private static final Map<RiskFactor, Integer> WEIGHTS = Map.of(
            RiskFactor.NO_AVATAR, 10,
            RiskFactor.NO_USERNAME, 15,
            RiskFactor.NEW_ACCOUNT, 30,
            RiskFactor.LANGUAGE_NOT_ALLOWED, 25);

    private static RiskPolicy policy(Set<String> allowedLanguages) {
        return new RiskPolicy(7, allowedLanguages, WEIGHTS, 20, 50);
    }

    private static JoinSignals cleanSignals() {
        // 有头像、有用户名、账号一年、语言在允许列表内
        return new JoinSignals(true, true, 365, "zh");
    }

    private static JoinRiskScorer scorer() {
        return new JoinRiskScorer(policy(Set.of("zh", "en")));
    }

    @Test
    @DisplayName("无风险信号 → 总分 0、裁决 ALLOW、无命中因子")
    void cleanSignalsScoreZero() {
        RiskAssessment assessment = scorer().assess(cleanSignals());

        assertThat(assessment.score()).isZero();
        assertThat(assessment.verdict()).isEqualTo(RiskAssessment.Verdict.ALLOW);
        assertThat(assessment.hits()).isEmpty();
    }

    @Test
    @DisplayName("单因子命中 → 总分等于该因子权重（未命中因子不进 hits）")
    void singleFactorContributesItsWeight() {
        RiskAssessment assessment = scorer().assess(
                new JoinSignals(false, true, 365, "zh"));

        assertThat(assessment.score()).isEqualTo(10);
        assertThat(assessment.hits()).containsOnlyKeys(RiskFactor.NO_AVATAR);
        assertThat(assessment.verdict()).isEqualTo(RiskAssessment.Verdict.ALLOW);
    }

    @Test
    @DisplayName("多因子累加")
    void multipleFactorsAccumulate() {
        RiskAssessment assessment = scorer().assess(
                new JoinSignals(false, false, 365, "zh"));

        assertThat(assessment.score()).isEqualTo(25); // 10 + 15
        assertThat(assessment.hits())
                .containsOnlyKeys(RiskFactor.NO_AVATAR, RiskFactor.NO_USERNAME);
    }

    @Test
    @DisplayName("达到 reviewThreshold → REVIEW（阈值是「达到即算」，不是「超过才算」）")
    void reachingReviewThresholdYieldsReview() {
        // NO_USERNAME(15) + NO_AVATAR(10) = 25 ≥ 20
        RiskAssessment assessment = scorer().assess(
                new JoinSignals(false, false, 365, "zh"));

        assertThat(assessment.score()).isEqualTo(25);
        assertThat(assessment.verdict()).isEqualTo(RiskAssessment.Verdict.REVIEW);
    }

    @Test
    @DisplayName("达到 rejectThreshold → REJECT")
    void reachingRejectThresholdYieldsReject() {
        // NEW_ACCOUNT(30) + LANGUAGE_NOT_ALLOWED(25) = 55 ≥ 50
        RiskAssessment assessment = scorer().assess(
                new JoinSignals(true, true, 3, "xx"));

        assertThat(assessment.score()).isEqualTo(55);
        assertThat(assessment.verdict()).isEqualTo(RiskAssessment.Verdict.REJECT);
    }

    @Test
    @DisplayName("账号年龄边界：恰好等于 newAccountDays 不算新账号，小于才算")
    void newAccountBoundaryIsExclusive() {
        JoinRiskScorer scorer = scorer();

        assertThat(scorer.assess(new JoinSignals(true, true, 7, "zh")).score()).isZero();
        assertThat(scorer.assess(new JoinSignals(true, true, 6, "zh")).score()).isEqualTo(30);
    }

    @Test
    @DisplayName("允许语言列表为空 → 不检查语言（该因子整体关闭）")
    void emptyAllowedLanguagesDisablesTheFactor() {
        JoinRiskScorer scorer = new JoinRiskScorer(policy(Set.of()));

        assertThat(scorer.assess(new JoinSignals(true, true, 365, "任意语言")).hits())
                .as("列表为空表示不做语言限制，而不是「所有语言都不允许」")
                .doesNotContainKey(RiskFactor.LANGUAGE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("语言不在允许列表 → 命中；列表为 null 同样视为关闭")
    void languageOutsideAllowedListHits() {
        assertThat(scorer().assess(new JoinSignals(true, true, 365, "fr")).hits())
                .containsKey(RiskFactor.LANGUAGE_NOT_ALLOWED);
        assertThat(scorer().assess(new JoinSignals(true, true, 365, null)).hits())
                .as("语言未知时不臆断为违规")
                .doesNotContainKey(RiskFactor.LANGUAGE_NOT_ALLOWED);

        JoinRiskScorer nullList = new JoinRiskScorer(policy(null));
        assertThat(nullList.assess(new JoinSignals(true, true, 365, "fr")).hits())
                .doesNotContainKey(RiskFactor.LANGUAGE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("权重表中没有的因子 → 命中也不计分（便于按需逐步启用因子）")
    void factorWithoutWeightContributesNothing() {
        RiskPolicy partial = new RiskPolicy(7, Set.of("zh"),
                Map.of(RiskFactor.NEW_ACCOUNT, 30), 20, 50);
        JoinRiskScorer scorer = new JoinRiskScorer(partial);

        // 无头像本应命中，但权重表没给它权重
        RiskAssessment assessment = scorer.assess(new JoinSignals(false, true, 365, "zh"));

        assertThat(assessment.score()).isZero();
        assertThat(assessment.verdict()).isEqualTo(RiskAssessment.Verdict.ALLOW);
    }

    @Test
    @DisplayName("权重为负数 → 构造期抛异常（负权重等于给风险减分，只可能是配置写错）")
    void negativeWeightFailsFast() {
        assertThatThrownBy(() -> new RiskPolicy(7, Set.of("zh"),
                Map.of(RiskFactor.NO_AVATAR, -1), 20, 50))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("阈值顺序颠倒（reject ≤ review）→ 构造期抛异常（否则 REVIEW 永远不可达）")
    void invertedThresholdsFailFast() {
        assertThatThrownBy(() -> new RiskPolicy(7, Set.of("zh"), WEIGHTS, 50, 50))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new RiskPolicy(7, Set.of("zh"), WEIGHTS, 60, 50))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("newAccountDays 为负 → 构造期抛异常")
    void negativeNewAccountDaysFailsFast() {
        assertThatThrownBy(() -> new RiskPolicy(-1, Set.of("zh"), WEIGHTS, 20, 50))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("signals 为 null → 抛异常（不做「无信号即放行」的默认）")
    void nullSignalsIsRejected() {
        assertThatThrownBy(() -> scorer().assess(null))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("裁决分档由阈值决定，且三档互斥完备")
    void verdictBandsAreExhaustive() {
        JoinRiskScorer scorer = scorer();

        assertThat(scorer.assess(cleanSignals()).verdict()).isEqualTo(RiskAssessment.Verdict.ALLOW);

        RiskAssessment review = scorer.assess(new JoinSignals(false, false, 365, "zh"));
        assertThat(review.verdict()).isEqualTo(RiskAssessment.Verdict.REVIEW);

        RiskAssessment reject = scorer.assess(new JoinSignals(false, false, 3, "xx"));
        assertThat(reject.verdict()).isEqualTo(RiskAssessment.Verdict.REJECT);
    }
}
