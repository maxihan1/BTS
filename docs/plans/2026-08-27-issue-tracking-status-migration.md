# issue-tracking 상태 이관 실행 (FR-WF-07 D2·D4·D5 · 로드맵 PR 7)

> 티어: T3
> slug: issue-tracking-status-migration
> type: migration
> agent: db-engineer
> 생성: 2026-08-27

> **spec 은 별도 파일이다** (T3 규칙) — `docs/specs/2026-08-27-issue-tracking-status-migration.md`

## Brief

**사용자 원문** — 「로드맵 PR 7 — issue-tracking 이관 실행」

**classify** — `type=migration` · `agent=db-engineer` · `slug=issue-tracking-status-migration` ·
선언 티어 **T3**. 근거는 표면 3중첩이다 — `MIGRATION`(V038) · `BE_MAIN`·`API`(issue-tracking) ·
포트 계약(설계 선택에 따라 `SHARED_KERNEL`). `/bts` 판정 규칙 ① 최고 티어.

**정본** — `~/.claude/plans/cozy-hatching-otter.md` PR 7 절(`:314`) ·
`docs/plan/product/project-workflow.md` §2.7(`:142`) · `.claude/STATE.md`(PR #411 이 세운 계약 6건).

### FR — FR-WF-07 의 D2·D4·D5 잔여분

- **D2 잔여** — cross-BC 포트 계약(이관 큐잉). 읽기 포트는 이미 있고 **쓰기**가 이 PR 몫
- **D4 잔여** — 이관 큐잉 호출. 지금은 이슈가 남으면 409 로 막고 상태별 건수만 응답에 싣는다
- **D5 잔여** — 「이관 후 이슈 상태 전량 이동」 테스트

### 범위 (로드맵 PR 7 절)

- `IssueStatusUsagePort` 에 `enqueueStatusMigration` 추가 + issue-tracking 어댑터 구현
- `BulkOperationType.STATUS_MIGRATION` 추가 — 엔진 우회 직접 재작성 + `issue_change_item` 이력 ·
  `BulkOperationProcessor` 분기
- `POST /api/v1/issues/{key}/transition` 이 `transitionId` 수용 + `toStatusKey` 모호 시 409
- `GET /api/v1/issues/{key}/transitions` 응답에 `transitionId` 추가
- issue-tracking **V038**

### 함께 닫아야 할 부채

- **143** — 이관 판정과 교체 사이 TOCTOU
- `countIssuesInStatus` 의 프로젝트 스코프 — 권한을 넓히기 전에 선행

### 착수 시점 실측 5건 (spec 이 판정할 것 · 재조사 불요)

1. **로드맵과 실제 구현이 어긋난다.** 로드맵은 「shared-kernel `com.bts.shared.issue.IssueStatusUsagePort`
   신설」이라 적었으나 실재하는 것은 `project-workflow/src/main/kotlin/com/bts/workflow/application/port/IssueStatusUsagePort.kt`
   이다. 어댑터(`adapter/outbound/IssueStatusUsageAdapter.kt`)가 jOOQ 동적 참조 `DSL.table("issues")`
   로 읽고, KDoc 이 근거를 적어 뒀다 — 「읽기 전용 스칼라 count 만 하는 것이 이 BC 의 선례
   (`IssueTypeUsagePort`)」. **PR 7 이 필요한 것은 쓰기라 그 면제가 그대로 넘어오지 않는다.**
   로컬 포트 유지 vs shared-kernel 승격이 이 PR 의 설계 갈림길이고 ADR 후보다.
2. **`shared-kernel` 에 `*UsagePort` 가 0건이다.** 로드맵 §손댈 파일 표가 지목한
   `shared-kernel/.../issue/IssueStatusUsagePort.kt` 는 실재하지 않는다. 표를 그대로 믿고
   「이미 있다」로 계획하면 어긋난다.
3. **마이그레이션이 필수다 — 추측이 아니다.** `V008__bulk_operations.sql:17` 에
   `CONSTRAINT chk_bulk_operations_operation_type CHECK (operation_type IN ('BULK_EDIT','BULK_TRANSITION'))`
   이 실재한다. 마지막 번호가 `V037__projects_archived_at.sql` 이므로 **V038** 이 맞다.
4. **`BulkOperationType` 은 현재 2값이다** — `BULK_EDIT` · `BULK_TRANSITION`
   (`issue-tracking/.../bulk/domain/BulkOperationType.kt:11`). exhaustive `when` 소비처와
   DB CHECK·DTO·프론트 유니온의 수동 갱신 지점 전수는 spec 이 센다.
5. **`countIssuesInStatus` 의 KDoc 이 이 PR 을 이름으로 예약해 뒀다** — 「프로젝트 → 스킴 →
   워크플로우 3단을 거슬러 정밀하게 좁히는 것은 로드맵 **PR 7** 의 일이다. 그때까지는 과하게
   막는 쪽을 택한다」. 부채 「프로젝트 스코프」가 곧 이 문장이다.

## 도메인 정리

**BC** — `issue-tracking` (프로덕션 변경 전량) + `shared-kernel` (계약 모듈 · BC 아님).
`classify.primary_bc` 가 `null` 이라 `BC_KEYWORDS` 정본으로 제목에서 추출했다.

**영향 엔티티**

| 엔티티 | 무엇이 바뀌나 |
|---|---|
| `bulk_operations` | `operation_type` CHECK 에 `STATUS_MIGRATION` 추가 (V038). 컬럼·테이블 변경 0 |
| `bulk_operation_items` | **무변경.** `FAILED` + `failure_reason` 계약을 그대로 쓴다 |
| `issues.current_state_key` | 이관 대상이 새 상태로 재작성된다. `resolution_id` 는 보존 |
| `issue_change_item` | 이관도 이력을 남긴다 (엔진을 건너뛴다고 감사 추적을 끊지 않는다) |

**새 용어 1건** — 「**상태 이관**(status migration)」. 기존 「일괄 전환(bulk transition)」과 구분된다 —
전환은 문으로 나가는 것이고 이관은 문 없이 옮기는 것이다. `glossary.md` 반영은 **Maxi 승인 후**에만
하며 이 PR 은 반영하지 않는다. `domain/issue-tracking.md` 도 자동 갱신하지 않는다.

**기존 결정과의 충돌 — 없음.** 관련 ADR 3건을 대조했다.

| ADR | 관계 |
|---|---|
| `docs/adr/2026-08-18-workflow-global-status-catalog.md` | 상태 키가 **전역**인 근거. 그래서 이관 범위를 프로젝트로 좁혀야 한다(spec F3) |
| `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` | 워크플로우 정의의 정본이 DB. 이관 판정이 DB 를 보는 이유 |
| `docs/adr/2026-08-18-workflow-transition-id-identity.md` | `transitionId` 계약. 이 PR 은 **건드리지 않는다**(#395 가 이미 완료) |

**ADR 후보 1건 (게이트 1 이 판정)** — 「이관은 per-issue 전환 권한을 우회하고 호출자의 발행 권한에
위임한다」(spec X4·D3 ①). 보안 표면의 의도적 결정이고 `IssueMutationPort` 의 신뢰 모델을
**쓰기 우회 경로로 확장**하는 것이라 별도 문서를 둘 값이 있는지 리뷰 렌즈가 본다.

**`grill-with-docs` 미호출 — 사유.** T3 이지만 신규 도메인 개념이 없다. 새 엔티티 0 · 새 경계 0 ·
새 포트는 기존 `IssueMutationPort` 패턴의 복제다. 용어 1건은 기존 일괄작업 타입의 추가일 뿐이다.

## Jira 대조 (계약 §1 — 0단계 재사용 승계 + 1단계 실물 조회)

> **정본은 spec 이다** — `docs/specs/2026-08-27-issue-tracking-status-migration.md` §Jira 대조에
> J1·J3·J4(승계) + **J5·J6·J7·J8·J9(신규 조회)** 9행과 편차 X1~X4 가 있다.
> 아래는 착수 시점의 승계분이며 spec 이 이를 포함해 확장했다.

§1-0 재사용 grep 결과 **직전 형제 PR 6 의 대조가 같은 표면**이었다
(`docs/plans/2026-08-26-workflow-draft-publish.md:40`). 그 표가 이관 실행을 **「PR 7 소관」으로
명시 이월**해 뒀으므로 아래 3행을 **출처 URL·조회일 그대로 승계**한다. 새로 조회하지 않는다 —
같은 표면을 두 번 조사하지 않는 것이 §1-0 이다.

| # | Jira Cloud 동작 | 원문 인용 | 출처 · 조회일 · 구분 |
|---|---|---|---|
| J1 | 이관은 상태 제거 즉시가 아니라 **발행(저장) 시점**에 시작 | "The moving process won't begin as soon as you remove a status from a workflow, but after you update the workflow to save it." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · 2026-08-26 · **Cloud** (PR 6 조회분 승계) |
| J3 | 삭제 대상 상태의 이슈를 **볼 수 있어야** 한다 | "If you need to see what work items are in the statuses you're deleting, try searching for work items and filtering by their statuses." | 위와 동일 · 2026-08-26 · **Cloud** (PR 6 조회분 승계) |
| J4 | 발행 요청이 **`statusMappings` 를 함께 받는다** | "the draft workflow includes new workflow statuses for an issue type, and mappings are provided to update issues with the original workflow status to the new workflow status" | https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-workflow-scheme-drafts/ · 2026-08-26 · **Cloud** (PR 6 조회분 승계) |

**PR 6 이 이 PR 로 넘긴 행** — 「J1 의 「이관 실행」 — 이슈 UPDATE 는 issue-tracking BC 소유다.
**PR 7** 소관」(`docs/plans/2026-08-26-workflow-draft-publish.md:70`). 이 PR 이 그 행을 닫는다.
PR 6 의 편차 X2(「발행 자체는 동기 · 비동기는 이관에만」)가 이 PR 의 설계 전제다.

### §1-1 실물 조회 결과 (2026-08-27)

Maxi 지시 「지라 클라우드처럼 해당 상태에서 이동할 상태를 선택할 수 있는 옵션을 주는 게 좋겠다」를
확인하러 조회했고 **결정적인 행 2개**를 얻었다. 전문과 원문 인용은 spec.

- **J7** — 「New status」 열에서 **빠지는 상태마다 대상을 각각** 고른다. Maxi 지시가 Jira 동작과 일치했다
- **J8** — 이관 시 **워크플로우 규칙이 통째로 안 돈다**(조건 `Restrict transition` · 검증기
  `Validate details` · 후처리 `Perform actions`). 엔진 우회가 발명이 아니라 **패리티**임을 확정했다
- **J9**(공간·작업유형별 예외 매핑)는 기각 → 편차 X1. J5(전환 충돌 400→409)·J6(일괄 상한)도 확보

### 조회 실패 1건 (생략과 구분해 기록)

`POST /rest/api/3/issue/{key}/transitions` 요청 본문의 전환 지목 형태는 `developer.atlassian.com`
REST 단일 페이지가 커서 `WebFetch` 가 truncate 돼 원문을 못 얻었다. **이 PR 의 설계를 가르지 않는다**
— 그 계약은 #395 가 이미 확정했고 이 PR 은 건드리지 않는다.

## 스펙

**정본** — `docs/specs/2026-08-27-issue-tracking-status-migration.md` (T3 규칙상 분리)

핵심 시나리오 3줄.

1. 빠지는 상태마다 대상을 따로 지정해 **한 건의** 이관 작업으로 큐잉한다 (J7 · Maxi 지시)
2. 워커가 **워크플로우 엔진을 우회**해 상태를 재작성하되 OCC·이력·이벤트·해결책은 보존한다 (J8)
3. 실패한 건은 개별로 `FAILED` + 사유로 남고 나머지는 계속 간다 (best-effort 계약 무변경)

★**범위가 절반으로 줄었다** — 로드맵 PR 7 의 4항목 중 3개(`transitionId` 수용 · 모호 시 409 ·
응답의 `transitionId`)를 **#395 가 이미 구현**했다. 컨트롤러를 직접 열어 확인했고 spec §범위 정정에 적었다.

## Sanity Check ✅ 통과

자체 점검에서 **gap 6건**을 찾아 1회 보강으로 전부 닫았다. 2회차 gap 없음.

| # | gap | 처리 |
|---|---|---|
| G1 | 엔진 우회가 권한 검사까지 삼키는지 불명 | ★D3 표로 8단계를 명시 고정 · 편차 X4 로 기록 |
| G2 | 우회하면 `IssueTransitioned` 가 사라져 검색 색인·보드가 썩는다 | F11 — 발행 유지 |
| G3 | 우회하면 낙관적 잠금이 빠질 수 있다 | F9 — `applyTransition` 재사용. 직접 SQL 금지 |
| G4 | 상태 키가 전역이라 **남의 프로젝트 이슈까지 옮겨진다** | F3 — `projectKeys` 를 명시로 받는다 |
| G5 | 「직접 재작성」의 seam 이 모호 | F9 로 확정 |
| G6 | `resolutionId=null` 이 **해결책을 지운다** | F10 — 기존값 보존 |

G4·G6 은 그대로 뒀으면 **데이터 손상**이었다. 둘 다 red 테스트(5·6번)와 뮤테이션 짝을 붙였다.

## Plan

### Task 1. V038 — `operation_type` CHECK 에 `STATUS_MIGRATION` 을 더한다

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V038__bulk_operations_status_migration.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/db/V038MigrationIntegrationTest.kt`]
- depends-on: []
- jira: []

**RED**.
- 파일 `…/bulk/db/V038MigrationIntegrationTest.kt` (`V008MigrationIntegrationTest` 서식 복제)
- 테스트 3건
  - `operation_type='STATUS_MIGRATION'` INSERT 가 **성공**한다
  - `operation_type='NONSENSE'` INSERT 는 여전히 **거부**된다 (CHECK 가 살아 있다는 비-공허 짝)
  - 기존 `BULK_EDIT`·`BULK_TRANSITION` INSERT 가 여전히 성공한다 (회귀)
- 실패 메시지 (예상). `new row for relation "bulk_operations" violates check constraint "chk_bulk_operations_operation_type"`

**GREEN**. `DROP CONSTRAINT` → `ADD CONSTRAINT` 로 3값 허용 + `COMMENT ON COLUMN` 갱신.

**REFACTOR**. 파일 머리에 한 줄 주석(마이그레이션 목적) · `DATA.md` §4 제약 이름 명시 규칙 확인.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*V038MigrationIntegrationTest' --rerun-tasks`

> ★**제약 이름을 그대로 재사용한다.** 이름을 바꾸면 다음 사람이 「어느 쪽이 진짜인가」를 못 푼다.
> `V008:16` 의 `chk_bulk_operations_operation_type` 을 그대로 쓴다.

---

### Task 2. shared-kernel 이관 큐잉 포트 계약을 신설한다 (fail-closed)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueStatusMigrationPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueStatusMigrationPortContractTest.kt`]
- depends-on: []
- jira: [J7]

**RED**.
- 파일 `shared-kernel/src/test/.../IssueStatusMigrationPortContractTest.kt`
- 테스트 3건
  - 인터페이스에 **default 구현이 0개**다 (fail-closed — 리플렉션으로 `isDefault` 를 센다)
  - 커맨드가 **매핑 목록**을 갖는다 (J7 — 단일 쌍이 아니다)
  - 커맨드가 **`projectKeys` 를 갖는다** (범위 없이 못 부른다)
- 실패 메시지 (예상). `IssueStatusMigrationPort` 클래스 없음

**GREEN**. `IssueStatusMigrationPort` + `StatusMigrationCommand(actorUserId, projectKeys, mappings)` +
`StatusMigrationMapping(fromStatusKey, toStatusKey)`. 계약 타입은 `String`·`UUID`·`Set`·`List` 뿐이고
반환은 `UUID` 다.

**REFACTOR**. KDoc — 의존 방향 다이어그램 · 「어댑터는 per-issue 전환 권한을 검사하지 않는다.
위조 차단은 호출자의 발행 권한 책임」(편차 X4) · fail-closed 사유. `IssueMutationPort` KDoc 을 준거로 한다.

**검증**. `./gradlew :modules:shared-kernel:test --tests '*IssueStatusMigrationPortContractTest' --rerun-tasks`
+ ArchUnit BC 격리 룰 통과

> **근거 learnings** — 「@Service / @Repository 의 KDoc 책임 분리 선언과 메서드 구현이 일치하는지
> 확인」(#8 ExternalAccountRepository 책임 침범). 포트 KDoc 이 약속한 신뢰 모델과 어댑터 구현이
> 어긋나면 그게 곧 보안 구멍이다.

---

### Task 3. `STATUS_MIGRATION` 타입과 `StatusMigration` payload 변형을 더한다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/domain/BulkOperationType.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/domain/BulkOperationPayload.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/repository/BulkOperationRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/application/BulkItemApplier.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/repository/BulkOperationRepositoryTest.kt`]
- depends-on: [1]
- jira: [J7]

**RED**.
- 파일 `…/bulk/repository/BulkOperationRepositoryTest.kt`
- 테스트 2건
  - `StatusMigration(mappings = mapOf("in_review" to "in_progress", "blocked" to "todo"))` 를 저장했다가
    읽으면 **같은 매핑**으로 복원된다 (JSONB 왕복)
  - `operation_type=STATUS_MIGRATION` 인데 payload 가 비면 빈 매핑으로 복원된다 (`toPayload` 의 null 가지)
- 실패 메시지 (예상). `Unresolved reference: STATUS_MIGRATION`

**GREEN**.
- `BulkOperationType` 에 `STATUS_MIGRATION` 추가
- `BulkOperationPayload` 에 `data class StatusMigration(val mappings: Map<String, String>)` 추가
- `BulkOperationRepository.toPayload` 의 `when (type)` **2곳**(`:367` null 가지 · `:372` 역직렬화 가지) 확장
- `BulkItemApplier.when (payload)` 에 `StatusMigration` 가지 추가 — 이 task 에서는 **`applyTransition` 을
  대상 상태로 부르는 최소 구현**만 둔다. 매핑 조회·보존 규칙은 Task 4

**REFACTOR**. `BulkOperationPayload` 머리 주석의 「BULK_EDIT(Edit) / BULK_TRANSITION(Transition)」을
3값으로 갱신 — 주석이 옛 목록을 남기면 그것이 두 번째 목록이 된다.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*BulkOperationRepositoryTest' --rerun-tasks`

> ★**이 task 의 본질은 컴파일러를 일하게 만드는 것이다.** `BulkItemApplier` 는 payload 로 분기하므로
> enum 만 늘리면 **컴파일러가 아무 말도 안 하고 옛 가지를 탄다.** sealed 변형을 더해야 `when` 이
> 깨지고 분기 누락이 구조적으로 불가능해진다 (D2). `when (type)` 2곳도 같은 원리로 강제된다.

---

### Task 4. 엔진을 우회하되 매핑·OCC·해결책은 지킨다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/application/BulkItemApplier.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/application/BulkItemApplierStatusMigrationTest.kt`]
- depends-on: [3]
- jira: [J7, J8]

**RED**.
- 파일 `…/bulk/application/BulkItemApplierStatusMigrationTest.kt` (신규)
- 테스트 5건 — spec §측정 가능한 완료 기준 1·2·4·6 + E8·E10
  - 매핑 2개일 때 각 이슈가 **자기 출발 상태의 대상**으로 간다 (완료기준 1 · J7)
  - 이관 후 `current_state_key` 가 **전량** 새 상태다 (완료기준 2)
  - 유효 전환이 0인 상태에서도 `TRANSITION_NOT_ALLOWED` **0건** — 엔진을 안 탄다 (완료기준 4 · J8)
  - 이관 후 `resolution_id` 가 **보존**된다 (완료기준 6 · G6)
  - 처리 시점 현재 상태가 매핑에 없으면 그 건만 FAILED — 임의 대상으로 밀지 않는다 (E8)
- 실패 메시지 (예상). Task 3 의 최소 구현이 매핑을 안 보므로 첫 테스트가 잘못된 대상으로 이동

**GREEN**. `StatusMigration` 가지를 완성한다.
- `existing.currentStateKey` 로 `mappings` 를 조회해 대상 결정 (없으면 예외 → 상위가 FAILED 기록)
- `repo.applyTransition(key, target, existing.version, existing.resolutionId)` — **기존 해결책을 그대로 실어 보낸다**
- `issueService.transitionIssue` 는 **부르지 않는다**

**REFACTOR**. 가지에 KDoc — 「무엇을 우회하고 무엇을 지키는가」 8단계 표를 요약하고 spec §D3 을 링크.
E12(큐잉 이후 들어온 이슈는 이관되지 않는다 · 부채 143)를 **KDoc 에 명시**한다.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*BulkItemApplierStatusMigrationTest' --rerun-tasks`

> **뮤테이션 짝 (GREEN 선커밋 뒤)** — ① `applyTransition` 의 `resolutionId` 인자를 `null` 로 바꾸면
> **해결책 보존 테스트만** red ② 매핑 조회를 첫 항목 고정으로 바꾸면 **매핑 테스트만** red.
> 저장소 규율대로 **커밋 후**에 흔든다.

---

### Task 5. 어댑터가 범위 안에서만 큐잉하고 잘못된 요청을 거부한다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/workflow/WorkflowStatusMigrationAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/integration/StatusMigrationEnqueueIntegrationTest.kt`]
- depends-on: [2, 3]
- jira: [J1, J6, J7]

**RED**.
- 파일 `…/bulk/integration/StatusMigrationEnqueueIntegrationTest.kt` (신규 · Testcontainers)
- 테스트 7건
  - **범위 밖 프로젝트의 같은 상태 이슈가 무변경**이다 (완료기준 5 · S6 · G4)
  - 큐잉하면 `bulk_operations` 1건(`STATUS_MIGRATION`·`PENDING`) + `items` N건 + pgmq 메시지가 생긴다 (J1)
  - 거부 — `from == to` (E1)
  - 거부 — `toStatusKey` 가 상태 카탈로그에 없음 (E2)
  - 거부 — 대상 0건 (E3) · 매핑 비어 있음 (E5)
  - 거부 — 상한 초과 (E4 · J6)
  - 거부 — 같은 `fromStatusKey` 가 두 번 (E7)
- 실패 메시지 (예상). `WorkflowStatusMigrationAdapter` 클래스 없음

**GREEN**. `AutomationIssueMutationAdapter` 를 준거로 어댑터를 만든다.
- `projectKeys` 로 **대상 이슈를 좁혀** 스냅샷을 뜬다
- 검증 6종을 통과한 요청만 `bulk_operations` + `items` 생성 후 pgmq enqueue
- `actorUserId` 를 그대로 `actor_id` 에 넣는다 (per-issue 권한 검사 없음 — 편차 X4)

**REFACTOR**. KDoc — 범위 필터가 없으면 남의 프로젝트 이슈가 함께 옮겨진다는 사유(G4)를 적는다.
`E6`(같은 대상으로 여러 출발이 몰리는 것)은 **허용**임을 명시 — 막지 않는 것도 결정이다.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*StatusMigrationEnqueueIntegrationTest' --rerun-tasks`

> **뮤테이션 짝** — `projectKeys` 필터를 지우면 **범위 테스트만** red 여야 한다.
> **근거 learnings** — 「영속 볼륨. 「마이그레이션이 안 넣음」≠「데이터 없음」」. 공용 dev DB 의
> 선재 행이 범위 테스트를 가짜 그린으로 만들 수 있으므로 **픽스처가 자기 프로젝트를 직접 만든다.**

---

### Task 6. 이관도 이벤트와 이력을 남긴다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/application/BulkItemApplier.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/application/BulkItemApplierStatusMigrationTest.kt`]
- depends-on: [4]
- jira: [J8]

**RED**.
- 파일 Task 4 와 같은 테스트 파일에 2건 추가
  - 이관 성공 시 `IssueTransitioned` 가 발행된다 (F11 · G2)
  - 이관 성공 시 `issue_change_item` 에 상태 변경이 남는다 (F12)
- 실패 메시지 (예상). 발행 0건 · 이력 0건

**GREEN**. `StatusMigration` 가지에서 `eventPublisher.publish(IssueTransitioned(...))` +
`IssueHistoryRecorder` 호출을 더한다. `plan.emitEvents`(후처리)는 **부르지 않는다** — plan 이 없다 (J8).

**REFACTOR**. KDoc 에 「무엇을 발행하고 무엇을 안 하는가」를 적는다 — Jira J8 의
"Perform actions rules won't automatically do anything" 과 결과가 같음을 인용한다.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*BulkItemApplierStatusMigrationTest' --rerun-tasks`

> ★**이벤트를 끄는 쪽이 더 위험하다.** `IssueTransitioned` 를 구독하는 검색 색인·보드·자동화가
> 옮겨간 이슈를 모른 채 썩는다 — DB 는 맞는데 화면이 틀리는 상태가 된다 (G2).

## Plan 메타

- **task 수** 6 · **예상 wave 4**
  - wave 1 — T1(마이그레이션) · T2(포트 계약) *병렬*
  - wave 2 — T3(enum + payload + repository) *T1 의존*
  - wave 3 — T4(엔진 우회) · T5(어댑터 큐잉) *병렬 · 파일 교집합 0*
  - wave 4 — T6(이벤트·이력) *T4 와 `BulkItemApplier.kt` 겹침 → 자동 직렬화*
- **구현 규율** TDD red-first (T3 = 정식 TDD + 마이그레이션 검증). `test:` → `feat:`/`fix:` 순서가
  커밋 그래프에서 대조되어야 한다
- **추가 검증** `ktlintCheck` · `detekt` (둘 다 `--rerun-tasks` — worktree 가 gradle 설정 캐시를
  어긋나게 해 `UP-TO-DATE` 로 조용히 건너뛴다) · 판별식 전량 · `verify-master-plan.sh` · doc-index drift 0
- **뮤테이션 검증 4건** (전부 **GREEN 선커밋 뒤**) — ①`StatusMigration` 가지를 `Transition` 본문으로
  바꾸면 완료기준 1·2·4 만 red ②`resolutionId` 를 `null` 로 바꾸면 완료기준 6 만 red
  ③`projectKeys` 필터를 지우면 완료기준 5 만 red ④V038 을 되돌리면 큐잉 통합 테스트만 red
- **Jira 매핑** — `J1→T5` · `J6→T5` · `J7→T2·T3·T4·T5` · `J8→T4·T6` ·
  `J3→범위 밖(PR 6 이 이미 충족 — 상태별 잔여 건수를 발행 응답에 실었다)` ·
  `J5→범위 밖(#395 가 이미 충족 — 이 PR 은 전환 API 경로를 건드리지 않는다)` ·
  `J4·J9→기각(편차 X1)`. **채택 항목 차집합 0.**
- **범위 밖 재확인** — project-workflow 결선 · 부채 143 · `countIssuesInStatus` 읽기 스코프는
  **PR 7b**. 이 PR 만으로는 사용자에게 보이는 변화가 0 이며 게이트 2 요약에 그대로 싣는다

## 리뷰 결과 (← /bts-review-plan 채움)
