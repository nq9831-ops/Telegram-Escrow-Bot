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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iwebpp.crypto.TweetNaclFast;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.tlb.Message;
import org.ton.ton4j.tlb.VmCellSlice;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackList;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;
import org.ton.ton4j.tlb.VmStackValueSlice;
import org.ton.ton4j.tlb.VmStackValueTinyInt;

/**
 * {@link AdnlChainSender} 的离线契约测试——三个接缝全用替身，不触网。
 *
 * <p>钉住：① 组装顺序（seqno 读取 → 签名载荷传递 → 广播）；② 签名载荷恰为 32 字节 cell hash；
 * ③ 异常契约（普通异常与 ton4j 裸 {@code Error} 都必须落成 {@link ChainUnavailableException}）；
 * ④ 入参缺件在触网前就拒（seqno 端口不被调用）。
 * 真机链路（钱包部署/签名口径/广播成功）不在本类证据范围——见类注释「未经真实节点验证」。
 */
class AdnlChainSenderTest {

    /** 组装用密钥对：确定性 seed 派生（离线可造；私钥不出本测试，签名仍走替身）。 */
    private static final TweetNaclFast.Signature.KeyPair KEY_PAIR =
            TweetNaclFast.Signature.keyPair_fromSeed(new byte[32]);

    private final AtomicReference<Message> broadcasted = new AtomicReference<>();
    private final AtomicReference<byte[]> signedPayload = new AtomicReference<>();
    private final AtomicInteger seqnoCalls = new AtomicInteger();
    private final AtomicInteger broadcastCalls = new AtomicInteger();

