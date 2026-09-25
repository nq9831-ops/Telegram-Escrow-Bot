// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 telegram-escrow-bot contributors
//
// EscrowContract 骨架的冒烟测试 —— 目的：验证 Acton 测试通道可用、合约可部署。
// 资金流程（入金/交付/确认/争议/裁决/退款）的行为测试，待多签语义（C1）定案、
// 合约实现落地后补充；此处只覆盖骨架的入口契约。

import "@acton/emulation/network"
import "@acton/emulation/testing"
import "@acton/testing/expect"

import "@wrappers/EscrowContract.gen"

fun setupTest(): (EscrowContract, Treasury) {
    val deployer = testing.treasury("deployer");

    val contract = EscrowContract.fromStorage();
    val res = contract.deploy(deployer.address, { value: grams("1") });
    expect(res).toHaveSuccessfulDeploy({ to: contract.address });

    return (contract, deployer);
}

get fun `test empty message is accepted`() {
    val (contract, deployer) = setupTest();

    val res = contract.sendAny(deployer.address, createEmptyCell());
    expect(res).toHaveSuccessfulTx({
        from: deployer.address,
        to: contract.address,
    });
}

get fun `test unknown message is rejected`() {
    val (contract, deployer) = setupTest();

    val res = contract.sendAny(deployer.address, beginCell().storeInt(0x999, 32).endCell());
    expect(res).toHaveFailedTx({
        from: deployer.address,
        to: contract.address,
        exitCode: 0xFFFF,
    });
}
