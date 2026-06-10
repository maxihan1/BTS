// 인증 감사 로그 관리자 전역 조회 포트 + read model — 필터/페이지네이션 검색 (FR-AU-10 D6/D7)

package com.atlas.bts.identity.audit

import java.time.Instant
import java.util.UUID

/**
 * 관리자 전역 조회용 감사 로그 단건 read model.
 *
 * self-service [AuthAuditLog] 와 달리 users JOIN 으로 [username]/[displayName] 을 함께 노출한다
 * (관리자가 사용자 식별을 화면에서 바로 할 수 있도록). user_id 가 null 이거나 users 에 미존재하면 둘 다 null.
 *
 * deviceFingerprint 는 현재 미사용이므로 read model 에서 제외한다 (V021 컬럼은 FR-MF-05 대비 보존).
 *
 * @property id auth_audit_logs PK(BIGINT, 단조 증가 — 정렬 tiebreaker).
 * @property userId 행위 주체. null = 사용자 미상(LOGIN_FAILURE/LDAP_UNAVAILABLE 등).
 * @property username users JOIN 결과. user_id null·미존재 시 null.
 * @property displayName users JOIN 결과. user_id null·미존재 시 null.
 */
data class AuthAuditLogAdminEntry(
    val id: Long,
    val userId: UUID?,
    val username: String?,
    val displayName: String?,
    val eventType: AuthEventType,
    val providerId: String,
    val ipAddress: String?,
    val userAgent: String?,
    val metadata: Map<String, String>,
    val createdAt: Instant,
)

/**
 * 관리자 조회 한 페이지 결과.
 *
 * @property items 현재 페이지 행(최신순).
 * @property totalElements 필터 적용 후 전체 건수(페이지 무관). totalPages 계산은 web 레이어 책임.
 */
data class AuthAuditLogAdminPage(
    val items: List<AuthAuditLogAdminEntry>,
    val totalElements: Long,
)

/**
 * 감사 로그 검색 조건.
 *
 * 모든 필터는 nullable 이며 비-null 항목만 WHERE 절에 AND 로 결합된다.
 * 시간 범위 [from]/[to] 는 경계를 포함(`>=` / `<=`)한다.
 *
 * @property page 0-based 페이지 번호.
 * @property size 페이지 크기(LIMIT). offset = page * size.
 */
data class AuthAuditLogSearchCriteria(
    val eventType: AuthEventType? = null,
    val userId: UUID? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val page: Int = 0,
    val size: Int = 50,
)

/**
 * 인증 감사 로그 관리자 전역 조회 포트 (FR-AU-10 D6/D7).
 *
 * self-service [AuthAuditLogService.findRecent](user-scoped)로는 불가한 전역 조회를 담당한다.
 * 마이그레이션 0 — 기존 auth_audit_logs(V021) 테이블/인덱스(event_type / created_at / (user_id, created_at))만 사용한다.
 */
interface AuthAuditLogAdminQueryRepository {
    /**
     * [criteria] 에 맞는 감사 로그를 최신순(created_at DESC, id DESC)으로 한 페이지 조회한다.
     *
     * 반환된 [AuthAuditLogAdminPage.totalElements] 는 페이지네이션과 무관한 필터 전체 건수다.
     */
    fun search(criteria: AuthAuditLogSearchCriteria): AuthAuditLogAdminPage
}
