// Slack 완료 모달용 이슈 완료 옵션(OCC 버전 + DONE 전이 후보 + resolution)을 결합 fail-closed로 조회하는 cross-BC 포트 (FR-SL-05 Task 1)

package com.bts.shared.issue

import java.util.UUID

/**
 * Slack "완료로 표시" 인터랙션용 완료 옵션을 열람 권한과 함께 원자적으로 조회하는 결합 포트 (FR-SL-05).
 *
 * ### fail-closed 계약
 * 이슈가 존재하지 않거나, viewer가 볼 수 없거나, 판정이 불확실한 모든 경우 `null`을 반환한다.
 * "판정 불가 시 허용"으로는 절대 수렴하지 않는다.
 *
 * ### default 금지
 * `IssueUnfurlPort`와 동일하게 default 구현을 두지 않는다. 구현체(issue-tracking 어댑터)가
 * 등록되지 않으면 slack-integration BC 부팅이 실패하는 편이, allow-all 기본값보다 안전하다.
 *
 * ### BC 경계 규칙
 * issue-tracking/project-workflow/identity-access BC 도메인 타입을 이 interface에 사용하면
 * `SharedKernelBoundaryArchTest`가 빌드를 차단한다. 파라미터·반환은 원시 타입과
 * [IssueCompletionOptions]만 쓴다.
 */
interface IssueCompletionOptionsPort {
    /**
     * [viewerUserId]가 [issueKey]를 볼 수 있으면 완료 옵션을, 없으면 `null`을 반환한다.
     *
     * @param issueKey "PROJ-123" 형태의 이슈 키.
     * @param viewerUserId 열람 권한을 판정할 Atlas 사용자 UUID(Slack 상호작용 사용자의 역매핑 결과).
     * @return 볼 수 있으면 [IssueCompletionOptions], 볼 수 없거나 이슈가 없으면 `null`.
     */
    fun getCompletionOptions(
        issueKey: String,
        viewerUserId: UUID,
    ): IssueCompletionOptions?
}

/**
 * Slack 완료 모달에 렌더할 이슈 완료 옵션 스냅샷.
 *
 * @property version 조회 시점 이슈의 OCC(낙관적 락) 버전.
 * @property doneTransitions 현재 상태에서 DONE 카테고리로 이동 가능한 전이 후보 목록.
 *   빈 리스트면 완료 전이가 불가능한 상태(이미 완료됨 등)를 의미한다.
 * @property resolutions 선택 가능한 resolution 목록. 빈 리스트면 프로젝트에 resolution이
 *   구성되지 않은 상태를 의미한다.
 */
data class IssueCompletionOptions(
    val version: Long,
    val doneTransitions: List<DoneTransition>,
    val resolutions: List<ResolutionOption>,
)

/**
 * DONE 카테고리로 이동 가능한 전이 후보 — published language 최소 표면.
 *
 * @property toStateKey 전이 도착 상태 키.
 * @property label 전이 표시 이름. Slack 모달 옵션 레이블 용도로만 사용하며 식별자가 아니다.
 */
data class DoneTransition(
    val toStateKey: String,
    val label: String,
)

/**
 * 선택 가능한 resolution(해결 방안) 옵션.
 *
 * @property id resolution UUID.
 * @property label resolution 표시 이름. Slack 모달 옵션 레이블 용도로만 사용한다.
 */
data class ResolutionOption(
    val id: UUID,
    val label: String,
)
