// Inbox 상태 변경 요청 DTO 모음 — 읽음/보관/일괄읽음 요청 바디 정의

package com.bts.notification.inbox.web.dto

import java.util.UUID

/**
 * 알림 읽음 상태 변경 요청.
 *
 * PATCH /api/v1/users/me/inbox/{id}/read 요청 바디.
 *
 * @param read true = 읽음 처리, false = 미읽음 처리
 */
data class ReadRequest(val read: Boolean)

/**
 * 알림 보관 상태 변경 요청.
 *
 * PATCH /api/v1/users/me/inbox/{id}/archive 요청 바디.
 *
 * @param archived true = 보관함에 추가, false = 보관함에서 제거
 */
data class ArchiveRequest(val archived: Boolean)

/**
 * 알림 일괄 읽음 처리 요청.
 *
 * POST /api/v1/users/me/inbox/read-all 요청 바디.
 *
 * @param ids 읽음 처리할 알림 ID 목록. null 또는 빈 목록이면 미읽음 전체를 읽음 처리한다.
 */
data class ReadAllRequest(val ids: List<UUID>? = null)
