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
import com.tg.escrow.core.NoticePolicy;
import com.tg.escrow.core.SilenceWindow;
import com.tg.escrow.escrow.EscrowOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易对手方通知（TradeNotifier）的行为固定测试。
 *
 * <p>本类钉住四件事：<b>发给谁</b>（当事人中 ≠ 操作者）、<b>失败不许上抛</b>、
 * <b>静默窗口只压制"待行动"档</b>、<b>资金节点必带链上未接入标注</b>。
 */
class TradeNotifierTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final Instant T0 = Instant.parse("2026-09-25T12:00:00Z");

    /** 记录主动通知：{@code chatId|silent|loud|text}——"发给谁 + 是否静默"都可断言。 */
    private static final class RecordingNotifications implements BotReplyPort {
        final List<String> sent = new ArrayList<>();
        boolean failNextSend;

        @Override
        public void sendText(long chatId, String text) {
            sent.add(chatId + "|loud|" + text);
        }

        @Override
        public void sendText(long chatId, String text, NoticePolicy policy) {
            if (failNextSend) {
                throw new TggException("模拟发送失败（对方拉黑/未会话/网络）");
            }
            sent.add(chatId + "|" + (policy.silent() ? "silent" : "loud") + "|" + text);
        }

        @Override
        public void sendTextWithWebApp(long chatId, String text, String buttonText, String url) {
            throw new UnsupportedOperationException("本类不测按钮消息");
        }

        @Override
        public void ackCallback(String callbackQueryId) {
            throw new UnsupportedOperationException("本类不测 callback 应答");
        }
    }

    private static EscrowOrder order() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        order.assignId(7L);
        return order;
    }

    private static TradeNotifier notifier(RecordingNotifications out, SilenceWindow window) {
        return new TradeNotifier(out, Clock.fixed(T0, ZoneId.of("UTC")), window);
    }

    /** T0 落在窗口内：11:00–13:00 UTC。 */
    private static final SilenceWindow IN_WINDOW =
            new SilenceWindow(LocalTime.of(11, 0), LocalTime.of(13, 0), ZoneId.of("UTC"));
    /** T0 落在窗口外：01:00–02:00 UTC。 */
    private static final SilenceWindow OUT_OF_WINDOW =
            new SilenceWindow(LocalTime.of(1, 0), LocalTime.of(2, 0), ZoneId.of("UTC"));

    @Test
    @DisplayName("收件人恒为「当事人中不等于操作者的那一方」——8 个事件逐一验证")
    void recipientIsAlwaysTheNonActor() {
        for (TradeEvent event : TradeEvent.values()) {
            RecordingNotifications byBuyer = new RecordingNotifications();
            notifier(byBuyer, null).notify(order(), BUYER, event);
            assertThat(byBuyer.sent).as("%s：买方操作 → 发给卖方", event).hasSize(1);
            assertThat(byBuyer.sent.get(0)).as("%s 的收件人", event).startsWith(SELLER + "|");

            RecordingNotifications bySeller = new RecordingNotifications();
            notifier(bySeller, null).notify(order(), SELLER, event);
            assertThat(bySeller.sent).as("%s：卖方操作 → 发给买方", event).hasSize(1);
            assertThat(bySeller.sent.get(0)).as("%s 的收件人", event).startsWith(BUYER + "|");
        }
    }

    @Test
    @DisplayName("操作者不是当事人 → 一条都不发，返回 RECIPIENT_UNREACHABLE（不猜收件人）")
    void actorNotAPartyIsUnreachable() {
        RecordingNotifications out = new RecordingNotifications();

        NotificationOutcome outcome = notifier(out, null).notify(order(), 999L, TradeEvent.LOCKED);

        assertThat(outcome).isEqualTo(NotificationOutcome.RECIPIENT_UNREACHABLE);
        assertThat(out.sent).isEmpty();
    }

    @Test
    @DisplayName("发送失败 → 返回 RECIPIENT_UNREACHABLE 且不上抛（状态迁移不该被通知拖垮）")
    void sendFailureIsReportedNotThrown() {
        RecordingNotifications out = new RecordingNotifications();
        out.failNextSend = true;

        NotificationOutcome outcome = notifier(out, null).notify(order(), BUYER, TradeEvent.LOCKED);

        assertThat(outcome).isEqualTo(NotificationOutcome.RECIPIENT_UNREACHABLE);
        assertThat(out.sent).isEmpty();
    }

    @Test
    @DisplayName("静默窗口内：资金节点仍响铃、待行动节点被压制、信息性节点本就静默")
    void silenceWindowOnlySuppressesActionNeeded() {
        for (TradeEvent event : TradeEvent.values()) {
            RecordingNotifications out = new RecordingNotifications();
            notifier(out, IN_WINDOW).notify(order(), BUYER, event);

            String flag = out.sent.get(0).split("\\|", -1)[1];
            switch (event) {
                case LOCKED, RELEASED, REFUNDED, DISPUTED ->
                        assertThat(flag).as("%s 是资金节点：静默窗口内也必须响铃", event).isEqualTo("loud");
                case CANCELLED ->
                        assertThat(flag).as("%s 是信息性节点：本就静默", event).isEqualTo("silent");
                default ->
                        assertThat(flag).as("%s 待对方行动：窗口内应被压制", event).isEqualTo("silent");
            }
        }
    }

    @Test
    @DisplayName("静默窗口外：待行动节点默认响铃（否则「静默」就退化成永久静音）")
    void outsideSilenceWindowActionNeededIsLoud() {
        RecordingNotifications out = new RecordingNotifications();

        notifier(out, OUT_OF_WINDOW).notify(order(), BUYER, TradeEvent.DELIVERED);

        assertThat(out.sent.get(0)).as("窗口外必须响铃").contains("|loud|");
    }

    @Test
    @DisplayName("资金类通知必须带「链上未接入」标注——暗示钱动了是会致损的谎报")
    void fundNotificationCarriesChainCaveat() {
        for (TradeEvent event : new TradeEvent[]{
                TradeEvent.LOCKED, TradeEvent.RELEASED, TradeEvent.REFUNDED}) {
            RecordingNotifications out = new RecordingNotifications();
            notifier(out, null).notify(order(), BUYER, event);

            assertThat(out.sent.get(0)).as("%s 的资金标注", event)
                    .contains("链上托管未接入");
        }

        RecordingNotifications plain = new RecordingNotifications();
        notifier(plain, null).notify(order(), BUYER, TradeEvent.CREATED);
        assertThat(plain.sent.get(0)).as("非资金节点不该挂资金标注").doesNotContain("链上托管未接入");
    }

    @Test
    @DisplayName("order / event 为空 → 抛（不静默降级成「不发」）")
    void nullArgumentsThrow() {
        TradeNotifier notifier = notifier(new RecordingNotifications(), null);

        assertThatThrownBy(() -> notifier.notify(null, BUYER, TradeEvent.LOCKED))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> notifier.notify(order(), BUYER, null))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("静默窗口未配置（null）→ 一律按事件默认策略发，不做静默判定")
    void noSilenceWindowMeansEventDefaults() {
        RecordingNotifications out = new RecordingNotifications();

        notifier(out, null).notify(order(), BUYER, TradeEvent.DELIVERED);

        assertThat(out.sent.get(0)).contains("|loud|");
    }
}
