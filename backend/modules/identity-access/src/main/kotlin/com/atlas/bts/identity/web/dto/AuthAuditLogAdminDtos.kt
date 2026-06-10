// 감사 로그 관리자 조회 응답 DTO — 단건 entry + 페이지 응답 (FR-AU-10 D6/D7)

package com.atlas.bts.identity.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * 감사 로그 단건 응답.
 *
 * read model [com.atlas.bts.identity.audit.AuthAuditLogAdminEntry] 의 web 표현.
 * nullable 필드(userId/username/displayName/ipAddress/userAgent)는 null 일 때 JSON 에서 생략한다
 * (`@JsonInclude(NON_NULL)`) — 프론트는 부재=null 로 해석한다.
 *
 * @property eventType [com.atlas.bts.identity.audit.AuthEventType] enum name 문자열.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuthAuditLogEntryResponse(
    val id: Long,
    val userId: UUID?,
    val username: String?,
    val displayName: String?,
    val eventType: String,
    val providerId: String,
    val ipAddress: String?,
    val userAgent: String?,
    val metadata: Map<String, String>,
    val createdAt: Instant,
)

/**
 * 감사 로그 한 페이지 응답.
 *
 * @property page 0-based 페이지 번호(요청값 echo).
 * @property size 페이지 크기(요청값 echo).
 * @property totalElements 필터 적용 후 전체 건수.
 * @property totalPages ceil(totalElements / size). size 가 0 이하일 일은 없으나 방어적으로 0 처리.
 */
data class AuthAuditLogPageResponse(
    val items: List<AuthAuditLogEntryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
