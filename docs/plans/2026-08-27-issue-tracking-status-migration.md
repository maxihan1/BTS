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
- 테스트 6건
  - 큐잉하면 `bulk_operations` 1건(`STATUS_MIGRATION`·`PENDING`) + pgmq 메시지가 생기고,
    **`bulk_operation_items` 는 0건 · `total_count` 는 0** 이다 (★리뷰 반영 · F4)
  - payload 에 `mappings` 와 `projectKeys` 가 **둘 다** 실린다 (F6 — 실행 시점 재해석의 입력)
  - 거부 — `from == to` (E1)
  - 거부 — `toStatusKey` 가 상태 카탈로그에 없음 (E2)
  - 거부 — 매핑이 비어 있음 (E5)
  - 거부 — 같은 `fromStatusKey` 가 두 번 (E7)
- 실패 메시지 (예상). `WorkflowStatusMigrationAdapter` 클래스 없음

**GREEN**. `AutomationIssueMutationAdapter` 를 준거로 어댑터를 만든다.
- **구조 검증 4종**(E1·E2·E5·E7)만 큐잉 시점에 한다 — 대상 건수·상한은 알 수 없다
- `bulk_operations` 만 만들고 pgmq enqueue. **items 는 만들지 않는다**
- `actorUserId` 를 그대로 `actor_id` 에 넣는다 (per-issue 권한 검사 없음 — 편차 X4)

**REFACTOR**. KDoc — ★**왜 items 를 여기서 안 만드는가**를 적는다. 장부 `TODOS.md:1613` 을 인용해
「세고 나서 옮긴다」를 피한 것임을 남긴다. `E6`(같은 대상으로 여러 출발이 몰리는 것)은
**허용**임도 명시 — 막지 않는 것도 결정이다.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*StatusMigrationEnqueueIntegrationTest' --rerun-tasks`

> ★**초안에서 뒤집힌 task 다.** 원래는 여기서 `projectKeys` 로 대상을 좁혀 **스냅샷**을 떴는데,
> `plan-eng-review` 가 그것이 곧 장부가 금지한 「세고 나서 옮긴다」임을 BLOCKER 로 잡았다.
> 범위 필터는 사라진 게 아니라 **Task 7 의 실행 시점으로 옮겨졌다.**

---

### Task 7. 워커가 실행 시점에 대상을 다시 긁는다 (「옮기면서 센다」)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/repository/BulkOperationRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/application/BulkOperationProcessor.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/integration/StatusMigrationMaterializeIntegrationTest.kt`]
- depends-on: [3, 5]
- jira: [J6]

**RED**.
- 파일 `…/bulk/integration/StatusMigrationMaterializeIntegrationTest.kt` (신규 · Testcontainers)
- 테스트 5건
  - ★**큐잉 이후 그 상태로 들어온 이슈도 이관된다** (완료기준 7 · E12). 큐잉 → claim 사이에
    이슈를 1건 더 넣고 그것까지 옮겨졌는지 본다 — **초안 설계에서는 red 였을 테스트다**
  - **범위 밖 프로젝트의 같은 상태 이슈는 무변경**이다 (완료기준 5 · S6 · G4).
    필터가 여기로 옮겨졌으므로 판정도 여기서 한다
  - 실행 시점 대상이 0건이면 `COMPLETED` · `total_count=0` 이다. **실패가 아니다** (완료기준 8 · E3)
  - 실행 시점 대상이 상한 초과면 `FAILED` + 사유다. **조용히 자르지 않는다** (E4 · J6)
  - 워커가 items 를 채우다 재시작해도 **중복 항목이 0** 이다 (완료기준 9 · E16 · F16)
- 실패 메시지 (예상). items 0건이라 `process()` 가 아무것도 처리하지 않고 끝난다

**GREEN**.
- `BulkOperationRepository` 에 **공개** 항목 적재 경로를 연다 — 지금 `insertItemsBatch` 는
  `private` 이고 `insert(operation)` 에서 1회만 불린다. 대상 조회(`current_state_key` ∈ 매핑 키
  ∩ 프로젝트 ∈ `projectKeys`)와 적재 + `total_count` 확정을 함께 둔다
- `BulkOperationProcessor.process()` 에 분기를 더한다 — `STATUS_MIGRATION` 이면
  `findItemsByOperationId` **전에** 적재를 먼저 돌린다
- 멱등은 `UNIQUE (bulk_operation_id, issue_key)` 에 기댄다. 새 중복 방지 코드를 만들지 않는다

