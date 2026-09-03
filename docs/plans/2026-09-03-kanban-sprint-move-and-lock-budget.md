# 칸반 소속 스프린트 이관과 스프린트 시작 락 예산 (부채 165 · 166)

> 티어: T3
> slug: kanban-sprint-move-and-lock-budget
> type: migration
> agent: db-engineer
> 생성: 2026-09-03

FR-BD-04 후속. 스펙 정본은 [docs/specs/2026-09-03-kanban-sprint-move-and-lock-budget.md](../specs/2026-09-03-kanban-sprint-move-and-lock-budget.md),
설계 결정은 [docs/adr/2026-09-03-kanban-sprint-move-and-lock-budget.md](../adr/2026-09-03-kanban-sprint-move-and-lock-budget.md).

## Brief

장부 부채 **165**·**166** 을 한 PR 로 닫는다. 둘 다 `agile-planning` 이고 같은 함수
`SprintApplicationService.start` 를 가리킨다. 마이그레이션이 끼므로 티어는 T3(섞이면 최고 티어).

- **165** — #431 이 `resolveTargetBoard` 에 `boardType == SCRUM` 을 넣어 **생성**만 막았다.
  `start`(`SprintApplicationService.kt:286-303`)는 보드를 다시 읽지 않고 종류도 안 본다. 선재 칸반
  소속 스프린트는 `start` 200 을 받고 ACTIVE 가 되는데 `BoardApplicationService.kt:256` 이
  `boardType == SCRUM` 일 때만 `findActiveByBoard` 를 부르므로 어느 화면에도 나타나지 않는다.
- **166** — `start` 가 락 뒤에서 `findActiveByBoard` 만 재조회하고 스프린트 자체(`status`·`version`)는
  락 앞 스냅샷을 쓴다. READ COMMITTED 에서만 안전한데 `agile-planning` 은 격리 수준을 어디에도
  안 박는다(실측 0건 · identity-access 는 37곳 명시). `pg_advisory_xact_lock` 은 무한 대기이고
  `lock_timeout`·`statement_timeout` 이 저장소 전역 0건이다. 형제 락
  `acquireProjectScrumBoardLock` 도 같은 상태다.

classify 결과 — `type=migration · agent=db-engineer · tier=T1(착수 시점 diff 없음)`.
**선언 티어는 Maxi 지정 T3** 가 우선한다(`/bts` 판정 5문 ②).

## 확정된 결정 (Maxi · 2026-09-03)

| # | 결정 | 근거 |
|---|---|---|
| D1 | 165 처방 = **이관 마이그레이션 + `start` 가드** | 데이터를 살리면서 불변식을 잠그는 유일한 조합 |
| D2 | ACTIVE 충돌 시 **이관 대상을 PLANNED 로 내린다** | Jira 근거 없음(J17). `findActiveByBoard` 가 `limit(1)` 이라 ACTIVE 유지 시 이관 뒤에도 안 보이고 409 로 손도 못 대며 선행 스프린트 완료일에 예고 없이 나타난다 |
| D3 | 166 범위 = 격리 명시 + 락 뒤 재조회 + 락 예산 · **형제 락까지** | 두 락이 같은 결함을 공유한다 |
| D4 | **한 PR · T3** | 둘 다 `start` 를 고친다. 스택 분할은 #429→#430 교착 사고의 재발 자리 |

## Jira 대조

계약 §1-0 **재사용 승계.** J1·J5·J6·J11 은 [ADR 2026-09-01](../adr/2026-09-01-board-type-and-active-sprint.md)
이 실물 조회로 확정한 표에서 **출처·조회일을 그대로 승계**한다(조회일 2026-09-01 · 전 행 Jira Cloud
company-managed). 이번 PR 이 **새로 건드리는 조작**(스프린트를 다른 보드로 옮긴다 · 한 보드에 활성
스프린트가 둘일 때)만 추가 조회했다 — 조회일 **2026-09-03**.

| # | 원문 인용 | 출처 | Cloud/DC |
|---|---|---|---|
| **J14** | *"Sprints aren't dependent on a specific board or project. It's therefore possible for a sprint to be displayed in more than one board."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J15** | `originBoardId` = *"the ID of the board where the sprint was originally created."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J16** | *"The sprint was created while viewing that board... A board referencing the sprint was copied... Issues included in the board's JQL filter are associated with the sprint."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J17** | origin board 를 **바꾸는 조작을 문서가 다루지 않는다.** 공식 답변은 「새 스프린트를 만들어 이슈를 옮겨라」 | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J18** | *"If it's not intended for issue SSPA-9 to be in sprint SSPB 3, then it's suggested to disassociate sprint SSPB 3 from issue SSPA-9"* — 처방이 **스프린트 상태를 건드리지 않는다** | https://support.atlassian.com/jira/kb/multiple-active-sprints-in-agile-scrum-board-when-the-parallel-sprint-option-is-disabled/ | **DC 전용 · Cloud 아님** |

