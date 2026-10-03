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

import org.ton.ton4j.cell.Cell;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 托管合约编译产物的加载（S5 部署）：从 classpath 资源读取 code BOC 并校验 hash。
 *
 * <h2>为什么把 code 放进资源而不是配置</h2>
 * <p>code cell 必须与 {@link EscrowStorageCodec} 的编码布局<b>同版本</b>——两者一起随
 * 代码库演进（改合约 = 两份产物一起换）。放进资源使"换 code 忘换 Java 版"和
 * "换 Java 版忘换 code"都变成编译/测试期可见的问题；放进外部配置则部署者多一个
 * 静默配错的机会。
 *
 * <h2>hash 校验（防静默漂移）</h2>
 * <p>资源目录另存 {@code EscrowContract.hash}（编译时 code hash 的 hex）。加载时
 * <b>实测 hash 与记录不符即抛</b>——而不是等到真机上地址对不上链下记录才发现。
 *
 * <h2>资源同步流程</h2>
 * <p>合约变更后（{@code ~/.acton/bin/acton build}）从 {@code build/EscrowContract.json}
 * 重新导出两个资源文件：
 * <pre>
 * python3 -c "import json; d=json.load(open('build/EscrowContract.json')); \
 *   open('tgg-chain/src/main/resources/escrow/EscrowContract.code.b64','w').write(d['code_boc64']); \
 *   open('tgg-chain/src/main/resources/escrow/EscrowContract.hash','w').write(d['hash'])"
 * </pre>
 * 忘了同步时：{@link #escrowCodeCell()} 抛"资源与 hash 记录不一致"（改动过合约的场景），
 * 或资源加载单测红（改动过资源文件的场景）。
 */
public final class EscrowCode {

    private static final String CODE_RESOURCE = "/escrow/EscrowContract.code.b64";
    private static final String HASH_RESOURCE = "/escrow/EscrowContract.hash";

    private EscrowCode() {
    }

    /**
     * 托管合约的 code cell（含 hash 校验）。
     *
     * @throws ChainUnavailableException 资源缺失 / BOC 非法 / hash 与记录不符
     */
    public static Cell escrowCodeCell() {
        String b64 = readTrimmed(CODE_RESOURCE);
        String expectedHash = readTrimmed(HASH_RESOURCE);

        Cell code;
        try {
            code = Cell.fromBoc(Base64.getDecoder().decode(b64));
            if (code == null) {
                throw new ChainUnavailableException("托管合约 code 资源解析为空");
            }
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception | Error e) {
            // ton4j 对非法 BOC 可能抛裸 Error——一并兜住
            throw new ChainUnavailableException(
                    "托管合约 code 资源解析失败：" + e.getMessage(), e);
        }

        String actualHash = hex(code.hash());
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new ChainUnavailableException(
                    "托管合约 code 资源与 hash 记录不一致（实测 " + actualHash
                            + "，记录 " + expectedHash + "）——重跑 acton build 并同步两个资源文件");
        }
        return code;
    }

    private static String readTrimmed(String resource) {
        try (InputStream in = EscrowCode.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ChainUnavailableException("托管合约资源缺失：" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("托管合约资源读取失败：" + resource, e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
