package com.tg.escrow.chain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 余额交叉验证器——把"读链上余额"从一次查询变成一次<b>取证</b>。
 *
 * <h2>为什么必须有这一层</h2>
 * <p>担保交易的每一步都由链上资金状态驱动：钱到了才发货、钱释放了才结单。若读取接口
 * 只返回一个裸布尔，那么"资金是否到位"这件事就把全部信任押在了单一数据源上——
 * 一个可以被限流、被缓存、被网络中间层篡改、甚至自身出错的第三方。
 *
 * <p>本类的做法是：同一事实至少问<b>两个独立来源</b>，一致才采信；并在返回值里
 * 留下证据（谁确认的、确认多深），使事后可复核。
 *
 * <h2>三条纪律（对应三个异常）</h2>
 * <ol>
 *   <li>可用源少于 2 → {@link ChainUnavailableException}（无法交叉）；</li>
 *   <li>源之间余额矛盾 → {@link ChainDisagreementException}，<b>不选多数、不取最大、
 *       不重试</b>——分歧说明至少一个源不可信，而此刻无法判断是哪一个；</li>
 *   <li>一致但确认深度不足 → {@link ChainNotFinalizedException}（等待，而非报故障）。</li>
 * </ol>
 *
 * <h2>刻意的取舍：容忍单源故障</h2>
 * <p>三个源里挂掉一个，剩下两个一致即可继续——可用性不能因为冗余而下降。
 * 但两个源里挂掉一个就不行：只剩单源时，"一致"无从谈起。这条边界是"容忍故障、
 * 不容忍独断"。
 *
 * <h2>余额比较用 {@code compareTo} 而非 {@code equals}</h2>
 * <p>{@code new BigDecimal("1.0").equals(new BigDecimal("1.00"))} 为 {@code false}——
 * 标度不同。若用 {@code equals}，两个源对同一个余额报出不同标度就会被判成"分歧"，
 * 而那是纯粹的表示差异，不是事实矛盾。资金系统里这类误报会让真分歧被淹没在噪声里。
 */
public final class BalanceCrossVerifier {

    /** 交叉验证所需的最少可用源数。 */
    public static final int MIN_SOURCES = 2;

    private BalanceCrossVerifier() {
    }

    /**
     * 读取并交叉验证某地址的余额。
     *
     * @param address              链上地址
     * @param sources              数据源列表（自建 liteserver 与公共 RPC 都可）
     * @param requiredConfirmations 要求的最小确认深度
     * @return 经交叉验证的结论（携带来源与确认数证据）
     * @throws ChainUnavailableException 可用源不足
     * @throws ChainDisagreementException 源之间余额矛盾
     * @throws ChainNotFinalizedException 一致但确认深度不足
     */
    public static VerifiedBalance read(String address, List<ChainSource> sources,
                                       int requiredConfirmations) {
        if (address == null || address.isBlank()) {
            throw new ChainUnavailableException("未提供链上地址");
        }
        if (requiredConfirmations < 0) {
            throw new ChainUnavailableException(
                    "确认数门槛为负（" + requiredConfirmations + "）——调用方计算有误，不应静默放行");
        }
        if (sources == null || sources.isEmpty()) {
            throw new ChainUnavailableException("未提供任何链上数据源");
        }

        List<BalanceObservation> available = new ArrayList<>();
        for (ChainSource source : sources) {
            try {
                available.add(source.observeBalance(address));
            } catch (ChainUnavailableException ex) {
                // 该源不可用：排除它，其余源继续。刻意不在这里记日志——
                // 日志由调用方在编排层统一处理，避免同一事件被打多份。
            }
            // 其余异常不捕获：实现 bug 应向上暴露（见 ChainSource 的契约）
        }

        if (available.size() < MIN_SOURCES) {
            throw new ChainUnavailableException("可用源不足，无法交叉验证：需要至少 "
                    + MIN_SOURCES + " 个，实为 " + available.size());
        }

        BigDecimal agreed = available.get(0).balance();
        for (BalanceObservation observation : available) {
            if (observation.balance().compareTo(agreed) != 0) {
                throw new ChainDisagreementException("多源余额不一致，拒绝采信：" + describe(available));
            }
        }

        int minConfirmations = available.stream()
                .mapToInt(BalanceObservation::confirmations)
                .min()
                .orElseThrow();
        if (minConfirmations < requiredConfirmations) {
            throw new ChainNotFinalizedException("确认数不足：多源最小确认 " + minConfirmations
                    + "，要求 " + requiredConfirmations);
        }

        // 最晚观测时刻：表示「截至此刻，该结论仍成立」
        Instant latest = available.stream()
                .map(BalanceObservation::observedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Set<String> sourceNames = available.stream()
                .map(BalanceObservation::source)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        return new VerifiedBalance(agreed, sourceNames, minConfirmations, latest);
    }

    private static String describe(List<BalanceObservation> observations) {
        return observations.stream()
                .map(o -> o.source() + "=" + o.balance().toPlainString() + "@" + o.confirmations())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
