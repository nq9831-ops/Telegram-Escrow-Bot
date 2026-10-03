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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.adnl.AdnlLiteClient;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;
import org.ton.ton4j.tlb.VmStackValueTinyInt;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;

/**
 * jetton 单 + 升级链真机核销（2026-10-02 第二波演练，在<b>新部署的 jetton 单合约</b>上）：
 *
 * <ol>
 *   <li><b>jetton 单部署</b>（asset=1、W=零哨兵）——核销 B10 的 jetton 分支（TON 单已在主演练验过）；</li>
 *   <li><b>W 推导（ADNL 真机）</b>——{@link AdnlJettonWalletQuery} 向真实 liteserver 查
 *       J(master, escrow)（核销 C8 的 ADNL provider 路线）；</li>
 *   <li><b>SET 两阶段 W</b>（联邦一次性写入）→ {@code ownJettonWallet} 读回一致；</li>
 *   <li><b>升级链</b>：{@code ProposeUpgrade}（提案 = 当前真实 code）→ 读 {@code upgradeStatus}
 *       ——<b>核销 B9 栈序</b>（合约返回 (newCode.hash(), proposedAt)；TVM 栈序由此实测定案）
 *       → {@code CancelUpgrade} → 读回 (0, 0)。<b>apply 不在本演练</b>（72h 时间锁，需真实等待）。</li>
 * </ol>
 *
 * <p>启用（双闸同前）：{@code TGG_LIVE_DEPLOY=true} + 助记词环境变量。
 */