**REFACTOR**. `BulkOperationProcessor` KDoc 의 「처리 흐름」 7단계에 **0단계(적재)** 를 더하고,
★**이 타입만 항목 생성 시점이 다르다**는 사실과 그 사유(`TODOS.md:1613`)를 적는다.
E15(이관 완료 → 정의 교체 사이 창은 PR 7b)도 함께 남긴다 — 닫힌 창과 남은 창을 구분해 적는다.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*StatusMigrationMaterializeIntegrationTest' --rerun-tasks`

> **뮤테이션 짝** — ① `projectKeys` 필터를 지우면 **범위 테스트만** red ② 적재를 `process()` 밖으로
> 빼(큐잉 시점으로 되돌리면) **「큐잉 이후 유입」 테스트만** red 여야 한다. 후자가 이 task 의
> 존재 이유를 그대로 재현하는 짝이다.
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

- **task 수 7** · **예상 wave 4** (리뷰 BLOCKER 로 T7 신설)
  - wave 1 — T1(마이그레이션) · T2(포트 계약) *병렬*
  - wave 2 — T3(enum + payload + repository) *T1 의존*
  - wave 3 — T4(엔진 우회) · T5(어댑터 큐잉) *병렬 · 파일 교집합 0*
  - wave 4 — T6(이벤트·이력) · T7(실행 시점 재해석) *병렬 · 파일 교집합 0*
    - T6 는 T4 와 `BulkItemApplier.kt` 가 겹쳐 자동 직렬화된다
    - T7 은 T3 과 `BulkOperationRepository.kt` 가 겹치지만 T3 이 wave 2 라 이미 끝나 있다
- **구현 규율** TDD red-first (T3 = 정식 TDD + 마이그레이션 검증). `test:` → `feat:`/`fix:` 순서가
  커밋 그래프에서 대조되어야 한다
- **추가 검증** `ktlintCheck` · `detekt` (둘 다 `--rerun-tasks` — worktree 가 gradle 설정 캐시를
  어긋나게 해 `UP-TO-DATE` 로 조용히 건너뛴다) · 판별식 전량 · `verify-master-plan.sh` · doc-index drift 0
- **뮤테이션 검증 5건** (전부 **GREEN 선커밋 뒤**) — ①`StatusMigration` 가지를 `Transition` 본문으로
  바꾸면 완료기준 1·2·4 만 red ②`resolutionId` 를 `null` 로 바꾸면 완료기준 6 만 red
  ③`projectKeys` 필터를 지우면 완료기준 5 만 red ④V038 을 되돌리면 큐잉 통합 테스트만 red
  ⑤**적재를 `process()` 밖(큐잉 시점)으로 되돌리면 완료기준 7 만 red** — 리뷰 BLOCKER 를
  그대로 재현하는 짝이라 이것이 없으면 T7 이 공허해진다
- **Jira 매핑** — `J1→T5` · `J6→T7` · `J7→T2·T3·T4·T5` · `J8→T4·T6` ·
  `J3→범위 밖(PR 6 이 이미 충족 — 상태별 잔여 건수를 발행 응답에 실었다)` ·
  `J5→범위 밖(#395 가 이미 충족 — 이 PR 은 전환 API 경로를 건드리지 않는다)` ·
  `J4·J9→기각(편차 X1)`. **채택 항목 차집합 0.**
- **범위 밖 재확인** — project-workflow 결선 · `countIssuesInStatus` 읽기 스코프는 **PR 7b**.
  이 PR 만으로는 사용자에게 보이는 변화가 0 이며 게이트 2 요약에 그대로 싣는다
- **부채 143 은 절반이 이 PR 로 옮겨왔다** — 큐잉 → 실행 창은 T7 이 닫는다. 남은 것은
  이관 완료 → 정의 교체 창이고 발행 경로의 루프가 필요해 PR 7b 다. `TODOS.md:1613` 의
  처방 문장이 「PR 7 이 한 몸으로」라 적혀 있으므로 **이 PR 에서 그 구분대로 정정한다**
  (`docs/rules/fr-sync-checklist.md` — 명세 변경 전수 동기화)

## 리뷰 결과

`type=migration` → 렌즈 **2종**(`/plan-eng-review` + `/plan-ceo-review`). `migration` 의 BLOCKER 는
무시 옵션이 없다(절대 규칙).

| 렌즈 | 결과 | BLOCKER | MAJOR | MINOR |
|---|---|---|---|---|
| `plan-eng-review` | ✅ 통과 (수정 반영 완료) | 1 → **닫힘** | 0 | 0 |
| `plan-ceo-review` | 🛑 **BLOCKER 1건** | **1** | 8 | 2 |

### eng 렌즈 — BLOCKER 1건 (반영 완료)

