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

import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.IncomingMessage;
import com.tg.escrow.core.MemberRole;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.FeeLedgerPort;
import com.tg.escrow.escrow.TonPayReferencePort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户级行为验收（本地可达的最深形态）：<b>真实 Spring 装配</b>下走
 * 「用户动作 → 可观察结果」——消息入口（等价 Telegram 投递）与真实 HTTP（含真实 initData 验签）。
 *
 * <h2>它验什么（与单测的分工）</h2>
 * <ul>
 *   <li><b>缺参聚焦提示</b>：从 {@code BotDispatcher} 消息入口发 {@code /escrow lock}（缺参）——
 *       用户将收到的回执必须是单行用法提示、而不是 19 条全量长文（真实装配的 router → handler 全线）；</li>
 *   <li><b>动作预检</b>：带<b>真实验签</b>的 initData 经 <b>真实 HTTP</b> POST {@code /api/escrow/order-actions}
 *       ——买方/卖方各自拿到的动作集必须与角色矩阵一致（真实的 web 层 + Jackson + 真库往返）。</li>
 *   <li><b>资金动作两击确认环</b>：{@code /escrow release} 第一次发出只回 offer（挂三段确认按钮、
 *       真库状态不动），补 {@code --yes} 才执行，重放同一枚按钮被拒——确认槽的一次性与按
 *       （订单, 动作）隔离都在真实装配下钉住。</li>
 * </ul>
 *
 * <h2>未覆盖（如实登记）</h2>
 * <p>status 回执的「订单页深链 url 按钮」（int-deeplink）在本工作树尚未落地——
 * {@code BotReply.ActionButton} 仍只有 {@code callbackData} 一种形态，故本文件暂无该项断言。
 *
 * <h2>运行前提</h2>
 * <p>同 {@code EscrowPersistenceIT}：可达的 MySQL（{@code TGG_DB_*}）与非空 {@code TELEGRAM_BOT_TOKEN}
 * （initData 验签与自签用同一个 token，从容器里的 {@link BotTokenConfig} 取——保证同源）。
 *
 * <h2>为什么到不了「真客户端」</h2>
 * <p>Telegram 客户端里的观感（菜单弹出、按钮点按、钱包弹窗）需要真实 bot token 与真机环境——
 * 那些面登记在 {@code docs/ONLINE-VERIFICATION.md}（A20/A21），本地不可替代。本 IT 是
 * 两端之间<b>本地可执行的最深一层</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"tgg.bot.username=it-test-bot",
                "tgg.admin.username=it-admin",
                "tgg.admin.password=it-pass",
                "tgg.tonpay.api-secret=it-tonpay-secret"})
class UserInteractionIT {

    private static final long BUYER_ID = 1001L;
    private static final long SELLER_ID = 2002L;

    @LocalServerPort
    int port;

    /** 默认不跟随重定向、真实 HTTP。 */
    @Autowired
    private TestRestTemplate rest;

    /** 真实分发器（BotWiring 装配）——等价 Telegram 投递后的路由。 */
    @Autowired
    private BotDispatcher dispatcher;

    @Autowired
    private BotTokenConfig tokenConfig;

    @Autowired
    private EscrowOrderStore orderStore;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate txTemplate;

    /** 同两个既有 IT：拦住启动期的真实 Telegram 调用（本 IT 不测连接）。 */
    @MockitoBean
    private BotRunner botRunner;

    @MockitoBean
    private BotMenuRegistrar botMenuRegistrar;

    /** 费用台账端口（真实装配——验收用）。 */
    @Autowired
    private FeeLedgerPort feeLedgerPort;

    /** TON Pay 引用端口（真实装配——验收用）。 */
    @Autowired
    private TonPayReferencePort tonPayReferences;

