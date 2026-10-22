// 워크플로우 상태 이관 큐잉 cross-BC 쓰기 포트 — project-workflow → issue-tracking (FR-WF-07 D2)
package com.bts.shared.issue

import java.util.UUID

/**
 * 워크플로우 상태 이관 큐잉 cross-BC 쓰기 포트 (FR-WF-07 D2 · 로드맵 PR 7).
 *
 * 워크플로우 정의를 발행할 때 사라지는 상태에 남아 있는 이슈들을 다른 상태로 옮기는 일괄작업을
 * **큐잉**하기 위한 포트다. 실제 이관은 issue-tracking 의 pgmq 워커가 나중에 수행한다.
 *
 * ### 의존 방향
 * ```
 * project-workflow ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
 * ```
 *
 * ### BC 격리 사유 — shared-kernel 배치
 *
 * project-workflow 와 issue-tracking 은 shared-kernel 만 공유 의존한다. 이 포트를 shared-kernel 에
 * 두면 두 BC 는 서로를 gradle 수준에서 의존하지 않는다(BC 격리 룰, ArchUnit 강제).
 *
 * project-workflow 로컬의 `IssueStatusUsagePort` 는 **읽기 전용 스칼라 count** 라서 로컬 배치가
 * 허용됐다. 이 포트는 **쓰기**이므로 그 면제가 넘어오지 않는다 — 쓰기 위임은 shared-kernel 계약을
 * 거쳐야 한다([IssueImportPort] · [IssueMutationPort] 선례).
 *
 * ### fail-closed — default 구현 없음
 *
 * 어댑터 부재 시 부팅 자체가 실패해야 한다. 빈 default 구현을 두면 어댑터 미결선 상태에서 이관이
 * silent-drop 되고, 호출자(발행 경로)는 「이관을 걸었다」고 오인한 채 정의를 교체한다 — 이관되지
 * 않은 이슈가 사라진 상태를 가리키는 **유령 상태**가 그대로 남는다. [IssueMutationPort] 와 동일한
 * fail-closed 패턴이다.
 *
 * ### 권한 — 어댑터는 per-issue 전환 권한을 검사하지 않는다 (편차 X4)
 *
 * 어댑터는 이관 대상 이슈 하나하나에 대해 TRANSITION 권한을 확인하지 않는다. 확인하면 관리자가
 * 건드릴 수 없는 이슈만 사라진 상태에 남아, 이 기능이 막으려던 유령 상태를 이 기능이 만든다.
 *
 * **위조 차단은 호출자의 발행 권한 책임**이다 — 호출자(project-workflow 발행 경로)가 워크플로우
 * 정의 PUBLISH 권한을 이미 검사했다고 신뢰한다. 관리자 1회 조작을 전제한 신뢰 모델이며,
 * [IssueMutationPort] 가 automation 실행기를 신뢰하는 것과 동형이다.
 *
 * 우회하는 것은 per-issue 전환 권한 **하나뿐**이다. 프로젝트 아카이브 가드 · 비관락 · OCC ·
 * `IssueTransitioned` 이벤트 발행은 기존 전환 경로 그대로 유지된다(구현체 책임).
 *
 * ### ★호출자의 범위 의무 — PUBLISH 가 덮는 것과 커맨드의 범위를 잇는다
 *
 * 위의 「PUBLISH 를 이미 검사했다고 신뢰한다」는 **무엇에 대한 PUBLISH 인지**까지 말해야 성립한다.
 * 커맨드의 범위가 그 워크플로우를 넘으면 신뢰는 권한 없는 대량 재작성이 된다. 워크플로우 하나에
 * PUBLISH 를 가진 호출자가 임의의 프로젝트와 살아 있는 임의의 출발 상태(예: `done→todo`)를 실어
 * 보낼 수 있기 때문이다. 그래서 호출자는 다음 둘을 지켜야 한다 — 계약이지 권고가 아니다.
 *
 * 1. **[StatusMigrationCommand.projectScopes] 는 발행 대상 워크플로우에 연결된 프로젝트와, 그
 *    프로젝트에서 이 워크플로우가 맡는 이슈 타입이어야 한다.** 사용자 입력을 그대로 실으면 안 된다.
 *    호출자가 자기 발행 트랜잭션 안에서 스킴 매핑을 **직접 조회해** 채운다.
 * 2. **[StatusMigrationMapping.fromStatusKey] 는 이번 발행이 제거하는 상태와 정확히 일치해야 한다.**
 *    제거되지 않는 상태를 실으면 살아 있는 상태의 이슈가 통째로 옮겨진다. 구현체는 출발 상태를
 *    의도적으로 검증하지 **않으므로**(사라지는 중이라 카탈로그에 이미 없을 수 있다) 이 축의 방어는
 *    호출자에게만 있다.
 *
 * **이 의무를 검사하는 장치는 지금 저장소에 없다.** 이 포트를 호출하는 코드가 아직 없기 때문이며,
 * 결선하는 **PR 7b 가 red-first 계약 테스트로 못박아야 한다** — 「그 워크플로우에 없는 프로젝트를
 * 실으면 발행이 거부된다」와 「제거되지 않는 상태를 실으면 발행이 거부된다」 두 축이다. 여기 적는
 * 이유는 하나다 — **저장소에 안 남는 약속은 약속이 아니다.**
 *
 * ### actor 는 호출자가 커맨드로 채워 전달
 *
 * 이관은 pgmq 워커(별도 스레드/프로세스)에서 비동기 실행되므로 SecurityContext 자체가 존재하지
 * 않을 수 있다. 어댑터는 SecurityContext 를 읽지 않고 [StatusMigrationCommand.actorUserId] 를
 * 신뢰한다(async 안전). [IssueMutationPort] 의 actor 계약과 동일하다.
 *
 * ### 계약 타입 — 원시 타입 전용
 *
 * 파라미터는 `String`·`Long`·`Boolean`·`UUID`·`Set`·`List`·`Map` 과 그것만 담은 이 파일의 VO 뿐이고
 * 반환은 `UUID` 다. shared-kernel 은 순수 계약
 * 모듈이라 `BulkOperationId` 같은 BC 내부 타입을 노출하지 않는다 — 노출하면 소비 BC 가 issue-tracking
 * 도메인에 결합돼 순환 의존이 재발한다.
 *
 * ### 메서드 1개 — 진행률 조회는 열지 않는다
 *
 * 큐잉만 좁게 연다. 진행률 조회는 기존 `GET /api/v1/bulk-operations/{id}` 가 이미 하므로 포트에
 * 조회 메서드를 만들지 않는다 — 두 번째 경로를 만들면 둘이 서로를 검사하지 않는다.
 *
 * @see StatusMigrationCommand
 * @see StatusMigrationMapping
 */
