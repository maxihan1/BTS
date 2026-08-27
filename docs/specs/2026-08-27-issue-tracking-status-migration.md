# issue-tracking 상태 이관 실행 (FR-WF-07 D2·D4·D5 · 로드맵 PR 7)

> 티어 T3 · slug `issue-tracking-status-migration` · type `migration` · 생성 2026-08-27
> plan `docs/plans/2026-08-27-issue-tracking-status-migration.md`

## 범위 정정 — 로드맵 PR 7 의 절반은 이미 끝나 있었다

착수 실측에서 로드맵 PR 7 절(`~/.claude/plans/cozy-hatching-otter.md:314`)의 4항목 중 **3항목이
이미 구현돼 있었다.** PR #395(FR-WF-05 전환 ID)가 「cross-BC 확대(재판정 승인)」로 issue-tracking
쪽까지 함께 했고, 그 사실이 로드맵 PR 7 절에 반영되지 않았다.

| 로드맵 PR 7 항목 | 실물 | 상태 |
|---|---|---|
| `POST /issues/{key}/transition` 의 `transitionId` 수용 | `TransitionIssueRequest.kt:35` `val transitionId: UUID? = null` | **이미 완료** |
| `toStatusKey` 모호 시 409 | `AmbiguousTransitionExceptionHandler.kt`(project-workflow) · `IssueController.kt:426` | **이미 완료** |
| `GET /issues/{key}/transitions` 응답에 `transitionId` | `AvailableTransitionsResponse.kt:74` `val transitionId: UUID?` | **이미 완료** |
| 이관 큐잉 포트 + `STATUS_MIGRATION` + 이관 실행 | 없음 | **이 PR** |

판정 방법은 learnings 「도메인·서비스·repo가 다 있어도 REST 노출이 없으면 기능이 없는 것이다」의
처방 그대로다 — **컨트롤러를 직접 열어 매핑을 세고**, 어긋나면 출처 PR 을 추적했다. 그 교훈의
역방향 사례다. 대장이 「할 일」이라 적은 것이 이미 있었다.

**따라서 이 PR 의 실질은 「이관 실행」 하나다.**

## 사용자 시나리오 (Given-When-Then)

**S1. 빠지는 상태마다 옮길 곳을 따로 고른다** (Maxi 지시 · Jira J7)
- Given 워크플로우에서 `in_review`(12건)와 `blocked`(3건) 두 상태를 뺀다
- When 관리자가 `in_review → in_progress` · `blocked → todo` 로 **각각** 지정한다
- Then 이관 작업 **1건**이 큐잉되고, 12건은 `in_progress` 로 3건은 `todo` 로 간다.
  두 상태를 한 대상으로 몰아넣도록 강요하지 않는다

**S2. 워크플로우 엔진을 타지 않는다** (Jira J8 과 동일)
- Given 이관 대상 이슈는 이미 워크플로우에서 빠진 상태에 있어 **유효한 전환이 하나도 없다**
- When 이관이 실행된다
- Then 전환 검증(조건·검증기)과 후처리를 거치지 않고 상태가 재작성되며,
  `TRANSITION_NOT_ALLOWED` 는 **0건**이다

**S3. 실패한 건이 개별로 남는다**
- Given 15건 중 1건이 이관 도중 소프트 삭제됐다
- When 이관이 실행된다
- Then 그 1건만 `bulk_operation_items.status='FAILED'` + `failure_reason='NOT_FOUND'` 이고
  나머지 14건은 `SUCCEEDED` 다 (best-effort — 기존 계약 그대로)

**S4. 이력이 남고 해결책이 보존된다**
- Given `BTS-42` 가 `완료 / 해결책=수정함` 이다
- When 이관된다
- Then 상태 변경이 `issue_change_item` 으로 보이고, **`resolution_id` 는 그대로다**

**S5. 기존 일괄 전환은 그대로다**
- Given `BULK_TRANSITION` 작업
- When 처리된다
- Then 종전과 동일하게 엔진(`IssueApplicationService.transitionIssue`)을 탄다. 회귀 0

