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
 * ### actor 는 호출 컨트롤러가 SecurityContext 에서 추출해 cmd 로 전달
 *
 * [BoardTransitionCommand.actorUserId] 를 채우는 유일한 곳은 호출 컨트롤러
 * ([com.bts.agileplanning.web.BoardController])가 SecurityContext 에서 추출한 값이다.
 * adapter 는 이 값을 신뢰한다(스레드 무관 → async 안전). adapter 가 직접 SecurityContext 를
 * 읽으면 future async 경로에서 actor 가 손실·오염되므로, cmd 로 명시 전달한다.
 * 컨트롤러는 절대 request body/param 으로 actor 를 받지 않는다(위조 차단, sec codereview-fix P1).
 *
 * ### BC 격리 사유 — shared-kernel 배치
 *
 * agile-planning 과 issue-tracking 이 shared-kernel 만 공유 의존한다.
 * agile-planning 이 issue-tracking 내부를 직접 import 하면 BC 경계가 무너지고
 * 순환 의존 위험이 생긴다. 이 포트를 shared-kernel 에 배치함으로써 두 BC 는 서로를
 * gradle 수준에서 의존하지 않는다 (BC 격리 룰, ArchUnit 강제).
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
     * actor 는 [BoardTransitionCommand.actorUserId] 로 전달받는다. adapter 는 이를 신뢰한다
     * (호출 컨트롤러가 SecurityContext 에서 추출해 채운 값 — 위조 차단).
     *
     * @param cmd 전이 커맨드. actor·이슈 키·대상 상태 키·OCC 버전·해결 ID 포함.
     * @return 전이 결과. 전이 후 상태 키·버전 포함.
     * @throws RuntimeException (issue-tracking BC 내부 예외)
     *   전이 불가·버전 충돌·권한 거부·해결 ID 누락 등. consumer 에서 catch 후 HTTP 매핑.
     */
    fun transition(cmd: BoardTransitionCommand): BoardTransitionResult
}

/**
 * 보드 카드 이동(전이) 커맨드 VO.
 *
 * ### actorUserId (보안 설계 — async 안전 + 위조 차단)
 *
 * actor 는 호출 컨트롤러([com.bts.agileplanning.web.BoardController])가 SecurityContext 에서
 * 추출해 이 cmd 로 전달한다. adapter 는 SecurityContext 를 직접 읽지 않고 이 값을 신뢰한다.
 * - async 안전: adapter 가 다른 스레드에서 실행돼도 actor 가 손실·오염되지 않는다.
 * - 위조 차단: cmd 를 채우는 유일한 곳이 컨트롤러의 SecurityContext 추출이며,
 *   컨트롤러는 절대 request body/param 으로 actor 를 받지 않는다(sec codereview-fix P1).
 *
 * @property actorUserId 전이 행위자 UUID. 컨트롤러가 SecurityContext 에서 추출해 채운다.
 * @property issueKey 이동할 이슈 키. 예: `"PROJ-1"`.
 * @property toStateKey 이동 대상 워크플로우 상태 키. 예: `"in-progress"`.
 * @property expectedVersion 낙관적 락(OCC) 기대 버전. 충돌 시 issue-tracking 이 예외를 던진다.
 * @property resolutionId DONE 카테고리 전이 시 필요한 해결 방안 ID. 불필요하면 null.
 */
data class BoardTransitionCommand(
    val actorUserId: UUID,
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