@EnabledIfEnvironmentVariable(named = "TGG_CHAIN_UPGRADE_WALLET_MNEMONIC", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TGG_LIVE_DEPLOY", matches = "true")
class S5LiveJettonUpgradeProbeTest {

    /** 自建 testnet jetton master（2026-09-29 演练产物）。 */
    private static final String JETTON_MASTER =
            "kQC87Ycbr28MRbK7PufCcsV_nHR6QUra1a_lsn05_UG9P_BV";

    private static final long DEPLOY_VALUE_NANOTON = 300_000_000L;
    private static final long MSG_VALUE_NANOTON = 200_000_000L;

    /** 未激活账户的 runMethod exit code（同 AdnlChainSender.seqnoFromRunMethod 的定案）。 */
    private static final int EXIT_NOT_INITIALIZED = -256;

    @Test
    @DisplayName("jetton 单部署 + W 推导/SET + 升级链 propose/status(B9)/cancel")
    void jettonAndUpgradeChain() throws Exception {
        byte[] seed = ChainWalletKeys.seedFromMnemonic(
                System.getenv("TGG_CHAIN_UPGRADE_WALLET_MNEMONIC"));
        long walletId = Long.parseLong(System.getenv()
                .getOrDefault("TGG_CHAIN_UPGRADE_WALLET_ID", "2147483645"));
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        String walletRaw = ChainWalletKeys.v5r1WalletAddress(seed, walletId).toRaw();

        // ① jetton 单（asset=1、W=零哨兵）
        var storage = EscrowStorageCodec.storageCell(walletRaw, walletRaw, walletRaw,
                new BigInteger("1000000000"), 0, EscrowStorageCodec.ZERO_ADDRESS, 1);
        var stateInit = EscrowDeployCodec.stateInit(EscrowCode.escrowCodeCell(), storage);
        var contract = EscrowDeployCodec.addressOf(stateInit);
        String contractRaw = contract.toRaw();
        System.out.println("JETTON-CONTRACT=" + contract.toBounceableTestnet());

        AdnlLiteClient client = AdnlLiteClient.builder().testnet().build();
        try (AdnlChainSender sender = AdnlChainSender.forNetwork(
                true, walletId, keyPair, Clock.systemUTC())) {
            sender.sendDeploy(contractRaw, stateInit, DEPLOY_VALUE_NANOTON);
            System.out.println("JETTON-DEPLOY-BROADCAST=OK");
            long state = waitForStateAtLeast0(client, contract);
            System.out.println("JETTON-CONTRACT-ACTIVE（escrowState=" + state + "）");

            // ② W 推导（ADNL 真机查询——C8 路线的真实节点核销）
            String derivedW = new AdnlJettonWalletQuery(true)
                    .queryWalletAddress(JETTON_MASTER, contract.toBounceableTestnet());
            System.out.println("DERIVED-OWN-WALLET=" + derivedW);

            // ③ SET（两阶段 W 第 2 步；幂等：链上已设置则跳过——合约"仅从零"守卫会拒重复写）
            String already = readOwnWalletOnce(client, contract);
            if (already != null) {
                System.out.println("SET-SKIPPED（链上已设置：" + already + "）");
            } else {
                sender.sendToContract(contractRaw, SetJettonWalletMessageCodec.set(derivedW),
                        MSG_VALUE_NANOTON);
                System.out.println("SET-SENT");
            }
            String onChainW = waitForOwnWallet(client, contract, 15);
            System.out.println("SET-OK ownJettonWallet=" + onChainW);

            // ④ 升级链：propose（提案 = 当前真实 code）→ status（B9 栈序）→ cancel → 归零
            Cell proposedCode = EscrowCode.escrowCodeCell();
            String proposedHashHex = java.util.HexFormat.of().formatHex(proposedCode.hash());
            long nowBefore = Instant.now().getEpochSecond();
            sender.sendToContract(contractRaw, UpgradeMessageCodec.proposeUpgrade(proposedCode),
                    MSG_VALUE_NANOTON);
            BigInteger[] status = waitForUpgradeProposal(client, contract);
            System.out.println("UPGRADE-STATUS-RAW=(tos0=" + status[0] + ", tos1=" + status[1] + ")");
            System.out.println("EXPECTED-CODE-HASH-HEX=" + proposedHashHex);
            System.out.println("PROPOSED-AT-EXPECTED≈" + nowBefore);

            sender.sendToContract(contractRaw, UpgradeMessageCodec.cancelUpgrade(),
                    MSG_VALUE_NANOTON);
            waitForUpgradeCleared(client, contract);
            System.out.println("UPGRADE-CANCEL-OK（upgradeStatus 归零）");
        } finally {
            client.close();
        }
        System.out.println("JETTON+UPGRADE-CHAIN=COMPLETE");
    }

    // ---- 轮询工具 ----

    private static long runGetInt(AdnlLiteClient client, Address target, String method)
            throws Exception {
        var result = client.runMethod(target, method);
        if (result.getExitCode() == EXIT_NOT_INITIALIZED) {
            return -1;   // 未激活
        }
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(method + " 退出码非 0：" + result.getExitCode());
        }
        VmStack stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(result.result)));
        VmStackValue top = stack.getStack().getTos().get(0);
        BigInteger value = (top instanceof VmStackValueInt intVal)
                ? intVal.getValue() : ((VmStackValueTinyInt) top).getValue();
        return value.longValueExact();
    }

    private static long waitForStateAtLeast0(AdnlLiteClient client, Address contract)
            throws Exception {
        for (int i = 1; i <= 15; i++) {
            long s = runGetInt(client, contract, "escrowState");
            if (s >= 0) {
                return s;
            }
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("等待合约激活超时（45s）");
    }

    private static String waitForOwnWallet(AdnlLiteClient client, Address contract, int rounds)
            throws Exception {
        for (int i = 1; i <= rounds; i++) {
            String w = readOwnWalletOnce(client, contract);
            if (w != null) {
                return w;
            }
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("等待 ownJettonWallet 落地超时");
    }

    /**
     * 读 {@code ownJettonWallet} 一次：有值返回 testnet 形态；零地址/未激活返回 null。
     *
     * <p>地址在栈上是 {@code VmStackValueSlice} → {@code VmCellSlice} → {@code Cell}
     * （<b>双层 getCell()</b>），解析用 {@code NftUtils.parseAddress}——与
     * {@code AdnlJettonWalletQuery.addressFromResult} 同一模式（该模式已真机核销）。
     */
    private static String readOwnWalletOnce(AdnlLiteClient client, Address contract)
            throws Exception {
        var result = client.runMethod(contract, "ownJettonWallet");
        if (result.getExitCode() != 0) {
            return null;
        }
        VmStack stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(result.result)));
        VmStackValue top = stack.getStack().getTos().get(0);
        if (!(top instanceof org.ton.ton4j.tlb.VmStackValueSlice slice)) {
            return null;
        }
        Address w = org.ton.ton4j.smartcontract.token.nft.NftUtils
                .parseAddress(slice.getCell().getCell());
        if (w == null || w.toRaw().equals(EscrowStorageCodec.ZERO_ADDRESS)) {
            return null;
        }
        return w.toBounceableTestnet();
    }

    private static BigInteger[] waitForUpgradeProposal(AdnlLiteClient client, Address contract)
            throws Exception {
        for (int i = 1; i <= 15; i++) {
            BigInteger[] pair = readUpgradeStatus(client, contract);
            if (pair[0].signum() != 0 || pair[1].signum() != 0) {
                return pair;
            }
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("等待升级提案落地超时（45s）");
    }

    private static void waitForUpgradeCleared(AdnlLiteClient client, Address contract)
            throws Exception {
        for (int i = 1; i <= 15; i++) {
            BigInteger[] pair = readUpgradeStatus(client, contract);
            if (pair[0].signum() == 0 && pair[1].signum() == 0) {
                return;
            }
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("等待升级提案清空超时（45s）");
    }

    /** 读 upgradeStatus（两值原样，BigInteger 全值——codeHash 是 256 位）——栈序由本演练输出定案（B9）。 */
    private static BigInteger[] readUpgradeStatus(AdnlLiteClient client, Address contract)
            throws Exception {
        var result = client.runMethod(contract, "upgradeStatus");
        if (result.getExitCode() == EXIT_NOT_INITIALIZED) {
            return new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO};
        }
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("upgradeStatus 退出码非 0：" + result.getExitCode());
        }
        VmStack stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(result.result)));
        var tos = stack.getStack().getTos();
        return new BigInteger[] {toBig(tos.get(0)), toBig(tos.get(1))};
    }

    private static BigInteger toBig(VmStackValue value) {
        return (value instanceof VmStackValueInt intVal)
                ? intVal.getValue() : ((VmStackValueTinyInt) value).getValue();
    }
}