### 채택 판정

- **J14·J15·J16 — 기등재 편차 X5 를 재확인한다(패리티 포기).** Jira 는 보드-스프린트를 **필터 기반
  표시**로 풀고 소속은 origin(생성 이력)일 뿐이다. BTS 는 `sprints.board_id NOT NULL` **직접 소속**이다.
  이 PR 은 X5 를 바꾸지 않는다 — 저장 필터·AQL 은 search BC 소관이라 BC 격리상 참조 불가라는
  X1 승계 사유가 그대로 유효하다.
- **J17 — 대응 없음.** Jira 에 「스프린트를 다른 보드로 옮긴다」는 **사용자 조작이 없다.** 그러나 이 PR 의
  이관은 사용자 조작이 아니라 **데이터 정정**이고, `V506__sprint_board_id.sql:49-53` 이 이미 같은
  일(선재 스프린트 전 행 `board_id` 백필)을 했다. 선례의 확장이지 새 조작이 아니다.
- **J18 — 준용하지 않는다 (의도적 편차 X9 · 신규).** Jira 는 ACTIVE 다중을 데이터로 막지 않고 표시로
  흡수한다. BTS 는 `SprintRepository.findActiveByBoard` 가 `orderBy(created_at asc).limit(1)` 이라
  흡수가 **불가**하다. 그대로 두면 이관 대상이 화면에서 계속 안 보이고, `start` 는 409 로 막히며,
  선행 스프린트 완료일에 예고 없이 나타난다. 근거는 Jira 원문이 아니라 **BTS 자체 일관성**이며,
  D2 가 그 근거로 서 있다는 사실을 여기 적는다.

### 조회했으나 원문을 확보하지 못한 것

**칸반 보드에서 스프린트 생성을 시도**했을 때 Jira 가 무엇을 돌려주는지. J6
(*"Active sprints are only available on Scrum boards."*)가 「스크럼 전용」까지만 적고 거부 동작을
다루지 않는다 — Jira 는 UI 자체가 없어 **도달 불가**로 보이나 원문 확증은 없다. #431 이 남긴 같은
공백을 이번에도 메우지 못했다.

## 도메인 정리

- **BC. `agile-planning` 단독.** cross-BC 는 `shared-kernel` 포트(`IssuePermissionResolver`)만 탄다.
  `WorkflowCache` 는 `project-workflow` 소유라 import 불가 — 락 예산 헬퍼는 이 BC 안에 둔다.
- **영향 엔티티.** `Sprint`(`status`·`version`·`boardId`) · `Board`(`boardType`) ·
  `board_columns`(신설 스크럼 보드에 복제). `sprint_issues` 는 **안 건드린다** — 할당이 `sprint_id`
  기준이라 `board_id` 변경과 무관하다.
- **새 용어 없음.** `glossary.md` 갱신 불필요. 「origin board」는 Jira 용어로만 인용하고 BTS 개념으로
  도입하지 않는다(편차 X5).
- **기존 결정과의 관계.**
  - [ADR 2026-09-01](../adr/2026-09-01-board-type-and-active-sprint.md) **D5**(활성 스프린트 보드당 1개)를
    **완성**한다 — #431 이 생성 경로만 결선했다.
  - `V506:66-68` 의 「마이그레이션이 기존 데이터를 조용히 바꾸지 않는다」에 **예외를 낸다**.
    새 ADR 이 그 예외를 명시 승인하고 범위를 「칸반에서 옮겨 오는 행」으로 한정한다.
  - [ADR 2026-05-26](../adr/2026-05-26-jooq-execute-advisory-lock-exception.md) 의 raw SQL 예외
    조항 (i)+(ii) 안에서 `set_config` 를 쓴다.
- **관련 ADR.** 위 3건 + 이 PR 이 신설하는
  [2026-09-03](../adr/2026-09-03-kanban-sprint-move-and-lock-budget.md).

## 스펙

정본 → [docs/specs/2026-09-03-kanban-sprint-move-and-lock-budget.md](../specs/2026-09-03-kanban-sprint-move-and-lock-budget.md)

핵심 시나리오 3줄.

