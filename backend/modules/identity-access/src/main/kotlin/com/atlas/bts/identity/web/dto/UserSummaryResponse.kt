// 사용자 목록 조회 응답 DTO — 담당자 셀렉터 typeahead용 최소 공개 필드

package com.atlas.bts.identity.web.dto

import java.util.UUID

/**
 * GET /api/v1/users 응답 개별 사용자 항목 (FR-IS-03 Task 4).
 *
 * ## PII 노출 범위
 * 인증된 사용자에게만 공개. 비밀번호 해시 / 세션 정보 등 민감 정보 절대 미포함.
 * 포함 필드: id / username / displayName / email — 담당자 셀렉터 표시에 필요한 최소 집합.
 *
 * @param id 사용자 내부 식별자 (UUID)
 * @param username 로그인 식별자 (LDAP uid, 이메일 등)
 * @param displayName 화면 표시 이름 (nullable — 외부 IdP 미제공 시 null 가능)
 * @param email 이메일 (nullable — 외부 IdP 미제공 시 null 가능)
 */
data class UserSummaryResponse(
    val id: UUID,
    val username: String,
    val displayName: String?,
    val email: String?,
)
