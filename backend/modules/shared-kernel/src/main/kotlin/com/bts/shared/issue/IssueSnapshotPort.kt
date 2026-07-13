// 자동화 조건 평가용 이슈 스냅샷 cross-BC 읽기 포트 — automation → issue-tracking (FR-AT-03)
package com.bts.shared.issue

import java.util.UUID

/**
 * 자동화 조건(FR-AT-03) 평가에 필요한 이슈 필드 값을 읽어오는 cross-BC 읽기 포트 — automation
 * BC 용(Task 3).
 *
 * automation BC 의 `ActionExecutor` 가 액션 실행 전 조건(`{"==": [{"var":"issue.type"}, "Bug"]}`
 * 같은 JSONLogic 부분집합 표현식)을 평가하려면 이슈의 현재 필드 값이 필요하다. issue-tracking
 * 내부를 직접 import 하지 않고 이 포트를 경유한다.
 *
 * ### fail-closed — default 구현 없음
 *
 * `fetch` 는 default 구현이 없다. [IssueMutationPort] 와 동형의 이유다 — adapter 부재 시 조용히
 * null 만 반환하는 default 를 허용하면, 어댑터 미결선 상태에서 모든 조건이 항상 "스냅샷 없음"으로
 * fail-safe SKIPPED 되어 버그가 부팅 시점이 아니라 런타임 규칙 실행 시점에서야 드러난다.
 * consumer(`ActionExecutor`)는 이 포트를 non-null 로 주입받아야 하며, adapter 미결선 시 Spring
 * 부팅 자체가 `NoSuchBeanDefinitionException` 으로 실패해야 한다.
 *
 * ### actor 필수 — 가시성 강제(SDD §12.4 "관리자 우회 없음")
 *
 * [fetch] 는 `actorUserId` 를 필수로 받는다. 어댑터(issue-tracking, Task 6)는 이 actor 의 권한으로
 * issue-tracking 이 기존에 보유한 가시성 강제 read 경로(보안 수준(FR-PM-06) 게이트 포함)를 그대로
 * 재사용한다 — 별도의 무필터 읽기 경로를 새로 만들지 않는다. actor 가 보안 수준 제한으로 이슈를
 * 볼 수 없으면(그룹 비멤버 등) [fetch] 는 null 을 반환하고, 조건 게이트는 이를 평가 실패로 보아
 * fail-safe 하게 액션을 건너뛴다(SKIPPED). "actor 를 넘기지 않고 시스템 권한으로 모두 읽는다"는
 * 설계는 관리자 우회에 해당하므로 채택하지 않았다.
 *
 * `actorUserId` 는 조건을 평가하는 자동화 룰의 actor(룰 소유자 또는 지정된 실행 주체)이며, 호출자
 * (automation 조건 게이트)가 채워 전달한다. adapter 는 SecurityContext 를 직접 읽지 않고 이 값을
 * 신뢰한다 — 자동화 실행은 pgmq 워커(별도 스레드/프로세스)에서 비동기 실행되므로 SecurityContext
 * 자체가 존재하지 않을 수 있다(async 안전). [SetFieldCommand] 의 actor 신뢰 모델과 동일한
 * 설계다.
 *
 * ### null 의 두 가지 의미
 *
 * [fetch] 가 null 을 반환하는 경우는 다음 두 가지를 구분하지 않고 하나로 취급한다.
 * 1. `issueKey` 에 해당하는 이슈가 존재하지 않음.
 * 2. 이슈는 존재하지만 `actorUserId` 가 보안 수준 제한으로 볼 수 없음.
 *
 * 두 경우 모두 "조건을 평가할 수 없다"는 점에서 consumer 입장의 처리(fail-safe SKIPPED)가 동일하므로
 * 구분할 필요가 없다. 구분이 필요해지면(예: 감사 로그 상세화) 별도 타입으로 확장한다.
 *
 * ### BC 격리 사유 — shared-kernel 배치
 *
 * automation 과 issue-tracking 이 shared-kernel 만 공유 의존한다. automation 이 issue-tracking
 * 내부를 직접 import 하면 BC 경계가 무너지고 순환 의존 위험이 생긴다. [IssueMutationPort] 와 같은
 * 배치 원칙이다.
 *
 * ### 의존 방향
 * ```
 * automation ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
 * ```
 *
 * @see IssueSnapshot
 * @see IssueMutationPort
 */
interface IssueSnapshotPort {
    /**
     * 이슈의 현재 필드 값을 조건 평가용 스냅샷으로 조회한다.
     *
     * 읽기 전용이며 부수 효과가 없다. 반환된 [IssueSnapshot] 은 조회 시점의 스냅샷이다 — 이후 이슈가
     * 변경돼도 갱신되지 않는다(호출자가 매번 새로 조회).
     *
     * @param actorUserId 조회 주체 UUID. 자동화 룰의 actor(룰 소유자 또는 지정된 실행 주체). 어댑터가
     *   이 actor 권한으로 가시성 강제 read 경로를 재사용한다(클래스 KDoc "actor 필수" 참조).
     * @param issueKey 조회할 이슈 키. 예: `"PROJ-1"`.
     * @return 조회된 스냅샷. 이슈가 존재하지 않거나 `actorUserId` 가 볼 수 없는 이슈면 null.
     */
    fun fetch(
        actorUserId: UUID,
        issueKey: String,
    ): IssueSnapshot?
}
