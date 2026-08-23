# FR-WF-05 — 전환 ID 식별자 (다중 전환 + 전역/최초 전환)

> FR. **FR-WF-05** · BC. project-workflow (shared-kernel 계약 추가 동반)
> 티어. T3 · 로드맵 PR 4 · 선행 #392(`213e8a317`) · #393(`00949bb1f`)
> 근거 ADR. `docs/adr/2026-08-18-workflow-transition-id-identity.md` (D1~D4, 채택됨)
> plan. `docs/plans/2026-08-20-backend-workflow-transition-id-multi-global.md`

## 사용자 시나리오 (Given-When-Then)

### S1. 같은 상태쌍에 이름이 다른 전환 둘

- **Given** 시스템 관리자가 워크플로우 `software-default` 를 보고 있고, 「검토 중 → 완료」 전환이 1개 있다.
- **When** 같은 상태쌍에 이름이 "조건부 승인" 인 전환을 하나 더 만든다.
- **Then** 201 로 생성되고 `GET /api/v1/workflows/software-default` 의 `transitions[]` 에 2건이 나온다.
  두 건은 서로 다른 `id`(UUID)를 갖고 `key`(`from__to`)는 같다.

### S2. 전역 전환

- **Given** 워크플로우에 상태가 4개 있다.
- **When** `kind=GLOBAL` · `fromStatusKey=null` · `toStatusKey=DONE` 인 전환을 만든다.
- **Then** 어느 상태에 있든 `availableTransitions` 후보에 그 전환이 포함된다.
  **단 현재 상태가 도착 상태와 같으면 제외한다** (자기 자신으로 가는 전환 금지).

### S3. 최초 전환

- **Given** 마이그레이션이 각 워크플로우에 `kind=INITIAL` 전환 1건을 백필해 뒀다.
- **When** 관리자가 상태 순서(`display_order`)를 바꾼다.
- **Then** 이슈 생성 시 진입 상태는 **바뀌지 않는다**. 시작 상태는 순서가 아니라 INITIAL 전환이 정한다.

### S4. 모호한 전환 호출

- **Given** S1 로 같은 상태쌍에 전환이 2개다.
- **When** 호출자가 `transitionId` 없이 `toStatusKey` 만으로 전환을 요청한다.
- **Then** **409 `AMBIGUOUS_TRANSITION`** 과 후보 목록(각 `transitionId`·`name`)을 응답한다.
  조용히 아무거나 고르지 않는다.
- **When** 호출자가 `transitionId` 를 실어 다시 요청한다.
- **Then** 그 전환으로 실행된다.

### S5. 기존 호출자 무변경

- **Given** agile-planning 보드 드래그앤드롭(`BoardApplicationService.kt:227`)과
  slack-integration 완료 모달(`SlackInteractionService.kt:385`)이 `toStateKey` 로만 호출한다.
- **When** 이 PR 이 머지된다.
- **Then** 두 곳은 **코드 변경 0** 이고, 후보가 1개인 한 지금과 똑같이 동작한다.

## Jira 대조

**비-UI 타입이므로 생략한다.** 화면은 로드맵 PR 8 이 담당한다.
단 스키마 설계의 Jira 패리티 근거는 ADR §D2 에 이미 기록돼 있다 — Jira Cloud 도 다이어그램에
별도 Create 노드를 그려 GLOBAL 과 INITIAL 을 나눈다.

## 기능 요구사항 (FR)