1. **S1** 마이그레이션 후 칸반에 매달렸던 스프린트가 스크럼 보드에 `PLANNED` 로 나타나고 담긴 이슈도 함께 보인다.
2. **S2** 칸반 보드 소속 스프린트에 `start` → **409 `AGILE_SPRINT_BOARD_NOT_SCRUM`** (전엔 200 + 안 보임).
3. **S4** advisory lock 은 **200ms** 만 기다리고 **503 `AGILE_UNAVAILABLE`** 로 끊는다 — 형제 락도 같다.

## Sanity Check

**❓ 발견 5건 → 4건 스스로 보강 · 1건 조치 불필요.** 전문은 spec `## Sanity Check`.

| # | gap | 처리 |
|---|---|---|
| G1 | `set_config(...,true)` 가 트랜잭션 스코프라 원복 없이는 **행 락 대기까지** 200ms 에 끊긴다 — 신규 회귀를 부를 뻔했다 | NFR **N6** 신설 — 락 획득 직후 `'0'` 원복 |
| G2 | `V507` 이 보드 이름 문자열의 **세 번째 사본**을 만든다 (부채 162 는 2개로 등재) | 제약에 명시 + **162 본문 갱신을 이 PR 범위에** 넣는다 |
| G3 | 형제 락 503 을 **어느 핸들러**에 다는지 미정 — 두 경로에서 도달한다 | E8 구체화 (양쪽 핸들러) |
| G4 | 마이그레이션 중 구버전 인스턴스가 칸반 ACTIVE 를 새로 만들 수 있다 | E9 신설 — 단일 호스트라 롤링 아님, 범위 밖 명시 |
| G5 | `ACTIVE → PLANNED` 시 `start_date` 정리 필요 여부 | **조치 불필요** — `Sprint.start()` 가 `status` 만 바꾼다(`Sprint.kt:76-84`) |

**남은 판단 위험 1건.** `55P03` 이 `JooqExceptionTranslator` 를 거쳐 도착하는 Spring 예외 타입은
문서에 적지 않고 **구현 단계 red-first 로 확정**한다 — 적으면 검증 안 된 두 번째 목록이 된다.

## Plan

경로 접두를 줄여 쓴다 — `AP_MAIN` = `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning`,
`AP_TEST` = `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning`,
`AP_MIG` = `backend/modules/agile-planning/src/main/resources/db/migration/agile-planning`.

### Task 1. V507 — 칸반 소속 스프린트를 스크럼 보드로 옮긴다 (R1·R2·R7)

**메타**.
- agent: `db-engineer`
- files: [`AP_MIG/V507__move_kanban_sprints_to_scrum_board.sql`, `AP_TEST/migration/KanbanSprintMoveMigrationTest.kt`]
- depends-on: []

**RED**: `AP_TEST/migration/KanbanSprintMoveMigrationTest.kt` 신설. 선례
`SprintBoardIdBackfillMigrationTest.kt:266-386` 의 부팅·시드 관용구를 복제한다.
```kotlin
@Test fun `칸반 보드에 붙은 스프린트가 프로젝트의 스크럼 보드로 옮겨진다`()
@Test fun `스크럼 보드가 없던 프로젝트에 신설되고 컬럼이 wip_limit 까지 복제된다`()
@Test fun `재적용해도 스크럼 보드가 중복 생성되지 않는다`()   // 멱등 · R7 · E11
@Test fun `V507 적용 후에도 V506 백필 6건의 단언이 전부 성립한다`()  // E10 회귀
```
실패 메시지(예상): `V507` 파일 부재 → Flyway 가 적용할 것이 없어 `board_id` 가 칸반 그대로.

**🔴 E11 — 멱등은 Flyway 로 못 잰다.** Flyway 는 같은 버전을 두 번 적용하지 않는다. 선례
(`SprintBoardIdBackfillMigrationTest`)가 `Flyway.configure().target("504")` → seed → `migrate()`
3단을 쓰는 그 구조를 따르고, 멱등만 **Flyway 밖에서 `V507` SQL 을 JDBC 로 직접 한 번 더 실행**해
`boards` 행 수 불변을 잰다.

**🔴 E10 — 기존 백필 테스트가 V507 까지 돈다.** 그 클래스는 일부러 `target` 을 안 고정했으므로
(`:31-33` KDoc) V507 도 적용된다. V506 ④ 가 전 스프린트를 스크럼에 붙이니 대상 0건일 것이나
**가정하지 않고** 그 6건이 여전히 초록임을 이 task 에서 실측한다.

**GREEN**: `V507__move_kanban_sprints_to_scrum_board.sql`. `V506` ②③ 형태를 복제하되
보드 신설을 `WHERE NOT EXISTS (…board_type='SCRUM'…)` 로 감싼다. 컬럼 복제는 `V506:33-45` 의
`LATERAL` 을 그대로. 대상 선정은 `deleted_at IS NULL` 인 보드 기준(E5).

