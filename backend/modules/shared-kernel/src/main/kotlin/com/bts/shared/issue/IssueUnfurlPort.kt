// Slack unfurl 카드용 이슈 스냅샷을 결합 fail-closed로 조회하는 cross-BC 포트 (FR-SL-03 Task 5)

package com.bts.shared.issue

import java.util.UUID

/**
 * Slack unfurl 카드에 필요한 이슈 스냅샷을 열람 권한과 함께 원자적으로 조회하는 결합 포트 (FR-SL-03 ADR D1).
 *
 * ### 왜 "권한 확인 + 상세 조회"를 하나의 메서드로 묶었는가
 * slack-integration BC는 Slack 채널에 공유된 Atlas 이슈 URL을 unfurl 카드로 되돌려야 한다.
 * "가시성 확인"과 "상세 조회"를 별도 포트 두 개로 나누면, 소비 BC(slack-integration)가 순서를 지키지
 * 않거나 조건을 놓치는 실수만으로 이슈 상세가 누출될 수 있다. 이 포트는 [viewerUserId]가 [IssueUnfurlView]
 * 를 요청한 이슈를 볼 수 없으면 **데이터 자체를 반환하지 않는다**(null) — fail-open이 구조적으로 불가능하다
 * (`com.bts.shared.permission.IssueVisibilityPort`와 동일 철학의 결합형 변형).
 *
 * ### fail-closed 계약
 * 이슈가 존재하지 않거나, viewer가 볼 수 없거나, 판정이 불확실한 모든 경우 `null`을 반환한다.
 * "판정 불가 시 허용"으로는 절대 수렴하지 않는다.
 *
 * ### default 금지
 * `IssueVisibilityPort`와 동일하게 default 구현을 두지 않는다. 구현체(issue-tracking 어댑터)가
 * 등록되지 않으면 slack-integration BC 부팅이 실패하는 편이, allow-all 기본값보다 안전하다.
 *
 * ### BC 경계 규칙
 * issue-tracking/identity-access BC 도메인 타입을 이 interface에 사용하면
 * `SharedKernelBoundaryArchTest`가 빌드를 차단한다. 파라미터·반환은 원시 타입과 [IssueUnfurlView]만 쓴다.
 */
interface IssueUnfurlPort {
    /**
     * [viewerUserId]가 [issueKey]를 볼 수 있으면 unfurl 카드 스냅샷을, 없으면 `null`을 반환한다.
     *
     * @param issueKey "PROJ-123" 형태의 이슈 키.
     * @param viewerUserId 열람 권한을 판정할 Atlas 사용자 UUID(Slack 공유자의 역매핑 결과).
     * @return 볼 수 있으면 [IssueUnfurlView], 볼 수 없거나 이슈가 없으면 `null`.
     */
    fun getVisibleIssueCard(
        issueKey: String,
        viewerUserId: UUID,
    ): IssueUnfurlView?
}

/**
 * Slack unfurl 카드에 렌더할 이슈 스냅샷.
 *
 * @property issueKey "PROJ-123" 형태의 이슈 키.
 * @property summary 이슈 제목.
 * @property statusLabel 워크플로우 상태 표시 라벨.
 * @property priorityLabel 우선순위 표시 라벨.
 * @property assigneeDisplayName 담당자 표시명. 담당자가 없으면 `null`(렌더 시 "미지정" 폴백).
 */
data class IssueUnfurlView(
    val issueKey: String,
    val summary: String,
    val statusLabel: String,
    val priorityLabel: String,
    val assigneeDisplayName: String?,
)
