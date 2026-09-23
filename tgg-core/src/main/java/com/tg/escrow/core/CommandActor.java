package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

/**
 * 命令执行者。
 *
 * <p>只带两个字段：用户 ID 与其<b>在当前群</b>内的角色。
 * 刻意不带"是否联邦管理员"——那是全局属性，由 {@link PermissionPolicy} 的白名单判定。
 * 放进来会让人误以为角色与白名单是同一层的东西，而实际上
 * <b>白名单是全局的、角色是群内的</b>，两者维度不同。
 *
 * @param userId 用户在 Telegram 的 ID
 * @param role   该用户在本群内的角色
 */
public record CommandActor(long userId, MemberRole role) {

    public CommandActor {
        if (role == null) {
            // 不做「未知角色按最低权限处理」的推断：角色拿不到意味着上游取数据出错，
            // 静默降级会让人以为命令"没反应"而不是"出错了"。
            throw new TggException("命令执行者的角色未提供（userId=" + userId + "）");
        }
    }
}