**REFACTOR**: 단계 주석을 `V506` 밀도로. **`project_key || ' 스크럼 보드'` 사본이 3개가 되는
사실을 SQL 주석에 적고 부채 162 를 가리킨다**(Sanity G2).

**검증**: `./gradlew :modules:agile-planning:test --tests '*KanbanSprintMoveMigrationTest*'`

### Task 2. V507 — ACTIVE 충돌을 PLANNED 로 내린다 (R3·R4·R5·R6 · 편차 X9)

**메타**.
- agent: `db-engineer`
- files: [`AP_MIG/V507__move_kanban_sprints_to_scrum_board.sql`, `AP_TEST/migration/KanbanSprintMoveMigrationTest.kt`]
- depends-on: [1]

**RED**:
```kotlin
@Test fun `목표 보드에 기존 ACTIVE 가 있으면 이관 대상 ACTIVE 는 PLANNED 가 된다`()      // R4
@Test fun `목표 보드에 ACTIVE 가 없으면 이관 대상 중 created_at id 최앞 1건만 ACTIVE 다`() // R5
@Test fun `created_at 동점이면 id 로 갈라 같은 스프린트가 반복 실행에서도 남는다`()      // R5 tie-break
@Test fun `기존 스크럼 보드의 원래 ACTIVE 스프린트는 상태도 소속도 그대로다`()            // R6
@Test fun `이관 후 어느 board_id 에도 ACTIVE 가 2건 이상 없다`()                          // R3 사후 불변식
@Test fun `COMPLETED 스프린트는 board_id 만 옮기고 상태는 그대로다`()                     // E4
```
**GREEN**: `UPDATE … SET status='PLANNED', version = version + 1, updated_at = now()` 를
R4/R5 술어로 좁힌다. 기존 스크럼 행은 `WHERE` 에서 제외(R6).
**🔴 정렬은 `(created_at, id)` 다** — `BoardRepository.kt:263-265` 가 이미 그 규칙이고 주석이
「동점이면 id 로 가른다」를 명시한다. `created_at` 만 쓰면 동점 시 살아남는 스프린트가 실행마다 갈린다.

**REFACTOR**: R4·R5 술어를 CTE 로 갈라 읽히게. ADR `D2` 를 주석에서 링크.

**검증**: 위와 같음 + 완료 기준 1·2·3 의 SQL 을 테스트가 그대로 단언한다.

### Task 3. start 종류 가드 — 409 로 거부한다 (R8 · J11)

**메타**.
- agent: `backend-engineer`
- files: [`AP_MAIN/application/SprintApplicationService.kt`, `AP_MAIN/application/SprintExceptions.kt`, `AP_TEST/application/SprintApplicationServiceTest.kt`]
- depends-on: []
- jira: [J11]

**RED**:
```kotlin
@Test fun `start 칸반 보드 소속 스프린트는 SprintBoardNotScrumException 을 던진다`()
@Test fun `start 는 FSM 검증 뒤 종류 가드 뒤 락 순서로 판정한다`()   // verifyOrder
@Test fun `start FSM 위반은 종류 가드보다 먼저 판정된다`()            // :1071 계약 보존
```
`verifyOrder` 는 기존 `:1063-1067` 을 확장한다 —
`boardRepository.findById` → `acquireSprintStartLock` → `findActiveByBoard` → `updateStatus`.

**GREEN**: `SprintApplicationService.start` 에 `sprint.start()` **뒤**, 락 **앞** 3줄.
`SprintExceptions.kt` 에 `SprintBoardNotScrumException`.

**REFACTOR**: `resolveTargetBoard` KDoc(`:426-433`)의 「비대칭」 문단을 갱신 — 「`start` 는 안
막는다」가 더 이상 사실이 아니다. **읽기 경로 비대칭(R11)은 그대로 유지**함을 명시.

**검증**: `./gradlew :modules:agile-planning:test --tests '*SprintApplicationServiceTest*'`

### Task 4. 409 `AGILE_SPRINT_BOARD_NOT_SCRUM` 응답 계약

**메타**.
- agent: `backend-engineer`
- files: [`AP_MAIN/web/SprintExceptionHandler.kt`, `AP_TEST/web/SprintControllerTest.kt`, `AP_TEST/integration/SprintIntegrationTest.kt`]
- depends-on: [3]

**RED**:
```kotlin
@Test fun `POST sprints id start 보드가 스크럼이 아니면 409 AGILE_SPRINT_BOARD_NOT_SCRUM 을 반환한다`()
```
통합에서도 실 경로 1건 — 상태가 `PLANNED` 그대로임을 함께 단언(S2).

