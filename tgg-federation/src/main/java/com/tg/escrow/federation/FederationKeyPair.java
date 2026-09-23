package com.tg.escrow.federation;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.util.Base64;

/**
 * 联邦节点的 Ed25519 密钥对，以及签名 / 验签。
 *
 * <h2>为什么密钥要从 seed 确定性恢复</h2>
 * <p>配置里只注入一个 32 字节 seed，公钥由它算出。这样不存在「私钥与公钥配置不一致」
 * 这种只能在验签失败时才暴露的故障——两者在构造期就绑定了。
 *
 * <h2>职责边界</h2>
 * <p>本类只做密码学原语，<b>不判断「谁能裁决」</b>——那是
 * {@code EscrowMultiSigRule} 的职责。分工的理由：规则是纯逻辑（可穷举测试），
 * 而密码学依赖实现库（只能靠已知向量与往返测试验证）。两者混在一起会互相拖累测试。
 *
 * <h2>签名载荷是什么</h2>
 * <p>本类对任意字节数组签名，不假设载荷结构。把「裁决内容」规范序列化成字节
 * 是调用方的责任——且序列化必须<b>无歧义</b>（否则同一裁决可能有两种字节表示，
 * 签名就对不上了）。
 */
public final class FederationKeyPair {

    /** Ed25519 私钥种子长度（RFC 8032）。 */
    public static final int SEED_LENGTH = 32;

    /** Ed25519 公钥长度（raw 编码）。 */
    private static final int PUBLIC_KEY_LENGTH = 32;

    private final Ed25519PrivateKeyParameters privateKey;
    private final Ed25519PublicKeyParameters publicKey;

    private FederationKeyPair(Ed25519PrivateKeyParameters privateKey,
                              Ed25519PublicKeyParameters publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    /**
     * 从 32 字节 seed 恢复密钥对。
     *
     * <p>长度不对或未提供即抛异常——密钥材料的问题必须拦在构造期，
     * 不能延迟到第一次签名时才暴露（那时可能已经在处理真实裁决）。
     */
    public static FederationKeyPair fromSeed(byte[] seed) {
        if (seed == null) {
            throw new FederationException("联邦节点 seed 未提供");
        }
        if (seed.length != SEED_LENGTH) {
            throw new FederationException(
                    "联邦节点 seed 长度应为 " + SEED_LENGTH + " 字节，实为 " + seed.length);
        }
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
        return new FederationKeyPair(priv, priv.generatePublicKey());
    }

    /** 从 Base64 编码的 seed 恢复（配置注入的实际入口）。 */
    public static FederationKeyPair fromSeedBase64(String seedBase64) {
        if (seedBase64 == null || seedBase64.isBlank()) {
            throw new FederationException("联邦节点 seed(Base64) 未提供");
        }
        byte[] seed;
        try {
            seed = Base64.getDecoder().decode(seedBase64.trim());
        } catch (IllegalArgumentException ex) {
            throw new FederationException("联邦节点 seed 不是合法 Base64", ex);
        }
        return fromSeed(seed);
    }

    /** 公钥的 Base64（写入对端节点清单的格式）。 */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /** 用本节点私钥签名。 */
    public byte[] sign(byte[] payload) {
        if (payload == null) {
            throw new FederationException("待签名载荷未提供");
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(payload, 0, payload.length);
        return signer.generateSignature();
    }

    /** 用本节点公钥验签。 */
    public boolean verify(byte[] payload, byte[] signature) {
        return verify(payload, signature, publicKey);
    }

    /**
     * 用给定的公钥 Base64 独立验签——<b>接收方的入口</b>。
     *
     * <p>与 {@link #sign} 不同，这里的输入来自外部（对端节点清单、网络传来的签名），
     * 因此任何格式问题都返回 {@code false} 而<b>不抛异常</b>：验签失败是正常业务结果
     * （应回执"签名不通过"），不是程序错误——若它抛异常，调用方很容易用 catch 吞掉，
     * 从而把"验签失败"变成"静默放行"。
     */
    public static boolean verify(byte[] payload, byte[] signature, String publicKeyBase64) {
        if (payload == null || signature == null || publicKeyBase64 == null
                || publicKeyBase64.isBlank()) {
            return false;
        }
        byte[] publicKeyBytes;
        try {
            publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (publicKeyBytes.length != PUBLIC_KEY_LENGTH) {
            return false;
        }
        try {
            return verify(payload, signature,
                    new Ed25519PublicKeyParameters(publicKeyBytes, 0));
        } catch (RuntimeException ex) {
            // 公钥字节长度正确但内容非法（非曲线上的点）时，BC 可能抛异常
            return false;
        }
    }

    private static boolean verify(byte[] payload, byte[] signature,
                                  Ed25519PublicKeyParameters publicKey) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, publicKey);
        signer.update(payload, 0, payload.length);
        return signer.verifySignature(signature);
    }
}
