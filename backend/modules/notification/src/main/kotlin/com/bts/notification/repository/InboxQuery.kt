// Inbox 목록 조회 조건 VO — 탭/검색/발신자/이슈키/기간 AND 결합 쿼리 파라미터 봉투

package com.bts.notification.repository

import java.time.Instant
import java.util.UUID

/**
 * Inbox 목록 조회 탭 구분.
 *
 * DB 조건으로의 매핑.
 * - ALL       — archived_at IS NULL (보관 안 된 전체, 읽음 무관)
 * - UNREAD    — read_at IS NULL AND archived_at IS NULL
 * - ARCHIVED  — archived_at IS NOT NULL
 */
enum class InboxTab {
    ALL,
    UNREAD,
    ARCHIVED,
}

/**
 * Inbox 목록 조회 조건 VO.
 *
 * 모든 필드는 optional. 존재하는 필드만 AND 결합으로 SQL 조건에 추가된다.
 * 탭과 검색 조건은 함께 적용 가능하다.
 *
 * @param tab     탭 구분 — 기본값 ALL
 * @param q       제목 부분일치 검색 (title ILIKE %q%)
 * @param senderId 발신자 UUID (actor_user_id 일치)
 * @param issueKey 이슈 키 문자열 (issue_key 일치)
 * @param from    생성 시각 하한 (created_at >= from)
 * @param to      생성 시각 상한 (created_at <= to)
 */
data class InboxQuery(
    val tab: InboxTab = InboxTab.ALL,
    val q: String? = null,
    val senderId: UUID? = null,
    val issueKey: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)