**GREEN**: `SprintExceptionHandler` 에 핸들러 + 코드 상수. 서식은 `:250-257`
(`SprintAlreadyActiveException` → 409) 을 복제.

**REFACTOR**: 핸들러 KDoc 의 매핑 목록에 새 행 추가.

**검증**: `--tests '*SprintControllerTest*'`

### Task 5. 격리 명시 + 락 뒤 재조회 (R9 · N1)

**메타**.
- agent: `backend-engineer`
- files: [`AP_MAIN/application/SprintApplicationService.kt`, `AP_TEST/application/SprintApplicationServiceTest.kt`]
- depends-on: [3]

**RED**:
```kotlin
@Test fun `start 는 락 뒤에 스프린트를 재조회해 그 version 으로 갱신한다`()
@Test fun `start 락 뒤 재조회에서 스프린트가 사라졌으면 404 다`()          // E6
@Test fun `start 락 뒤 재조회에서 이미 ACTIVE 면 전환 위반이다`()          // E7
```
첫 테스트는 `findById` 를 **락 앞 0L / 락 뒤 1L** 로 다르게 stub 하고
`updateStatus(sprintId, ACTIVE, 1L)` 를 단언한다. **뮤테이션 짝** — 재조회를 지우고 락 앞
스냅샷으로 되돌리면 이 1건만 red 임을 확인한다.

**GREEN**: `@Transactional(isolation = Isolation.READ_COMMITTED)` +
락 뒤 `findById` → FSM 재검증 → 재조회 `version` 으로 `updateStatus`.

**REFACTOR**: 락 앞뒤 판정 이유를 KDoc 3줄로. **격리 명시에는 판별식을 걸지 않는다** — 자기
파일을 읽는 단언은 리뷰에서만 도는 약한 판정이라 실효 판정은 이 재조회 테스트가 진다.

**검증**: `--tests '*SprintApplicationServiceTest*'` + 뮤테이션 1회 red 확인(GREEN 선커밋 뒤)

### Task 6. advisory lock 예산 — 두 락 공용 헬퍼 (R10 · N2·N3·N4·N6)

**메타**.
- agent: `backend-engineer`
- files: [`AP_MAIN/repository/AdvisoryLockBudget.kt`, `AP_MAIN/repository/SprintRepository.kt`, `AP_MAIN/repository/BoardRepository.kt`, `AP_TEST/repository/AdvisoryLockBudgetTest.kt`]
- depends-on: []

**RED**: Testcontainers 에서 홀더 트랜잭션이 같은 키를 쥔 채, 두 번째 호출이 **200ms 안에**
예외로 끊기는지 단언. **`55P03` 이 `JooqExceptionTranslator`(`AgilePlanningTestcontainersConfig.kt:123`)를
거쳐 도착하는 Spring 예외 타입을 추측하지 않는다** — 먼저 `assertThatThrownBy { … }` 로 실제
타입을 찍어 확인한 뒤 그 타입으로 단언을 고정한다.
```kotlin
@Test fun `홀더가 있으면 sprint-start 락은 200ms 안에 끊긴다`()
@Test fun `홀더가 있으면 scrum-board 락도 200ms 안에 끊긴다`()   // 형제 락
@Test fun `락 획득 후 lock_timeout 이 0 으로 원복된다`()          // N6
```
세 번째는 **같은 트랜잭션 안에서** `SHOW lock_timeout` 을 락 획득 뒤에 읽어 `0` 임을 단언한다.
`set_config(…, true)` 는 트랜잭션 스코프라 **트랜잭션 밖에서 읽으면 항상 `0` 이 나와 공허 통과**한다.
원복을 지우면 `200ms` 가 남아 red — 뮤테이션이 결함 지점을 실제로 지난다.

**GREEN**: `AdvisoryLockBudget.kt` 신설(신규 파일 · 439·491줄짜리 리포지터리를 더 늘리지 않는다).
```
set_config('lock_timeout', ?, true)  →  pg_advisory_xact_lock(hashtextextended(?, 0))
                                     →  set_config('lock_timeout', '0', true)
```
`SprintRepository.acquireSprintStartLock` · `BoardRepository.acquireProjectScrumBoardLock` 이
이 헬퍼를 부른다. 키 접두는 그대로 유지(N4).