| ID | 내용 |
|---|---|
| F1 | `workflow_transitions.id`(UUID)가 전환의 1급 식별자다. `key`(`from__to`)는 하위호환 계산 프로퍼티로 남기되 매칭 기준이 아니다 |
| F2 | `UNIQUE(workflow_id, from_state_id, to_state_id)` 를 해제해 같은 상태쌍에 전환을 여럿 둔다 |
| F3 | `kind` 3종(`NORMAL`·`GLOBAL`·`INITIAL`)으로 전환 종류를 가른다. `GLOBAL`·`INITIAL` 만 `from` 이 NULL |
| F4 | `INITIAL` 은 워크플로우당 정확히 1개 (부분 유니크 인덱스로 강제) |
| F5 | `availableTransitions` 가 GLOBAL 전환을 현재 상태와 무관하게 후보에 넣는다. 단 `to == 현재 상태` 는 제외 |
| F6 | 전환 정의 CRUD — 생성·수정·삭제 |
| F7 | `transitionId` 로 전환을 지목해 실행할 수 있다. 없으면 `(from, to)` 로 후보를 찾아 **정확히 1개일 때만** 실행 |
| F8 | 후보가 2개 이상이면 409 `AMBIGUOUS_TRANSITION` + 후보 목록 |
| F9 | `Workflow.of()` invariant 재정의 — 「(from,to) 중복 금지」 제거, 「GLOBAL·INITIAL 의 from 은 NULL」·「INITIAL 은 1개」 추가 |
| F10 | 시작 상태 해석이 `display_order` 최소가 아니라 INITIAL 전환을 따른다 (`WorkflowKeyResolverImpl.kt:83`·`:123`) |

## 비기능 요구사항 (NFR)

| ID | 내용 |
|---|---|
| N1 | **읽기 API 응답 형태 불변.** `GET /api/v1/workflows/{key}` 는 `{ key, name, description, states[], transitions[] }` 를 유지하고 새 필드(`transitions[].id`·`kind`)는 **추가만** 한다 |
| N2 | **agile-planning·slack-integration main 0줄** (유지 · 실측 확인). shared-kernel 은 nullable 필드 **추가**만 (컴파일 파괴 없음). ~~issue-tracking 0줄~~ → **issue-tracking main 5파일 +80/−8 — 게이트 2 cross-BC 재판정(plan `:1451`)으로 승인된 확대.** `transitionId` 왕복을 REST 로 잇는 최소 표면(`TransitionIssueRequest`·`IssueController`·`IssueApplicationRequests`·`IssueApplicationService`·`AvailableTransitionsResponse`) |
| N3 | ~~`apps/web` 0파일~~ → **`apps/web` 27파일 +2302/−282.** 프론트 범위 재판정(plan `:1615` · Maxi C안)으로 이 PR 이 흡수했다. 로드맵 PR 8 로 남는 것은 **워크플로우 편집기 UI 본체**다 |
| N4 | 마이그레이션은 `DATA.md` §4 add → backfill → drop 3단 분할을 따른다. `workflow_states` 는 이 PR 에서 **DROP 하지 않는다** |
| N5 | 모든 쓰기 경로 끝에 `WorkflowCache.invalidate(workflowKey)` |
| N6 | 워크플로우 상태를 원시 SQL 로 심지 않는다 — 픽스처 헬퍼 사용 |

## API 인터페이스 (REST)

### ★ 결정 D-1 — 경로 충돌 해소

로드맵은 전환 CRUD 를 `POST/PUT/DELETE /api/v1/workflows/{key}/transitions` 에 두라 했으나
**그 경로의 POST 는 이미 존재한다** — `WorkflowController.kt:96` 의 `plan()`(전환 계획 계산).
Spring 은 같은 method+path 를 두 번 매핑하면 기동에 실패하므로 충돌을 반드시 해소해야 한다.

**채택 — (b) 기존 `plan` 을 `POST /api/v1/workflows/{key}/transitions/plan` 으로 옮기고
`/transitions` 를 전환 정의 컬렉션에 준다.**

근거.
1. REST 자원 의미상 `/workflows/{key}/transitions` 는 「전환 정의 컬렉션」이 맞다. `plan` 은 자원이
   아니라 계산이므로 하위 동사 경로가 정확하다.
