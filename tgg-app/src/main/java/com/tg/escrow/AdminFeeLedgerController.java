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
package com.tg.escrow;

import com.tg.escrow.escrow.FeeLedgerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API（S6）：费用台账查询（ET-29/30）——对账用（最近 100 条，新到旧）。
 *
 * <p><b>金额为字符串</b>（{@code toPlainString}）：机器消费出口保留精确精度，
 * 不比照面向用户的 MoneyFormat 去尾零——对账应按字核对。
 *
 * <p><b>鉴权</b>：同其余 {@code /admin/**} 端点——需 HTTP Basic，
 * 未配置 {@code tgg.admin.*} 凭据时一律 401；部署侧 Nginx 的 {@code deny all} 是第一道门。
 */
@RestController
@RequestMapping("/admin/fee-ledger")
public class AdminFeeLedgerController {

    /** 对账视图项（金额为精确字符串）。 */
    public record FeeLedgerItem(long orderId, String currency, String grossAmount,
                                String platformFee, String sellerNet, boolean firstOrderWaived,
                                String recordedAt) {
    }

    private final FeeLedgerRepository repository;

    public AdminFeeLedgerController(FeeLedgerRepository repository) {
        if (repository == null) {
            throw new com.tg.escrow.common.TggException("Admin API：费用台账仓库不可为空");
        }
        this.repository = repository;
    }

    /** 最近 100 条放款账目（新到旧）。 */
    @GetMapping
    public List<FeeLedgerItem> list() {
        return repository.findTop100ByOrderByIdDesc().stream()
                .map(row -> new FeeLedgerItem(row.getOrderId(), row.getCurrency(),
                        row.getGrossAmount() == null ? null : row.getGrossAmount().toPlainString(),
                        row.getPlatformFee() == null ? null : row.getPlatformFee().toPlainString(),
                        row.getSellerNet() == null ? null : row.getSellerNet().toPlainString(),
                        row.isFirstOrderWaived(),
                        row.getRecordedAt() == null ? null : row.getRecordedAt().toString()))
                .toList();
    }
}
