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

import com.iwebpp.crypto.TweetNaclFast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.tlb.ActionSendMsg;
import org.ton.ton4j.tlb.CurrencyCollection;
import org.ton.ton4j.tlb.InternalMessageInfoRelaxed;
import org.ton.ton4j.tlb.MessageRelaxed;
import org.ton.ton4j.tlb.MsgAddressIntStd;
import org.ton.ton4j.tlb.OutList;
import org.ton.ton4j.tlb.StateInit;

import java.math.BigInteger;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WalletV5R1MessageBuilder} 的金标准对拍测试——与官方 {@code @ton/ton} 逐位一致。
 *
 * <h2>金标准来源（2026-10-02 生成）</h2>
 * <p>官方 SDK 脚本（{@code /tmp/tonv5probe/probe4.mjs}，参数与下同）：
 * <pre>
 * 词组 = golden（见 {@link ChainWalletKeysTest}）   walletId = testnet 默认 2147483645
 * seqno = 0    timeout(validUntil) = 1999999999   sendMode = 3
 * 动作 = 单条 ActionSendMsg{ to=0:276ec568…f4d（固定 raw）, value=300000000,
 *          init=StateInit{code=0xABCD(16b), data=0xDA7A(16b)}, body=空, bounce=true }
 * </pre>
 * 其 {@code createTransfer} 产物（含签名）的 BOC 与 hash 固化为常量——Java 自实现同参组装
 * 必须逐位命中。**这是首笔真机被拒（ton4j 组装为错误版本格式）后的重建**：不通过本用例
 * 就不要再上真机（省 testnet 币与排查时间）。
 */
class WalletV5R1MessageBuilderTest {

    /** 官方 SDK 生成的完整 body（含签名）BOC。 */
    private static final String JS_BODY_BOC64 =
            "te6cckEBBgEAoAABoXNpZ25////9dzWT/wAAAAC+rswXCkIqFpv8yZW2IAXFLxEQC03JeJ6HjXfbplRj7vs9wjzLw3fEn8I+Z/H8iqHiiO8e7MUgae5nRe9yWSlD4AECCg7DyG0DAgMAAAJpYgATt2K0Qs6CzOBm1rvuBurlX+GZ2ROIoyoOhuM3zHgXpqCPDRgAAAAAAAAAAAAAAAAAAjIEBQAEq80ABNp6F5g1aA==";
    private static final String JS_BODY_HASH =
            "45e865317cda82b838f7089ffa22fb89042be1d08de5c6bafa37bdf78aa4e4f3";

    private static final long WALLET_ID = 2147483645L;
    private static final long VALID_UNTIL = 1999999999L;
    private static final BigInteger VALUE = new BigInteger("300000000");
    private static final String CONTRACT_HASH_HEX =
            "276ec568859d0599c0cdad77dc0dd5cabfc333b2271146541d0dc66f98f02f4d";
    private static final String GOLDEN_WORDS =
            "virus safe such tourist balance august track home now outside fantasy series "
                    + "adapt shift next coach mad industry layer great misery season squeeze tunnel";

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    @DisplayName("自证：金标准 BOC 与其记录 hash 一致（防常量抄录错）")
    void goldenBocMatchesItsHash() {
        Cell jsBody = Cell.fromBoc(Base64.getDecoder().decode(JS_BODY_BOC64));
        assertThat(hex(jsBody.hash())).isEqualTo(JS_BODY_HASH);
    }

    @Test
    @DisplayName("端到端对拍：同参数组装的 external body hash == 官方 SDK 金标准")
    void bodyMatchesOfficialSdkVector() throws Exception {
        var code = CellBuilder.beginCell().storeUint(0xABCD, 16).endCell();
        var data = CellBuilder.beginCell().storeUint(0xDA7A, 16).endCell();
        var info = InternalMessageInfoRelaxed.builder()
                .bounce(true)
                .dstAddr(MsgAddressIntStd.builder().workchainId((byte) 0)
                        .address(new BigInteger(CONTRACT_HASH_HEX, 16)).build())
                .value(CurrencyCollection.builder().coins(VALUE).build())
                .build();
        var message = MessageRelaxed.builder()
                .info(info)
                .init(StateInit.builder().code(code).data(data).build())
                .body(CellBuilder.beginCell().endCell())
                .build();
        var action = ActionSendMsg.builder()
                .mode(3 | WalletV5R1MessageBuilder.SEND_MODE_IGNORE_ERRORS)
                .outMsg(message)
                .build();
        var outList = OutList.builder().actions(List.of(action)).build().toCell();

        Cell signingMessage = WalletV5R1MessageBuilder.signingMessage(
                WALLET_ID, VALID_UNTIL, 0, WalletV5R1MessageBuilder.outActionsSegment(outList));

        byte[] seed = ChainWalletKeys.seedFromMnemonic(GOLDEN_WORDS);
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        byte[] signature = AdnlChainSender.networkSigner(keyPair).sign(signingMessage.hash());

        Cell body = WalletV5R1MessageBuilder.externalBody(signingMessage, signature);

        assertThat(hex(body.hash()))
                .as("与官方 SDK 金标准逐位一致（不通过不得再上真机）")
                .isEqualTo(JS_BODY_HASH);
    }
}