**S6. 남의 프로젝트 이슈는 건드리지 않는다**
- Given 상태 키 `in_review` 를 A팀·B팀이 함께 쓰고 있다
- When A팀 워크플로우에서만 `in_review` 를 뺀다
- Then A팀 프로젝트의 이슈만 옮겨지고 **B팀 이슈는 무변경**이다

## Jira 대조 (전 타입 필수)

§1-0 재사용 grep 으로 **직전 형제 PR 6**(`docs/plans/2026-08-26-workflow-draft-publish.md:40`)에서
J1·J3·J4 를 **출처 URL·조회일 그대로 승계**했다. 이번에 새로 건드리는 조작 4건
(상태별 대상 선택 · 규칙 우회 범위 · 전환 충돌 상태 코드 · 일괄 상한)만 §1-1 실물 조회했다.

| # | Jira Cloud 동작 | 원문 인용 | 출처 · 조회일 · 구분 |
|---|---|---|---|
| J1 | 이관은 상태 제거 즉시가 아니라 **발행(저장) 시점**에 시작 | "The moving process won't begin as soon as you remove a status from a workflow, but after you update the workflow to save it." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · 2026-08-26 · **Cloud** (PR 6 승계) |
| J3 | 삭제 대상 상태의 이슈를 **볼 수 있어야** 한다 | "If you need to see what work items are in the statuses you're deleting, try searching for work items and filtering by their statuses." | 위와 동일 · 2026-08-26 · **Cloud** (PR 6 승계) |
| J4 | 발행 요청이 **`statusMappings` 를 함께 받는다** | "the draft workflow includes new workflow statuses for an issue type, and mappings are provided to update issues with the original workflow status to the new workflow status" | https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-workflow-scheme-drafts/ · 2026-08-26 · **Cloud** (PR 6 승계) |
| **J7** | **빠지는 상태마다 옮길 곳을 「New status」 열에서 각각 고른다** | "In the modal that shows up, choose new statuses in the **New status** column." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · **2026-08-27** · **Cloud** |
| **J8** | **이관은 워크플로우 규칙을 통째로 타지 않는다** | "Regardless of what statuses you choose, workflow rules won't be triggered. This means that _Restrict transition_ and _Validate details_ rules won't stop any transitions, and _Perform actions_ rules won't automatically do anything." | 위와 동일 · **2026-08-27** · **Cloud** |
| **J9** | 공간·작업유형별 **예외 매핑**을 더 얹을 수 있다 | "If you want to move some work items to other statuses, select **Move work items in certain spaces / work types to other statuses** and choose new statuses. Select **Add exception** to move more work items to other statuses." | 위와 동일 · **2026-08-27** · **Cloud** |
| J5 | 전환 **충돌**의 상태 코드를 400 에서 **409 로 옮겼다** | "Currently, if multiple issue transitions are requested at the same time, only one of those transitions will succeed, and the rest of those requests will return a 400 error code." / "we will soon be replacing the 400 (Bad Request) status code with 409 (Conflict)" / "the latter more accurately describes the reason for the API request failing" | https://developer.atlassian.com/cloud/jira/platform/change-notice-update-in-simultaneous-transitions-issue-api/ · **2026-08-27** · **Cloud** |
| J6 | 일괄 변경에 **건수 상한**이 있다 | "You can only edit 1000 work items at once." | https://support.atlassian.com/jira-software-cloud/docs/edit-multiple-issues-at-the-same-time/ · **2026-08-27** · **Cloud** |

### 채택 판정

- **J7 채택 (Maxi 지시).** 커맨드가 **상태별 매핑 목록**을 받는다. 빠지는 상태가 여러 개면 각각
  다른 대상으로 갈 수 있고, 이관 작업은 **1건**으로 묶인다 — 지라도 모달 1개·태스크 1개다.
- **J8 채택.** 규칙 우회 범위의 **직접 근거**다. 지라도 조건(`Restrict transition`)·
  검증기(`Validate details`)·후처리(`Perform actions`)를 전부 건너뛴다. BTS 의 엔진 우회는
  발명이 아니라 패리티다. ★D3 결정의 근거가 이 행이다.