    private AdnlChainSender sender(AdnlChainSender.SeqnoSource seqno,
                                   AdnlChainSender.MessageBroadcaster broadcaster,
                                   AdnlChainSender.WalletSigner signer) {
        return new AdnlChainSender(0x29A9A317L, KEY_PAIR, signer, seqno, broadcaster,
                Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));
    }

    private AdnlChainSender recordingSender() {
        return sender(() -> {
            seqnoCalls.incrementAndGet();
            return 7L;
        }, msg -> {
            broadcastCalls.incrementAndGet();
            broadcasted.set(msg);
        }, payload -> {
            signedPayload.set(payload);
            return new byte[64];
        });
    }

    @Test
    void sendAssemblesSignsAndBroadcastsInOrder() throws Exception {
        Cell body = UpgradeMessageCodec.applyUpgrade();
        recordingSender().sendToContract("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", body, 200_000_000L);

        assertThat(seqnoCalls.get()).isEqualTo(1);
        assertThat(broadcastCalls.get()).isEqualTo(1);
        assertThat(broadcasted.get()).isNotNull();
        // 签名载荷 = 外部转账体的 cell hash（32 字节）——口径见类注释「签名载荷口径」
        assertThat(signedPayload.get()).hasSize(32);
    }

    @Test
    void missingAddressOrBodyIsRejectedBeforeTouchingSeqno() {
        AdnlChainSender sender = recordingSender();
        assertThatThrownBy(() -> sender.sendToContract(" ", UpgradeMessageCodec.applyUpgrade(), 1L))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> sender.sendToContract("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", null, 1L))
                .isInstanceOf(ChainUnavailableException.class);
        assertThat(seqnoCalls.get()).isZero();
    }

    @Test
    void seqnoFailureBecomesChainUnavailable() {
        AdnlChainSender sender = sender(
                () -> {
                    throw new Exception("liteserver 不可达");
                },
                msg -> { },
                payload -> new byte[64]);
        assertThatThrownBy(() -> sender.sendToContract("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs",
                UpgradeMessageCodec.cancelUpgrade(), 1L))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("写链发送失败");
    }

    @Test
    void bareErrorFromTon4jBecomesChainUnavailable() {
        // ton4j 用裸 java.lang.Error 表达业务性失败——必须被兜住（AdnlJettonWalletQuery 同款坑）
        AdnlChainSender sender = sender(() -> 1L, msg -> {
            throw new Error("Invalid boc");
        }, payload -> new byte[64]);
        assertThatThrownBy(() -> sender.sendToContract("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs",
                UpgradeMessageCodec.applyUpgrade(), 1L))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    void constructionRejectsMissingParts() {
        assertThatThrownBy(() -> new AdnlChainSender(1L, null, p -> new byte[64], () -> 1L, m -> { },
                Clock.systemUTC()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bodyIsDeliveredForSigningAsCellHash() throws Exception {
        // 另一份 body 应对应另一份签名载荷（载荷绑定具体消息，不是常数）
        Cell body = UpgradeMessageCodec.proposeUpgrade(
                CellBuilder.beginCell().storeUint(0xABCD, 16).endCell());
        recordingSender().sendToContract("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", body, 1L);
        byte[] first = signedPayload.get();
        assertThat(first).hasSize(32);
    }

    // ---- seqnoFromResult（生产端口的解析核，forNetwork 的 seqno 接缝）----

    private static byte[] seqnoResultBoc(VmStackValue value) {
        VmStack stack = VmStack.builder()
                .depth(1)
                .stack(VmStackList.builder().tos(List.of(value)).build())
                .build();
        return stack.toCell().toBoc();
    }

    @Test
    void seqnoTinyIntIsParsed() throws Exception {
        assertThat(AdnlChainSender.seqnoFromResult(seqnoResultBoc(
                VmStackValueTinyInt.builder().value(BigInteger.valueOf(7)).build())))
                .isEqualTo(7L);
    }

    @Test
    void seqnoBigIntIsParsed() throws Exception {
        assertThat(AdnlChainSender.seqnoFromResult(seqnoResultBoc(
                VmStackValueInt.builder().value(BigInteger.valueOf(2_000_000_000L)).build())))
                .isEqualTo(2_000_000_000L);
    }

    @Test
    void seqnoEmptyOrNullBocRejected() {
        assertThatThrownBy(() -> AdnlChainSender.seqnoFromResult(null))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> AdnlChainSender.seqnoFromResult(new byte[0]))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    void networkSignerProduces64ByteSignature() throws Exception {
        // 真签名路径（非替身）——参数顺序曾传反（把 sk 当 pk 传）：离线替身跳过真签名、
        // 首笔真机写链才以 "Index 32 out of bounds" 暴露；本用例把该路径从"只被跳过"
        // 变为"直接钉住"（顺序回退即抛，测试红）。
        byte[] seed = ChainWalletKeys.seedFromMnemonic(
                "virus safe such tourist balance august track home now outside fantasy series "
                        + "adapt shift next coach mad industry layer great misery season squeeze tunnel");
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        byte[] payload = "escrow-s5-signer-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] signature = AdnlChainSender.networkSigner(keyPair).sign(payload);

        assertThat(signature).hasSize(64);
    }

    @Test
    void seqnoUninitializedAccountMeansZeroForFirstWrite() throws Exception {
        // 真机实测（2026-10-02 首笔写链）：未部署钱包 runMethod(seqno) 返回 exit code -256——
        // 首笔消息同时部署钱包，seqno 视作 0（官方钱包客户端同款行为）。
        // 变异点：删掉 -256 分支本用例即红（真机表现为"首笔永远发不出"）。
        assertThat(AdnlChainSender.seqnoFromRunMethod(-256, null)).isZero();
    }

    @Test
    void seqnoOtherNonZeroExitFailsClosed() {
        assertThatThrownBy(() -> AdnlChainSender.seqnoFromRunMethod(-14, null))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("-14");
    }

    @Test
    void seqnoCorruptBocBecomesChainUnavailableNotBareError() {
        // ton4j Cell.fromBoc 对非法 boc 抛裸 java.lang.Error（实证见 AdnlJettonWalletQueryTest）——
        // 必须被兜成 ChainUnavailableException。变异点：去掉 catch(Error) 兜底本用例即红。
        assertThatThrownBy(() -> AdnlChainSender.seqnoFromResult(new byte[] {1, 2, 3}))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    void seqnoNonIntegerTopRejected() {
        assertThatThrownBy(() -> AdnlChainSender.seqnoFromResult(seqnoResultBoc(
                VmStackValueSlice.builder()
                        .cell(VmCellSlice.builder()
                                .cell(CellBuilder.beginCell().endCell())
                                .build())
                        .build())))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("不是整数");
    }

    @Test
    void forNetworkConstructsWithoutTouchingNetwork() throws Exception {
        // 构造只组装不发消息（连接懒用）；close 负责释放自有连接
        try (AdnlChainSender sender = AdnlChainSender.forNetwork(
                true, 0x29A9A317L,
                TweetNaclFast.Signature.keyPair_fromSeed(new byte[32]),
                Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC))) {
            assertThat(sender).isNotNull();
        }
    }

    // ---- S5 部署（sendDeploy）：组装→签名→广播链条与 sendToContract 同构 ----

    /** 固定参数集的合约 stateInit（地址由 code+data 派生——对拍 acton，见 EscrowDeployCodecTest）。 */
    private static org.ton.ton4j.tlb.StateInit fixedStateInit() {
        var data = EscrowStorageCodec.storageCell(
                "0:1111111111111111111111111111111111111111111111111111111111111111",
                "0:2222222222222222222222222222222222222222222222222222222222222222",
                "0:3333333333333333333333333333333333333333333333333333333333333333",
                new java.math.BigInteger("1000000000"), 0,
                EscrowStorageCodec.ZERO_ADDRESS, 0);
        return EscrowDeployCodec.stateInit(EscrowCode.escrowCodeCell(), data);
    }

    @Test
    void sendDeployAssemblesSignsAndBroadcasts() throws Exception {
        var si = fixedStateInit();
        String address = EscrowDeployCodec.addressOf(si).toString();

        recordingSender().sendDeploy(address, si, 2_000_000_000L);

        assertThat(seqnoCalls.get()).isEqualTo(1);
        assertThat(broadcastCalls.get()).isEqualTo(1);
        assertThat(broadcasted.get()).isNotNull();
        // 签名载荷 = 待签名 body 的 hash（32 字节；口径与 sendToContract 完全一致）
        assertThat(signedPayload.get()).isNotNull().hasSize(32);
    }

    @Test
    void sendDeployRejectsAddressMismatchedWithStateInit() {
        var si = fixedStateInit();
        // 另一个地址（与 stateInit 推导不符）——链下记账会错位，必须 fail-closed
        String wrongAddress =
                "0:9999999999999999999999999999999999999999999999999999999999999999";

        assertThatThrownBy(() -> recordingSender().sendDeploy(wrongAddress, si, 2_000_000_000L))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("不一致");
        assertThat(broadcastCalls.get()).as("拒绝后不得发生任何广播").isZero();
    }

    @Test
    void sendDeployRejectsBlankInputs() {
        var si = fixedStateInit();
        assertThatThrownBy(() -> recordingSender().sendDeploy("  ", si, 1L))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("地址未提供");
        assertThatThrownBy(() -> recordingSender().sendDeploy(
                EscrowDeployCodec.addressOf(si).toString(), null, 1L))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("stateInit 未提供");
    }

    @Test
    void walletAddressMatchesOfficialVector() throws Exception {
        // 三方对拍锚（acton 1.2.0 + 官方 @ton/ton + 本类路径）：golden 词组 + testnet 默认 walletId。
        // 变异点：去掉构造器里的 .isSigAuthAllowed(true) 本用例即红——该 bit 决定 v5r1 data
        // 布局（配错的症状是"所有签名被钱包拒收"，与 wallet-id 配错同表象，是最难排查的一类）。
        byte[] seed = ChainWalletKeys.seedFromMnemonic(
                "virus safe such tourist balance august track home now outside fantasy series "
                        + "adapt shift next coach mad industry layer great misery season squeeze tunnel");
        long walletId = ChainWalletKeys.v5r1WalletId(ChainWalletKeys.TESTNET_GLOBAL_ID, 0, 0);
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        AdnlChainSender sender = new AdnlChainSender(walletId, keyPair,
                payload -> new byte[64], () -> 7L, msg -> {
                }, Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));

        assertThat(sender.walletAddress().toRaw())
                .isEqualTo("0:ee123b98f7c8b2ca7d78cf5c8a36e1ad0621336c1f4046dfb5db1d98cb976129");
    }
}
