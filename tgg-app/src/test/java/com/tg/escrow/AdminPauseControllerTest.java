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

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 紧急暂停操作面端点（ET-21）的行为固定测试——每个分支钉住「哪个状态码 + 有没有发消息」。
 */
class AdminPauseControllerTest {

    private static final String ADDR = "EQAVmZ-escrow-contract-address";

    @Test
    @DisplayName("缺合约地址 → 400，零消息")
    void missingAddressRejected() {
        ChainGateway chain = mock(ChainGateway.class);
        AdminPauseController controller = new AdminPauseController(chain);

        ResponseEntity<Map<String, Object>> resp = controller.pause(
                new AdminPauseController.PauseActionRequest("  "));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chain, never()).pause(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("pause：地址合法 → 200 且发 PauseMessage（去首尾空白）")
    void pauseSendsMessage() throws ChainUnavailableException {
        ChainGateway chain = mock(ChainGateway.class);
        AdminPauseController controller = new AdminPauseController(chain);

        ResponseEntity<Map<String, Object>> resp = controller.pause(
                new AdminPauseController.PauseActionRequest(" " + ADDR + " "));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ok", true);
        verify(chain).pause(ADDR);
    }

    @Test
    @DisplayName("unpause：地址合法 → 200 且发 UnpauseMessage")
    void unpauseSendsMessage() throws ChainUnavailableException {
        ChainGateway chain = mock(ChainGateway.class);
        AdminPauseController controller = new AdminPauseController(chain);

        ResponseEntity<Map<String, Object>> resp = controller.unpause(
                new AdminPauseController.PauseActionRequest(ADDR));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chain).unpause(ADDR);
    }

    @Test
    @DisplayName("写链栈未接线 → 503（「发不出去」绝不回报成功）")
    void unavailableIsServiceUnavailable() throws ChainUnavailableException {
        ChainGateway chain = mock(ChainGateway.class);
        doThrow(new ChainUnavailableException("升级/写链钱包未配置"))
                .when(chain).pause(ADDR);
        AdminPauseController controller = new AdminPauseController(chain);

        ResponseEntity<Map<String, Object>> resp = controller.pause(
                new AdminPauseController.PauseActionRequest(ADDR));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("未接线");
    }

    @Test
    @DisplayName("构造：链网关不可为空")
    void chainRequired() {
        assertThatThrownBy(() -> new AdminPauseController(null))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
