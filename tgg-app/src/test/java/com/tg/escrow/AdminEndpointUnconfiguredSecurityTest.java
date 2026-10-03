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
package com.tg.escrow;

import com.tg.escrow.chain.ChainGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig} 的 <b>fail-closed</b> 测试——刻意<b>不配</b> {@code tgg.admin.*}。
 *
 * <p>单独一个类的理由：凭据是上下文级配置，一个测试类只能有一种取值。而"忘了配"这条路径
 * 恰恰是最需要钉住的一条——它的正确行为是<b>拒绝一切访问</b>，而不是"没配就放行"。
 * 后者会让部署者在"以为已受保护"的状态下把后台开放出去，比不引入安全层更危险。
 *
 * <p>注意用 {@code @WebMvcTest} 而非全上下文：全上下文会去连真实库与要求 Bot token，
 * 把一条纯 web 层策略的验证拖成环境依赖，那是没必要的耦合。
 */
@WebMvcTest(controllers = {AdminChainController.class, AdminFeeLedgerController.class})
@Import(SecurityConfig.class)
class AdminEndpointUnconfiguredSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChainGateway chainGateway;
    @MockitoBean
    private EscrowChainDeploymentService escrowChainDeploymentService;
    @MockitoBean
    private com.tg.escrow.escrow.FeeLedgerRepository feeLedgerRepository;

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("未配置 tgg.admin.* → /admin/** 一律 401：忘了配 = 拒绝访问，而不是放行")
    void unconfiguredAdminRejectsEverything() throws Exception {
        // 不带凭据
        mockMvc.perform(get("/admin/chain/own-jetton-wallet").param("contract", "x"))
                .andExpect(status().isUnauthorized());

        // 带任意凭据也不行——账号集是空的，没有"猜对"的可能（换一个端点再验一次）
        mockMvc.perform(get("/admin/fee-ledger")
                        .header("Authorization", basic("ops", "whatever")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/chain/own-jetton-wallet")
                        .param("contract", "x")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isUnauthorized());
    }
}
