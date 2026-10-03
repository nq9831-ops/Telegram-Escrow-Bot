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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.smartcontract.token.nft.NftUtils;
import org.ton.ton4j.tlb.VmCellSlice;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackList;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueCell;
import org.ton.ton4j.tlb.VmStackValueSlice;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AdnlJettonWalletQuery} 的<b>离线</b>测试。
 *
 * <p>本类刻意只覆盖**不需要连节点**的两步纯逻辑——参数怎么构造、结果怎么解析。
 * 这两步恰恰是手写 provider 最容易悄悄写错的地方（栈值类型、slice 位宽、boc 反序列化路径），
 * 而它们此前在 Tonlib 版里完全没被自动化测试覆盖过（那一版只能靠真机观察）。
 *
 * <p>「连上 liteserver 真能查到地址」这件事<b>不在本类覆盖范围内</b>——那需要真实网络与节点，
 * 属集成面，已在 {@link AdnlJettonWalletQuery} 的类注释里如实标注为未验证。
 */
class AdnlJettonWalletQueryTest {

    /** 把合法地址的末字符换掉——形态仍是 48 字符 base64url，但 CRC16 必然对不上。 */
    private static String corruptChecksum(String address) {
        char last = address.charAt(address.length() - 1);
        return address.substring(0, address.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    /** 模拟 get_wallet_address 的返回：一个含该地址的 slice 值。 */
    private static byte[] walletAddressResultBoc(Address wallet) {
        VmStack stack = VmStack.builder()
                .depth(1)
                .stack(VmStackList.builder()
                        .tos(List.of(VmStackValueSlice.builder()
                                .cell(VmCellSlice.builder()
                                        .cell(CellBuilder.beginCell().storeAddress(wallet).endCell())
                                        .build())
                                .build()))
                        .build())
                .build();
        return stack.toCell().toBoc();
    }

    @Test
    @DisplayName("结果解析往返：把地址编成 slice 栈 → boc → 解析回来，地址不变（mainnet 形态）")
    void parsesAddressFromRunMethodResult() throws Exception {
        Address wallet = Address.of(TestAddresses.valid());

        String parsed = AdnlJettonWalletQuery.addressFromResult(walletAddressResultBoc(wallet), false);

        assertThat(parsed).isEqualTo(wallet.toString(true, true, true));
    }

    @Test
    @DisplayName("网络 tag 跟随 testnet 标志：testnet=kQ / mainnet=EQ（ton4j toString 三参无 testnet 语义，testnet 须走 toBounceableTestnet）")
    void networkTagFollowsTestnetFlag() throws Exception {
        Address wallet = Address.of(TestAddresses.valid());
        byte[] boc = walletAddressResultBoc(wallet);

        String testnet = AdnlJettonWalletQuery.addressFromResult(boc, true);
        String mainnet = AdnlJettonWalletQuery.addressFromResult(boc, false);

        assertThat(testnet).startsWith("kQ");
        assertThat(mainnet).startsWith("EQ");
        // 解码级对账：两形态是同一 workchain+hash（toRaw 形态相等），仅 tag 与 CRC 不同
        assertThat(Address.of(testnet).toRaw()).isEqualTo(Address.of(mainnet).toRaw());
    }

    @Test
    @DisplayName("参数构造往返：owner 编成 slice 栈值后能原样解回（位宽留 0 = 整个 cell）")
    void ownerArgRoundTripsToTheSameAddress() {
        Address owner = Address.of(TestAddresses.validOther());

        VmStackValue arg = AdnlJettonWalletQuery.ownerArg(owner);
        VmStackValueSlice back = VmStackValueSlice.deserialize(CellSlice.beginParse(arg.toCell()));

        assertThat(NftUtils.parseAddress(back.getCell().getCell()))
                .isEqualTo(owner);
    }

    @Test
    @DisplayName("返回值不是 slice（例如误取到 cell）→ 抛 ChainUnavailableException，不 ClassCastException")
    void nonSliceResultIsReportedAsChainUnavailable() {
        // 这正是不能用 RunMethodResult.getCellByIndex(0) 的原因——它会把非 cell 的返回值强转崩掉
        VmStack stack = VmStack.builder()
                .depth(1)
                .stack(VmStackList.builder()
                        .tos(List.of(VmStackValueCell.builder()
                                .cell(CellBuilder.beginCell().storeUint(1, 8).endCell())
                                .build()))
                        .build())
                .build();
        byte[] boc = stack.toCell().toBoc();

        assertThatThrownBy(() -> AdnlJettonWalletQuery.addressFromResult(boc, false))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("空结果 / null / 非法 boc → 一律抛 ChainUnavailableException，不返回空串")
    void invalidResultIsReportedAsChainUnavailable() {
        assertThatThrownBy(() -> AdnlJettonWalletQuery.addressFromResult(null, false))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> AdnlJettonWalletQuery.addressFromResult(new byte[0], false))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> AdnlJettonWalletQuery.addressFromResult(new byte[]{1, 2, 3}, false))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("地址校验和不符 → 抛 ChainUnavailableException（ton4j 抛的是裸 Error，必须被接住）")
    void badChecksumIsReportedAsChainUnavailable() {
        // 失败发生在第一步 Address.of，早于 client()，故不会连节点
        AdnlJettonWalletQuery query = new AdnlJettonWalletQuery(true);

        assertThatThrownBy(() -> query.queryWalletAddress(
                corruptChecksum(TestAddresses.valid()), TestAddresses.valid()))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("由 ChainSettings 构造（provider 选择接在 testnet 开关上），且构造不触网")
    void fromChainSettingsDoesNotConnect() {
        ChainSettings settings = new ChainSettings(TestAddresses.valid(), true);

        assertThat(AdnlJettonWalletQuery.from(settings)).isNotNull();
    }
}
