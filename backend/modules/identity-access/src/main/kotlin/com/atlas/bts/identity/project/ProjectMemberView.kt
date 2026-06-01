// 멤버십에 사용자 표시 정보를 합친 읽기 전용 뷰 모델 (FR-PM-01 Task B2)

package com.atlas.bts.identity.project

import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 멤버 읽기 전용 뷰 모델 (FR-PM-01 Task B2).
 *
 * [ProjectMembership] 도메인 값 객체를 표시 필드로 오염시키지 않기 위해
 * 리포지토리 조인 쿼리 결과 전용 모델로 분리한다.
 * `project_memberships m LEFT JOIN users u ON m.user_id = u.id` 결과를 담는다.
 *
 * ## LEFT JOIN 의미
 * users 행이 없는 orphan 멤버십도 포함한다. 이 경우 [displayName]과 [username]은 null이다.
 *
 * ## 사용 범위
 * 리포지토리 읽기 메서드 반환값 및 서비스/컨트롤러 응답 DTO 변환에만 사용한다.
 * 도메인 불변식 적용 대상이 아니다.
 *
 * @property projectId 프로젝트 식별자
 * @property userId 사용자 식별자
 * @property role 프로젝트 내 역할
 * @property createdAt 멤버십 생성 시각
 * @property updatedAt 멤버십 최종 수정 시각
 * @property displayName users.display_name — 사용자가 설정하지 않았거나 행이 없으면 null
 * @property username users.username — users 행이 없으면 null
 */
data class ProjectMemberView(
    val projectId: UUID,
    val userId: UUID,
    val role: ProjectRole,
    val createdAt: Instant,
    val updatedAt: Instant,
    val displayName: String?,
    val username: String?,
)
