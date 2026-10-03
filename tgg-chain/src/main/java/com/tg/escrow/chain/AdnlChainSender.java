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

import com.iwebpp.crypto.TweetNaclFast;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.adnl.globalconfig.TonGlobalConfig;
import org.ton.ton4j.adnl.AdnlLiteClient;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.tlb.ActionSendMsg;
import org.ton.ton4j.tlb.CurrencyCollection;
import org.ton.ton4j.tlb.ExternalMessageInInfo;
import org.ton.ton4j.tlb.InternalMessageInfoRelaxed;
import org.ton.ton4j.tlb.MessageRelaxed;
import org.ton.ton4j.tlb.MsgAddressIntStd;
import org.ton.ton4j.tlb.OutList;
import org.ton.ton4j.smartcontract.wallet.v5.WalletV5;
import org.ton.ton4j.tl.liteserver.responses.RunMethodResult;
import org.ton.ton4j.tlb.Message;
import org.ton.ton4j.tlb.StateInit;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;
import org.ton.ton4j.tlb.VmStackValueTinyInt;

/**
 * 经联邦钱包（wallet v5r1）把消息体发给托管合约的写链实现（⑥ / S5 共用）。
 *
 * <h2>发送形态：钱包转账式内部消息，不是裸外部消息直投合约</h2>
 * <p>合约的授权判据是 {@code in.senderAddress == storage.federation}（contracts/EscrowContract.tolk），
 * 所以消息必须由联邦钱包发出：本类组装「联邦钱包 v5r1 的 external message」——钱包收到后
 * 把我们的 body 作为内部消息转给目标合约。用 ton4j 的 {@code WalletV5.createExternalTransferBody}
 * 与 {@code prepareExternalMsg}（签名外部注入，钱包实例只带公钥即可纯组装），再经
 * {@code AdnlLiteClient.sendExternalMessage} 广播。
 *
 * <h2>接缝（离线可测）</h2>
 * <p>三个可替换端口——{@link WalletSigner}（签名）、{@link SeqnoSource}（seqno）、
 * {@link MessageBroadcaster}（广播）——测试用替身把组装/异常契约整条钉住，不触网。
 *
 * <h2>验证状态（2026-10-02 真机核销后更新）</h2>
 * <p><b>已真机验证</b>（testnet，B10/B11 核销）：部署消息接受——演练合约从 uninitialized → active；
 * 链上存证接受——{@code evidenceOf} 读回一致；联邦钱包冷启动（首笔消息同时部署钱包）成功。
 * 离线三接缝替身仍把组装/异常契约整条钉住，不触网。
 *
 * <p><b>签名载荷口径（已定案，2026-10-02）</b>：签 {@code hash(signingMessage)}（32 字节 cell hash），
 * signingMessage = 「sign」magic + walletId + validUntil + seqno + outActions（<b>不含签名</b>），
 * external body = [signingMessage][detached 签名 64B]——由 {@link WalletV5R1MessageBuilder}
 * 逐位复刻官方（{@code WalletV5R1MessageBuilderTest} 金标准对拍）。<b>原「推定」已被真机证伪</b>：
 * ton4j 的 {@code createExternalTransferBody} 拼的是另一种版本钱包的格式（magic "send"），
 * 真机表现为钱包静默拒收——该高层组装整体弃用。
 */
public final class AdnlChainSender implements ChainMessageSender, ChainDeploySender, AutoCloseable {

    /** 外部消息签名单元：对钱包签名载荷做 Ed25519 签名（生产由 FederationKeyPair::sign 接入）。 */
    @FunctionalInterface
    public interface WalletSigner {
        /** 允许抛受检异常——密钥访问/签名实现各有各的异常面，由 sendToContract 统一兜成契约异常。 */
        byte[] sign(byte[] payload) throws Exception;
    }

    /** seqno 读取端口（测试替身替换点；生产实现经 ADNL {@code runMethod("seqno")}）。 */
    @FunctionalInterface
    public interface SeqnoSource {
        long currentSeqno() throws Exception;
    }

    /** 广播端口（测试替身替换点；生产实现调 {@code AdnlLiteClient.sendExternalMessage}）。 */
    @FunctionalInterface
    public interface MessageBroadcaster {
        void broadcast(Message message) throws Exception;
    }

    /** 外部消息有效期：组装时刻起 2 分钟（钱包消息过期即作废，防旧消息被重放）。 */
    private static final Duration VALIDITY = Duration.ofMinutes(2);