| # | 지적 | 처리 |
|---|---|---|
| E-B1 | 초안이 큐잉 시점 스냅샷이라 그 뒤 그 상태로 들어온 이슈를 버렸다. 장부 `TODOS.md:1613` 이 이 PR 을 이름으로 지목해 「세고 나서 옮긴다가 아니라 **옮기면서 센다**」를 처방해 뒀는데 정확히 그 반대였다 | **닫힘.** D4 = 실행 시점 재해석. Task 7 신설 · F15·F16 · E3·E4·E12 개정 · E15·E16 추가 · red 7·8·9 · 뮤테이션 ⑤ |

복잡도 게이트(12파일 · 신규 타입 5)는 울렸으나 **적정 규모로 판정**했다(D5) — 12 중 6이 테스트이고
프로덕션 7 중 3은 한·두 줄 추가다. 의미 있는 축소안이 없다.

### ceo 렌즈 — 🛑 BLOCKER 1건

| # | 지적 | 근거 |
|---|---|---|
| **C-B1** | **이관 1건이 사외 웹훅 N건을 쏜다.** F11 이 `IssueTransitioned` 발행을 유지하기로 했는데, 그 근거는 「검색 색인·보드가 썩는다」뿐이었다. 실제 소비자를 세어 보니 `search-export-import/.../WebhookDispatchWorker.kt` 가 있다 — **사외로 나가는 웹훅**이다. 1,000건 이관은 웹훅 1,000건이고 **웹훅은 되돌릴 수 없다.** notification 도 같은 이벤트로 수신자를 푼다 | 폭발 반경이 검토되지 않은 채 결정이 내려졌다. 관리자 1회 조작의 사외 부작용이 미판정이다 |

**처방 후보** — ①이벤트에 「이관에 의한 변경」 표시를 실어 소비자가 거를 수 있게 한다 ②이관 전용
이벤트 타입을 따로 둔다 ③발행은 유지하되 웹훅·알림 소비자가 그 타입을 무시하도록 PR 7b 에서
결선한다. **①이 최소 변경이면서 소비자 선택권을 남긴다.**

### ceo 렌즈 — MAJOR 8건

| # | 지적 | 처방 |
|---|---|---|
| C-1 | **빈 `projectKeys` 가 조용히 「할 일 없음」이 된다.** E5 는 빈 매핑만 막는다. 빈 범위는 통과해 실행 시점 0건 → `COMPLETED`. 운영자는 「이관 완료」를 보고 상태를 지운다 | 큐잉 시점에 빈 `projectKeys` 거부 (E5 에 한 줄) |
| C-2 | **상한 초과가 막다른 길이다.** E4 는 초과를 `FAILED` 로 두는데, 재시도해도 같은 결과라 **그 상태를 영영 못 뺀다.** 1,000명 규모에서 한 상태 1,000건 초과는 현실적이다 | ①청크 분할 ②이관에만 상한 완화 ③최소한 `FAILED` 사유에 「분할해 다시 시도」를 싣는다 |
| C-3 | **실패가 조용하다.** `bulk_operations` 가 `COMPLETED` 인데 `failed_count > 0` 이면 그 이슈들은 옛 상태에 남는다 = 유령. plan 에 메트릭·알림·대시보드가 **0건** | `failed_count > 0` 으로 끝난 `STATUS_MIGRATION` 을 로그·메트릭으로 드러낸다 |
| C-4 | **진단 가능한 실패가 `UNKNOWN` 으로 뭉개진다.** F13(새 실패 코드 금지)을 지키면 E8(매핑에 없는 상태)·E14(아카이브)가 `UNKNOWN` 으로 떨어지는데, 그 KDoc 은 「예상치 **못한** 내부 오류」다 | `STATE_NOT_IN_MAPPING` · `PROJECT_ARCHIVED` 2값 추가 + F13 문구 정정 |
| C-5 | **T7 의 핵심 테스트가 타이밍 의존이라 flaky 하다.** 「큐잉 → claim 사이에 이슈 1건 추가」는 워커가 즉시 claim 하면 순서가 뒤집힌다 | 워커를 자동 폴링이 아니라 **수동 트리거**로 두고 삽입 → 트리거 순서를 테스트가 통제한다 |
| C-6 | **마이그레이션 락·롤백 절차가 없다.** `ADD CONSTRAINT CHECK` 는 전체 스캔 + `ACCESS EXCLUSIVE` 락이다. 롤백 방향도 없다 — `STATUS_MIGRATION` 행이 하나만 생겨도 CHECK 를 못 되돌린다. **`DATA.md` 에 CHECK 무중단 규칙이 0건**(grep)이라 프로젝트 규칙 자체의 공백이다 | `NOT VALID` → `VALIDATE CONSTRAINT` 2단계. 롤백 절차를 V038 주석에 적는다 |
| C-7 | **로드맵 정본이 여전히 틀린 채로 있다.** spec §범위 정정은 spec 안에만 있고, `~/.claude/plans/cozy-hatching-otter.md` PR 7 절은 아직 `transitionId` 수용·409·응답을 할 일로 적는다. **다음 사람이 또 속는다** | 이 PR 에서 로드맵 PR 7 절을 정정한다 (명세 동기화 규칙) |
| C-8 | **구버전 워커가 새 enum 값에 죽는다** — `toPayload(enumValueOf(operationType))`. 이 PR 은 호출자가 없어 행이 안 생기므로 **지금은 안전**하지만 **PR 7b 진입 즉시 실재**한다 | PR 7b 착수 조건에 배포 순서·롤백 처방을 명시 |

