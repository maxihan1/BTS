// Inbox 조회 전용 응답 DTO — 미읽음 카운트 및 일괄읽음 결과

package com.bts.notification.inbox.web.dto

/**
 * 미읽음 알림 카운트 응답.
 *
 * GET /api/v1/users/me/inbox/unread-count 응답 페이로드.
 *
 * @param count 미읽음 IN_APP 알림 수
 */
data class UnreadCountResponse(val count: Long)

/**
 * 일괄 읽음 처리 결과 응답.
 *
 * POST /api/v1/users/me/inbox/read-all 응답 페이로드.
 *
 * @param updated 읽음 처리된 알림 수
 */
data class ReadAllResponse(val updated: Int)
