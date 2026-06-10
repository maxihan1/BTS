// 인증 감사 로그 이벤트 데이터 클래스 (FR-09-31)

package com.atlas.bts.identity.audit

import java.time.Instant
import java.util.UUID

/**
 * 인증 감사 로그 단일 이벤트.
 *
 * PII 포함 필드 (ipAddress, userAgent, deviceFingerprint)는 로그 출력 시
 * Logback 마스킹 패턴을 통해 보호해야 한다. (DEVELOPMENT.md §보안 규칙)
 *
 * DB persistence + 월 단위 파티션은 SDD 19.9 후속 PR에서 구현 예정.
 *
 * @property userId 이벤트 주체 사용자 ID. null = 사용자 미상
 *   (LOGIN_FAILURE/LDAP_UNAVAILABLE 등 인증 전이라 주체를 특정할 수 없는 이벤트).
 */
data class AuthAuditLog(
    val userId: UUID?,
    val eventType: AuthEventType,
    val providerId: String,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val deviceFingerprint: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
)
