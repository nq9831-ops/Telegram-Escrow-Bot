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

/**
 * 链上能力入口——把「配置 + provider + 推导」收成一个可注入的单元。
 *
 * <h2>为什么不是一个"可能为 null 的 bean"</h2>
 * <p>先前用 {@code @Bean} 返回 {@code null} 来表达「未配置 = 能力未启用」。那是个脆弱接缝：
 * 一旦将来某个组件<b>强制构造注入</b>该 bean，未配置就不再表现为「能力关闭」，
 * 而是抛 {@code NoSuchBeanDefinitionException} 让整个上下文起不来——把"没配"变成了"启动失败"。
 * 本类**永不为 null**：未配置时 {@link #enabled()} 为 false，由调用方显式判断该走哪条路。
 *
 * <h2>为什么 jetton-master 必须真的被用上</h2>
 * <p>先前 provider 的工厂只消费了 {@code settings.testnet()}，{@code jettonMasterAddress}
 * 校验完就被丢掉——那等于"校验了一项与运行无关的东西"。本类把它用在真正的地方：
 * 推导托管合约自身的 jetton 钱包地址（{@code ownJettonWallet}），而那个值会写进合约 storage，
 * 是入金守门的信任锚点。
 *
 * <h2>未启用时抛什么</h2>
 * <p>{@link #deriveOwnJettonWallet(String)} 在未启用时抛 {@link ChainUnavailableException}
 * （而不是返回空值或零地址）——与 chain 模块一贯的失败方向一致：宁可让调用方失败，
 * 也不要给一个"看起来能用"的假锚点。
 *
 * <p>本类实现 {@link AutoCloseable}，持有 provider 的底层连接（懒建，构造不触网）。
 */
public final class ChainGateway implements AutoCloseable {

    /** {@code null} 表示未启用。 */
    private final ChainSettings settings;
    /** {@code null} 表示未启用。 */
    private final JettonWalletQuery query;
    /** 升级消息随附 value（nanoton）：0.2 TON——与合约测试同口径（够付处理费，多余部分原路退）。 */
    private static final long UPGRADE_MESSAGE_VALUE_NANOTON = 200_000_000L;

    /** 裁决消息随附 value（nanoton）：与升级同口径 0.2 TON（同一个处理费预算）。 */
    private static final long RESOLVE_MESSAGE_VALUE_NANOTON = 200_000_000L;

    /** {@code null} = 写链栈未接线（升级/裁决的链上执行恒「未执行」，fail-closed）。 */
    private final ChainMessageSender sender;
    /** {@code null} = 提案状态查询未接线。 */
    private final UpgradeStatusQuery statusQuery;
    /** {@code null} = 链上状态查询未接线（对账读面，ET-19）。 */
    private final EscrowStateQuery escrowStateQuery;
    /** {@code null} = 部署接缝未接线（S5 部署恒拒绝，fail-closed）。 */
    private final ChainDeploySender deploySender;

    private ChainGateway(ChainSettings settings, JettonWalletQuery query) {
        this(settings, query, null, null, null, null);
    }

    private ChainGateway(ChainSettings settings, JettonWalletQuery query,
                         ChainMessageSender sender, UpgradeStatusQuery statusQuery) {
        this(settings, query, sender, statusQuery, null, null);
    }

    private ChainGateway(ChainSettings settings, JettonWalletQuery query,
                         ChainMessageSender sender, UpgradeStatusQuery statusQuery,
                         ChainDeploySender deploySender) {
        this(settings, query, sender, statusQuery, deploySender, null);
    }

    private ChainGateway(ChainSettings settings, JettonWalletQuery query,
                         ChainMessageSender sender, UpgradeStatusQuery statusQuery,
                         ChainDeploySender deploySender, EscrowStateQuery escrowStateQuery) {
        this.settings = settings;
        this.query = query;
        this.sender = sender;
        this.statusQuery = statusQuery;
        this.deploySender = deploySender;
        this.escrowStateQuery = escrowStateQuery;
    }

    /**
     * 接上写链栈（⑥：升级三动作 + 提案跟踪）——返回配置好写链接缝的新实例。
     *
     * <p>与既有工厂分离的原因：写链栈（cell 组装/签名/广播）是独立的可缺省能力，
     * 未接线时升级方法照旧恒抛 {@link ChainUnavailableException}，绝不假装已上链。
     */
    public ChainGateway withUpgradeWriters(ChainMessageSender sender, UpgradeStatusQuery statusQuery) {
        return new ChainGateway(this.settings, this.query, sender, statusQuery,
                this.deploySender, this.escrowStateQuery);
    }

    /** 接上部署接缝（S5 部署）——未接线时 {@link #deployEscrow} 恒拒绝（fail-closed）。 */
    public ChainGateway withDeploySender(ChainDeploySender deploySender) {
        return new ChainGateway(this.settings, this.query, this.sender, this.statusQuery,
                deploySender, this.escrowStateQuery);
    }