    private final long walletId;
    private final WalletV5 wallet;
    private final WalletSigner signer;
    private final SeqnoSource seqnoSource;
    private final MessageBroadcaster broadcaster;
    private final Clock clock;
    /** 非 {@code null} = 本类持有 lite-server 节点池（testnet 装配），{@link #close()} 负责释放。 */
    private final LiteServerPool<AdnlLiteClient> ownedPool;
    /** 非 {@code null} = 本类持有单个 ADNL 连接（mainnet / 回退装配），{@link #close()} 负责释放。 */
    private final AdnlLiteClient ownedClient;

    /**
     * @param walletId      钱包 v5r1 wallet id（标准默认 0x29A9A317；与联邦钱包部署一致）
     * @param walletKeyPair 联邦钱包密钥对——组装只需公开部分（地址/数据/签名消息体），
     *                      实际签名走 {@code signer}（私钥不必出签名单元）
     * @param signer        钱包签名单元（联邦私钥侧）
     * @param seqnoSource   seqno 读取端口
     * @param broadcaster   广播端口
     * @param clock         时钟（一律注入——项目惯例，JpaModerationAdapters 同款纪律）
     */
    public AdnlChainSender(long walletId,
                           TweetNaclFast.Signature.KeyPair walletKeyPair,
                           WalletSigner signer,
                           SeqnoSource seqnoSource,
                           MessageBroadcaster broadcaster,
                           Clock clock) {
        this(walletId, walletKeyPair, signer, seqnoSource, broadcaster, clock, null, null);
    }

    private AdnlChainSender(long walletId,
                            TweetNaclFast.Signature.KeyPair walletKeyPair,
                            WalletSigner signer,
                            SeqnoSource seqnoSource,
                            MessageBroadcaster broadcaster,
                            Clock clock,
                            LiteServerPool<AdnlLiteClient> ownedPool,
                            AdnlLiteClient ownedClient) {
        this.walletId = walletId;
        this.wallet = WalletV5.builder()
                .keyPair(Objects.requireNonNull(walletKeyPair, "联邦钱包密钥对未提供"))
                .walletId(walletId)
                // v5r1 官方 data 首 bit（is_signature_auth_allowed）必须为 true——ton4j 默认 false，
                // 不设它会得到**另一个地址**（data 不同）：seqno 会打到错地址、外部消息 init 也带错
                // data。2026-10-02 与官方 TS SDK / acton 三方对拍定位（ChainWalletKeysTest 固化向量）。
                .isSigAuthAllowed(true)
                .build();
        this.signer = Objects.requireNonNull(signer, "钱包签名单元未提供");
        this.seqnoSource = Objects.requireNonNull(seqnoSource, "seqno 读取端口未提供");
        this.broadcaster = Objects.requireNonNull(broadcaster, "广播端口未提供");
        this.clock = Objects.requireNonNull(clock, "时钟未提供");
        this.ownedPool = ownedPool;
        this.ownedClient = ownedClient;
    }

    /**
     * 生产装配：单 {@code AdnlLiteClient} 承载 seqno 读取与广播（懒建连——构造不触网），
     * 签名用密钥对的私钥侧（Ed25519 detached 签名，TweetNaCl {@code Signature.sign}）。
     *
     * <p>钱包地址由密钥对推导（{@code Contract.getAddress()}），无需配置；seqno 经
     * {@code runMethod("seqno")} 读取（每次发送前取新值，防双花序号复用）。
     *
     * <p><b>未经真实节点验证</b>（同本类口径）——本工厂把三接缝接到真实网络出口，
     * 真机链路核对前不得称「可用」。
     */
    public static AdnlChainSender forNetwork(boolean testnet,
                                             long walletId,
                                             TweetNaclFast.Signature.KeyPair walletKeyPair,
                                             Clock clock) throws ChainUnavailableException {
        final LiteServerPool<AdnlLiteClient> pool;
        final AdnlLiteClient singleClient;
        try {
            LiteServerPool<AdnlLiteClient> builtPool = null;
            AdnlLiteClient builtClient = null;
            if (testnet) {
                TonGlobalConfig bundled = LiteServerPool.loadBundledTestnetConfig();
                if (bundled != null) {
                    // 2026-10 服务器实况定案：ton4j 的 useServerRotation 只在「连接失败」时换节点，
                    // 「区块不同步」的 query 失败（"block is not in db / out of sync"）直接抛——
                    // 单节点抖动即整栈瘫痪。改为应用层节点池（LiteServerPool）：任何失败换下一台，
                    // 8 个节点全部不可用才抛。构造即预热（首个可达节点），全不可达装配期 fail-fast。
                    builtPool = LiteServerPool.forConfig(bundled, clock);
                } else {
                    builtClient = AdnlLiteClient.builder().testnet().build(); // 资源缺失时回退 URL
                }
            } else {
                // mainnet 未池化：无内置全局配置（ton4j mainnet() 每次从 GitHub 拉取）。
                // 要池化，用 LiteServerPool.forConfig 传入 mainnet 配置即可。
                builtClient = AdnlLiteClient.builder().mainnet().build();
            }
            pool = builtPool;
            singleClient = builtClient;
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // 建连构件失败=环境坏（配了 seed 却起不了 ADNL）——抛契约异常，装配期 fail-fast
            throw new ChainUnavailableException("ADNL 客户端构建失败：" + e.getMessage(), e);
        }
        WalletSigner signer = networkSigner(walletKeyPair);
        WalletV5 addressWallet = WalletV5.builder().keyPair(walletKeyPair).walletId(walletId)
                // 同上：v5r1 data 首 bit 必须 true（否则 seqno 查询会打到另一个地址）
                .isSigAuthAllowed(true)
                .build();
        SeqnoSource seqno = () -> {
            RunMethodResult result = pool != null
                    ? pool.execute(c -> c.runMethod(addressWallet.getAddress(), "seqno"))
                    : singleClient.runMethod(addressWallet.getAddress(), "seqno");
            return seqnoFromRunMethod(result.getExitCode(), result.result);
        };
        MessageBroadcaster broadcast = pool != null
                // 广播失败换节点重发安全：同一条已签名 external 消息由钱包按 seqno 去重
                ? msg -> pool.execute(c -> {
                    c.sendExternalMessage(msg);
                    return null;
                })
                : msg -> singleClient.sendExternalMessage(msg);
        return new AdnlChainSender(walletId, walletKeyPair, signer, seqno,
                broadcast, clock, pool, singleClient);
    }