**🔴 N7 — `@Transactional(propagation = MANDATORY)` 는 호출자 리포지터리 메서드에 그대로 남긴다.**
헬퍼가 Spring 프록시를 안 타는 순수 함수면 애너테이션이 아예 안 먹는다. `SprintRepository.kt:130`
KDoc 이 못박은 대로 `REQUIRED` 로 새면 **락이 그 자리에서 풀리는데 예외 없이 조용히 성공한다.**
판별식을 추가한다 — 트랜잭션 **없이** 부르면 예외가 나는 테스트 2건(두 락 각각).
```kotlin
@Test fun `트랜잭션 없이 acquireSprintStartLock 을 부르면 예외다`()
@Test fun `트랜잭션 없이 acquireProjectScrumBoardLock 을 부르면 예외다`()
```

**REFACTOR**: `DATA.md §5` 예외 (i)+(ii) 충족 사유와 실측 근거(206ms · `55P03`)를 KDoc 에.

**검증**: `--tests '*AdvisoryLockBudgetTest*'` · **N5 — 예산을 건 채로만 돌린다.**
`agile-planning` 은 컨테이너 1개·DB 1개 공용이라 무한 대기 테스트는 다른 클래스를 물고 멈춘다.

### Task 7. 락 타임아웃 → 503 (두 핸들러 · E8)

**메타**.
- agent: `backend-engineer`
- files: [`AP_MAIN/application/SprintExceptions.kt`, `AP_MAIN/web/SprintExceptionHandler.kt`, `AP_MAIN/web/BoardExceptionHandler.kt`, `AP_TEST/web/SprintControllerTest.kt`, `AP_TEST/web/BoardControllerIntegrationTest.kt`]
- depends-on: [6]

**RED**: 락 타임아웃이 **503 `AGILE_UNAVAILABLE`** 로 나가는지 두 경로에서.
`ensureScrumBoard` 는 ①`SprintApplicationService.resolveTargetBoard`(스프린트 **생성**) ②보드
컨트롤러 두 곳에서 도달하므로 **한쪽만 걸면 다른 쪽이 500** 이다(Sanity G3).

**GREEN**: Task 6 이 확정한 실제 예외 타입을 도메인 예외로 감싸고 두 핸들러에 매핑.
서식 정본은 `WorkflowExceptionHandler.kt:186-198`(503 `WORKFLOW_UNAVAILABLE`).

**REFACTOR**: 두 핸들러 KDoc 매핑 목록 갱신.

**검증**: `--tests '*SprintControllerTest*' --tests '*BoardControllerIntegrationTest*'`

### Task 8. 동시성 테스트 강화 — 행 대신 실패로 (부채 167 ②)

**메타**.
- agent: `qa-engineer`
- files: [`AP_TEST/integration/SprintIntegrationTest.kt`]
- depends-on: [3, 5, 6]

**RED**: `:307-349` 의 기존 테스트가 **실패 이유를 삼키고**(`runCatching`) `futures.map { it.get() }`
에 타임아웃이 없어 락이 안 풀리면 **행**으로 나타난다. 다음으로 바꾼다.
```kotlin
val results = futures.map { it.get(30, TimeUnit.SECONDS) }
assertThat(results.mapNotNull { it.exceptionOrNull() })
    .singleElement().isInstanceOf(SprintAlreadyActiveException::class.java)
// try/finally { executor.shutdownNow() }
```
**GREEN**: 프로덕션 코드 변경 없음 — Task 3·5·6 이 이미 계약을 세웠다. 이 task 는 그 계약을
**재는 눈**을 고친다.

**REFACTOR**: 왜 `runCatching` 만으로는 부족한지 KDoc 에 1문단. 선례
`BoardApplicationServiceTest.kt:391-431` 의 강한 단언을 링크.

**검증**: `--tests '*SprintIntegrationTest*'`

### Task 9. 장부 동시 갱신 — 2파일 (선택 아님)

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`, `docs/plans/2026-08-12-debt24-master.md`]
- depends-on: [1, 2, 3, 4, 5, 6, 7, 8]

**RED**: 해당 없음(문서). 판별식이 red 역할을 한다 —
`scripts/workflow/debt-ledger-mapping.test.ts` 가 두 파일의 집합 일치를 양방향으로 강제한다.

**GREEN**.
- `TODOS.md:1292`(165) · `:1302`(166) → `## ✅ … (해소 · PR #440)`
- `TODOS.md:1312`(167) 본문에 **「②는 #440 에서 닫혔다」** 추가. 항목은 ①③ 이 남아 **⬜ 유지**
- `TODOS.md` 162 본문에 **「V507 이 세 번째 사본을 만들었다」** 추가 (Sanity G2)
- **🔴 `TODOS.md` 157 본문 갱신 (리뷰 D8)** — 실측이 장부보다 크다.
  `SprintApplicationService.kt` **600줄** · `SprintRepository.kt` **491줄** ·
  `BoardRepository.kt` **439줄** (2026-09-03 `wc -l` · 상한 300). 장부는 마지막 하나만 적어
  다음 사람이 범위를 절반으로 오산한다. **항목은 ⬜ 유지** — 쪼개기는 이 PR 이 안 한다