    /** 接上链上状态查询（ET-19 对账读面）——未接线时 {@link #escrowStateOf} 恒拒绝（fail-closed）。 */
    public ChainGateway withEscrowStateQuery(EscrowStateQuery escrowStateQuery) {
        return new ChainGateway(this.settings, this.query, this.sender, this.statusQuery,
                this.deploySender, escrowStateQuery);
    }

    /**
     * 由配置派生：未配置（空白）→ 未启用的实例；配了但非法 → 照常抛（不吞配置错误）。
     *
     * <p>注意本方法<b>永不返回 {@code null}</b>——调用方不必做空判断，只需问 {@link #enabled()}。
     */
    public static ChainGateway fromOptional(String jettonMaster, boolean testnet) {
        return ChainSettings.fromOptional(jettonMaster, testnet)
                .map(settings -> new ChainGateway(settings, AdnlJettonWalletQuery.from(settings)))
                .orElseGet(() -> new ChainGateway(null, null));
    }

    /**
     * 用给定配置与 provider 构造——<b>供装配与测试注入替身</b>。
     *
     * <p>生产走 {@link #fromOptional(String, boolean)}；本入口的存在是为了让上层能在不触网的前提下
     * 用替身 provider 驱动推导逻辑（如同包内的 {@code ChainGatewayTest}、以及 tgg-app 侧的
     * 端点测试）。
     */
    public static ChainGateway of(ChainSettings settings, JettonWalletQuery query) {
        if (settings == null || query == null) {
            throw new ChainUnavailableException("链上入口：配置与 provider 均不可为空");
        }
        return new ChainGateway(settings, query);
    }

    /**
     * 链上裁决执行（S5 · 方案 A）：由<b>联邦钱包</b>向托管合约发 {@code Resolve}。
     *
     * <p>与升级三方法<b>同一接缝</b>（{@link ChainMessageSender}）：写链栈未接线时照旧
     * fail-closed 恒抛——「状态登记已做」与「链上真出款」必须分开如实报告，绝不假装出款。
     *
     * <p>合约侧的授权判据是 {@code in.senderAddress == storage.federation}（types.tolk 的
     * Resolve 分支），所以必须经联邦钱包发；裸外部消息直投会被拒（本合约刻意没有 external
     * 入口 + 重放保护 + 签名绑定）。
     *
     * @param contractAddress 托管合约地址（friendly 形式）
     * @param release         {@code true}=RELEASE 放款卖方；{@code false}=REFUND 退款买方
     * @throws ChainUnavailableException 缺合约地址 / 写链栈未接线 / 发送失败——本次裁决仅完成状态登记
     */
    public void resolveDispute(String contractAddress, boolean release) throws ChainUnavailableException {
        if (contractAddress == null || contractAddress.isBlank()) {
            // 「缺地址」与「未接线」是两件事：前者该补配置、后者该装写链栈——分开报才能让人一眼看出方向。
            // 也不能把 null 交给发送器：那等于向空地址发钱。
            throw new ChainUnavailableException(
                    "裁决出款缺少托管合约地址——本次裁决仅完成状态登记，未发生真实出款");
        }
        requireSender().sendToContract(contractAddress, ResolveMessageCodec.resolve(release),
                RESOLVE_MESSAGE_VALUE_NANOTON);
    }

    // ---- S5 部署与存证（2026-10-02）----

    /** 存证消息随附 value（nanoton）：0.2 TON——合约要写 storage，与升级同口径。 */
    private static final long EVIDENCE_MESSAGE_VALUE_NANOTON = 200_000_000L;

    /** SetJettonWallet 消息随附 value（nanoton）：0.2 TON——合约要写 storage，同口径。 */
    private static final long SET_JETTON_WALLET_MESSAGE_VALUE_NANOTON = 200_000_000L;

    /**
     * 部署托管合约实例（S5）：向 H(code,data) 派生地址投递 StateInit。
     *
     * <p>部署接缝未接线时 fail-closed 恒抛——「链下订单已登记」与「链上合约已存在」
     * 必须分开如实报告，绝不假装已部署。
     *
     * @param contractAddress 目标地址（须与 stateInit 推导一致；接缝实现另有自检）
     * @param stateInit       合约 { code, data }
     * @param valueNanoton    合约初始余额（部署后自身开销 + 演练资金来源）
     */
    public void deployEscrow(String contractAddress, org.ton.ton4j.tlb.StateInit stateInit,
                             long valueNanoton) throws ChainUnavailableException {
        if (deploySender == null) {
            throw new ChainUnavailableException("部署接缝未接线——S5 部署恒「未部署」");
        }
        deploySender.sendDeploy(contractAddress, stateInit, valueNanoton);
    }

    /**
     * 写入 ownJettonWallet（两阶段 W 的第 2 步）：联邦在部署后按 (master, 合约地址)
     * 推导出真实钱包地址，经 {@code SetJettonWallet} 一次性写入合约。
     */
    public void setOwnJettonWallet(String contractAddress, String walletAddress)
            throws ChainUnavailableException {
        requireSender().sendToContract(contractAddress,
                SetJettonWalletMessageCodec.set(walletAddress),
                SET_JETTON_WALLET_MESSAGE_VALUE_NANOTON);
    }

