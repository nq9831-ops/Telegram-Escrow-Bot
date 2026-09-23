package com.tg.escrow.escrow;

import com.tg.escrow.escrow.EscrowMultiSigRule.Party;

/**
 * 单方签名验证器——把"某一方的签名是否有效"这件事抽象出来。
 *
 * <p><b>为什么是接口</b>：三方签名的产生方式<b>根本不同</b>——
 * <ul>
 *   <li>联邦仲裁节点：服务端持有的 Ed25519 密钥，
 *       由 {@code tgg-federation} 的 {@code FederationKeyPair} 验证；</li>
 *   <li>买方 / 卖方：钱包产生（{@code ton_proof} / TON Connect），
 *       验证依赖链上环境，属 {@code tgg-chain} 的职责。</li>
 * </ul>
 * 若不抽象，裁决守卫就得同时依赖两套验证实现，而链上那套现在还不存在——
 * 结果是整个裁决链路无法测试。抽成接口后，规则与编排可以在<b>纯本地</b>被穷举测试，
 * 链上实现只替换这一个注入点。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li>验签失败返回 {@code false}，<b>不要抛异常</b>——失败是正常业务结果；</li>
 *   <li>不得假设 {@code payload} 或 {@code signature} 非空（调用方会做防御性检查，
 *       但实现也不应因空值崩溃）。</li>
 * </ul>
 */
@FunctionalInterface
public interface PartySignatureVerifier {

    /**
     * @param party     该签名声称的签署方
     * @param payload   被签名的内容（即 {@link EscrowVerdict#canonicalBytes()}）
     * @param signature 签名值
     * @return {@code true} = 验签通过
     */
    boolean verify(Party party, byte[] payload, byte[] signature);
}
