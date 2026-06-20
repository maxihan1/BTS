// 칸반 보드 카드 이동(전이 위임) cross-BC 포트 — agile-planning → issue-tracking 위임

package com.bts.shared.board

import java.util.UUID

/**
 * 보드 카드 이동(워크플로우 전이) cross-BC 위임 포트 — agile-planning BC 용 (FR-BD-01).
 *
 * agile-planning BC 가 카드 이동 시 기존 issue-tracking 전이 메커니즘에 위임하기 위해
 * 이 포트를 호출한다. 전이 규칙 검증·권한 강제·OCC·이벤트 발행은 모두 issue-tracking 이 담당한다.
 *
 * ### fail-closed — default 구현 없음
 *
 * 권한·규칙을 강제하는 쓰기 경로이므로 adapter 부재 시 부팅 자체가 실패해야 한다.
 * 빈 default 구현을 허용하면 adapter 미결선 상태에서 쓰기 요청이 silent-drop 될 위험이 있다.
 * [com.bts.shared.permission.IssuePermissionResolver] 의 fail-closed 패턴과 동일.
 *
 * ### actor 는 adapter 가 SecurityContext 에서 추출
 *
 * [BoardTransitionCommand] 에 actorUserId 를 포함하지 않는다.
 * 호출 스택이 동기 요청이므로 SecurityContext 가 살아 있으며,
 * adapter 구현체가 `CurrentActor.current()` 로 추출한다.
 * cmd 로 actor 를 전달하면 호출자가 임의 actor 를 주입할 수 있어 보안 위반이다
 * (sec CONCERN-3, Plan review 반영).
 *
 * ### 의존 방향
 * ```
 * agile-planning ──(port)──▶ shared-kernel ◀──(impl)──  issue-tracking
 * ```
 *
 * @see BoardTransitionCommand
 * @see BoardTransitionResult
 */
interface IssueTransitionPort {
    /**
     * 이슈를 지정한 상태로 전이한다.
     *
     * actor 는 adapter 가 SecurityContext 에서 추출한다. cmd 로 받지 않는다.
     *
     * @param cmd 전이 커맨드. 이슈 키·대상 상태 키·OCC 버전·해결 ID 포함.
     * @return 전이 결과. 전이 후 상태 키·버전 포함.
     * @throws RuntimeException (issue-tracking BC 내부 예외)
     *   전이 불가·버전 충돌·권한 거부·해결 ID 누락 등. consumer 에서 catch 후 HTTP 매핑.
     */
    fun transition(cmd: BoardTransitionCommand): BoardTransitionResult
}

/**
 * 보드 카드 이동(전이) 커맨드 VO.
 *
 * ### actorUserId 부재 (보안 설계)
 *
 * actor 는 adapter 구현체가 `CurrentActor.current()` 로 SecurityContext 에서 직접 추출한다.
 * cmd 로 actor 를 전달하면 호출자가 임의 actor 를 주입할 수 있어 권한 우회가 가능하므로
 * 이 VO 에 포함하지 않는다.
 *
 * @property issueKey 이동할 이슈 키. 예: `"PROJ-1"`.
 * @property toStateKey 이동 대상 워크플로우 상태 키. 예: `"in-progress"`.
 * @property expectedVersion 낙관적 락(OCC) 기대 버전. 충돌 시 issue-tracking 이 예외를 던진다.
 * @property resolutionId DONE 카테고리 전이 시 필요한 해결 방안 ID. 불필요하면 null.
 */
data class BoardTransitionCommand(
    val issueKey: String,
    val toStateKey: String,
    val expectedVersion: Long,
    val resolutionId: UUID?,
)

/**
 * 보드 카드 이동(전이) 결과 VO.
 *
 * [IssueTransitionPort.transition] 성공 후 반환된다.
 *
 * @property issueKey 전이한 이슈 키.
 * @property currentStateKey 전이 완료 후 현재 상태 키.
 * @property version 전이 후 갱신된 OCC 버전. 다음 이동 시 expectedVersion 으로 사용.
 */
data class BoardTransitionResult(
    val issueKey: String,
    val currentStateKey: String,
    val version: Long,
)
