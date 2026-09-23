package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

import java.util.List;
import java.util.Optional;

/**
 * 解析后的 Bot 命令。
 *
 * @param name 命令名（已归一化为小写，不含前导斜杠与 {@code @botname} 后缀）
 * @param args 参数列表（按空白切分，保持原始大小写）
 */
public record BotCommand(String name, List<String> args) {

    public BotCommand {
        if (name == null || name.isBlank()) {
            throw new TggException("命令名未提供");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }

    /**
     * 按位置取可选参数。
     *
     * <p>越界返回 {@link Optional#empty()} 而不抛异常：命令的后几个参数往往是可选的，
     * 让每个调用方都写边界检查会把"缺参数"和"代码写错"混在一起。
     */
    public Optional<String> argOpt(int index) {
        if (index < 0 || index >= args.size()) {
            return Optional.empty();
        }
        return Optional.of(args.get(index));
    }

    /** 参数个数。 */
    public int argCount() {
        return args.size();
    }
}
