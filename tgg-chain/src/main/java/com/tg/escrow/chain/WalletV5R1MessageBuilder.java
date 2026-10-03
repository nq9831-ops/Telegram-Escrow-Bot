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
package com.tg.escrow.chain;

import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.cell.CellSlice;

/**
 * v5r1 钱包 external 消息体的官方组装（自实现，逐位对齐 {@code @ton/ton}）。
 *
 * <h2>为什么不能用 ton4j 的 WalletV5 高层组装（2026-10-02 首笔真机写链定案）</h2>
 * <p>ton4j 的 {@code createExternalTransferBody} 拼的是<b>另一种版本钱包</b>的格式：
 * 首 32 位 magic 为 {@code 0x73656e64}（"send"），而 <b>v5r1 规范是 {@code 0x7369676e}
 * （"sign"）</b>——真机表现为<b>钱包静默拒收全部外发消息</b>（广播成功、链上无痕迹）。
 * 官方布局（{@code @ton/ton} 的 {@code createWalletTransferV5R1}，随对拍测试固化）：
 * <pre>
 * signingMessage = [ "sign":32 ][ walletId:32 ][ validUntil:32 ][ seqno:32 ][ outActions 段 ]
 * outActions 段   = [ Maybe-ref: OutList（sendMsg 动作） ][ 扩展动作位:1 = 0 ]
 * signature       = sign( hash(signingMessage) )        ← 签【不含签名】的消息哈希
 * external body   = [ signingMessage 内容 ][ signature:64B ]   ← 签名在尾部（内联）
 * </pre>
 *
 * <p>另注意 v5r1 的 send-mode 安全规则（官方 {@code patchV5R1ActionsSendMode}）：
 * <b>external 的 sendMsg 动作须含 IGNORE_ERRORS(2) 位</b>——外部消息改动的动作都必须是
 * "忽略失败"语义；我们生产路径用的 mode=3（PAY_GAS_SEPARATELY|IGNORE_ERRORS）本就满足。
 *
 * <p>纯逻辑、无副作用、不触网——由金标准向量（官方 SDK 生成）对拍钉住。
 */
public final class WalletV5R1MessageBuilder {

    /** v5r1 external 认证 magic："sign"（0x7369676e）。 */
    public static final long AUTH_SIGNED_EXTERNAL = 0x7369676EL;

    /** external 动作的 send mode 必须位（IGNORE_ERRORS）——安全规则，构建时强制。 */
    public static final int SEND_MODE_IGNORE_ERRORS = 2;

    private WalletV5R1MessageBuilder() {
    }

    /**
     * 待签消息（signingMessage）——签名对象是其 <b>hash</b>。
     *
     * @param walletId    v5r1 wallet id（见 {@link ChainWalletKeys#v5r1WalletId}）
     * @param validUntil  过期时刻（unix 秒）
     * @param seqno       钱包序号（未部署钱包为 0）
     * @param outActions  {@link #outActionsSegment} 的产物
     */
    public static Cell signingMessage(long walletId, long validUntil, long seqno, Cell outActions) {
        if (outActions == null) {
            throw new IllegalArgumentException("v5r1 组装：outActions 段不可为空");
        }
        return CellBuilder.beginCell()
                .storeUint(AUTH_SIGNED_EXTERNAL, 32)
                .storeUint(walletId, 32)
                .storeUint(validUntil, 32)
                .storeUint(seqno, 32)
                .storeSlice(CellSlice.beginParse(outActions))
                .endCell();
    }

    /**
     * out-actions 段（无扩展动作的最小形态）：{@code [Maybe-ref: OutList][0:1]}。
     *
     * @param outList 动作列表（{@code OutList.toCell()} 的产物；无动作传 {@code null}）
     */
    public static Cell outActionsSegment(Cell outList) {
        return CellBuilder.beginCell()
                .storeRefMaybe(outList)
                .storeBit(false)
                .endCell();
    }

    /**
     * external body = {@code [signingMessage 内容][signature:64B]}（官方
     * {@code packSignatureToTail}：签名内联在尾部）。
     */
    public static Cell externalBody(Cell signingMessage, byte[] signature) {
        if (signature == null || signature.length != 64) {
            throw new IllegalArgumentException(
                    "v5r1 组装：ed25519 签名应为 64 字节，实为 "
                            + (signature == null ? "null" : signature.length));
        }
        return CellBuilder.beginCell()
                .storeSlice(CellSlice.beginParse(signingMessage))
                .storeBytes(signature)
                .endCell();
    }
}