- **J1 채택 (이 PR 이 닫는 행).** PR 6 이 「이관 실행은 PR 7 소관」으로 넘긴 그것이다.
- **J3 이미 충족.** PR 6 이 상태별 잔여 건수를 발행 응답에 실었다. 추가 작업 없음.
- **J5 채택 (무변경으로).** BTS 는 이미 모호 전환을 409 로 돌려준다(#395). 이 PR 은 깨지 않는다.
- **J6 채택.** 기존 `BULK_OPERATION_MAX_SIZE` 상한을 이관에도 그대로 적용한다.
- **J9 기각** → X1.
- **J4 부분 채택** → X1.

### 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| X1 | 지라는 **공간·작업유형별 예외 매핑**(J9)과 `issueTypeId` 축(J4)을 갖는다. BTS 는 **상태 단위 매핑만** | PR 6 X1 승계. 워크플로우가 전역이고 유형별 배정은 스킴(FR-WF-02)이 담당한다 — 두 축을 이관에 섞으면 스킴과 겹친다. 예외 축은 후속 과제로 남긴다 |
| X2 | **발행은 동기 · 이관만 비동기** | PR 6 X2 승계. 이 PR 의 설계 전제다 |
| X3 | 지라는 이관을 **발행 흐름 안**에서 시작한다. BTS 는 **큐잉만** 하고 워커가 나중에 돈다 | 다중 BC 트랜잭션 금지. 결선은 PR 7b 소관이라 이 PR 은 큐잉 능력만 만든다 |
| X4 | 지라 문서는 **권한**에 대해 말하지 않는다. BTS 는 **per-issue TRANSITION 권한을 우회**하고 호출자의 발행 권한에 위임한다 | 「지라 클라우드가 그렇게 한다」고 주장하지 않는다 — 근거는 BTS 내부 선례(`IssueMutationPort` 의 호출자 신뢰 모델)와 ★D3 의 유령 상태 논증이다. 계약 §1-4 가 이 구분을 적으라고 요구한다 |

### 조회를 시도했으나 원문을 확보하지 못한 것 1건

`POST /rest/api/3/issue/{issueIdOrKey}/transitions` 의 **요청 본문에서 전환을 id 로 지목하는 형태**는
`developer.atlassian.com` REST 단일 페이지가 커서 `WebFetch` 가 truncate 돼 원문 인용을 얻지 못했다.
**생략이 아니라 조회 실패다** — 계약 §1 이 「생략」과 「조회했고 대응이 없었다」를 다른 기록으로
남기라 하므로 셋째 상태를 그대로 적는다. 이 항목은 **이 PR 의 설계를 가르지 않는다** — 해당
계약은 #395 가 이미 확정했고 이 PR 은 건드리지 않는다.

## 기능 요구사항 (FR)

FR-WF-07 의 D2·D4·D5 중 **issue-tracking + shared-kernel 몫**이다.

| # | 요구사항 | 근거 |
|---|---|---|
| F1 | shared-kernel 에 쓰기 포트 `com.bts.shared.issue.IssueStatusMigrationPort` 를 신설한다. **default 구현을 두지 않는다**(fail-closed) | `IssueMutationPort` KDoc §fail-closed. adapter 미결선이면 부팅이 실패해야 한다 |
| F2 | 커맨드는 **상태별 매핑 목록**을 받는다 (J7). 계약 타입은 `String`·`UUID` 뿐이고 반환은 `UUID` | shared-kernel 은 순수 계약 모듈이라 `BulkOperationId` 같은 BC 내부 타입을 노출하지 않는다 |
| F3 | 커맨드는 **대상 프로젝트 범위를 명시로** 받는다 | ★상태 키가 전역이라 범위 없이 긁으면 다른 프로젝트 이슈까지 옮긴다 (S6). 과다 집계는 안전하지만 **과다 이동은 데이터 손상**이다 |
| F4 | issue-tracking 어댑터가 `bulk_operations` 1건(`STATUS_MIGRATION`) + `bulk_operation_items` N건을 만들고 pgmq `q_bulk_operations` 에 넣는다 | 기존 인프라 재사용 |
| F5 | `BulkOperationType` 에 `STATUS_MIGRATION` 을 추가한다 | `BulkOperationRepository.toPayload` 의 `when (type)` **2곳**이 컴파일로 강제된다 |
| F6 | `BulkOperationPayload` 에 **새 변형** `StatusMigration(mappings)` 을 추가한다 | ★D2 결정. sealed class 라 `BulkItemApplier.when(payload)` 가 **컴파일 타임에 깨진다** — 분기 누락이 구조적으로 불가능 |
| F7 | 항목 처리 시 이슈의 **현재 상태로 매핑을 조회**해 대상을 정한다. 매핑에 없으면 그 건만 FAILED | 매핑이 여러 개라 대상은 항목마다 다르다 (S1) |
| F8 | 전환 검증(조건·검증기)과 후처리를 우회한다 | Jira **J8** 과 동일. 이관 대상은 유효한 전환이 없다 |
| F9 | 상태 쓰기는 **`IssueRepository.applyTransition` 을 그대로 재사용**한다. 직접 SQL 을 쓰지 않는다 | 그 메서드가 이미 OCC(`expectedVersion` · 0 row → 충돌)를 품고 있다. 엔진은 그 **위** 단계에만 있다 |
| F10 | `applyTransition` 에 **기존 `resolution_id` 를 그대로 실어** 보존한다 | `null` 을 넘기면 해결책이 **지워진다**(비DONE 재전환 clear 시맨틱). S4 |
| F11 | `IssueTransitioned` 이벤트를 발행한다 | 끄면 검색 색인·보드·감사가 옮겨간 이슈를 모른 채 썩는다 |
| F12 | `IssueHistoryRecorder` 로 상태 변경 이력을 남긴다 | 엔진을 건너뛴다고 이력까지 건너뛰면 감사 추적이 끊긴다 |
| F13 | 실패 항목은 기존 `BulkItemFailureRecorder` + `FailureReasonCode` 경로를 그대로 탄다 | best-effort 계약 무변경. 새 실패 코드를 만들지 않는다 |
| F14 | V038 으로 `chk_bulk_operations_operation_type` CHECK 에 `STATUS_MIGRATION` 을 더하고 `COMMENT` 를 갱신한다 | `V008:17` 의 CHECK 가 실재해 enum 만 늘리면 INSERT 가 거부된다 |

### ★D3 확정 — 우회 경계

`transitionIssue` 의 8단계 중 이관이 지나는 것과 건너뛰는 것을 **명시로** 고정한다.

| 단계 | 이관 | 근거 |
|---|---|---|
| ① `assertPermission(TRANSITION, Issue)` | **우회** | 남기면 관리자가 못 건드리는 이슈가 사라진 상태를 가리킨 채 남는다 — 이 기능이 막으려던 유령 상태를 이 기능이 만든다. 위조 차단은 호출자(PR 7b 발행 경로)의 `PUBLISH` 권한 책임 (`IssueMutationPort` 신뢰 모델). **편차 X4 로 기록** |
| ② `projectArchiveGuard` | **유지** | 아카이브 프로젝트는 쓰기 초크포인트가 이미 막는다. 이관만 예외를 둘 이유가 없다 |
| ③ `findByKeyForUpdate` (비관락) | **유지** | 동시 편집과의 경합을 그대로 막는다 |
| ④ `WorkflowKeyResolver` | **불필요** | 워크플로우를 찾을 필요가 없다 |
| ⑤ `workflowPort.plan()` (엔진) | **우회** | 유효한 전환이 없다. Jira **J8** 과 동일 |
| ⑥ `repo.applyTransition` | **유지** | OCC 포함. F9·F10 |
| ⑦ `IssueTransitioned` 발행 | **유지** | F11 |
| ⑧ `plan.emitEvents` | **N/A** | plan 자체가 없다. Jira J8 의 "Perform actions rules won't automatically do anything" 과 결과가 같다 |

### 이 PR 이 하지 **않는** 것 (사유 명시)

| 항목 | 사유 |
|---|---|
| project-workflow 결선 (`WorkflowPublishService` 가 포트를 부르는 자리) | ★D1 결정 = issue-tracking + shared-kernel 만. 「한 PR = 한 BC」. **PR 7b** 소관 |
| 부채 **143** — 이관 판정과 교체 사이 TOCTOU | 판정이 project-workflow 안에 있다. PR 7b |
| `countIssuesInStatus` 의 프로젝트 스코프(읽기 과다 집계) | 같은 이유. **쓰기 쪽 범위는 F3 이 이 PR 에서 막는다** — 과다 집계는 안전하고 과다 이동은 아니다 |
| 공간·작업유형별 예외 매핑 (Jira J9) | X1 |
| 이관 마법사 UI · 발행 다이얼로그 | 로드맵 PR 10 |
| `transitionId` 수용 · 모호 시 409 · 응답의 `transitionId` | **#395 가 이미 했다**(§범위 정정) |

## 비기능 요구사항 (NFR)

| # | 요구사항 | 판정 방법 |
|---|---|---|
| N1 | `BULK_TRANSITION` 회귀 **0**. 엔진 경로는 한 줄도 바뀌지 않는다 | 기존 일괄 전환 테스트 전량 green + diff 에서 `Transition` 가지 무변경 확인 |
| N2 | 새 REST 엔드포인트 **0건**. 이 PR 은 포트로만 들어온다 | `IssueController`·`BulkOperationController` 의 매핑 수 불변 |
| N3 | 멱등 — 워커 재실행 시 종단(SUCCEEDED/FAILED) 항목은 스킵 | 기존 `BulkOperation.isTerminal` 경로 재사용 |
| N4 | actor 는 커맨드로 전달받고 어댑터는 `SecurityContext` 를 읽지 않는다 | pgmq 워커에는 `SecurityContext` 가 없다. `IssueMutationPort` 선례와 동형 |
| N5 | 상한은 기존 `BULK_OPERATION_MAX_SIZE` 를 따른다 (J6) | 상한 초과 시 큐잉이 거부된다 |
| N6 | cross-BC 프로덕션 코드 **0줄**. shared-kernel 은 BC 가 아니라 계약 모듈이다 | ArchUnit BC 격리 룰 통과 |

## API 인터페이스 (REST)

**신규 REST 엔드포인트 0건.** 이 PR 의 진입점은 REST 가 아니라 cross-BC 포트다.

```
project-workflow ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
                          IssueStatusMigrationPort
```

```kotlin
// shared-kernel — com.bts.shared.issue
interface IssueStatusMigrationPort {
    /**
     * 이관을 큐잉하고 일괄작업 id 를 돌려준다. 실패는 예외로 던진다.
     * 어댑터는 per-issue 전환 권한을 검사하지 않는다 — 호출자가 발행 권한으로 이미 검사했다고 신뢰한다.
     */
    fun enqueueStatusMigration(cmd: StatusMigrationCommand): UUID
}

data class StatusMigrationCommand(
    val actorUserId: UUID,
    /** 대상 프로젝트 범위. 상태 키가 전역이라 이것이 없으면 남의 프로젝트 이슈가 함께 옮겨진다. */
    val projectKeys: Set<String>,
    /** 빠지는 상태마다 옮길 곳. Jira 의 「New status」 열과 같다(J7). */
    val mappings: List<StatusMigrationMapping>,
)

data class StatusMigrationMapping(
    val fromStatusKey: String,
    val toStatusKey: String,
)
```

`fun` 1개로 좁게 연다. 진행률 조회는 기존 `GET /api/v1/bulk-operations/{id}` 가 이미 하므로
포트에 조회 메서드를 만들지 않는다 — 두 번째 경로를 만들면 둘이 서로를 검사하지 않는다.

## 데이터 모델 변경

**`V038__bulk_operations_status_migration.sql`** (issue-tracking · 마지막 번호 V037 확인)

```sql
ALTER TABLE bulk_operations DROP CONSTRAINT chk_bulk_operations_operation_type;
ALTER TABLE bulk_operations ADD  CONSTRAINT chk_bulk_operations_operation_type
    CHECK (operation_type IN ('BULK_EDIT', 'BULK_TRANSITION', 'STATUS_MIGRATION'));
COMMENT ON COLUMN bulk_operations.operation_type IS '… 허용값: BULK_EDIT, BULK_TRANSITION, STATUS_MIGRATION …';
```

- 새 테이블 **0** · 새 컬럼 **0** · 백필 **0**. CHECK 확장 하나뿐이다
- `bulk_operation_items` 는 무변경 — `FAILED` + `failure_reason` 계약을 그대로 쓴다
- `payload` 는 JSONB 라 스키마 변경 없이 매핑 목록을 담는다. 형태 강제는 Kotlin sealed class 가 한다

```kotlin
data class StatusMigration(
    /** fromStatusKey → toStatusKey. 항목의 현재 상태로 조회해 대상을 정한다(F7). */
    val mappings: Map<String, String>,
) : BulkOperationPayload()
```

## 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| E1 | 매핑에 `from == to` 가 섞임 | 큐잉 거부. 옮길 것이 없는데 작업만 남는다 |
| E2 | `toStatusKey` 가 상태 카탈로그에 없음 | 큐잉 거부. 유령 상태를 만드는 것이 이 기능이 막으려던 그 사고다 |
| E3 | 대상 이슈 **0건** | 큐잉 거부. `total_count=0` 작업은 「완료」와 「할 일 없음」이 구분되지 않는다 |
| E4 | 대상이 상한(`BULK_OPERATION_MAX_SIZE`) 초과 | 큐잉 거부 (J6) |
| E5 | 매핑 목록이 **비어 있음** | 큐잉 거부 |
| E6 | 같은 `toStatusKey` 로 여러 `from` 이 몰림 | **허용**. 지라도 막지 않는다 (J7 은 대상 유일성을 요구하지 않는다) |
| E7 | 같은 `fromStatusKey` 가 매핑에 **두 번** 나옴 | 큐잉 거부. 어느 대상인지 정할 수 없다 |
| E8 | 처리 시점에 이슈의 현재 상태가 **매핑에 없음** (그 사이 누가 옮김) | 그 건만 FAILED. 임의 대상으로 밀어 넣지 않는다 (F7) |
| E9 | 이관 도중 이슈가 소프트 삭제됨 | 그 건만 `FAILED`/`NOT_FOUND`. 나머지 계속 (S3) |
| E10 | 이관 도중 다른 사용자가 그 이슈를 수정 | `applyTransition` 이 0 row → `VERSION_CONFLICT` 로 FAILED. 덮어쓰지 않는다 |
| E11 | 워커가 중간에 죽고 재시작 | 종단 항목 스킵, 미처리분만 이어서 (N3) |
| E12 | 큐잉 이후 그 상태로 **새 이슈가 들어옴** | **이 PR 범위 밖 — 이관되지 않는다.** 항목은 큐잉 시점 스냅샷이다. 이 창이 부채 **143**(TOCTOU)이고 PR 7b 가 닫는다. 숨기지 않고 KDoc 에 적는다 |
| E13 | 어댑터가 결선되지 않은 채 부팅 | **부팅 실패** (F1 fail-closed). silent-drop 보다 낫다 |
| E14 | 아카이브된 프로젝트의 이슈가 대상에 섞임 | `projectArchiveGuard` 가 막아 그 건만 FAILED (D3 ②) |

## 제약 조건

- **한 PR = 한 BC.** 프로덕션 변경은 issue-tracking 뿐이고 shared-kernel 은 계약 모듈이다
- **BC 격리.** issue-tracking 은 project-workflow 를 import 하지 않는다. 의존 방향은 포트가 정한다
- **다중 BC 트랜잭션 금지.** 그래서 동기 UPDATE 가 아니라 `bulk_operations` 큐잉이다
- **`DATA.md` §4** — 마이그레이션 규칙(제약 이름 명시 · 시각 컬럼 `TIMESTAMPTZ`)을 따른다
- **대상 상태가 새 워크플로우에 실제로 있는지는 호출자가 보증한다.** issue-tracking 은 워크플로우
  정의를 모른다 — 이 PR 은 상태 카탈로그 존재만 방어적으로 확인한다(E2)
- **이 PR 만으로는 사용자에게 보이는 변화가 0 이다** (결선이 PR 7b). 의도된 상태이며 게이트 2
  요약에 그대로 싣는다 — 「기능이 반쪽」이 아니라 「두 BC 로 나눈 절반」이다

## 측정 가능한 완료 기준

**red-first 4건**

1. 매핑이 여러 개일 때 각 이슈가 **자기 출발 상태의 대상**으로 간다 (S1 · F7)
2. 이관 후 대상 이슈의 `issues.current_state_key` 가 **전량** 새 상태다
3. 이관 중 실패한 건이 `bulk_operation_items` 에 `FAILED` + `failure_reason` 으로 남는다
4. 이관 경로가 **엔진을 타지 않는다** — 유효 전환이 0인 상태에서도 `TRANSITION_NOT_ALLOWED` 0건

**추가 red (자체 sanity check 가 찾은 것)**

5. 범위 밖 프로젝트의 같은 상태 이슈가 **무변경**이다 (S6 · F3)
6. 이관 후 `resolution_id` 가 **보존**된다 (S4 · F10)

**뮤테이션 짝 (비-공허 확인)**

- `BulkItemApplier` 의 `StatusMigration` 가지를 지우면 → **컴파일 실패**여야 한다(sealed 강제)
- 가지를 `Transition` 과 같은 본문으로 바꾸면 → **정확히 4번만** red 여야 한다
- `applyTransition` 의 `resolutionId` 인자를 `null` 로 바꾸면 → **정확히 6번만** red
- `projectKeys` 필터를 지우면 → **정확히 5번만** red
- V038 의 CHECK 확장을 되돌리면 → 큐잉 통합 테스트가 DB 제약 위반으로 red

**게이트**

- `./gradlew :modules:issue-tracking:test :modules:shared-kernel:test ktlintCheck detekt --rerun-tasks`
- 판별식 전량 (`pnpm test:workflow`)
- `bash scripts/verify-master-plan.sh` PASS · doc-index drift 0
- FR 수 **불변 143** · 신규 API **0** · 신규 의존성 **0** · cross-BC 프로덕션 **0줄**

## Sanity Check

**❓ 발견 6건 — 전부 보강 완료 (1회)**

| # | gap | 처리 |
|---|---|---|
| G1 | 엔진 우회가 **권한 검사까지** 삼키는지 불명 | ★D3 표로 8단계를 명시 고정. ①만 우회하고 편차 X4 로 기록 |
| G2 | 우회하면 `IssueTransitioned` 이벤트가 사라져 **검색 색인·보드가 썩는다** | F11 로 발행 유지를 못박음 |
| G3 | 우회하면 **낙관적 잠금**이 빠질 수 있다 | F9 — `applyTransition` 재사용으로 OCC 가 그대로 따라온다. 직접 SQL 금지 |
| G4 | 상태 키가 전역이라 **남의 프로젝트 이슈까지 옮겨진다** | F3 — 커맨드가 `projectKeys` 를 명시로 받는다. red 5번 |
| G5 | 「직접 재작성」이 모호 — 어느 seam 인가 | F9 가 `IssueRepository.applyTransition` 으로 확정 |
| G6 | `applyTransition(…, resolutionId=null)` 이 **해결책을 지운다** | F10 — 기존값 보존. red 6번 |

**Maxi 결정 반영 3건** — D1(BC 범위 = issue-tracking + shared-kernel) · D2(payload 변형 신설) ·
J7(상태별 대상 선택). D3(우회 경계)은 Jira **J8** 원문이 근거를 주어 A안으로 확정했다.

**✅ 통과** — 2회차 gap 없음. 남은 미확정은 「조회 실패 1건」뿐이고 그것은 이 PR 의 설계를 가르지 않는다.