    /**
     * 生产签名单元（真实 TweetNaCl ed25519 detached 签名）——纯构造，可直接离线测试。
     *
     * <p><b>参数顺序（2026-10-02 首笔真机写链的教训）</b>：{@code TweetNaClFast.Signature}
     * 构造器语义是 <b>(publicKey, secretKey)</b>——曾误写为 (secretKey, publicKey)，
     * 32 字节公钥被当私钥使用，真机签名即 {@code Index 32 out of bounds for length 32}。
     * 因离线三接缝替身跳过真签名，此错从未在测试中暴露；现由
     * {@code AdnlChainSenderTest.networkSignerProduces64ByteSignature} 直接钉住。
     *
     * <p><b>detached 截取（同日第二处）</b>：{@code TweetNaClFast.Signature.sign(m)} 返回的是
     * <b>signed message</b>（[签名 64B][消息] 共 96B），而链与官方 SDK 使用的是
     * <b>detached 签名（仅 64B）</b>——取前 64 字节（金标准对拍验证：
     * {@code WalletV5R1MessageBuilderTest}）。
     */
    static WalletSigner networkSigner(TweetNaclFast.Signature.KeyPair keyPair) {
        return payload -> {
            byte[] signedMessage = new TweetNaclFast.Signature(
                    keyPair.getPublicKey(), keyPair.getSecretKey()).sign(payload);
            byte[] detached = new byte[64];
            System.arraycopy(signedMessage, 0, detached, 0, 64);
            return detached;
        };
    }

    /**
     * seqno runMethod 结果的语义处理（纯逻辑，可离线测试）。
     *
     * <p><b>-256 = 账户未初始化</b>（2026-10-02 首笔真机写链实测）：钱包尚未部署时，
     * lite server 对 {@code seqno} get 方法返回 exit code -256——首笔消息的职责本就是
     * "部署钱包 + 转发内部消息"，此时 seqno 视作 0（与官方钱包客户端同款行为）。
     * 其余非 0 退出码仍视为链路故障（fail-closed）。
     *
     * @param exitCode  runMethod 退出码
     * @param resultBoc exitCode=0 时的栈 boc（其余情况不会被读取）
     */
    static long seqnoFromRunMethod(int exitCode, byte[] resultBoc) throws ChainUnavailableException {
        if (exitCode == -256) {
            return 0L;
        }
        if (exitCode != 0) {
            throw new ChainUnavailableException("seqno 查询退出码非 0：" + exitCode);
        }
        return seqnoFromResult(resultBoc);
    }

