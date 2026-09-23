package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

/**
 * 入群申请时可获得的信号。
 *
 * <p><b>这里只有能从 Telegram 侧客观取得的字段</b>，没有"行为可疑度"这类需要推断的东西。
 * 保持输入面窄有两个好处：评分可复现（同样的输入必得同样的分），以及不引入
 * 无法解释的因子——当用户申诉"为什么拒绝我"时，每个因子都能指向一个具体事实。
 *
 * @param hasAvatar      是否有头像
 * @param hasUsername    是否设置了用户名
 * @param accountAgeDays 账号年龄（天）
 * @param languageCode   语言代码（可为 {@code null}，表示未知）
 */
public record JoinSignals(boolean hasAvatar, boolean hasUsername, int accountAgeDays,
                          String languageCode) {

    public JoinSignals {
        if (accountAgeDays < 0) {
            throw new TggException("账号年龄为负（" + accountAgeDays + "）——信号采集有误");
        }
    }
}
