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
package com.tg.escrow.webapp;

import com.tg.escrow.chain.ConfirmMessageCodec;
import com.tg.escrow.chain.DeliverMessageCodec;
import com.tg.escrow.chain.DisputeMessageCodec;
import com.tg.escrow.chain.FundMessageCodec;
import com.tg.escrow.chain.RefundMessageCodec;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.common.TggException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;

import jakarta.servlet.http.HttpServletRequest;

import java.math.BigInteger;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TON Connect 交易构造端点（Mini App 资金链，2026-10-02）——把「用户自己的钱包」接进托管合约。
 *
 * <h2>它解决什么</h2>
 * <p>合约的资金路径守卫要求 {@code sender == storage.buyer/seller}——消息必须由<b>当事方自己的
 * 钱包</b>签名。本端点把「构造」与「签名」拆开：后端用现有 codec 产出交易参数
 * （收款地址 / 金额 / payload BOC），前端交 {@code @tonconnect/ui} 的
 * {@code sendTransaction} 让用户钱包签名广播。后端<b>从不</b>触碰用户私钥。
 *
 * <h2>身份与鉴权（与 /api/trade 同款）</h2>
 * <p>{@code SecurityConfig} 对非 admin 路径 {@code permitAll}——防护在本端点内部：
 * 一切请求先过 {@link WebAppInitDataVerifier} 的 Telegram initData 验签，验签结果给出
 * {@code userId}，再与订单的买卖双方对照（角色矩阵见 {@link #chainTx}）。
 *
 * <h2>金额语义（防"钱包扣错钱"）</h2>
 * <ul>
 *   <li>{@code FUND}：{@code amount = chainAmountNano（订单 V11 列）+} {@link #FUND_GAS_MARGIN_NANOTON}
 *       的 gas 余量——合约守卫要求到账 ≥ storage.amount，余部留作合约存储/操作预算（不退）；</li>
 *   <li>其余消息：固定 {@link #MSG_GAS_MARGIN_NANOTON} 附着 gas。</li>
 * </ul>
 *
 * <h2>TON Connect 参数形态（官方文档 2026-10-02 核实）</h2>
 * <p>{@code sendTransaction({validUntil, network, messages:[{address(TEP-2 friendly), amount, payload(BOC base64)}]})}
 * ——本端点的响应字段与之逐项对应（network: testnet='-3'、mainnet='-239'）。
 */
@RestController
@RequestMapping(EscrowChainTxController.BASE_PATH)
public class EscrowChainTxController {

    /** 资金链端点根路径（与 {@code /api/trade} 并列；SecurityConfig 对非 admin 路径 permitAll，防护在验签）。 */
    public static final String BASE_PATH = "/api/escrow";

    /** FUND 消息的 gas 余量（nanoton）：覆盖钱包→合约的内部消息费；余部留作合约存储预算。 */
    public static final long FUND_GAS_MARGIN_NANOTON = 50_000_000L;

    /** 非资金消息的附着 gas（nanoton）。 */
    public static final long MSG_GAS_MARGIN_NANOTON = 50_000_000L;

    /** 交易请求有效期（秒）——TON Connect validUntil = now + 本值。 */
    public static final long VALID_FOR_SECONDS = 600L;

    /** 支持的动作（与合约 AllowedMessage 的资金类消息一一对应）。 */
    public enum Action {
        /** 买方入金（OPEN → LOCKED）。 */
        FUND,
        /** 卖方标记已交付（LOCKED → DELIVERED）。 */
        DELIVER,
        /** 买方确认收货（DELIVERED → RELEASED）。 */
        CONFIRM,
        /** 任一方请求退款（LOCKED → REFUNDED）。 */
        REFUND,
        /** 当事任一方发起争议（LOCKED/DELIVERED → DISPUTED）。 */
        DISPUTE
    }

    private final WebAppInitDataVerifier verifier;
    private final EscrowOrderLookupPort lookup;
    private final EscrowOrderStore store;
    private final boolean testnet;
    private final String webappUrl;
    private final Clock clock;

    public EscrowChainTxController(WebAppInitDataVerifier verifier, EscrowOrderLookupPort lookup,
                                   EscrowOrderStore store,
                                   @Value("${tgg.chain.testnet:true}") boolean testnet,
                                   @Value("${tgg.webapp.url:}") String webappUrl,
                                   Clock clock) {
        if (verifier == null || lookup == null || store == null || clock == null) {
            throw new TggException("资金链端点：验签器、订单端口与时钟均不可为空");
        }
        this.verifier = verifier;
        this.lookup = lookup;
        this.store = store;
        this.testnet = testnet;
        this.webappUrl = webappUrl == null ? "" : webappUrl;
        this.clock = clock;
    }

    /** 绑定请求：Mini App 在 TON Connect 连接成功后上报钱包地址。 */
    public record WalletBindRequest(String initData, Long orderId, String address) {
    }

    /** 交易构造请求。 */
    public record ChainTxRequest(String initData, Long orderId, String action) {
    }

    /**
     * 绑定当前用户在该订单中的 TON 钱包地址（V11 列）。
     *
     * <p>角色由订单的 {@code buyer_user_id / seller_user_id} 与验签出的 userId 对照判定；
     * 非当事方 403。部署前可重复绑定（用户重连换钱包）；部署后改绑会被实体拒绝（400）。
     */
    @PostMapping("/wallet")
    public ResponseEntity<Map<String, Object>> bindWallet(
            @RequestBody(required = false) WalletBindRequest req) {
        if (req == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少请求体");
        }
        Optional<Long> me = verifier.verifyUserId(req.initData());
        if (me.isEmpty()) {
            return fail(HttpStatus.UNAUTHORIZED, "身份校验失败：initData 无效或已过期，请重新打开 Mini App");
        }
        if (req.orderId() == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少订单号（orderId）");
        }
        if (req.address() == null || req.address().isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "缺少钱包地址（address）");
        }
        EscrowOrder order = lookup.byId(req.orderId()).orElse(null);
        if (order == null) {
            return fail(HttpStatus.BAD_REQUEST, "订单 #" + req.orderId() + " 不存在");
        }
        String role;
        try {
            if (order.getBuyerUserId() == me.get()) {
                order.attachBuyerTonAddress(req.address().trim(), clock.instant());
                role = "BUYER";
            } else if (order.getSellerUserId() == me.get()) {
                order.attachSellerTonAddress(req.address().trim(), clock.instant());
                role = "SELLER";
            } else {
                return fail(HttpStatus.FORBIDDEN, "你不是该订单的当事方，无法绑定钱包地址");
            }
            store.save(order);
        } catch (EscrowException | IllegalArgumentException ex) {
            return fail(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("role", role);
        body.put("boundAddress", req.address().trim());
        return ResponseEntity.ok(body);
    }

    /**
     * 构造一笔资金路径交易的 TON Connect 参数（用户在钱包里签名后广播）。
     *
     * <p>角色矩阵：FUND/CONFIRM → 仅买方；DELIVER → 仅卖方；REFUND/DISPUTE → 任一当事方。
     * 订单未部署（无合约地址/金额）时 400——资金链以"已部署的合约实例"为前提。
     */
    @PostMapping("/chain-tx")
    public ResponseEntity<Map<String, Object>> chainTx(
            @RequestBody(required = false) ChainTxRequest req) {
        if (req == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少请求体");
        }
        Optional<Long> me = verifier.verifyUserId(req.initData());
        if (me.isEmpty()) {
            return fail(HttpStatus.UNAUTHORIZED, "身份校验失败：initData 无效或已过期，请重新打开 Mini App");
        }
        if (req.orderId() == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少订单号（orderId）");
        }
        Action action;
        try {
            action = Action.valueOf(req.action() == null ? "" : req.action().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fail(HttpStatus.BAD_REQUEST,
                    "动作（action）必须是 FUND / DELIVER / CONFIRM / REFUND / DISPUTE 之一");
        }
        EscrowOrder order = lookup.byId(req.orderId()).orElse(null);
        if (order == null) {
            return fail(HttpStatus.BAD_REQUEST, "订单 #" + req.orderId() + " 不存在");
        }
        if (order.getChainContractAddress() == null
                || order.getChainContractAddress().isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "订单尚未部署链上合约（资金链以已部署实例为前提）");
        }

        // 角色矩阵（合约守卫的镜像——早拒比发出去再被链拒省一轮排查）
        long meId = me.get();
        boolean buyer = order.getBuyerUserId() == meId;
        boolean seller = order.getSellerUserId() == meId;
        boolean allowed = switch (action) {
            case FUND, CONFIRM -> buyer;
            case DELIVER -> seller;
            case REFUND, DISPUTE -> buyer || seller;
        };
        if (!allowed) {
            return fail(HttpStatus.FORBIDDEN, "当前身份无权执行该动作（" + action + "）");
        }

        BigInteger amount;
        try {
            if (action == Action.FUND) {
                String chainAmount = order.getChainAmountNano();
                if (chainAmount == null || chainAmount.isBlank()) {
                    return fail(HttpStatus.BAD_REQUEST,
                            "订单未绑定链上金额（请先完成部署与地址/金额回填）");
                }
                amount = new BigInteger(chainAmount)
                        .add(BigInteger.valueOf(FUND_GAS_MARGIN_NANOTON));
            } else {
                amount = BigInteger.valueOf(MSG_GAS_MARGIN_NANOTON);
            }
        } catch (NumberFormatException ex) {
            return fail(HttpStatus.BAD_REQUEST, "订单的库中金额非法");
        }

        Address contract;
        try {
            contract = Address.of(order.getChainContractAddress());
        } catch (Exception | Error ex) {
            return fail(HttpStatus.BAD_REQUEST, "订单链上地址非法：" + ex.getMessage());
        }

        Cell payload = switch (action) {
            case FUND -> FundMessageCodec.fund();
            case DELIVER -> DeliverMessageCodec.deliver();
            case CONFIRM -> ConfirmMessageCodec.confirm();
            case REFUND -> RefundMessageCodec.refund();
            case DISPUTE -> DisputeMessageCodec.dispute();
        };

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("action", action.name());
        body.put("network", testnet ? "-3" : "-239");
        // TON Connect 要求 TEP-2 friendly 形态（raw 会被钱包拒）
        body.put("address", testnet
                ? contract.toBounceableTestnet() : contract.toString(true, true, true));
        body.put("amount", amount.toString());
        body.put("payload", Base64.getEncoder().encodeToString(payload.toBoc()));
        body.put("validUntil", clock.instant().getEpochSecond() + VALID_FOR_SECONDS);
        return ResponseEntity.ok(body);
    }

    /** 动作可用性查询请求（Mini App 按结果过滤动作按钮）。 */
    public record OrderActionsRequest(String initData, Long orderId) {
    }

    /**
     * 该用户对该订单此刻「可能可用」的链上动作（前端按钮过滤的唯一数据源）。
     *
     * <h2>为什么只按这三个维度过滤</h2>
     * <p>判据只用<b>稳定</b>事实：角色（买卖方身份不漂移）、终态（交易已结束时不再有链上动作）、
     * 部署态（未部署合约则任何链上消息都无从发出）。<b>刻意不按中间状态硬收窄</b>——链下状态是
     * 流程登记、不是链上状态的镜像（链上入金已发生而链下尚未登记 LOCKED 的滞后完全可能），
     * 按中间状态过滤会把此刻真正合法的按钮误藏；<b>最终裁决在合约</b>（点错得到可读拒绝），
     * 本端点只负责砍掉「显然不该出现」的按钮（如卖方看到"支付入金"）。
     */
    @PostMapping("/order-actions")
    public ResponseEntity<Map<String, Object>> orderActions(
            @RequestBody(required = false) OrderActionsRequest req) {
        if (req == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少请求体");
        }
        Optional<Long> me = verifier.verifyUserId(req.initData());
        if (me.isEmpty()) {
            return fail(HttpStatus.UNAUTHORIZED, "身份校验失败：initData 无效或已过期，请重新打开 Mini App");
        }
        if (req.orderId() == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少订单号（orderId）");
        }
        EscrowOrder order = lookup.byId(req.orderId()).orElse(null);
        if (order == null) {
            return fail(HttpStatus.BAD_REQUEST, "订单 #" + req.orderId() + " 不存在");
        }
        long meId = me.get();
        boolean buyer = order.getBuyerUserId() == meId;
        boolean seller = order.getSellerUserId() == meId;
        if (!buyer && !seller) {
            return fail(HttpStatus.FORBIDDEN, "你不是该订单的当事方");
        }

        boolean deployed = order.getChainContractAddress() != null
                && !order.getChainContractAddress().isBlank();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("role", buyer ? "BUYER" : "SELLER");
        body.put("state", order.currentState().name());
        body.put("deployed", deployed);
        body.put("actions", availableActions(order.currentState(), buyer, deployed));
        return ResponseEntity.ok(body);
    }

    /**
     * 「此刻该角色可能用到」的动作集（顺序即前端按钮展示顺序）。
     *
     * <p>终态与未部署 → 空集；其余只按角色分——中间状态为什么不收窄，见
     * {@link #orderActions} 的维度说明。
     */
    static List<String> availableActions(EscrowOrder.State state, boolean buyer, boolean deployed) {
        if (!deployed) {
            return List.of();
        }
        return switch (state) {
            case RELEASED, REFUNDED, CANCELLED -> List.of();
            default -> buyer
                    ? List.of("FUND", "CONFIRM", "REFUND", "DISPUTE")
                    : List.of("DELIVER", "REFUND", "DISPUTE");
        };
    }

    /**
     * TON Connect manifest（按部署域名动态生成——静态文件无法知道生产 URL）。
     *
     * <p>钱包 App 连接前会拉取本文件（{@code url/name/iconUrl}）。未配置 {@code tgg.webapp.url}
     * 时回退到请求自身的 origin（本地/隧道调试可用；生产必须配齐 HTTPS 域名）。
     */
    @GetMapping("/tonconnect-manifest.json")
    public ResponseEntity<Map<String, Object>> tonConnectManifest(HttpServletRequest request) {
        String base = webappUrl;
        if (base == null || base.isBlank()) {
            int port = request.getServerPort();
            String suffix = (port == 80 || port == 443) ? "" : ":" + port;
            base = request.getScheme() + "://" + request.getServerName() + suffix;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", trimmed + "/miniapp");
        body.put("name", "TON 担保交易");
        body.put("iconUrl", trimmed + "/icon-180.png");
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }
}
