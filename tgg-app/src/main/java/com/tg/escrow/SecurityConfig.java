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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 应用层安全门禁——保护 {@code /admin/**}。
 *
 * <h2>它补的是什么缺口</h2>
 * <p>管理端点（如 {@code /admin/chain} 的部署期地址推导、费用台账 / 裁决 / 对账等）此前
 * <b>应用层无任何鉴权</b>，完全依赖部署侧 Nginx 的 {@code location ^~ /admin/} {@code deny all}。
 * 那是一道好防线，但它是<b>部署配置</b>：换一台没配好 Nginx 的机器、或反代规则被改动，
 * 后台端点就直接暴露。本类把门禁下沉到应用内，使"没鉴权"不再是默认状态——
 * 与 ON-LINE 清单 C1「应用层仍无鉴权，仅 Nginx 兜底」那条遗留相对。
 *
 * <h2>为什么不保护全部端点</h2>
 * <p>Spring Security 默认保护<b>所有</b>端点，因此本类必须显式放行其余路径，否则会误伤：
 * <ul>
 *   <li>{@code /api/trade/**}（Mini App）：那里的身份来自 Telegram 签发的 {@code initData} 验签，
 *       认证发生在业务层（{@code WebAppInitDataVerifier}），<b>不是</b>本层的用户名/密码。
 *       若这里也要求认证，Mini App 会被 401 挡住——所以放行是刻意的，不等于"无防护"。</li>
 *   <li>{@code /miniapp}（静态页）与根路径：公开资源。</li>
 * </ul>
 *
 * <h2>未配置凭据时的方向</h2>
 * <p>{@code tgg.admin.username/password} 未配置（或只配一半）时，{@link #userDetailsService} 返回
 * <b>不含任何账号</b>的实例——于是 {@code /admin/**} 一律认证失败（401）。
 * 绝不"未配置就放行"：那会让部署者在"以为已受保护"的状态下把后台开放出去。
 * 取向与项目其它配置项一致（空 = 该能力关闭），但这里的"关闭"是<b>拒绝访问</b>而非"开放"。
 *
 * <h2>CSRF 为什么关掉</h2>
 * <p>本应用是纯 API：无浏览器表单、不靠 Cookie 会话，CSRF 防护没有对象。保留它只会让
 * {@code /admin/**} 下的 POST 端点因缺少 token 而全部 403——那是把"安全的默认"
 * 变成"无法使用的端点"。代价是必须确保没有基于 Cookie 的会话认证（当前确实没有，
 * 用的是 HTTP Basic：凭据每次随请求发送，攻击者无法靠"浏览器自动带 Cookie"借道）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 受保护的后台路径前缀。后台端点都应落在这个前缀下（新增时不必改本类）。 */
    static final String ADMIN_PATTERN = "/admin/**";

    @Bean
    public SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
        http
                // 纯 API，无 Cookie 会话与浏览器表单——CSRF 无对象（详见类注释）。
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ADMIN_PATTERN).authenticated()
                        // 其余放行：Mini App 的 /api/** 在业务层做 initData 验签，
                        // /miniapp 是公开静态页。此处放行 ≠ 无防护，而是防护在别处。
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    /**
     * 管理员账号，来源为配置（部署者注入，不写进仓库）。
     *
     * <p><b>未配置、或只配了一半 → 不提供任何账号</b>，于是 {@code /admin/**} 一律 401。
     * 这是本类最要紧的一条 fail-closed：把"忘了配"变成"访问被拒"，而不是"门户大开"。
     */
    @Bean
    public UserDetailsService userDetailsService(
            @Value("${tgg.admin.username:}") String username,
            @Value("${tgg.admin.password:}") String password,
            PasswordEncoder encoder) {
        if (username.isBlank() || password.isBlank()) {
            return new InMemoryUserDetailsManager();   // 空账号集：谁都登不进来
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username)
                        .password(encoder.encode(password))
                        .roles("ADMIN")
                        .build());
    }

    /** 口令编码器：账号在内存中也存编码后的值，不落明文。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