    /** 本用例创建的订单号（可有多个——确认环用例要造多单）；{@code @AfterEach} 逐条清理。 */
    private final List<Long> createdOrderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdOrderIds) {
            txTemplate.execute(status -> entityManager
                    .createNativeQuery("DELETE FROM escrow_orders WHERE id = :id")
                    .setParameter("id", id)
                    .executeUpdate());
        }
        createdOrderIds.clear();
    }

    // ── 验收一：消息入口的缺参回执（真实装配的 dispatcher 路由） ─────────────

    @Test
    @DisplayName("用户级：发 /escrow lock（缺参）→ 单行用法提示（非 19 条长文）；发 /escrow → 引导表单")
    void focusedUsageThroughMessageEntry() {
        CommandActor actor = new CommandActor(BUYER_ID, MemberRole.MEMBER, null);
        // 私聊语义：chatId 即用户 ID（Telegram 的真实形态；0 不是合法会话）
        long privateChatId = BUYER_ID;

        BotReply lockMissing = dispatcher.handle(
                IncomingMessage.text(privateChatId, BUYER_ID, 1L, "/escrow lock"), actor);
        assertThat(lockMissing.text())
                .as("用户缺参时看到的是这一条命令的用法，不是一整屏")
                .contains("用法：/escrow lock <订单号>")
                .contains("查看全部命令")
                .doesNotContain("/escrow invite");

        BotReply bare = dispatcher.handle(
                IncomingMessage.text(privateChatId, BUYER_ID, 2L, "/escrow"), actor);
        assertThat(bare.text()).as("无子命令仍回全量清单（它是『查看全部命令』的入口）")
                .contains("/escrow invite");
        assertThat(bare.offerWebApp()).as("用法类回执引导去表单").isTrue();
    }

    @Test
    @DisplayName("用户级：群管理命令已不执行——管理员发 /kick → 只得帮助回执，无管理动作（群管理全摘验收）")
    void moderationCommandsAreGone() {
        // 用户动作：管理员在群会话发一条旧的管理命令
        CommandActor admin = new CommandActor(BUYER_ID, MemberRole.ADMIN, null);
        BotReply reply = dispatcher.handle(
                IncomingMessage.text(-100999L, BUYER_ID, 1L, "/kick 2002"), admin);

        // 可观察结果：不是管理动作回执（旧行为「已踢出 2002」），而是统一帮助文案——
        // 无处理器命令的明确出口：绝不静默（回执存在）、也不误执行（不含管理动词）
        assertThat(reply).isNotNull();
        assertThat(reply.text())
                .as("群管理命令已随全族摘除——只有帮助出口")
                .contains("担保交易助手")
                .doesNotContain("已踢出")
                .doesNotContain("踢出");
    }

    @Test
    @DisplayName("用户级：回复对方消息 + /escrow create <金额> <币种> → 免输 ID 进入风险预览（真实链路）")
    void replyFormCreateThroughDispatcher() {
        CommandActor actor = new CommandActor(BUYER_ID, MemberRole.MEMBER, null);
        long privateChatId = BUYER_ID;
        // 回复了 SELLER_ID 的消息（41 号）——卖方从"被回复者"解析，无需手输数字 ID
        IncomingMessage message = IncomingMessage.textWithReply(
                privateChatId, BUYER_ID, 1L, "/escrow create 100 USDT", SELLER_ID, 41L);

        BotReply reply = dispatcher.handle(message, actor);

        assertThat(reply.text())
                .as("回复式创建进入风险预览（真正的两步流入口）")
                .contains("风险提示")
                .contains("回复");
        assertThat(reply.text())
                .as("不把被回复者的数字 ID 回吐给用户——那正是他不想输的东西")
                .doesNotContain(String.valueOf(SELLER_ID));
    }

    @Test
    @DisplayName("用户级：费用台账端点已挂载且未配置凭据时 fail-closed（401——不裸奔）")
    void feeLedgerAdminEndpointIsWiredAndLocked() {
        var resp = rest.getForEntity(
                "http://127.0.0.1:" + port + "/admin/fee-ledger", String.class);

        assertThat(resp.getStatusCode())
                .as("无凭据请求 → 401（配置了 tgg.admin.* 即需认证；未配置时同样 401——两种配置都不裸奔）")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("用户级：放款账目落库 → 运维带凭据查 /admin/fee-ledger 可见（真实 DB + 真实 HTTP）")
    void feeLedgerIsWritableAndQueryableOverHttp() {
        long orderId = 9_500_001L;
        feeLedgerPort.record(new FeeLedgerPort.Entry(orderId, "USDT",
                new BigDecimal("100"), new BigDecimal("1"), new BigDecimal("99"), false));
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("it-admin:it-pass".getBytes(StandardCharsets.UTF_8)));

            var resp = rest.exchange("http://127.0.0.1:" + port + "/admin/fee-ledger",
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .contains("\"orderId\":" + orderId)
                    .as("DB DECIMAL(24,8) 定型——对账按字核对")
                    .contains("100.00000000");
        } finally {
            txTemplate.execute(status -> entityManager
                    .createNativeQuery("DELETE FROM fee_ledger WHERE order_id = :id")
                    .setParameter("id", orderId)
                    .executeUpdate());
        }
    }

    @Test
    @DisplayName("用户级：TON Pay 全链路——HTTP 登记（initData 验签）→ HTTP webhook（HMAC 验签）→ 结算落库")
    void tonPayRegisterThenWebhookSettles() throws Exception {
        // 1) 造订单（买方 BUYER_ID、卖方 SELLER_ID）
        EscrowOrder order = new EscrowOrder(BUYER_ID, SELLER_ID, new BigDecimal("100"),
                "USDT", Instant.now());
        orderStore.save(order);
        createdOrderIds.add(order.getId());
        String reference = "ref-it-" + order.getId();

        try {
            // 2) 真实 HTTP 登记（买方身份 + initData 验签）
            Map<String, Object> reg = Map.of("initData", initData(BUYER_ID),
                    "orderId", order.getId(), "reference", reference);
            ResponseEntity<Map> regResp = rest.postForEntity(
                    "http://127.0.0.1:" + port + "/api/tonpay/reference", reg, Map.class);
            assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            // 3) 真实 HTTP webhook（正确 HMAC 签名 + 金额吻合 → 结算）
            String body = "{\"event\":\"transfer.completed\",\"reference\":\"" + reference
                    + "\",\"amount\":\"100\",\"asset\":\"USDT\",\"txHash\":\"tx-it-1\"}";
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-TonPay-Signature", hmacHex("it-tonpay-secret", body));

            var resp = rest.exchange("http://127.0.0.1:" + port + "/api/tonpay/webhook",
                    HttpMethod.POST,
                    new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers), Map.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(String.valueOf(resp.getBody().get("message"))).contains("已结算");
            // 4) 结算落库（真实 store 复查）
            assertThat(tonPayReferences.isSettled(reference)).isTrue();
        } finally {
            txTemplate.execute(status -> entityManager
                    .createNativeQuery("DELETE FROM tonpay_references WHERE reference = :ref")
                    .setParameter("ref", reference)
                    .executeUpdate());
        }
    }

    /** HMAC-SHA256 hex——与 TonPayWebhookVerifier 同算法但**独立复算**（不共享实现）。 */
    private static String hmacHex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of()
                .formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    // ── 验收二：真实 HTTP 的动作预检（真实 initData 验签 + 真库） ────────────

    @Test
    @DisplayName("用户级：POST /api/escrow/order-actions（真实验签）→ 角色矩阵与未部署空集")
    void orderActionsOverRealHttp() {
        EscrowOrder order = new EscrowOrder(BUYER_ID, SELLER_ID, new BigDecimal("1"),
                "TON", Instant.now());
        orderStore.save(order);
        createdOrderIds.add(order.getId());
        assertThat(order.getId()).as("订单已落库（真库往返）").isNotNull();

        // ① 未部署：买方视角 → 空集 + deployed:false
        Map<String, Object> undeployed = postActions(order.getId(), initData(BUYER_ID));
        assertThat(undeployed).containsEntry("ok", true)
                .containsEntry("role", "BUYER").containsEntry("deployed", false);
        assertThat(actions(undeployed)).isEmpty();

        // ② 部署后：买方 → FUND/CONFIRM/REFUND/DISPUTE（不含 DELIVER）
        order.attachChainContractAddress(
                org.ton.ton4j.address.Address
                        .of(org.ton.ton4j.address.Address.BOUNCEABLE_TAG, 0,
                                filledHash((byte) 0x5a))
                        .toString(true, true, true),
                Instant.now());
        orderStore.save(order);
        Map<String, Object> asBuyer = postActions(order.getId(), initData(BUYER_ID));
        assertThat(asBuyer).containsEntry("role", "BUYER").containsEntry("deployed", true);
        assertThat(actions(asBuyer))
                .contains("FUND", "CONFIRM", "REFUND", "DISPUTE")
                .doesNotContain("DELIVER");

        // ③ 部署后：卖方 → DELIVER/REFUND/DISPUTE（不含 FUND/CONFIRM）
        Map<String, Object> asSeller = postActions(order.getId(), initData(SELLER_ID));
        assertThat(asSeller).containsEntry("role", "SELLER");
        assertThat(actions(asSeller))
                .contains("DELIVER", "REFUND", "DISPUTE")
                .doesNotContain("FUND", "CONFIRM");
    }

    // ── 验收三：资金动作的两击确认环（dispatcher → ConfirmGate → 服务层 → 真库） ──────

    @Test
    @DisplayName("用户级：放款两击——第 1 击只回 offer（挂三段确认按钮、订单不动），第 2 击才落款，第 3 击被拒")
    void releaseTakesTwoTapsThroughConfirmGate() {
        long orderId = createDeliveredOrder();

        // ── 第 1 击：只登记确认槽，不动订单 ─────────────────────────────────
        BotReply offer = partySays(BUYER_ID, 1L, "/escrow release " + orderId);
        assertThat(offer.text())
                .as("措辞必须让用户知道订单还没动——否则他会以为钱已经放了")
                .contains("需要二次确认")
                .contains("订单 #" + orderId)
                .contains("尚未执行")
                .contains("/escrow release " + orderId + " --yes");
        assertThat(offer.buttons())
                .as("offer 回执必须挂上那枚确认按钮——否则第 2 击在客户端不可达")
                .hasSize(1);
        assertThat(offer.buttons().get(0).callbackData())
                .as("三段确认形态：与用户在文本里补发 --yes 严格等价")
                .isEqualTo("rl:" + orderId + ":y");
        assertThat(offer.buttons().get(0).text())
                .as("按钮文案自带「确认」与订单号——降低误点")
                .contains("确认").contains("放款")
                .contains(String.valueOf(orderId));
        assertThat(stateOf(orderId))
                .as("第 1 击绝不执行：真库里的状态仍是待验收")
                .isEqualTo("DELIVERED");

        // ── 第 2 击：命中确认槽才放行到既有守卫链 ───────────────────────────
        BotReply confirmed = partySays(BUYER_ID, 2L, "/escrow release " + orderId + " --yes");
        assertThat(confirmed.text())
                .as("确认命中 → 走的就是既有执行路径（回执文案与单发命令同源）")
                .contains("订单 #" + orderId + " 已放款给卖方（流程登记）")
                .doesNotContain("需要二次确认");
        assertThat(stateOf(orderId)).isEqualTo("RELEASED");
        assertThat(confirmed.buttons())
                .as("终态订单不再挂动作按钮（没有可做的事，给了只会误点）")
                .isEmpty();

        // ── 第 3 击：同一枚按钮再点一次 → 槽已消费，不得执行第二遍 ─────────
        BotReply replay = partySays(BUYER_ID, 3L, "/escrow release " + orderId + " --yes");
        assertThat(replay.text())
                .as("确认槽一次性——重放不能把放款执行第二遍")
                .contains("确认已过期或未发起")
                .doesNotContain("已放款给卖方");
    }

    @Test
    @DisplayName("用户级：确认槽按（订单, 动作）隔离——未发起的 --yes 被拒，撞错动作不吞掉原确认")
    void confirmSlotIsScopedToOrderAndAction() {
        long orderId = createDeliveredOrder();

        // ① 没有第 1 击就直接补 --yes → 拒绝（第 2 击不能凭空执行）
        BotReply skipFirstTap = partySays(BUYER_ID, 1L, "/escrow release " + orderId + " --yes");
        assertThat(skipFirstTap.text())
                .as("未发起就没有可命中的确认槽")
                .contains("确认已过期或未发起");
        assertThat(stateOf(orderId)).isEqualTo("DELIVERED");

        // ② 发起放款，随后用「退款」的 --yes 去撞：动作不符 → 拒，且原槽不被吞
        partySays(BUYER_ID, 2L, "/escrow release " + orderId);
        BotReply wrongAction = partySays(BUYER_ID, 3L, "/escrow refund " + orderId + " --yes");
        assertThat(wrongAction.text())
                .as("动作不符必须拒绝——一次误点不能放行另一笔资金动作")
                .contains("确认已过期或未发起");
        assertThat(stateOf(orderId))
                .as("撞错动作不执行任何东西")
                .isEqualTo("DELIVERED");

        // ③ 原动作的确认依然有效（② 没有把槽吞掉）
        BotReply stillValid = partySays(BUYER_ID, 4L, "/escrow release " + orderId + " --yes");
        assertThat(stillValid.text())
                .as("换动作撞一下不该让用户重新发起——原确认仍在")
                .contains("订单 #" + orderId + " 已放款给卖方（流程登记）");
        assertThat(stateOf(orderId)).isEqualTo("RELEASED");
    }

    /**
     * 当事人（买方/卖方）在私聊里发一条命令——等价 Telegram 投递后的路由（真实 dispatcher）。
     * 私聊语义下 chatId 即用户 ID（与既有用例一致）。
     */
    private BotReply partySays(long userId, long messageId, String text) {
        return dispatcher.handle(IncomingMessage.text(userId, userId, messageId, text),
                new CommandActor(userId, MemberRole.MEMBER, null));
    }

    /**
     * 造一笔推进到 {@code DELIVERED}（待买方验收）的真库订单：买方托管 → 卖方交付。
     *
     * <p>两步都走真实命令链路（真实 router → 服务层 → 真库），且都<b>只登记状态、不动钱</b>
     * ——链上托管/出款属 S5 合约。
     */
    private long createDeliveredOrder() {
        EscrowOrder order = new EscrowOrder(BUYER_ID, SELLER_ID, new BigDecimal("100"),
                "USDT", Instant.now());
        orderStore.save(order);
        createdOrderIds.add(order.getId());

        partySays(BUYER_ID, 1L, "/escrow lock " + order.getId());
        partySays(SELLER_ID, 1L, "/escrow deliver " + order.getId());

        assertThat(stateOf(order.getId()))
                .as("前置：订单已推进到待验收（真实 dispatcher + 真库）")
                .isEqualTo("DELIVERED");
        return order.getId();
    }

    /** 真库复查订单状态（不读内存对象——命令层走的是另一条载入路径）。 */
    private String stateOf(long orderId) {
        return txTemplate.execute(status -> (String) entityManager
                .createNativeQuery("SELECT state FROM escrow_orders WHERE id = :id")
                .setParameter("id", orderId)
                .getSingleResult());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Map<String, Object> postActions(long orderId, String initData) {
        Map<String, Object> body = Map.of("initData", initData, "orderId", orderId);
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://127.0.0.1:" + port + "/api/escrow/order-actions", body, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = new HashMap<>(resp.getBody());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<String> actions(Map<String, Object> body) {
        return (List<String>) body.get("actions");
    }

    private static byte[] filledHash(byte fill) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, fill);
        return hash;
    }

    /**
     * 自签 initData（与 {@code WebAppInitDataVerifier} 的验签同源算法）。
     * token 取自容器里的 {@link BotTokenConfig}——与验签器读同一来源，保证同源。
     */
    private String initData(long userId) {
        String token = tokenConfig.token();
        long authDate = Instant.now().getEpochSecond();
        String userJson = "{\"id\":" + userId + "}";
        List<String> rawFields = new ArrayList<>(List.of(
                "auth_date=" + authDate,
                "user=" + URLEncoder.encode(userJson, StandardCharsets.UTF_8)));
        rawFields.sort(String::compareTo);
        List<String> dcsLines = new ArrayList<>(List.of(
                "auth_date=" + authDate,
                "user=" + userJson));
        dcsLines.sort(String::compareTo);
        try {
            Mac outer = Mac.getInstance("HmacSHA256");
            outer.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] secret = outer.doFinal(token.getBytes(StandardCharsets.UTF_8));
            Mac inner = Mac.getInstance("HmacSHA256");
            inner.init(new SecretKeySpec(secret, "HmacSHA256"));
            String hash = java.util.HexFormat.of()
                    .formatHex(inner.doFinal(String.join("\n", dcsLines)
                            .getBytes(StandardCharsets.UTF_8)));
            return String.join("&", rawFields) + "&hash=" + hash;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("用户级：暂停操作面三态——无凭据 401 / 缺地址 400 / 未接线写链 503（fail-closed 绝不回报成功）")
    void pauseEndpointFailsClosedOnUnwiredChain() {
        String url = "http://127.0.0.1:" + port + "/admin/pause";
        String basic = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("it-admin:it-pass".getBytes(StandardCharsets.UTF_8));

        // ① 无凭据 → 401（Basic 门）
        var anonymous = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>("{\"contractAddress\":\"EQit-dummy\"}", new HttpHeaders()),
                String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // ② 有凭据但缺合约地址 → 400
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", basic);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var missing = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // ③ 有凭据 + 合法地址，但 IT 未配置联邦钱包 seed → 写链栈未接线 → 503
        //    （关键语义：发不出去绝不回报 200——真实装配下的 fail-closed 行为）
        var unwired = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>("{\"contractAddress\":\"EQit-dummy\"}", headers), String.class);
        assertThat(unwired.getStatusCode())
                .as("写链栈未接线 → 503（不是 200、也不是 500）")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unwired.getBody()).contains("未接线");
    }
}