2. **이동 비용이 실측으로 작다.** `apps/web` 에 이 엔드포인트의 **실호출부가 0** 이다 —
   `src/api/*.ts` 어디에도 없고 MSW 목 핸들러(`mocks/workflow-handlers.ts:30`) 1건과 백엔드
   `WorkflowControllerMvcTest` Case 3 만 안다. 손댈 곳이 2곳뿐이다.
3. 대안 (a)(CRUD 를 `/transition-definitions` 로) 는 같은 자원을 두 이름으로 부르게 만든다 —
   post-action 경로가 이미 `.../transitions/{transitionKey}/post-actions` 라 `transitions` 를
   전환 자원 이름으로 쓰고 있다.

**게이트 1 에서 Maxi 확인 대상.** 아직 아무도 호출하지 않아 되돌리기 쉬운 변경이지만 계약 변경이다.

### 전환 정의 CRUD (신규)

| 메서드 | 경로 | 응답 |
|---|---|---|
| `POST` | `/api/v1/workflows/{key}/transitions` | 201 + 생성된 전환 |
| `PUT` | `/api/v1/workflows/{key}/transitions/{transitionId}` | 200 + 수정된 전환 |
| `DELETE` | `/api/v1/workflows/{key}/transitions/{transitionId}` | 204 |

요청 바디(생성).

```json
{ "fromStatusKey": "IN_REVIEW", "toStatusKey": "DONE", "name": "조건부 승인", "kind": "NORMAL" }
```

