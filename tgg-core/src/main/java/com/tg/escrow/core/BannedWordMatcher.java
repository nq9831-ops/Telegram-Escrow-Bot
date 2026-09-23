package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 违禁词匹配（原文档 G5）。
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li><b>两种规则</b>：精确词（子串命中）与正则（{@code find} 语义）。精确词优先返回——
 *       顺序稳定才可能复现判定结果。</li>
 *   <li><b>大小写不敏感</b>：大小写变换是最廉价的规避手段（{@code Spam} / {@code SPAM} /
 *       {@code sPaM}）。用 {@link Locale#ROOT} 做折叠而非默认 locale——土耳其语环境下
 *       {@code I} 的小写是 {@code ı}，用默认 locale 会让判定结果随部署机器的语言变化。</li>
 *   <li><b>子串命中，不要求词边界</b>：中文没有词边界概念，而"加微信"这类违禁词
 *       必然出现在句中。代价是英文短词会误伤（{@code ass} 命中 {@code class}）——
 *       这是<b>词表编写</b>需要承担的责任，不是匹配器的。匹配器把它做成确定行为并测试钉住，
 *       避免"有时命中有时不命中"。</li>
 *   <li><b>空白条目一律忽略</b>：空串做违禁词会命中一切文本——这是配置疏忽里后果最严重的一种。</li>
 * </ul>
 *
 * <h2>未做（有意）</h2>
 * <p>未实现 Trie / Aho-Corasick。当前是逐词 {@code contains}，词表规模上千时会有性能压力；
 * 届时替换内部结构即可，公开行为（本类的测试）不变。过早做多模式匹配会把接口绑死在
 * 一种实现上，而具体阈值还没有负载数据支撑。
 *
 * <p>正则长度上限 {@value #MAX_REGEX_LENGTH} 字符用于限制灾难性回溯（ReDoS）的入口——
 * 正则来自群配置，属不可信输入。这不是完整防护（真正的防护需要执行超时），
 * 但拦住了"一条超长正则拖垮整个进程"这一类。
 */
public final class BannedWordMatcher {

    /** 单条正则的长度上限。见类注释关于 ReDoS 的说明。 */
    public static final int MAX_REGEX_LENGTH = 500;

    /** 命中规则的类型。 */
    public enum Kind {
        /** 精确词命中。 */
        EXACT,
        /** 正则命中。 */
        REGEX
    }

    /**
     * 命中结果。
     *
     * @param rule 命中的规则原文（精确词为其配置原文，正则为模式字符串）
     * @param kind 规则类型
     */
    public record Match(String rule, Kind kind) {
    }

    /** 精确词：原文（用于返回）+ 折叠后（用于比较）。 */
    private record ExactEntry(String source, String folded) {
    }

    private final List<ExactEntry> exactWords;
    private final List<Pattern> regexPatterns;
    private final List<String> regexSources;

    private BannedWordMatcher(List<ExactEntry> exactWords,
                              List<Pattern> regexPatterns,
                              List<String> regexSources) {
        this.exactWords = exactWords;
        this.regexPatterns = regexPatterns;
        this.regexSources = regexSources;
    }

    /**
     * 编译词表与正则表。
     *
     * <p>正则在此处一次性编译——非法正则必须拦在构造期：等到第一条消息进来才抛，
     * 意味着机器人已经上线、且那条消息会被静默放过。
     *
     * @param exactWords    精确词表，可为 {@code null}（视为空）
     * @param regexPatterns 正则表，可为 {@code null}（视为空）
     */
    public static BannedWordMatcher compile(List<String> exactWords, List<String> regexPatterns) {
        List<ExactEntry> words = new ArrayList<>();
        if (exactWords != null) {
            for (String raw : exactWords) {
                if (isBlank(raw)) {
                    continue; // 空白词条忽略：空串若入表会命中一切文本
                }
                String trimmed = raw.trim();
                words.add(new ExactEntry(trimmed, fold(trimmed)));
            }
        }

        List<Pattern> patterns = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        if (regexPatterns != null) {
            for (String raw : regexPatterns) {
                if (isBlank(raw)) {
                    continue;
                }
                String source = raw.trim();
                if (source.length() > MAX_REGEX_LENGTH) {
                    throw new TggException("违禁词正则过长（上限 " + MAX_REGEX_LENGTH
                            + " 字符，实为 " + source.length() + "）");
                }
                try {
                    patterns.add(Pattern.compile(source));
                    sources.add(source);
                } catch (PatternSyntaxException ex) {
                    throw new TggException("违禁词正则非法：" + source, ex);
                }
            }
        }

        return new BannedWordMatcher(List.copyOf(words), List.copyOf(patterns), List.copyOf(sources));
    }

    /**
     * 返回第一个命中的规则。
     *
     * <p>无命中返回 {@link Optional#empty()}——这是绝大多数消息的正常结局，不是异常。
     * {@code text} 为 {@code null} 或空同样返回空：非文本消息是常态。
     */
    public Optional<Match> firstMatch(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        String folded = fold(text);

        for (ExactEntry entry : exactWords) {
            if (folded.contains(entry.folded())) {
                return Optional.of(new Match(entry.source(), Kind.EXACT));
            }
        }

        for (int i = 0; i < regexPatterns.size(); i++) {
            if (regexPatterns.get(i).matcher(text).find()) {
                return Optional.of(new Match(regexSources.get(i), Kind.REGEX));
            }
        }

        return Optional.empty();
    }

    /** 是否命中任一规则。 */
    public boolean matches(String text) {
        return firstMatch(text).isPresent();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String fold(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
