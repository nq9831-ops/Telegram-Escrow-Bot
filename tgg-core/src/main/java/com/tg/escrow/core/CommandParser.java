package com.tg.escrow.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Telegram 命令解析器——纯函数，不依赖 Telegram API。
 *
 * <p>把它抽成纯函数的原因是<b>可测性</b>：命令解析是 Bot 的入口，一旦它错了，
 * 后面的权限校验、参数处理全都建立在错的前提上。而直连 Telegram 的解析器只能靠
 * 手工发消息验证，覆盖不到边界。
 *
 * <h2>三处需要留意的 Telegram 细节</h2>
 * <ol>
 *   <li><b>{@code @botname} 后缀</b>：群里输入命令时客户端可能自动补成 {@code /ban@mybot}。
 *       而且一个群可以同时存在多个 bot——发给别人的命令必须<b>不响应</b>，
 *       否则会与对方 bot 的行为打架。</li>
 *   <li><b>命令名长度上限 32 字符</b>（Telegram 的规定）。超长的只可能不是命令。</li>
 *   <li><b>不是 shell</b>：不做引号解析、不做转义。消息里的引号就是普通字符——
 *       按 shell 规则拆分会造成"用户以为能引用、实际不能"的错位预期。</li>
 * </ol>
 *
 * <h2>宽容与严格的边界</h2>
 * <p>宽容处：容忍前导空白、多个连续空白、命令名大小写。
 * 严格处：非 {@code /} 开头一律不解析；参数<b>保持原样</b>不做大小写折叠
 * （用户 ID、封禁理由都不该被改）。
 */
public final class CommandParser {

    /** Telegram 命令名的长度上限。 */
    public static final int MAX_COMMAND_NAME_LENGTH = 32;

    private CommandParser() {
    }

    /**
     * 解析命令，不校验 {@code @botname}。
     *
     * <p>带 {@code @} 后缀的命令在此重载下<b>一律不解析</b>——因为无从判断是否该由本 bot 处理。
     */
    public static Optional<BotCommand> parse(String text) {
        return parse(text, null);
    }

    /**
     * 解析命令，并在带 {@code @botname} 后缀时校验是否发给本 bot。
     *
     * @param text                消息文本
     * @param expectedBotUsername 本 bot 的用户名（不含 {@code @}）；为 {@code null} 时表示"不认领任何 @ 后缀命令"
     * @return 解析结果；非命令、或 {@code @} 后缀指向别的 bot 时返回空
     */
    public static Optional<BotCommand> parse(String text, String expectedBotUsername) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String trimmed = text.trim();
        if (!trimmed.startsWith("/")) {
            return Optional.empty();
        }

        String[] parts = trimmed.split("\\s+");
        String token = parts[0].substring(1); // 去掉前导 '/'
        if (token.isEmpty()) {
            // 只有斜杠，或 "/ 123" 这种——没有命令名
            return Optional.empty();
        }

        String name = token;
        int atIndex = token.indexOf('@');
        if (atIndex >= 0) {
            name = token.substring(0, atIndex);
            String suffix = token.substring(atIndex + 1);
            // 群里可能有多个 bot：发给别人的命令不该由我处理
            if (expectedBotUsername == null || expectedBotUsername.isBlank()
                    || !suffix.equalsIgnoreCase(expectedBotUsername.trim())) {
                return Optional.empty();
            }
        }

        if (name.isEmpty()
                || name.length() > MAX_COMMAND_NAME_LENGTH
                || !isValidName(name)) {
            return Optional.empty();
        }

        List<String> args = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                args.add(parts[i]);
            }
        }

        return Optional.of(new BotCommand(name.toLowerCase(Locale.ROOT), args));
    }

    /** 命令名只允许字母、数字与下划线（Telegram 实际支持的就是这些）。 */
    private static boolean isValidName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