- **🔴 `TODOS.md` 신규 항목 168 등재 (리뷰 D9)** — 「agile-planning — 보드 화면이 활성 스프린트를
  한 개만 그려 다중 ACTIVE 를 흡수하지 못한다」 `⬜ · 미배정 · T2`. 편차 `X9` 를 강제하는 **구조적
  한계**이고 ADR 기각 대안 A-6 의 추적 지점이다. 「쉬운 말」·「방치하면」 두 줄 필수(판별식)
- `docs/plans/2026-08-12-debt24-master.md:224`·`:225` → `⬜`→`✅`, `미배정`→`#440`.
  **168 행도 같은 표에 신설**(`⬜ · 미배정 · agile-planning`) — 장부와 마스터는 양방향 차집합 0 이라
  한쪽만 넣으면 판별식이 red 다
- `docs/progress.html` 은 **손대지 않는다** — post-merge 훅이 재생성한다

**REFACTOR**: 없음.

**검증**: `node --experimental-strip-types --test scripts/workflow/debt-ledger-mapping.test.ts` +
`pnpm test:workflow` 전량

## Plan 메타

- **task 수**: 9 · **예상 wave**: 4
  - wave 1 — T1 · T3 · T6 (서로 독립)
  - wave 2 — T2(T1 과 파일 겹침) · T4(dep 3) · T5(T3 과 파일 겹침) · T7(dep 6)
  - wave 3 — T8 (dep 3·5·6)
  - wave 4 — T9 (장부 · 전 task 선행)
- **구현 규율**: TDD red-first. `test:` 커밋이 `feat:` 보다 먼저 — 판별식 ①d 가 CI 에서 대조한다.
- **추가 검증**: `./gradlew :modules:agile-planning:test ktlintCheck detekt` (**파이프 금지** —
  `| tail` 을 붙이면 종료 코드가 tail 것이 된다) · `pnpm test:workflow` ·
  `bash scripts/verify-master-plan.sh` · `node scripts/build-doc-index.mjs --check`
- **Jira 매핑**: `J11 → T3` · `J14·J15·J16 → 편차 X5 재확인(task 없음 · 코드 변경이 아니다)` ·
  `J17 → 대응 없음(ADR D1 이 이관 근거를 BTS 데이터 정정으로 세운다)` ·
  `J18 → 편차 X9(준용하지 않음 · ADR D2)`. **채택 판정을 받은 번호는 J11 하나이고 T3 에 물렸다 —
  차집합 0.**
- **뮤테이션 짝 2건**: T5(락 뒤 재조회 제거) · T6(`lock_timeout` 원복 제거). 둘 다
  **GREEN 선커밋 뒤**에 확인한다 — 미커밋 원복은 소실이다.

## 리뷰 결과

`type == migration` → `/plan-eng-review` + `/plan-ceo-review` **2종** (bts-review-plan Step 2 표).
**BLOCKER 0 · findings 6 (P1 3 · P2 3) · 전량 반영 또는 결정 완료.**

### 절차 편차 (게이트 1 에서 확인할 것)

두 gstack 리뷰 스킬은 섹션마다 AskUserQuestion STOP 을 요구한다. 이 세션은 배경 작업이라
게이트 발화를 전부 돌리면 게이트 1 에 도달하지 못한다. **분석은 4섹션 + CEO 렌즈 전량 수행**하고,
**의례적 게이트만 합쳐 2건(D8·D9)으로 발화**했다. P1 3건은 계획 자체의 오류라 그 자리에서 고쳤다.
`plan-eng-review` scope gate 는 경로가 명시돼 예외 2 적용(질문 없음), `plan-ceo-review` 모드는
부채 수정이라 기본값 **HOLD SCOPE**.

### findings