### ceo 렌즈 — MINOR 2건

| # | 지적 | 처방 |
|---|---|---|
| C-9 | 「PR 7b 가 온다」가 기계로 남지 않는다. 부채 143 나머지 절반 · 결선 · `countIssuesInStatus` 스코프가 전부 그 약속에 매달려 있다 | 이 PR 에서 `TODOS.md` 에 PR 7b 항목을 등재한다 (장부 `:1613` 정정과 같은 커밋) |
| C-10 | 포트 KDoc 이 「위조 차단은 호출자 책임」이라 약속하는데 **이 PR 엔 호출자가 없어** 그 약속을 검사할 장치가 없다 | PR 7b 착수 조건에 「발행 경로가 `PUBLISH` 검사 뒤에만 포트를 부른다」를 명시 |

### 기각한 발견 2건 (실측으로)

- **인덱스 부재 의심 → 기각.** `V029__issue_filter_indexes.sql:16` 에 `(project_id, current_state_key)`
  복합 부분 인덱스가 있고 **정확히 우리 질의 모양**이다. `projectKeys` 스코프 설계를 성능 측면에서도
  뒷받침한다 — 전역 스캔이었다면 이 인덱스를 못 탄다
- **`FailureReasonCode` 신설 강제 → 기각.** `UNKNOWN` fallback 이 있어 F13 을 지킬 수 있다.
  다만 그 결과가 나쁘다는 것이 C-4 다

### CEO 4문 답

| 질문 | 답 |
|---|---|
| 두 BC 로 나눈 것이 옳은 거래인가 | **옳다.** PR #411 이 2일 전 정확히 같은 경계로 끊은 선례가 있고 「한 PR = 한 BC」는 하드 규칙이다. 다만 C-9 로 보완해야 약속이 증발하지 않는다 |
| 로드맵 정본의 유통기한 | **C-7.** spec 안에만 정정하면 로드맵은 계속 거짓말한다 |
| 부채 143 절반만 닫힘 | 절반은 **이 PR 이 닫는다**(E-B1 반영으로 승격). 나머지는 C-9 로 장부에 못 박는다 |
| 알림 1,000건 | **C-B1 — 알림보다 웹훅이 문제다.** 사외로 나가고 되돌릴 수 없다 |

### 생략한 절차

- **Outside Voice (교차 모델 검증)** — `codex` CLI 부재로 실행 불가. 조용히 건너뛰지 않고 여기 적는다
- **Section 11 (Design & UX)** — UI 범위 0. 신규 REST 엔드포인트 0건, 화면 변경 0

## GSTACK REVIEW REPORT

| Runs | Status | Findings |
|---|---|---|
| `plan-eng-review` (HOLD SCOPE) | ✅ 통과 | BLOCKER 1 → 닫힘 · 복잡도 게이트 1 → 적정 판정 |
| `plan-ceo-review` (HOLD SCOPE) | 🛑 BLOCKER | BLOCKER 1 · MAJOR 8 · MINOR 2 · 기각 2 |
| Outside Voice (codex) | ⏭ 생략 | `codex` CLI 부재 |

**VERDICT — 🛑 게이트 1 정지.** eng 렌즈 BLOCKER 는 닫혔으나 ceo 렌즈가 **C-B1(사외 웹훅 폭발
반경 미판정)** 을 새로 냈다. `type=migration` 이라 무시 옵션이 없다. Maxi 결정 필요.

**UNRESOLVED DECISIONS:**
- C-B1 처방 선택 — ①이벤트에 이관 표시 ②전용 이벤트 타입 ③소비자 측 무시(PR 7b)
- MAJOR 8건 중 이번 PR 에서 닫을 것과 PR 7b 로 넘길 것의 경계
