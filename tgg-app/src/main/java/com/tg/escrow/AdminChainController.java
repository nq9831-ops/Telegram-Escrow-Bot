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
import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.chain.TonAddresses;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.common.TggException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin API（S5 部署支撑）：推导托管合约自身的 jetton 钱包地址（{@code ownJettonWallet}）。
 *
 * <h2>它解决什么</h2>
 * <p>部署 Tolk 合约时要往 storage 里写 {@code ownJettonWallet}——那是入金守门的信任锚点
 * （合约只认「由我方 jetton 钱包发来的 transfer_notification」）。这个值必须由
 * 「配置里的 jetton master + 合约地址」推导出来，且<b>写错不会报错，只会静默收不到入金</b>。
 * 所以部署前需要一次可核对的推导——本端点就是那次推导的入口。
 *
 * <h2>为什么做成「按需调用的端点」而不是启动时自动跑</h2>
 * <p>推导要连 liteserver。若放在启动路径上，一次网络故障就会变成「应用起不来」——
 * 把「链上不可用」升级成「服务不可用」，方向是错的。端点形态让它在<b>运维真的需要时</b>才发生。
 *
 * <h2>状态码</h2>
 * <ul>
 *   <li>{@code 200} 推导成功（{@code {ok:true, ownJettonWallet:"…"}}）；</li>
 *   <li>{@code 400} 合约地址缺失/空白/形态非法——请求本身不完整，与链是否可用无关；</li>
 *   <li>{@code 503} 链上能力未启用（未配置 {@code tgg.chain.jetton-master}）——不是请求的错，是本部署没开；</li>
 *   <li>{@code 502} 推导失败（节点不可达 / 回复不可作信任锚点）——<b>绝不返回假地址</b>。</li>
 * </ul>
 *
 * <h2>验证状态（如实标注）</h2>
 * <p>端点逻辑已由 {@code AdminChainControllerTest} 用替身 provider 离线覆盖（四条分支各有用例）。
 * 但<b>真实推导未经真机验证</b>——那取决于 AdnlLiteClient 能否连上 liteserver。
 *
 * <p><b>鉴权</b>：已纳入 {@link SecurityConfig} 的应用层门禁——{@code /admin/**} 需 HTTP Basic 认证，
 * 且<b>未配置 {@code tgg.admin.*} 凭据时一律 401</b>。部署侧 Nginx 的 {@code location ^~ /admin/}
 * {@code deny all} 仍是第一道门。它不泄露资金信息，但会暴露链上读取能力，仍不应公网开放。
 */
@RestController
@RequestMapping(AdminChainController.BASE_PATH)
public class AdminChainController {

    /** 链上管理端点根路径（与其它 {@code /admin/} 端点并列，同在 Nginx 的 {@code /admin/} deny 之下）。 */
    public static final String BASE_PATH = "/admin/chain";

    private final ChainGateway gateway;
    private final EscrowChainDeploymentService deploymentService;

    public AdminChainController(ChainGateway gateway,
                                EscrowChainDeploymentService deploymentService) {
        if (gateway == null) {
            throw new TggException("Admin API：链上入口不可为空");
        }
        if (deploymentService == null) {
            throw new TggException("Admin API：部署编排不可为空");
        }
        this.gateway = gateway;
        this.deploymentService = deploymentService;
    }

    /**
     * 推导给定托管合约的 {@code ownJettonWallet}（用配置里的 jetton master）。
     *
     * @param contract 托管合约地址（friendly 形式）
     * @return 见类注释的状态码表
     */
    @GetMapping("/own-jetton-wallet")
    public ResponseEntity<Map<String, Object>> ownJettonWallet(
            @RequestParam(value = "contract", required = false) String contract) {
        if (contract == null || contract.isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "缺少合约地址（contract）");
        }
        // 形态非法属「请求错」（400），不是「链不可用」（502）。先前只靠推导失败后的
        // ChainUnavailableException 兜底，会把"你传了个坏地址"误报成"链挂了"，把排查方向带偏。
        if (!TonAddresses.isUsableAsAnchor(contract.trim())) {
            return fail(HttpStatus.BAD_REQUEST,
                    "合约地址形态非法（应为 36 字节 friendly 地址，即 48 字符 base64url）");
        }
        if (!gateway.enabled()) {
            return fail(HttpStatus.SERVICE_UNAVAILABLE,
                    "链上能力未启用：未配置 tgg.chain.jetton-master");
        }
        try {
            String wallet = gateway.deriveOwnJettonWallet(contract);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("ownJettonWallet", wallet);
            return ResponseEntity.ok(body);
        } catch (ChainUnavailableException ex) {
            // 推导失败（节点不可达 / 回复不可作锚点）——如实回报，绝不给一个"看起来能用"的假锚点
            return fail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    /** 部署请求体（金额字段用字符串传链上最小单位——避免 JSON 数字精度对不上整型 nanoton）。 */
    public record DeployRequest(Long orderId, String buyerAddress, String sellerAddress,
                                String federationAddress, Integer asset, String chainAmountNano,
                                Long deployValueNanoton) {
    }

    /**
     * 对指定订单部署链上托管合约实例（S5）。
     *
     * <h2>状态码</h2>
     * <ul>
     *   <li>{@code 200} 部署消息已提交（回执含合约地址与 jetton 补发状态）；</li>
     *   <li>{@code 400} 请求参数缺失/非法，或订单不存在 / 已绑定其它链上地址（业务拒绝，含原因）；</li>
     *   <li>{@code 502} 链上发送失败（写链栈未接线 / 节点不可达）——<b>绝不假装已部署</b>。</li>
     * </ul>
     */
    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody(required = false) DeployRequest req) {
        if (req == null || req.orderId() == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少订单号（orderId）");
        }
        if (req.asset() == null || (req.asset() != 0 && req.asset() != 1)) {
            return fail(HttpStatus.BAD_REQUEST, "资产类型（asset）必须是 0(TON) 或 1(JETTON)");
        }
        BigInteger amount = null;
        if (req.chainAmountNano() != null && !req.chainAmountNano().isBlank()) {
            try {
                amount = new BigInteger(req.chainAmountNano().trim());
            } catch (NumberFormatException ex) {
                return fail(HttpStatus.BAD_REQUEST, "托管金额（chainAmountNano）不是合法整数");
            }
            if (amount.signum() <= 0) {
                return fail(HttpStatus.BAD_REQUEST, "托管金额（chainAmountNano）必须为正");
            }
        }
        if (req.deployValueNanoton() == null || req.deployValueNanoton() <= 0) {
            return fail(HttpStatus.BAD_REQUEST, "部署附带金额（deployValueNanoton）必须为正整数 nanoton");
        }
        // TON Connect 线（2026-10-02）：买方/卖方地址与金额均可缺省——由部署编排从订单 V11 列读取
        // （用户在 Mini App 连接钱包后即已落库）；两边皆无由服务层抛明确原因 → 400。
        // 联邦地址无库来源（部署者身份，非订单当事方），仍必填。
        if (req.federationAddress() == null || req.federationAddress().isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "联邦地址（federationAddress）必填");
        }
        try {
            EscrowChainDeploymentService.DeploymentResult result = deploymentService.deploy(
                    req.orderId(), req.buyerAddress(), req.sellerAddress(),
                    req.federationAddress(), req.asset(), amount, req.deployValueNanoton());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("orderId", result.orderId());
            body.put("contractAddress", result.contractAddress());
            body.put("deploySubmitted", result.deploySubmitted());
            body.put("jettonWalletSet", result.jettonWalletSet());
            body.put("jettonWallet", result.jettonWallet());
            return ResponseEntity.ok(body);
        } catch (ChainUnavailableException ex) {
            // 链上发送失败（未接线/节点不可达）——如实回报，绝不假装已部署
            return fail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (EscrowException | IllegalArgumentException ex) {
            // 业务拒绝（订单不存在/改绑/参数非法）——400 + 可读原因
            return fail(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String error) {
        // 用 LinkedHashMap 而非 Map.of：error 可能为 null（异常 message 缺省时）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }
}