| # | 렌즈 | 심각도 | 근거 | 판정 |
|---|---|---|---|---|
| **F1** | eng · 아키텍처 | **P1** (8/10) | `SprintRepository.kt:130` KDoc — *"`REQUIRED` 로 두면 … **락이 그 자리에서 풀리는데 예외 없이 조용히 성공한다** — 계약 위반이 침묵한다."* 락 예산 헬퍼로 추출하며 `MANDATORY` 를 잃을 수 있다 | **반영** — NFR `N7` 신설 · Task 6 에 「트랜잭션 없이 부르면 예외」 판별식 2건 추가 |
| **F2** | eng · 코드품질 | **P1** (9/10) | `BoardRepository.kt:263-265` 가 이미 `orderBy(CREATED_AT.asc(), ID.asc())` 이고 주석이 「동점이면 id 로 가른다」. spec R5 는 `created_at` 만 적어 **동점 시 살아남는 스프린트가 실행마다 갈린다** | **반영** — R5 를 `(created_at, id)` 로 · Task 2 에 tie-break 테스트 추가 |
| **F3** | eng · 테스트 | **P1** (8/10) | `SprintBoardIdBackfillMigrationTest.kt:31-33` KDoc — *"★ 마지막 단계에 `target("506")` 을 쓰지 않는다 … V507 이 들어올 때"*. 그 설계 덕에 클래스는 안 죽지만 **V507 까지 돌게 된다** | **반영** — 엣지 `E10` 신설 · Task 1 에 「V507 적용 후에도 백필 6건이 초록」 회귀 테스트 추가 |
| **F4** | eng · 테스트 | P2 (8/10) | Flyway 는 같은 버전을 두 번 적용하지 않는다. 「재적용 멱등」을 Flyway 로는 못 잰다 | **반영** — 엣지 `E11` 신설 · 멱등은 **JDBC 로 `V507` SQL 직접 재실행**으로 잰다 |
| **F5** | eng · 코드품질 | P2 (9/10) | 장부 157 은 `BoardRepository.kt` 439줄만 적는데 실측은 `SprintRepository.kt` **491줄** · `SprintApplicationService.kt` **600줄** — 장부가 실제보다 작다 | **Maxi 결정 D8 = 「157 본문만 갱신」** — Task 9 에 반영. 쪼개기는 범위 밖 |
| **F6** | ceo · HOLD SCOPE | P2 | 기각 대안 **A-6**(`limit(1)` 제거)이 ADR 표에만 있고 장부에 없다. 편차 `X9` 를 강제하는 구조적 한계가 추적되지 않는다 | **Maxi 결정 D9 = 「새 부채 168 등재」** — Task 9 에 반영 |

### 기각한 findings

- **`ensureScrumBoard` 에 200ms 가 짧다** (초기 7/10 → **3/10 기각**). `BoardApplicationService.kt:205`
  가 `columns = emptyList()` 로 삽입만 한다 — 락 안이 가볍다. 컬럼 시드는 조회 시 자가 치유가 한다.
- **`V507` 이 `V506` ②③ 를 복제한다 (DRY)** — 마이그레이션은 불변 이력이라 DRY 대상이 아니다.
- **두 락 순차 획득 시 `set_config` 덮어쓰기** (3/10). 각 호출이 자기 앞뒤로 걸고 되돌리므로 안전.
- **`start` 쿼리 2회 증가 (성능)** — 락 앞 `findById` + 락 뒤 재조회. 정당한 비용, N+1 아님.

### 섹션별 요약

| 섹션 | 결과 |
|---|---|
| Step 0 스코프 | 범위 유지. task 9 · 파일 12개 — 8파일 임계를 넘지만 **마이그레이션 1 + 서비스 1 + 리포지터리 2 + 핸들러 2 + 테스트 6** 구성이라 새 추상화가 1개(`AdvisoryLockBudget.kt`)뿐이다. 쪼개면 같은 `start` 함수를 두 번 열어 D4 와 충돌 |
| 1 아키텍처 | 1건 (F1) |
| 2 코드품질 | 2건 (F2 · F5) |
| 3 테스트 | 2건 (F3 · F4) · 커버리지 gap 0 (Task 1~8 이 R1~R11 · N1~N7 전량을 문다) |
| 4 성능 | 0건 |
| CEO (HOLD SCOPE) | 1건 (F6). 범위 확장·축소 제안 0 |
| outside voice | **미실행** — `codex_reviews` 상태를 배경 세션에서 확인하지 않았다. 게이트 1 에서 Maxi 가 필요하다고 보면 `/bts-codereview`(체인 [6], T3 = 2종)에서 잡는다 |

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 1 | CLEAR | mode: HOLD_SCOPE, 0 critical gaps, 1 finding (A-6 → 부채 168) |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 5 issues, 0 critical gaps (P1 3 반영 · P2 2 결정) |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | UI 변경 0 — 해당 없음 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** CEO + ENG CLEARED — ready to implement. 배경 세션이라 outside voice 는 안 돌았고,
체인 [6] `/bts-codereview` 가 T3 규정대로 독립 리뷰 2종을 별도로 돈다.

NO UNRESOLVED DECISIONS
