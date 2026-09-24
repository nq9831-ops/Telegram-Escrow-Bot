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
package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

/**
 * 证据双通道（ET-43）：群内实时记录 + 关键节点上链存证，<b>各自成功/失败分别上报</b>。
 *
 * <h2>硬规则：一条失败不得静默吞掉另一条</h2>
 * <p>核心规则「证据双通道：机器人实时记录 + 关键节点上链存证」要求两条通道<b>互相独立</b>：
 * 上链失败时，群内留痕仍须完成并如实回报其成功；反之亦然。{@link #record} 捕获各通道异常
 * 转为报告状态——<b>不抛、不吞</b>：抛会让调用方以为整条记录失败而重试（造成重复留痕），
 * 吞会让证据缺一条而不自知。{@link Report#complete()} 只有双通道全成才为 {@code true}。
 *
 * <h2>上链是可选项，但必须显式</h2>
 * <p>构造要求两个通道都非空——"暂不上链"的部署应显式传空实现（{@code e -> {}}），
 * 而不是传 {@code null} 静默关闭一条腿。
 *
 * <p>无状态；两个通道由调用方注入（群内记录与链上存证的具体实现属装配层）。
 */
public final class EvidenceChannel {

    /** 证据保存通道（群内记录或链上存证的最小契约）。 */
    @FunctionalInterface
    public interface Sink {
        /** 保存一份证据。失败抛异常（由 {@link EvidenceChannel#record} 捕获转状态）。 */
        void save(String evidence);
    }

    /**
     * 双通道上报结果。
     *
     * @param groupLogSaved 群内留痕是否成功
     * @param chainSaved    链上存证是否成功
     */
    public record Report(boolean groupLogSaved, boolean chainSaved) {
        /** 双通道是否全部成功。 */
        public boolean complete() {
            return groupLogSaved && chainSaved;
        }
    }

    private final Sink groupLog;
    private final Sink chain;

    /**
     * @param groupLog 群内留痕通道（必填）
     * @param chain    链上存证通道（可选用空实现显式关闭）
     */
    public EvidenceChannel(Sink groupLog, Sink chain) {
        if (groupLog == null || chain == null) {
            throw new EscrowException("证据双通道：两个通道都必须配置（上链可选须用空实现显式声明）");
        }
        this.groupLog = groupLog;
        this.chain = chain;
    }

    /**
     * 记录一份证据到两条通道。
     *
     * @param evidence 证据内容（如交付凭证哈希、争议陈述）
     * @return 各通道独立的成功状态（不抛、不吞）
     */
    public Report record(String evidence) {
        if (evidence == null) {
            throw new EscrowException("证据双通道：证据内容不可为空");
        }
        boolean savedGroup = trySave(groupLog, evidence);
        boolean savedChain = trySave(chain, evidence);
        return new Report(savedGroup, savedChain);
    }

    private static boolean trySave(Sink sink, String evidence) {
        try {
            sink.save(evidence);
            return true;
        } catch (RuntimeException ex) {
            // 各自上报：单通道失败不抛、不吞——如实回报状态，让调用方决定补救
            return false;
        }
    }
}