    /**
     * 链上存证（ET-43 链上腿）：把证据哈希记进合约的 map。
     *
     * @param contractAddress 该订单的托管合约地址
     * @param evidenceHash    证据哈希（256 位非负；链下由 saltedHash 转换）
     */
    public void recordEvidence(String contractAddress, java.math.BigInteger evidenceHash)
            throws ChainUnavailableException {
        requireSender().sendToContract(contractAddress,
                RecordEvidenceMessageCodec.record(evidenceHash),
                EVIDENCE_MESSAGE_VALUE_NANOTON);
    }

    // ---- 合约升级入口（⑥）：提案 → 冷静期 → 执行，经写链接缝发给合约 ----
    // 合约侧时间锁是权威（now ≥ proposedAt + 72h，EscrowContract.tolk 的 Apply 分支）；
    // 链下只决定「什么时候发 Apply」，发早了会被合约以 UpgradeNotReady 拒绝（提案保留）。

    /** 发升级提案（ProposeUpgrade，仅联邦钱包可发；已有提案须先 Cancel）。 */
    public void proposeUpgrade(String contractAddress, org.ton.ton4j.cell.Cell newCode)
            throws ChainUnavailableException {
        requireSender().sendToContract(contractAddress, UpgradeMessageCodec.proposeUpgrade(newCode),
                UPGRADE_MESSAGE_VALUE_NANOTON);
    }

    /** 发升级执行（ApplyUpgrade）——冷静期未满会被合约拒绝，提案保留、期满可重试。 */
    public void applyUpgrade(String contractAddress) throws ChainUnavailableException {
        requireSender().sendToContract(contractAddress, UpgradeMessageCodec.applyUpgrade(),
                UPGRADE_MESSAGE_VALUE_NANOTON);
    }

    /** 发升级撤销（CancelUpgrade，随时可撤）。 */
    public void cancelUpgrade(String contractAddress) throws ChainUnavailableException {
        requireSender().sendToContract(contractAddress, UpgradeMessageCodec.cancelUpgrade(),
                UPGRADE_MESSAGE_VALUE_NANOTON);
    }

    /** 发紧急暂停（ET-21，PauseMessage——仅联邦钱包可发；幂等，重复发无副作用）。 */
    public void pause(String contractAddress) throws ChainUnavailableException {
        requireSender().sendToContract(contractAddress, PauseMessageCodec.pause(),
                UPGRADE_MESSAGE_VALUE_NANOTON);
    }

    /** 发解除暂停（ET-21，UnpauseMessage——仅联邦钱包可发；幂等）。 */
    public void unpause(String contractAddress) throws ChainUnavailableException {
        requireSender().sendToContract(contractAddress, PauseMessageCodec.unpause(),
                UPGRADE_MESSAGE_VALUE_NANOTON);
    }

    /** 读链上托管状态（ET-19 对账读面；合约 {@code get fun escrowState}）。 */
    public int escrowStateOf(String contractAddress) throws ChainUnavailableException {
        if (escrowStateQuery == null) {
            throw new ChainUnavailableException("链上状态查询未接线——对账读面尚未装配");
        }
        return escrowStateQuery.queryState(contractAddress);
    }

    /** 读链上升级提案状态（合约 get fun upgradeStatus）。 */
    public UpgradeStatus upgradeStatus(String contractAddress) throws ChainUnavailableException {
        if (statusQuery == null) {
            throw new ChainUnavailableException("升级提案查询未接线——写链栈尚未装配");
        }
        return statusQuery.query(contractAddress);
    }

    private ChainMessageSender requireSender() throws ChainUnavailableException {
        if (sender == null) {
            throw new ChainUnavailableException("写链栈未接线——升级/裁决的链上执行恒「未执行」");
        }
        return sender;
    }

    /** 链上能力是否已启用（即是否配了 jetton-master）。 */
    public boolean enabled() {
        return settings != null;
    }

    /**
     * 推导托管合约自身的 jetton 钱包地址（{@code ownJettonWallet}）——本类存在的理由。
     *
     * <p>用配置里的 jetton master 与给定的合约地址去问链，并对回复做「可作信任锚点」的判定
     * （空 / 形态非法 / 全零地址一律拒）。
     *
     * @param contractAddress 托管合约地址
     * @return 可信的 jetton 钱包地址
     * @throws ChainUnavailableException 链上能力未启用，或查询/判定失败
     */
    public String deriveOwnJettonWallet(String contractAddress) throws ChainUnavailableException {
        if (!enabled()) {
            throw new ChainUnavailableException(
                    "链上能力未启用：未配置 tgg.chain.jetton-master");
        }
        return new JettonWalletAddressResolver(query)
                .resolve(settings.jettonMasterAddress(), contractAddress);
    }

    @Override
    public void close() throws Exception {
        if (query instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