    /**
     * 从 {@code runMethod("seqno")} 的结果 boc 解析 seqno（纯逻辑，可离线测试）。
     * TVmStack 整数有 tiny/大整数两变体，都要认（AdnlUpgradeStatusQuery 同款）。
     */
    static long seqnoFromResult(byte[] resultBoc) throws ChainUnavailableException {
        if (resultBoc == null || resultBoc.length == 0) {
            throw new ChainUnavailableException("seqno 返回空结果");
        }
        try {
            VmStack stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(resultBoc)));
            if (stack.getStack() == null || stack.getStack().getTos().isEmpty()) {
                throw new ChainUnavailableException("seqno 返回空栈");
            }
            VmStackValue top = stack.getStack().getTos().get(0);
            BigInteger value;
            if (top instanceof VmStackValueInt intVal) {
                value = intVal.getValue();
            } else if (top instanceof VmStackValueTinyInt tinyVal) {
                value = tinyVal.getValue();
            } else {
                throw new ChainUnavailableException(
                        "seqno 不是整数：" + top.getClass().getSimpleName());
            }
            return value.longValueExact();
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception | Error e) {
            // ton4j Cell.fromBoc 非法输入抛裸 Error（AdnlJettonWalletQuery 同款坑）——必须兜住
            throw new ChainUnavailableException("seqno 结果解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void sendToContract(String contractAddress, Cell body, long valueNanoton)
            throws ChainUnavailableException {
        if (contractAddress == null || contractAddress.isBlank()) {
            throw new ChainUnavailableException("写链：目标合约地址未提供");
        }
        if (body == null) {
            throw new ChainUnavailableException("写链：消息体未提供");
        }
        try {
            // 自组装 v5r1 external（见 sendExternalMessage 的说明——ton4j 高层组装为错误格式，已弃用）
            sendExternalMessage(Address.of(contractAddress), null, body, valueNanoton, true);
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainUnavailableException("写链发送失败：" + e.getMessage(), e);
        } catch (Error e) {
            // ton4j 用裸 java.lang.Error 表达业务性失败（AdnlJettonWalletQuery 同款坑）——
            // catch(Exception) 拦不住它，必须兜住以维持「拿不到结果即 ChainUnavailableException」的契约
            throw new ChainUnavailableException("写链发送失败：" + e.getMessage(), e);
        }
    }

    /**
     * 部署托管合约（S5）：向 H(code,data) 派生地址发一条<b>携带 StateInit</b> 的钱包消息。
     *
     * <h2>组装机制（为什么走 body 注入而不是 recipients）</h2>
     * <p>ton4j 的 {@code Destination} 没有 stateInit 字段（它为"向已存在合约发消息"设计）。
     * 但 {@code createExternalTransferBody} 有一个兜底分支：{@code recipients == null} 时
     * 直接采用 {@code config.body} 为 inner request——本方法把
     * {@link EscrowDeployCodec#deployInnerRequest} 的产物（携带 StateInit 的 ActionSendMsg）
     * 从那个注入点送入。签名与广播链条与 {@link #sendToContract} 完全同构。
     *
     * <h2>地址自检（fail-closed）</h2>
     * <p>传入地址必须与 stateInit 推导一致——不一致意味着"部署到一个地址、链下记账到另一个"，
     * 真金会打水漂。此处直接拒绝，不交给链上碰运气。
     *
     * @param contractAddress 目标合约地址（friendly / raw；须与 stateInit 推导一致）
     * @param stateInit       合约 { code, data }（{@link EscrowCode} + {@link EscrowStorageCodec} 的产物）
     * @param valueNanoton    随部署附带的 TON（合约初始余额）
     */
    public void sendDeploy(String contractAddress, StateInit stateInit, long valueNanoton)
            throws ChainUnavailableException {
        if (contractAddress == null || contractAddress.isBlank()) {
            throw new ChainUnavailableException("部署：目标合约地址未提供");
        }
        if (stateInit == null) {
            throw new ChainUnavailableException("部署：stateInit 未提供");
        }
        Address declared;
        Address derived;
        try {
            declared = Address.of(contractAddress);
            derived = stateInit.getAddress();
        } catch (Exception | Error e) {
            throw new ChainUnavailableException("部署：地址解析失败：" + e.getMessage(), e);
        }
        if (declared.wc != derived.wc
                || !java.util.Arrays.equals(declared.hashPart, derived.hashPart)) {
            throw new ChainUnavailableException(
                    "部署：合约地址与 stateInit 推导不一致——拒绝向错误地址部署（链下记账会错位）");
        }
        try {
            // 自组装 v5r1 external：stateInit 直接进内部消息的 init（官方语义）；
            // 不再经 EscrowDeployCodec.deployInnerRequest（该路径随 ton4j 错误格式一并弃用）
            sendExternalMessage(derived, stateInit, null, valueNanoton, true);
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception | Error e) {
            throw new ChainUnavailableException("部署发送失败：" + e.getMessage(), e);
        }
    }

    /**
     * 自组装并广播一笔 v5r1 external 消息（{@link #sendToContract} / {@link #sendDeploy} 的公共下半场）。
     *
     * <p><b>为什么自组装（2026-10-02 首笔真机写链定案）</b>：ton4j 的
     * {@code createExternalTransferBody}/{@code prepareExternalMsg} 拼的是<b>另一种版本钱包</b>
     * 的 external 格式（首 32 位 magic 为 "send"；v5r1 规范为 "sign"）——真机表现为
     * <b>钱包静默拒收全部外发消息</b>（广播成功、链上无任何痕迹）。本方法用
     * {@link WalletV5R1MessageBuilder} 逐位复刻官方布局（金标准对拍：
     * {@code WalletV5R1MessageBuilderTest} 与官方 {@code @ton/ton} 同 hash），
     * 签名对象为 {@code signingMessage.hash()}。
     *
     * <p><b>钱包冷启动</b>：seqno 为 0（钱包未部署）时，external 附带钱包自身 stateInit——
     * 一条消息同时完成"部署钱包 + 转发内部消息"；seqno ≥ 1 时不再附（init 与链上既有
     * 状态不符会被拒）。此语义由 2026-10-02 真机核销确认（-256=未初始化 → 视作 0）。
     */
    private void sendExternalMessage(Address dst, StateInit init, Cell body, long valueNanoton,
                                     boolean bounce) throws ChainUnavailableException {
        try {
            long seqno = seqnoSource.currentSeqno();
            long validUntil = clock.instant().getEpochSecond() + VALIDITY.toSeconds();

            var info = InternalMessageInfoRelaxed.builder()
                    .bounce(bounce)
                    .dstAddr(MsgAddressIntStd.builder()
                            .workchainId((byte) dst.wc)
                            .address(dst.toBigInteger())
                            .build())
                    .value(CurrencyCollection.builder()
                            .coins(BigInteger.valueOf(valueNanoton))
                            .build())
                    .build();
            MessageRelaxed.MessageRelaxedBuilder msgBuilder = MessageRelaxed.builder()
                    .info(info)
                    .body(body == null ? CellBuilder.beginCell().endCell() : body);
            if (init != null) {
                msgBuilder.init(init);
            }
            var action = ActionSendMsg.builder()
                    .mode(3)   // PAY_GAS_SEPARATELY | IGNORE_ERRORS（v5r1 external 安全规则：须含 IGNORE_ERRORS）
                    .outMsg(msgBuilder.build())
                    .build();
            java.util.List<org.ton.ton4j.tlb.OutAction> actionList = List.of(action);
            Cell outList = OutList.builder().actions(actionList).build().toCell();
            Cell signingMessage = WalletV5R1MessageBuilder.signingMessage(
                    walletId, validUntil, seqno,
                    WalletV5R1MessageBuilder.outActionsSegment(outList));
            byte[] signature = signer.sign(signingMessage.hash());
            Cell externalBody = WalletV5R1MessageBuilder.externalBody(signingMessage, signature);

            Address walletAddress = wallet.getAddress();
            var extInfo = ExternalMessageInInfo.builder()
                    .dstAddr(MsgAddressIntStd.builder()
                            .workchainId((byte) walletAddress.wc)
                            .address(walletAddress.toBigInteger())
                            .build())
                    .build();
            Message.MessageBuilder extBuilder = Message.builder().info(extInfo).body(externalBody);
            if (seqno == 0) {
                extBuilder.init(wallet.getStateInit());
            }
            broadcaster.broadcast(extBuilder.build());
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception | Error e) {
            throw new ChainUnavailableException("写链发送失败：" + e.getMessage(), e);
        }
    }

    /**
     * 本发送器使用的钱包地址（诊断/自检入口）。
     *
     * <p>用途：核销或排障时把该值与钱包客户端（acton / Tonkeeper）显示的地址对比——
     * 不等即说明 {@code wallet-id} 配错或 v5r1 参数未对齐，此时任何签名都会被钱包拒收。
     * 地址由"公钥 + wallet_id + isSigAuthAllowed(true)"推出（见 {@link ChainWalletKeys} 的公式注释）。
     */
    public Address walletAddress() {
        return wallet.getAddress();
    }

    @Override
    public void close() {
        // 仅释放本类自有的连接（forNetwork 装配）；替身/外部持有连接的构造不在此关闭
        if (ownedPool != null) {
            ownedPool.close();
        } else if (ownedClient != null) {
            ownedClient.close();
        }
    }
}
