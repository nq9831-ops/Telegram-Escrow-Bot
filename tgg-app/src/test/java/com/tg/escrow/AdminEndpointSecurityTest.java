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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig} 的门禁行为测试——<b>配了凭据</b>的那条路径。
 *
 * <p>它钉住两件方向相反的事，缺一都不算验过：
 * <ol>
 *   <li><b>该拦的拦住</b>：{@code /admin/**} 无凭据或凭据错误一律 401（后台不再只靠 Nginx 兜底）；</li>
 *   <li><b>不该拦的不拦</b>：{@code /api/**} 不被本层拦截——Mini App 的认证发生在业务层
 *       （{@code initData} 验签）。若这条测不出，一次"顺手全拦"就会把 Mini App 打死。</li>
 * </ol>
 *
 * <p>用 {@code @WebMvcTest} 切片而非全上下文：安全是 web 层的事，不该为此去连数据库。
 * 凭据经 {@code @TestPropertySource} 注入，与生产同一套 {@code tgg.admin.*} 绑定。
 */
@WebMvcTest(controllers = {AdminChainController.class, AdminFeeLedgerController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "tgg.admin.username=ops",
        "tgg.admin.password=secret-for-test"
})
class AdminEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChainGateway chainGateway;
    @MockitoBean
    private EscrowChainDeploymentService escrowChainDeploymentService;
    @MockitoBean
    private com.tg.escrow.escrow.FeeLedgerRepository feeLedgerRepository;

    /** 手工拼 Basic 头，避免为一个测试再引入 spring-security-test 依赖。 */
    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("无凭据访问 /admin/** → 401（两个后台端点各验一次：链地址推导 + 费用台账）")
    void adminWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/chain/own-jetton-wallet").param("contract", "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/fee-ledger"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("凭据错误 → 401")
    void adminWithWrongCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/chain/own-jetton-wallet").param("contract", "x")
                        .header("Authorization", basic("ops", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("用户名对但错配密码 → 401（不因用户名命中而放行）")
    void mismatchedPasswordIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/chain/own-jetton-wallet")
                        .param("contract", "x")
                        .header("Authorization", basic("ops", "secret-for-test-x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("凭据正确 → 安全层放行，进入 controller（此处因地址形态非法得 400，关键是不是 401）")
    void adminWithCorrectCredentialsPassesSecurity() throws Exception {
        mockMvc.perform(get("/admin/chain/own-jetton-wallet")
                        .param("contract", "not-an-address")
                        .header("Authorization", basic("ops", "secret-for-test")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Mini App 的 /api/** 不被本层拦截（404 也行，只要不是 401）")
    void apiPathsAreNotInterceptedBySecurity() throws Exception {
        // 该 controller 不在本测试切片内 → 404。关键是它**不是 401**：
        // 证明安全层没有把 Mini App 挡在门外（那里的认证在业务层做 initData 验签）。
        mockMvc.perform(MockMvcRequestBuilders.post("/api/trade/create"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("公开静态页 /miniapp 不被拦截（真能拿到页面，而非 401）")
    void miniappIsNotIntercepted() throws Exception {
        // 切片内静态资源处理器是生效的，所以这里能真拿到 200——比"不是 401"更强的证据。
        mockMvc.perform(get("/miniapp/index.html"))
                .andExpect(status().isOk());
    }
}
