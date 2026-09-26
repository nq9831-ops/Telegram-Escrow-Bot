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

import com.tg.escrow.common.TggException;
import com.tg.escrow.core.ChannelMembershipPort;
import com.tg.escrow.core.ChannelSubscriptionCheck;
import com.tg.escrow.core.GroupAdminPort;
import com.tg.escrow.core.MemberJoined;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 入群订阅门卫（GM-16 / T60）：把 {@link ChannelSubscriptionCheck}（此前零生产消费者的纯判定）
 * 接上真实入群路径，并补上它一直缺的<b>强制动作</b>。
 *
 * <h2>为什么需要这个类，而不是直接调 check</h2>
 * <p>{@code ChannelSubscriptionCheck} 只回答"订阅够不够"，它既不知道去哪查订阅，也不能踢人。
 * 本类补齐这两件事：查（{@link ChannelMembershipPort}）与执行（{@link GroupAdminPort#kick}），
 * 通过验证后再委派给 {@link MemberJoinHandler} 走既有的欢迎/保护模式路径。
 *
 * <h2>三条 fail-safe 方向（都不许把故障或我们的错变成对用户的处罚）</h2>
 * <ol>
 *   <li><b>查不清（UNKNOWN）→ 不踢，也不放行</b>：不发欢迎语（未过门禁不得默认通过），
 *       回一句"暂时无法确认"。若把查询故障当成"未订阅"，一次网络抖动就会误踢已订阅的用户。</li>
 *   <li><b>必订频道不在白名单（配置错误）→ 不踢</b>：那是我们的配置错，不能让用户承担；
 *       留痕并回一句"配置有误，请联系管理员"。</li>
 *   <li><b>未配置必订频道 → 不查也不拦</b>：直接委派，省一次 API 调用。</li>
 * </ol>
 *
 * <p>只有<b>明确</b>查到未订阅才踢——破坏性动作需要确凿证据，这与项目一贯的
 * 「无法确认就不做破坏性动作」一致。
 */
public final class JoinSubscriptionGate {

    private final ChannelSubscriptionCheck check;
    private final ChannelMembershipPort membership;
    private final GroupAdminPort admin;
    private final MemberJoinHandler inner;
    private final Set<String> requiredChannels;

    /**
     * @param check            订阅判定（白名单 + 必订校验）
     * @param membership       订阅查询端口
     * @param admin            群管理动作端口（踢人）
     * @param inner            通过验证后的入群处理（欢迎 / 保护模式）
     * @param requiredChannels 必订频道（空集 = 不启用本门禁）
     */
    public JoinSubscriptionGate(ChannelSubscriptionCheck check, ChannelMembershipPort membership,
                               GroupAdminPort admin, MemberJoinHandler inner,
                               Set<String> requiredChannels) {
        if (check == null || membership == null || admin == null || inner == null) {
            throw new TggException("入群订阅门卫：判定器/查询端口/管理动作端口/入群处理器均不可为空");
        }
        if (requiredChannels == null) {
            throw new TggException("入群订阅门卫：必订频道集合不可为空引用（不启用请传 Set.of()）");
        }
        this.check = check;
        this.membership = membership;
        this.admin = admin;
        this.inner = inner;
        this.requiredChannels = Set.copyOf(requiredChannels);
        // 配置自检：必订频道不在白名单时 passes 会抛——在此触发即「启动期 fail-fast」，
        // 而不是等第一个用户入群才在日志里淹掉（那会让群治理静默失效）。
        check.passes(Set.of(), this.requiredChannels);
    }

    /**
     * 处理一次入群：先过订阅门禁，通过后交给 {@link MemberJoinHandler}。
     *
     * @param joined 入群事件（{@code null} 即抛——不静默放行）
     * @return 应发出的回执
     */
    public Optional<String> onMemberJoined(MemberJoined joined) {
        if (joined == null) {
            throw new TggException("入群订阅门卫：事件不可为空");
        }
        if (requiredChannels.isEmpty()) {
            return inner.onMemberJoined(joined);
        }

        Set<String> subscribed = new LinkedHashSet<>();
        boolean anyUnknown = false;
        for (String channel : requiredChannels) {
            switch (membership.membershipOf(channel, joined.userId())) {
                case SUBSCRIBED -> subscribed.add(channel);
                case NOT_SUBSCRIBED -> { /* 明确未订阅：不加入已订阅集合 */ }
                case UNKNOWN -> anyUnknown = true;
            }
        }

        if (anyUnknown) {
            return Optional.of("⚠️ 暂时无法确认你的频道订阅状态，请稍后再试或联系管理员。");
        }

        // 必订频道非空且均可判定：此时 passes 不会因配置错而抛（配置错已在构造期被挡下）
        if (check.passes(subscribed, requiredChannels)) {
            return inner.onMemberJoined(joined);
        }

        // 未订阅：真正移出。回执的受众是**留在群里的其他人/管理员**——被移出者看不到它
        // （Telegram 另有移除提示给他），所以措辞按"群视角"写，不写成对被移出者说话。
        if (!MemberJoinHandler.attemptKick(admin, joined.chatId(), joined.userId())) {
            return Optional.of("⚠️ 未能移出未订阅 " + String.join("、", requiredChannels)
                    + " 的新成员（操作失败），请管理员手动处理。");
        }
        return Optional.of("⚠️ 新成员因未订阅 " + String.join("、", requiredChannels) + " 已被移出。");
    }
}