interface IssueStatusMigrationPort {
    /**
     * 상태 이관을 큐잉하고 일괄작업 id 를 돌려준다.
     *
     * 구현체는 일괄작업 1건을 만들어 pgmq 큐에 넣기까지만 한다. **대상 이슈는 이 시점에 확정되지
     * 않는다** — 워커가 실행 시점에 [StatusMigrationCommand.mappings] 의 출발 상태 키와
     * [StatusMigrationCommand.projectScopes] 범위로 이슈를 다시 긁는다. 큐잉과 실행 사이에 그 상태로
     * 들어온 이슈도 대상이 된다(「세고 나서 옮긴다」가 아니라 「옮기면서 센다」).
     *
     * 이 포트는 권한을 판단하지 않는다 — per-issue 전환 권한 우회와 그 신뢰 모델은 이 인터페이스의
     * KDoc §권한 참조.
     *
     * @param cmd 이관 커맨드. actor · 대상 프로젝트 범위 · 상태별 매핑 목록.
     * @return 생성된 일괄작업 id. 호출자는 이 id 로 기존 일괄작업 조회 API 에서 진행률을 본다.
     * @throws RuntimeException (issue-tracking BC 내부 예외) 상한 초과·프로젝트 부재·큐잉 실패 등.
     *   실패는 결과 값이 아니라 예외로 전달한다([IssueMutationPort] 와 동일한 계약).
     */
    fun enqueueStatusMigration(cmd: StatusMigrationCommand): UUID
}

