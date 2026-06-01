// 한 사용자의 프로젝트 내 역할 보유를 나타내는 도메인 값 객체 (FR-PM-01)

package com.atlas.bts.identity.project

import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 멤버십 도메인 값 객체.
 *
 * 한 사용자([userId])가 한 프로젝트([projectId])에서 가지는 역할([role])을 표현한다.
 * `project_members` 테이블 행과 1:1 매핑되며, 모든 필드는 불변(val)이다.
 *
 * ## 생명주기
 * 멤버 초대(생성) → 역할 변경([role] 업데이트) → 멤버 제거(삭제).
 * 역할 변경 시 이 객체를 직접 변경하지 않고 새 인스턴스를 생성한다.
 *
 * ## 필드
 * - [projectId]: 프로젝트 식별자 (`projects.id` FK).
 * - [userId]: BTS 내부 사용자 식별자 (`users.id` FK).
 * - [role]: 이 멤버의 역할. [ProjectRole] 참조.
 * - [createdAt]: 멤버십 최초 생성 시각.
 * - [updatedAt]: 마지막으로 역할이 변경된 시각.
 *
 * @see ProjectRole 허용 역할 목록 및 [ProjectRole.from] 정규화 함수
 * @see docs/decisions/2026-06-01-project-membership-model.md 설계 결정 ADR
 */
data class ProjectMembership(
    val projectId: UUID,
    val userId: UUID,
    val role: ProjectRole,
    val createdAt: Instant,
    val updatedAt: Instant,
)
