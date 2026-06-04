// 사용자에게 부여된 전역 시스템 역할 할당을 표현하는 불변 데이터 클래스 (FR-PM-08)

package com.atlas.bts.identity.systemrole

import java.time.Instant
import java.util.UUID

/**
 * 사용자 × 전역 시스템 역할 할당.
 *
 * `system_role_assignments` 테이블의 한 행에 대응하며, 한 사용자에게 부여된
 * 전역 [SystemRole]을 나타낸다. 프로젝트 단위 멤버십과 달리 프로젝트 식별자를
 * 포함하지 않는다 (docs/decisions/2026-06-04-system-admin-role.md D1).
 *
 * 모든 필드는 불변(`val`)이다.
 *
 * @property userId 역할이 부여된 사용자 식별자(`users.id`).
 * @property role 부여된 전역 시스템 역할.
 * @property createdAt 역할이 부여된 시각.
 */
data class SystemRoleAssignment(
    val userId: UUID,
    val role: SystemRole,
    val createdAt: Instant,
)