/**
 * 상태 이관 커맨드 VO.
 *
 * project-workflow 발행 경로가 [IssueStatusMigrationPort.enqueueStatusMigration] 호출 시 전달한다.
 *
 * ### projectScopes — 범위가 없으면 남의 프로젝트를 옮긴다
 *
 * 상태 키는 전역이다. 범위를 생략할 수 있게 두면 같은 상태 키를 쓰는 다른 프로젝트의 이슈까지
 * 함께 옮겨진다. 과다 집계(읽기)는 안전하지만 **과다 이동은 데이터 손상**이므로 범위를 기본값 없는
 * 필수 파라미터로 못박는다.
 *
 * ### ★프로젝트만으로는 범위가 안 된다 — 이슈 타입 축 (WF-07-2)
 *
 * 스킴은 `(scheme_id, issue_type_id) → workflow_id` 다. 한 프로젝트가 Bug→WF1 · Task→WF2 를 쓰면
 * WF1 발행이 빼는 상태가 WF2 에서는 멀쩡히 살아 있을 수 있다. 프로젝트로만 좁히면 WF1 이관이
 * **WF2 의 Task 까지 옮긴다.** 그래서 프로젝트마다 [StatusMigrationTypeFilter] 를 붙인다.
 * 키가 프로젝트인 `Map` 이라 한 프로젝트에 필터가 둘 실리는 모순은 타입이 막는다.
 *
 * @property actorUserId 실행 주체 UUID. 워크플로우 정의를 발행한 관리자.
 * @property projectScopes 대상 프로젝트 키 → 그 프로젝트에서 옮길 이슈 타입. 비우면 안 된다(구현체가 거부).
 * @property mappings 빠지는 상태마다 옮길 곳. 여기 없는 상태의 이슈는 이관 대상이 아니다.
 * @see IssueStatusMigrationPort.enqueueStatusMigration
 */
data class StatusMigrationCommand(
    val actorUserId: UUID,
    val projectScopes: Map<String, StatusMigrationTypeFilter>,
    val mappings: List<StatusMigrationMapping>,
)

/**
 * 한 프로젝트 안에서 이관이 건드릴 이슈 타입(`issues.type_id`) VO.
 *
 * 스킴 매핑의 두 모양을 그대로 옮긴다.
 *
 * - 워크플로우가 **타입별 매핑**으로만 실렸으면 그 타입들**만** — `issueTypeIds = {Bug}`,
 *   `exceptListed = false`.
 * - 워크플로우가 **default 매핑**(`issue_type_id IS NULL`)이면 「다른 워크플로우에 명시된 타입을 **뺀**
 *   전부」 — `issueTypeIds = {Task}`, `exceptListed = true`. 형제가 없으면 `issueTypeIds` 가 비고, 그것이
 *   「모든 타입」이다.
 *
 * default 쪽을 「전체 타입을 나열」로 바꾸지 않는 이유 — 큐잉과 실행 사이에 새 타입이 생기면 그 타입의
 * 이슈는 default 워크플로우 소관인데도 나열에서 빠져 **유령 상태로 남는다.** 제외 목록은 그 창이 없다.
 *
 * @property issueTypeIds `issue_types.id` 집합. [exceptListed] 에 따라 포함 목록이거나 제외 목록이다.
 * @property exceptListed true 면 [issueTypeIds] 를 **뺀** 모든 타입, false 면 [issueTypeIds] 만.
 *   false 인데 [issueTypeIds] 가 비면 아무것도 고르지 않는다 — 구현체가 거부한다.
 */
data class StatusMigrationTypeFilter(
    val issueTypeIds: Set<Long>,
    val exceptListed: Boolean,
)

/**
 * 상태 1개의 이관 대상 매핑 VO.
 *
 * 상태 키는 워크플로우 정의의 상태 키이며 프로젝트별 id 가 아니다 — 범위는
 * [StatusMigrationCommand.projectScopes] 가 따로 좁힌다.
 *
 * @property fromStatusKey 정의에서 사라지는 상태 키. 이 상태에 남은 이슈가 이관 대상이다.
 * @property toStatusKey 옮겨 갈 상태 키. 새 정의에 존재해야 한다(구현체 검증).
 * @see StatusMigrationCommand.mappings
 */
data class StatusMigrationMapping(
    val fromStatusKey: String,
    val toStatusKey: String,
)