- `kind` 생략 시 `NORMAL`.
- `kind=GLOBAL`·`INITIAL` 이면 `fromStatusKey` 는 반드시 없어야 한다 (있으면 400).
- `kind=NORMAL` 인데 `fromStatusKey` 가 없으면 400.
- 권한은 `WorkflowDefinitionPermission.UPDATE` resolver 호출 (#393 이 신설한 관례).
  **존재 확인을 권한 단언보다 먼저 두지 않는다** — 403/404 의미가 뒤집힌다.

### 이동

| 종전 | 이후 |
|---|---|
| `POST /api/v1/workflows/{key}/transitions` (전환 계획) | `POST /api/v1/workflows/{key}/transitions/plan` |

### 응답 추가 필드 (N1 — 추가만)

`GET /api/v1/workflows/{key}` 의 `transitions[]` 각 원소에 `id`·`kind` 추가.
`fromStateKey` 는 GLOBAL·INITIAL 일 때 `null` 이 될 수 있다 — 프론트 Zod 가 이 필드를
`z.string()` 으로 강제하고 있으면 깨지므로 **PR 8 이 nullable 로 완화**한다. 이 PR 은 프론트를
건드리지 않으므로, 백엔드 계약 변경 사실을 plan 의 「다음 PR 이 물려받는 제약」에 남긴다.

### ★ 결정 D-2 — 모호 전환 409 를 sealed 확장 없이 낸다

`TransitionResult` 는 sealed interface 이고 `IssueApplicationService.kt:1175` 가 4케이스를
exhaustive `when` 으로 받는다. 케이스를 추가하면 **issue-tracking 이 컴파일 실패**한다 = cross-BC
프로덕션 변경 = N2 위반이고 로드맵이 PR 7 에 배정한 일을 앞당기는 것이다.

**채택 — 예외 경로.** 전환 해석 실패는 **이미 예외 경로**다 (`WorkflowEngine.resolveTransition` 이
`WorkflowNotFoundException` 을 던진다). 모호성도 같은 층에서 `AmbiguousTransitionException(candidates)`
로 던지고 `WorkflowExceptionHandler` 가 **409 + `AMBIGUOUS_TRANSITION` + 후보 목록**으로 변환한다.
`WorkflowSchemeExceptionHandler` 의 `SchemeInUseException(usedByProjects) → 409` 가 정확한 선례다.
이렇게 하면 sealed 는 그대로이고 cross-BC 프로덕션 0줄이 지켜진다.

**검증 필수.** 예외가 issue-tracking 컨트롤러 경로에서도 409 로 나오는지 **통합 테스트로 실측**한다
— `@RestControllerAdvice` 스캔 범위에 따라 500 으로 뭉개질 수 있고, 그러면 이 결정이 무효다.
red-first 3번이 정확히 이 판정이다.

## 데이터 모델 변경

`V207__transitions_multi_and_global.sql` (project-workflow 대역 V200~V299).

```sql
-- ① 다중 전환 허용
ALTER TABLE workflow_transitions DROP CONSTRAINT workflow_transitions_workflow_id_from_state_id_to_state_id_key;

-- ② kind 도입
ALTER TABLE workflow_transitions
  ADD COLUMN kind TEXT NOT NULL DEFAULT 'NORMAL'
  CHECK (kind IN ('NORMAL', 'GLOBAL', 'INITIAL'));

-- ③ workflow_statuses 참조로 재지정 (add → backfill → 구 컬럼은 남긴다)
ALTER TABLE workflow_transitions
  ADD COLUMN from_status_id UUID NULL REFERENCES workflow_statuses (id),
  ADD COLUMN to_status_id   UUID NULL REFERENCES workflow_statuses (id),
  ADD COLUMN display_order  INT  NOT NULL DEFAULT 0;

-- ④ 백필 — from_state_id/to_state_id → workflow_statuses 행으로 대응
-- ⑤ INITIAL 백필 — 워크플로우별 display_order 최소 상태를 도착지로 1건씩
-- ⑥ display_order 백필 — row_number() OVER (PARTITION BY workflow_id ORDER BY created_at, name)
-- ⑦ 부분 유니크 — 워크플로우당 INITIAL 1개
CREATE UNIQUE INDEX uq_workflow_transitions_initial
  ON workflow_transitions (workflow_id) WHERE kind = 'INITIAL';
```

### ★ 정정 (2026-08-20 · wave 2 실측) — `NOT NULL` 승격과 `CHECK` 는 3단계로 이연한다

이 절은 원래 ⑤ 「백필 후 `to_status_id` 를 NOT NULL 로 승격」과 ⑧ 「`ck_transition_kind_from`
CHECK」를 이 PR 에 넣으라고 적었다. **그것이 `DATA.md §4-1` 의 add → backfill → drop 3단 분할과
어긋난다.** 2단계(지금)는 구·신 컬럼이 **공존**하는 구간이고, 구 컬럼으로 쓰는 코드가 아직 살아 있다.
신 컬럼에 NOT NULL 을 걸면 그 코드가 전부 깨진다.

**실측 파급** — `workflow_transitions` 에 직접 INSERT 하는 테스트가 **23파일**이고 그중 **21파일이
`to_status_id` 를 채우지 않는다**(issue-tracking 15 · project-workflow 6). 프로덕션 쪽은 더 나쁘다 —
`YamlSeedService.kt:451-456` 이 구 컬럼만 채우므로 **빈 DB 부팅 시 표준 워크플로우 시드가 전부
제약 위반으로 실패**한다.

**채택.** ⑤와 ⑧을 **로드맵 마지막 PR(3단계, `workflow_states` DROP 과 같은 PR)로 이연**한다.
그 대신 이 PR 은 —

1. **프로덕션 쓰기 경로가 신 컬럼을 채운다** — `YamlSeedService` · `WorkflowWriteRepository`.
   새로 들어오는 행은 신 컬럼이 정본이다.
2. **읽기가 구·신 양쪽을 견딘다** — 신 컬럼이 NULL 이면 구 컬럼으로 폴백한다. 3단계에서 폴백을 지운다.
3. **정의 시점 강제는 애플리케이션이 진다** — `Workflow.of()` 의 invariant(GLOBAL·INITIAL 의 from 은
   NULL · INITIAL 은 1개)가 이미 ⑧이 하려던 일을 한다. ADR §D4 의 「모호하면 정의 시점에 막는다」가
   그 근거다.

**기각한 대안** — 21파일을 전환 INSERT 픽스처 헬퍼로 일괄 이주(#393 의 `WorkflowStatusFixture`
선례). 방향은 옳지만 이 PR 을 두 배로 키우고, 3단 분할을 지키면 **애초에 필요 없는 작업**이다.
헬퍼 이주는 3단계 PR 이 NOT NULL 을 걸 때 그 PR 의 몫으로 남긴다.

- **`from_state_id`·`to_state_id` 는 이 PR 에서 DROP 하지 않는다** (N4). `workflow_states` 도
  살려 둔다 — 로드맵이 「마지막 PR 에서 떨어뜨린다」고 못박았다.
- **`init_codegen.sql` 미러 필수.** 컬럼을 더하면 jOOQ 상수가 생성되지 않아 리포지토리가 컴파일되지
  않는다 (`[[jooq-init-codegen-mirror]]`).
- 백필은 **유일성 가드**를 포함한다 — 대응하는 `workflow_statuses` 행이 없으면 `RAISE EXCEPTION`.
  V204 가 세운 관례다.

## 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | GLOBAL 전환의 도착 상태 = 현재 상태 | 후보에서 제외 (자기 자신 전환 금지) |
| E2 | INITIAL 전환을 2개째 만들려 함 | 409 — 부분 유니크 인덱스 + `Workflow.of()` 양쪽에서 막는다 |
| E3 | `kind=GLOBAL` 인데 `fromStatusKey` 를 보냄 | 400 |
| E4 | `kind=NORMAL` 인데 `fromStatusKey` 없음 | 400 |
| E5 | INITIAL 전환을 삭제하려 함 | 409 — 시작 상태가 사라지면 이슈 생성이 불가능해진다 |
| E6 | 전환에 validator·post-action 이 매달린 채 삭제 | CASCADE 로 함께 삭제. FR-WF-06 이 규칙 편집을 아직 열지 않아 고아 규칙이 남으면 안 된다 |
| E7 | 삭제된(soft-deleted) 워크플로우의 전환 CRUD | 404 |
| E8 | 존재하지 않는 `transitionId` 로 PUT/DELETE | 404 |
| E9 | 다른 워크플로우의 `transitionId` 를 이 워크플로우 경로로 호출 | 404 (경로의 `key` 와 소속 대조) |
| E10 | 모호 전환이 **보드 드래그앤드롭**에서 발생 | 409 로 정직하게 막는다. 선택 다이얼로그는 후속 (ADR §영향-부정) |
| E11 | `toStatusKey` 후보가 0개 | 기존과 동일 404 (`WorkflowNotFoundException`) |
| E12 | GLOBAL 전환이 여럿이고 같은 도착지 | 그것도 모호 — S4 와 같은 409 경로 |
| E13 | 전환이 출발지·도착지로 가리키는 상태를 워크플로우 편성에서 빼려 함 | 409 `WORKFLOW_STATUS_REFERENCED_BY_TRANSITION` — 막은 전환 이름을 본문에 싣는다. FK 가 `ON DELETE CASCADE`(V207 ③)라 그냥 두면 전환이 **하드 삭제**된다 |

## 제약 조건

- **한 PR = 한 BC** 가 원칙이고 **이 PR 은 그 예외다.** 승인 위치는 plan `:1451`(cross-BC 재판정) ·
  `:1615`(프론트 범위 · Maxi C안). shared-kernel 은 nullable 필드 추가만 허용(컴파일 파괴 없음).
  agile-planning·slack-integration main 소스는 **0줄을 유지**한다. issue-tracking main 과 `apps/web` 은
  재판정으로 열린 범위 안이다 — 그 밖으로 넓히려면 다시 판정을 받는다.
- **jOOQ 레코드 접근은 `Record.required(field)`** (`!!` 금지). 반환 타입 `T & Any`.
- **권한 축은 `isSystemAdmin` 하나.** `WorkflowDefinitionPermission` 4종은 감사·에러 메시지용 구분이지
  부여 단위가 아니다.
- **`RawWorkflowStateInsertGuardTest` · `WorkflowStatusFixtureParityTest` · `CacheInvalidationCoverageTest`
  를 초록으로 유지한다.** 셋 다 #393 이 세운 재유입 차단 판별식이다.
- **project-workflow 통합테스트는 issue-tracking 마이그레이션을 `testRuntimeOnly` 로 의존한다**
  (`[[bts-cross-bc-test-migration]]`). 마이그레이션 체인 테스트를 짤 때 이 전제를 지운다고 착각하지 않는다.

## 측정 가능한 완료 기준

| # | 기준 | 확인 방법 |
|---|---|---|
| C1 | 같은 (from,to) 에 이름이 다른 전환 2개가 생성되고 둘 다 조회된다 | red-first 통합테스트 |
| C2 | GLOBAL 전환이 모든 상태에서 후보에 나오고 자기 자신은 제외된다 | red-first 통합테스트 |
| C3 | `toStatusKey` 만으로 호출 시 후보 2개면 **409 `AMBIGUOUS_TRANSITION`** + 후보 목록 | red-first 통합테스트 (issue 경로 실측 포함) |
| C4 | 상태 `display_order` 를 바꿔도 이슈 생성 진입 상태가 안 바뀐다 | 통합테스트 |
| C5 | `GET /api/v1/workflows/{key}` 응답의 기존 5필드 구조가 그대로다 | 계약 스냅샷 테스트 |
| C6 | V207 마이그레이션 체인이 빈 DB·기존 DB 양쪽에서 통과 | `V207MigrationTest` (`V200MigrationTest.kt` 패턴, `quay.io/tembo/pg16-pgmq:latest`) |
| C7 | INITIAL 백필이 각 워크플로우당 정확히 1건, 도착지가 종전 `minByOrNull` 결과와 동일 | 마이그레이션 테스트 |
| C8 | agile-planning·slack-integration main **0줄** (재판정이 안 연 BC 는 그대로다) | `git diff --name-only main...HEAD -- backend/modules/{agile-planning,slack-integration}/src/main` 이 빈 출력 |
| C9 | `./gradlew :modules:project-workflow:test :modules:shared-kernel:test` 초록 | CI |
| C10 | 판별식 전량 pass | `pnpm test:workflow` EXIT 0. **건수를 적지 않는다** — 손으로 센 숫자는 판별식이 늘 때마다 조용히 낡는다(부채 `65`). 참고로 2026-08-23 실측은 393건 |

## Sanity Check

**gap 4건 발견 — 2건은 이 문서에서 결정으로 흡수, 2건은 게이트 1 확인 대상.**

| # | gap | 처리 |
|---|---|---|
| G1 | ❓ 발견 — 로드맵 PR 4 절 요약에 **INITIAL(최초 전환)이 빠져 있었다**. ADR §D2 와 FR §2.5 제목은 포함한다 | **범위에 포함**했다 (F3·F4·F10·C4·C7). 로드맵 요약이 축약이고 ADR 이 정본이다 |
| G2 | ❓ 발견 — `POST /{key}/transitions` **경로 충돌** (기존 plan) | 결정 D-1 로 흡수. **게이트 1 확인 대상** |
| G3 | ❓ 발견 — 모호 전환 409 를 sealed 확장으로 내면 **issue-tracking 이 컴파일 실패**(cross-BC) | 결정 D-2 로 흡수 (예외 경로). **게이트 1 확인 대상** + 통합테스트로 실측 |
| G4 | ❓ 발견 — `fromStateKey` 가 nullable 이 되면 프론트 Zod 가 깨질 수 있다 | ~~PR 8 이 물려받을 제약~~ → **재판정으로 이 PR 이 흡수해 닫았다.** `workflowTransitionViewSchema`(`apps/web/src/api/workflows.ts:40`)에서 완화했고 렌더 계층은 Task 20 이 처리 |

**남은 미확정 0건.** 2회째 보강 없이 게이트 1 로 간다.
