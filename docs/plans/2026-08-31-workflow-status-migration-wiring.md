# project-workflow 상태 이관 결선 + 발행 루프 (FR-WF-07 D4 · 로드맵 PR 7b)

> 티어: T2
> slug: workflow-status-migration-wiring
> type: api
> agent: backend-engineer
> 생성: 2026-08-31

> **T2 라 spec 을 이 파일 `## 스펙` 절로 흡수한다** — 별도 `docs/specs/` 파일을 만들지 않는다.

## Brief

**사용자 원문** — 「로드맵 PR 7b — 상태 이관 결선. `WorkflowPublishService` 가
`IssueStatusMigrationPort` 를 실제로 호출하게 한다.」

**classify** — `type=api` · `agent=backend-engineer` · `primary_bc=project-workflow` ·
`slug` 는 가독성을 위해 `workflow-status-migration-wiring` 으로 바꿔 썼다(판별식은 plan 파일명
형식 `YYYY-MM-DD-slug.md` 만 보고 classify 캐시와 대조하지 않는다 — 실측).

**★선언 티어는 T2 이고 classify 출력과 다르다.** classify 가 `tier=T1` 을 냈으나 표면이
`BE_MAIN` + `API`(신규 엔드포인트)라 표에 따르면 T2 다. `/bts` 판정 5문 ②(Maxi 지정 우선)로
**T2 를 선언**한다. classify 가 티어를 표면에서 추론하지 않는 것은 별건 결함이고 이 PR 범위 밖이다.
게이트 2 요약에 선언 티어와 실측 티어를 나란히 싣는다.

**정본** — 승인된 실행 계획 `~/.claude/plans/enchanted-brewing-bee.md` ·
로드맵 `~/.claude/plans/cozy-hatching-otter.md` PR 7b 절(`:348`) ·
착수 조건 각주 `docs/plan/product/project-workflow.md` §2.7 ·
장부 `TODOS.md` 부채 144(`:1629`) · 부채 143(`:1603`).

### FR — FR-WF-07 의 D4 잔여분

`docs/plan/product/project-workflow.md:151` 의 D4 는 「초안 CRUD · 발행 · 기본값 복원 · 이관 포트」인데
**결선 한 자리만 남아 있다**. PR #414 로 포트·어댑터·워커·`cause` 표시가 전부 들어왔으나
호출자가 없어 사용자에게 보이는 변화가 0 이다. 이 PR 이 D4 를 닫는다.

### 왜 지금 이것인가

`IssueStatusMigrationPort` 는 실재하고, `WorkflowStatusMigrationAdapter` 도 실재하고, pgmq 워커도
돈다. 그런데 `WorkflowPublishService` 는 여전히 빠지는 상태에 이슈가 남으면 409 로 막고 건수만
응답에 실을 뿐이다 — 관리자가 이관을 시작할 입구가 없다. Obsidian `learnings.md:758` 이 적은
「도메인·서비스·repo 가 다 있어도 REST 노출이 없으면 기능이 없는 것이다」가 정확히 이 상태다.

### 확정 결정 3건 (Maxi 승인 · 계획 단계)

| # | 결정 | 근거 |
|---|---|---|
| D2 | **별도 `migrate` 엔드포인트.** `POST /publish` 는 의미 불변 | 발행 API 가 「발행하지 않고 202」를 돌려주는 상태를 안 만든다. 부수 효과로 트랜잭션이 BC 를 안 넘는다 |
| D3 | **결선 먼저.** `cause` 필터 3 BC · VIEW 오진은 범위 밖 | 마법사 UI 가 PR 10 이라 실사용 유입구가 아직 닫혀 있다. 후속은 PR 10 의 명시적 선행으로 장부 등재 |
| D4 | **`replaceDefinition` 직후 재카운트 + 롤백.** 잔여 창은 한계로 명시 | BC 를 안 넘고 뒤쪽 창의 범위를 줄인다. 부채 143 은 「축소」로 갱신하고 닫았다고 쓰지 않는다 |

### 범위

**넣는 것** — ① 결선 ② `workflowId → projectKeys` 3단 JOIN(결선의 전제) ③ `countIssuesInStatus`
프로젝트 스코프 ④ 뒤쪽 창 축소 ⑤ 권한 검사 순서 판정(C-10) ⑥ 포트 KDoc 계약 2축 red-first
⑦ C-8 배포 순서·롤백 문서.

**빼는 것 (장부 등재 후 이월)** — `cause="STATUS_MIGRATION"` 소비자 필터(search-export-import ·
notification · slack-integration 3 BC) · VIEW 미보유 이슈 `NOT_FOUND` 오진(`TODOS.md:1655`) ·
PR 7 잔여 7건(`TODOS.md:1650-1657`). 전부 다른 BC 라 「한 PR = 한 BC」에 걸린다.

## Jira 대조 (전 타입 필수)

§1-0 재사용 grep 으로 형제 PR 6(`docs/plans/2026-08-26-workflow-draft-publish.md`)과
PR 7(`docs/specs/2026-08-27-issue-tracking-status-migration.md`)에서 J1·J4·J6·J7·J8 을
**출처 URL·조회일 그대로 승계**했다. 이번 PR 이 새로 건드리는 조작 2건(카운트의 프로젝트 스코프 ·
아카이브 프로젝트 취급)만 §1-1 실물 조회했고, 그 결과는 아래 「조회했으나 문서에 없던 것」에 적었다.

| # | Jira Cloud 동작 | 원문 인용 | 출처 · 조회일 · 구분 |
|---|---|---|---|
| J1 | 이관은 상태 제거 즉시가 아니라 **발행(저장) 시점**에 시작 | "The moving process won't begin as soon as you remove a status from a workflow, but after you update the workflow to save it." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · 2026-08-26 · **Cloud** (PR 6 승계) |
| J4 | 발행 요청이 **`statusMappings` 를 함께 받는다** | "the draft workflow includes new workflow statuses for an issue type, and mappings are provided to update issues with the original workflow status to the new workflow status" | https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-workflow-scheme-drafts/ · 2026-08-26 · **Cloud** (PR 6 승계) |
| J6 | 발행은 **비동기 task** 이고 응답의 location 링크로 진행을 확인한다 | "The publish operation is asynchronous, and you should follow the location link in the response to determine the status of the task" | 위와 동일 · 2026-08-26 · **Cloud** (PR 6 승계) |
| J7 | 빠지는 상태마다 옮길 곳을 **각각** 고른다 | "In the modal that shows up, choose new statuses in the **New status** column." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · 2026-08-27 · **Cloud** (PR 7 승계) |
| J8 | 이관은 워크플로우 규칙을 타지 않는다 | "Regardless of what statuses you choose, workflow rules won't be triggered." | 위와 동일 · 2026-08-27 · **Cloud** (PR 7 승계) |
| **J10** | 이관 확정 조작이 **발행 버튼 자체**다 | "Review your changes and select **Update workflow**." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · **2026-08-31** · **Cloud** |

### 채택 판정

- **J1·J7·J8 채택 (이 PR 이 닫는 행).** PR 7 이 능력을 만들고 이 PR 이 호출자를 붙여 J1 을 실제로 성립시킨다. 상태별 매핑 목록(J7)과 규칙 우회(J8)는 PR 7 이 이미 구현했고 이 PR 은 그것을 부른다.
- **J6 채택 (형태만).** 「비동기 + 폴링 핸들」 모델을 그대로 쓴다 — 다만 BTS 는 폴링 대상이 발행 태스크가 아니라 **이관 태스크**다(`GET /api/v1/bulk-operations/{id}`). 발행 자체는 여전히 동기다(X2 승계).
- **J10 → X5.** 지라는 확정 조작이 하나(**Update workflow**)지만 BTS 는 둘로 나눈다.
- **J4 → X5.** 같은 축이다.

### 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| X1 | 지라는 공간·작업유형별 예외 매핑을 갖는다. BTS 는 **상태 단위 매핑만** | PR 6·7 X1 승계. 워크플로우가 전역이고 유형별 배정은 스킴(FR-WF-02)이 담당한다 |
| X2 | **발행은 동기 · 이관만 비동기** | PR 6·7 X2 승계 |
| X4 | 지라 문서는 권한을 말하지 않는다. BTS 는 per-issue TRANSITION 권한을 우회하고 **호출자의 발행 권한**에 위임한다 | PR 7 X4 승계. 이 PR 이 그 위임의 수신자를 만든다 — 그래서 **F5 판정**이 이 편차의 값을 처음으로 지불한다 |
| **X5** | 지라는 `statusMappings` 를 **발행 요청에 실어** 조작 1회로 끝낸다(J4·J10). BTS 는 **`POST …/publish/migrate` 와 `POST …/publish` 두 호출**로 나눈다 | 한 호출로 묶으면 발행 API 가 「발행하지 않고 202 를 돌려주는」 상태를 갖게 된다. 나누면 각 엔드포인트의 의미가 하나로 유지되고, 부수적으로 **트랜잭션이 BC 를 안 넘는다**(F4 참조) — `DATA.md §6` 을 만나지 않는 것은 이 분할의 결과다. 화면 흐름은 지라와 같다(빠지는 상태 확인 → 대상 지정 → 진행률 → 발행 확정) |
| **X6** | 지라는 이관 대상 범위를 문서화하지 않는다. BTS 는 **그 워크플로우를 쓰는 프로젝트로 명시 한정**한다 | 아래 「조회했으나 문서에 없던 것」 ① 참조. 근거가 지라에 없으므로 「지라가 그렇게 한다」고 주장하지 않는다 — BTS 내부 근거는 상태 키가 전역이라 스코프가 없으면 남의 프로젝트 이슈까지 세어 발행을 과하게 막는다는 것이다 |
| **X7** | **아카이브 프로젝트는 카운트·이관 범위에서 뺀다** | 아래 「조회했으나 문서에 없던 것」 ② 참조. 근거는 E7 |

### 조회했으나 문서에 없던 것 2건 (생략이 아니다)

`support.atlassian.com` 의 이관 문서를 2026-08-31 에 다시 조회했다. 두 축 모두 **문서에 서술이 없다**.

① **이관 대상 이슈의 범위** — 그 워크플로우를 쓰는 프로젝트로 한정되는지, 사이트 전역인지에 대한
문장이 페이지에 없다. 따라서 X6 은 지라 패리티가 아니라 BTS 판단이다.
② **아카이브 프로젝트·아카이브 이슈의 취급** — 포함·제외 서술이 페이지에 없다. X7 도 BTS 판단이다.

`developer.atlassian.com` 의 workflow-scheme-drafts REST 페이지는 **단일 페이지가 커서 `WebFetch`
가 truncate** 돼 이번에는 원문을 새로 얻지 못했다. PR 6 이 같은 페이지에서 확보한 J4·J6 인용을
승계하는 것으로 갈음한다. **조회 실패이지 생략이 아니다** — PR 7 spec 이 같은 페이지에서 같은
한계를 기록했다.

## 도메인 정리

**BC** — `project-workflow` 단일. 쓰기는 `bulk_operations`(issue-tracking 소유)에만 일어나고
그 경로는 `shared-kernel` 의 `IssueStatusMigrationPort` 계약을 거친다.

**영향 엔티티** — `WorkflowDraft`(읽기) · `WorkflowVersion`/정의 편성(읽기·교체) ·
`WorkflowSchemeIssueTypeMapping`·`ProjectWorkflowSchemeAssignment`(신규 읽기 경로) ·
`Project`(신규 읽기 — key·deleted_at·archived_at) · `BulkOperation`(포트를 통한 생성).

**새 용어** — 없다. 「이관(status migration)」·「발행(publish)」·「스킴(scheme)」은
`glossary.md` 에 이미 있고 이 PR 이 의미를 바꾸지 않는다. `glossary.md` 갱신 대상 아님.

**관련 ADR 4건** — 충돌 0건.

| ADR | 이 PR 과의 관계 |
|---|---|
| `2026-06-14-fr-nt-05-transition-event-outbox.md` | 이관이 발행하는 `IssueTransitioned` 가 이 아웃박스를 탄다. `cause` 소비자 필터는 **범위 밖**(X8) |
| `2026-06-05-issue-browse-view-permission.md` | VIEW 축의 존재 숨김. 이관 경로의 `NOT_FOUND` 오진이 여기서 나오지만 **범위 밖**(X9) |
| `2026-06-02-issue-permission-scheme-model.md` | per-issue 권한 모델. X4 가 이것을 우회하는 근거 |
| `2026-07-27-workflow-scheme-canonical-vocabulary.md` | 3단 JOIN 이 쓰는 스킴 어휘의 정본 |

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1 — 이관이 필요한 발행.**
Given 관리자가 워크플로우 `WF` 의 초안에서 상태 `done` 을 뺐고, `WF` 를 쓰는 프로젝트에 `done`
상태 이슈가 12건 있다.
When `POST /api/v1/workflows/WF/publish` 를 부른다.
Then 409 `WORKFLOW_PUBLISH_MAPPING_REQUIRED` 와 `pendingIssueCounts = {done: 12}` 를 받는다. (현행 동작 — 이 PR 이 바꾸지 않는다)

**S2 — 이관 예약.**
Given S1 의 상태.
When `POST /api/v1/workflows/WF/publish/migrate` 에 `{baseVersion, mappings:[{from:"done", to:"closed"}]}` 를 싣는다.
Then 202 와 `{bulkOperationId}` 를 받고, `bulk_operations` 에 `STATUS_MIGRATION` 1건이 큐잉된다.

**S3 — 발행 확정.**
Given `GET /api/v1/bulk-operations/{id}` 가 `COMPLETED` 를 돌려준다.
When `POST /api/v1/workflows/WF/publish` 를 다시 부른다.
Then 200 과 새 `versionNo` 를 받는다 — 잔여 0건이라 더 막지 않는다.

**S4 — 이관 중 유입.**
Given S3 의 재호출이 진행 중이고, 편성 교체 직후 다른 세션이 이슈 1건을 `done` 으로 옮겨 커밋했다.
When 발행 트랜잭션이 교체 직후 재카운트를 돈다.
Then 잔여 1건이 보여 409 로 **롤백**된다. 정의는 바뀌지 않고 관리자는 S2 부터 다시 한다.

### Jira 대조

위 `## Jira 대조` 절이 정본이다. 매핑 — J1→F1 · J7→F2 · J8→(PR 7 기구현) · J6→F3 · J10→X5.

### 기능 요구사항 (FR)

- **F1 결선.** `WorkflowPublishService` 가 `IssueStatusMigrationPort.enqueueStatusMigration` 을 실제로 호출한다. 이것이 없으면 PR 7 이 만든 경로는 사용자에게 보이지 않는다.
- **F2 매핑 접수.** `POST /api/v1/workflows/{key}/publish/migrate` 가 `mappings: [{fromStatusKey, toStatusKey}]` 를 받는다. 빠지는 상태마다 다른 대상을 지정할 수 있고 이관 작업은 **1건**으로 묶인다.
- **F3 진행 조회.** 응답은 `bulkOperationId` 만 돌려준다. 진행률은 기존 `GET /api/v1/bulk-operations/{id}` 가 담당한다 — 새 조회 API 를 만들지 않는다.
- **F4 트랜잭션 경계.** `migrate` 는 `@Transactional` 이며 project-workflow 를 **읽기만** 하고 `bulk_operations` 에만 쓴다. 어댑터의 `BulkOperationEnqueuePublisher` 가 `Propagation.MANDATORY` 라 경계가 필요하고, 두 BC 에 **쓰지** 않으므로 `DATA.md §6`(다중 BC 트랜잭션 금지)을 만나지 않는다. `publish` 는 project-workflow 에만 쓴다.
- **F5 권한 순서.** `requirePermission(actorId, PUBLISH)` 가 `migrate` 와 `publish` 양쪽에서 **첫 줄**이며, 그 뒤에만 포트를 부른다. X4 가 위임한 「위조 차단은 호출자 책임」의 이행 지점이다.
- **F6 범위 자가 조회.** `StatusMigrationCommand.projectKeys` 는 **요청에서 받지 않고** 호출자가 직접 조회해 채운다. 요청 DTO 에 그 필드를 두지 않는다.
- **F7 매핑 출발지 제한.** `mappings` 의 `fromStatusKey` 집합이 이번 발행이 제거하는 상태 집합의 **부분집합**이 아니면 거부한다.
- **F8 매핑 도착지 제한.** `toStatusKey` 가 초안의 상태 집합에 없으면 거부한다 — 사라질 상태로 옮기는 것을 막는다.
- **F9 프로젝트 스코프.** `countIssuesInStatus(statusKey, projectIds)` 로 확장하고, `projectIds` 는 그 워크플로우를 쓰는 프로젝트로 한정한다. 상태 키가 전역이라 스코프가 없으면 남의 프로젝트 이슈까지 세어 발행을 과하게 막는다.
- **F10 뒤쪽 창 축소.** `replaceDefinition` 직후 같은 트랜잭션에서 재카운트하고, 잔여가 있으면 던져 롤백한다.

### 비기능 요구사항 (NFR)

- **N1** 3단 JOIN 은 발행·미리보기 경로에서 상태 개수와 무관하게 **1회만** 돈다(상태마다 반복 금지).
- **N2** 스키마 변경 0건 — 마이그레이션 파일을 추가하지 않는다. 따라서 `init_codegen.sql` 미러도 불변이다.
- **N3** `shared-kernel` 포트 시그니처 불변 — `StatusMigrationCommand` 를 고치지 않는다.
- **N4** 기존 발행·미리보기의 성공 응답 형태를 바꾸지 않는다(프론트 회귀 0).

### API 인터페이스 (REST)

```
POST /api/v1/workflows/{key}/publish/migrate
Request   { "baseVersion": 7, "mappings": [{ "fromStatusKey": "done", "toStatusKey": "closed" }] }
202       { "data": { "bulkOperationId": "<uuid>" } }
400       WORKFLOW_MIGRATION_INVALID_MAPPING   — F7·F8 위반
403       (권한 없음 — 전역 PUBLISH 미보유)
404       WORKFLOW_NOT_FOUND
409       WORKFLOW_VERSION_CONFLICT           — baseVersion 불일치
```

`POST /api/v1/workflows/{key}/publish` · `POST …/publish/preview` 는 **요청·응답 불변**.

**★프론트 동기화 의무.** `WorkflowPublishExceptionHandler` 에 새 코드를 더하면
`apps/web/src/hooks/__tests__/workflow-admin-error.test.ts` 의 `HANDLER_FILES` 양방향 차집합이
red 가 된다. `apps/web/src/i18n/workflow-editor-labels.ts` 문구를 **같은 PR 에서** 넣는다.

### 데이터 모델 변경

**없다.** 읽기 경로만 추가한다.

```sql
SELECT DISTINCT p.id, p.key
FROM workflow_scheme_issue_type_mappings m
JOIN project_workflow_scheme_assignments a ON a.workflow_scheme_id = m.scheme_id
JOIN projects p ON p.id = a.project_id
WHERE m.workflow_id = ?
  AND p.deleted_at IS NULL
  AND p.archived_at IS NULL
```

컬럼 근거 — `V201__workflow_schemes.sql:52,78`(할당 표의 FK 는 `workflow_scheme_id`) ·
`V202__assignment_project_id_to_uuid.sql`(`project_id` UUID 정정 완료) ·
`V001__issues_initial.sql:11`(`projects.deleted_at`) · `V037__projects_archived_at.sql:5`(`archived_at`).

### 엣지 케이스

- **E1 스킴 미할당 워크플로우** — `projectIds` 가 빈 집합. `countIssuesInStatus` 는 **0 을 반환**한다. 빈 `IN ()` 을 「전체」로 흘리면 fail-open 이다.
- **E2 매핑 0건으로 migrate 호출** — `mappings` 가 비면 거부한다(400). 큐잉해도 옮길 것이 없고, PR 7 이 닫은 「0건 큐잉」 결함을 되살리지 않는다.
- **E3 연쇄 매핑** `{a→b, b→c}` — `toStatusKey` 가 초안에 있어야 하므로(F8) `b` 가 제거 대상이면 애초에 거부된다.
- **E4 중복 `fromStatusKey`** — 같은 출발지가 두 번 오면 거부한다. 어느 쪽이 이기는지가 순서 의존이 된다.
- **E5 소프트 삭제 프로젝트** — JOIN 에서 제외한다. 그 프로젝트 이슈는 세지도 옮기지도 않는다.
- **E6 소프트 삭제 이슈** — 기존 어댑터가 이미 `deleted_at IS NULL` 로 제외한다. 불변.
- **E7 아카이브 프로젝트 (★판정)** — **카운트·이관 범위에서 뺀다.**
  근거. `BulkItemApplier:256` 이 `projectArchiveGuard.checkByIssue` 로 아카이브 프로젝트 이슈의
  변경을 거부한다(→ `PROJECT_ARCHIVED`). 포함하면 그 이슈는 **영원히 옮겨지지 않는데 카운트에는
  계속 잡혀** 발행이 관리자가 풀 수 없는 상태로 막힌다. 빼면 그 이슈는 사라진 상태에 남지만,
  아카이브 프로젝트는 이미 쓰기 잠금 상태라 아무도 전환시키지 않아 **정지 상태로 무해**하다.
  **한계.** 프로젝트를 다시 활성화하면 그 이슈들이 워크플로우에 없는 상태에 남아 있다.
  이것을 숨기지 않고 아래 「제약 조건」과 `TODOS.md` 에 남긴다.
  `PROJECT_ARCHIVED` 는 생산자를 잃지 않는다 — 큐잉과 워커 실행 **사이**에 아카이브되는 경합이 남는다.
- **E8 뒤쪽 창 유입** — S4. 재카운트가 잡아 롤백한다.
- **E9 이관 실패분이 남은 채 재발행** — `bulk_operation_items` 에 FAILED 가 남고 이슈는 원래 상태 그대로다. 재카운트가 그것을 세므로 발행은 계속 막힌다 — **의도된 동작**이다.

### 제약 조건

- **C1** `한 PR = 한 BC`. 쓰기는 `shared-kernel` 포트를 통해서만 나간다. `com.bts.issue..` 직접 import 금지(`ProjectWorkflowArchitectureTest` 룰 3 — 허용 목록이 빈 집합).
- **C2** `DATA.md §6` — 다중 BC 트랜잭션 금지. F4 가 이 제약을 만나지 않는 이유를 설명한다.
- **C3 배포 순서.** 결선 이후 `bulk_operations` 에 `operation_type='STATUS_MIGRATION'` 행이 처음 생긴다. 구버전 워커는 `BulkOperationRepository.kt:531-533` 의 `enumValueOf` 에서 죽는다. **워커가 새 enum 을 아는 버전으로 먼저 올라간 뒤** 결선을 배포한다. `FailureReasonCode` 의 `STATE_NOT_IN_MAPPING`·`PROJECT_ARCHIVED` 도 `:205` 에서 같은 `enumValueOf` 를 타므로 같은 순서 제약을 공유한다.
- **C4 남는 것 — 잔여 창.** 재카운트 → COMMIT 구간은 닫히지 않는다. 그 사이 커밋된 전환은 아직 옛 정의를 보므로 정당하고, 완전히 닫으려면 전환 핫패스가 워크플로우 정의 행을 잠가야 한다(issue-tracking BC). `TODOS.md` 부채 143 을 **「축소」로 갱신**하고 닫았다고 쓰지 않는다.
- **C5 남는 것 — 아카이브 유령.** E7 의 한계. 별건 등재.

### 범위 밖 (deviation 기록)

| # | 항목 | 사유 · 이월 |
|---|---|---|
| X8 | `cause="STATUS_MIGRATION"` 소비자 필터 | 소비자가 search-export-import · notification · slack-integration **3 BC**. C1 에 걸린다. **실측** — 그 문자열이 issue-tracking 밖 전 BC 통틀어 0건이고, 웹훅 `parseIssueTransitioned` 는 4필드 화이트리스트라 `cause` 를 파싱조차 안 한다. **PR 10 의 명시적 선행**으로 `TODOS.md` 등재 |
| X9 | VIEW 미보유 이슈가 `NOT_FOUND` 로 오진 | issue-tracking BC. `TODOS.md:1655` 에 이미 등재돼 있다 — **PR 10 선행**으로 승격 |
| X10 | PR 7 잔여 7건(`TODOS.md:1650-1657`) | issue-tracking BC. 특히 `SYSTEM_ACTOR_UUID` 는 결선 뒤 「누가 옮겼나」를 전부 같은 UUID 로 찍는다 — **PR 10 선행** |

### 측정 가능한 완료 기준

1. `migrate` 가 정상 매핑에 202 + `bulkOperationId` 를 돌려주고 `bulk_operations` 에 행이 1건 생긴다.
2. F7·F8·E2·E4 위반이 각각 400 으로 거부된다.
3. 권한 없는 actor 의 `migrate`·`publish` 에서 **포트 호출이 0회**임을 스파이로 단언한다(403 만으로는 순서를 못 잰다).
4. 같은 상태 키를 쓰는 **타 프로젝트** 이슈가 `pendingIssueCounts` 에 안 잡힌다.
5. 스킴 미할당 워크플로우의 `pendingIssueCounts` 가 비어 있다(E1).
6. 아카이브 프로젝트 이슈가 카운트에 안 잡힌다(E7).
7. 교체 직후 유입이 있으면 발행이 롤백되고 정의가 안 바뀐다(S4·F10).
8. 뮤테이션 — 재카운트 · F7 가드 · F8 가드 · `project_id IN` 필터 · 빈 집합 0 분기 · 스파이 단언을 각각 지우면 **그 테스트만** red.
9. `./gradlew :modules:project-workflow:test --rerun-tasks` 실패 0 · ktlintCheck · detekt.
10. `pnpm --filter web test` 초록(`workflow-admin-error` 양방향 차집합 포함) · `pnpm test:workflow` 456/456 · doc-index drift 0 · `verify-master-plan` PASS · **FR 143 불변**.

## Sanity Check

스스로 흔든 결과 gap **3건**을 찾아 전부 이 문서 안에서 보강했다(각 항목에 `❓ 발견` 표시).

- **❓ 발견 1 — `mappings` 가 빈 배열일 때가 명세에 없었다.** 승인된 계획에도 없다. PR 7 이 「jOOQ 빈 배치가 NULL 행을 쏜다」로 0건 큐잉을 구조적으로 막았는데, 결선이 빈 배열을 그대로 흘리면 그 결함을 되살린다. **E2 로 추가**했다.
- **❓ 발견 2 — 같은 `fromStatusKey` 가 두 번 오는 경우가 없었다.** 어댑터의 E5/E5b 가드가 잡을 수도 있으나 **호출자가 먼저 거부하는 편이 오류 위치가 정확하다**. **E4 로 추가**했다.
- **❓ 발견 3 — 이관이 일부 FAILED 로 끝난 뒤 재발행하면?** 계획에 서술이 없었다. 재카운트가 잔여를 세므로 발행은 계속 막히는 것이 옳다 — 「일부 실패인데 발행이 통과」가 유령을 만든다. **E9 로 명시**했다.

**Maxi 결정이 필요한 것 1건 — E7(아카이브 프로젝트)** 은 spec 이 「제외」로 판정했으나 Jira 근거가
없는 BTS 내부 결정이고 **유령 이슈라는 대가**가 따른다. 게이트 1 요약에 그대로 싣는다.

## Plan

### Task 1. 워크플로우 → 프로젝트 역방향 조회 (3단 JOIN)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/repository/ProjectWorkflowSchemeAssignmentRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/repository/ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest.kt`]
- depends-on: []
- jira: []

**왜 이것이 선행인가.** 포트가 `StatusMigrationCommand.projectKeys` 를 요구하는데 저장소에
`workflowId → projectKeys` 역방향이 **없다**. `ProjectLookupPort.findIdByKey` 는 정방향뿐이고
`detachMappingsByWorkflowId` 는 detach 전용이다. Task 2·3 이 둘 다 여기 매달린다.

**RED**:
- 파일: `…/ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest.kt`
- 테스트 5개
  ```kotlin
  @Test fun `워크플로우를 쓰는 스킴에 할당된 프로젝트를 전부 돌려준다`()
  @Test fun `다른 스킴에만 있는 프로젝트는 안 나온다`()
  @Test fun `스킴에 할당되지 않은 워크플로우는 빈 집합이다`()          // E1 의 뿌리
  @Test fun `소프트 삭제된 프로젝트는 제외한다`()                      // E5
  @Test fun `아카이브된 프로젝트는 제외한다`()                          // E7
  ```
- 실패 메시지 (예상): `findProjectRefsByWorkflowId` 미해결 참조

**GREEN**:
- 파일: `…/ProjectWorkflowSchemeAssignmentRepository.kt`
- `fun findProjectRefsByWorkflowId(workflowId: UUID): List<ProjectRef>` + `data class ProjectRef(id: UUID, key: String)`
- 스펙 `## 데이터 모델 변경` 의 SQL 그대로. `DSL.table()` 동적 참조는 이 파일의 기존 관례(`:41`)를 따른다

**REFACTOR**:
- 필드 상수를 파일 상단 기존 상수 블록으로 · KDoc 에 「왜 정방향 조회로는 안 되는가」 1문단

**검증**: `cd backend && ./gradlew :modules:project-workflow:test --tests '*ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest' --rerun-tasks`

**뮤테이션 짝**: `p.archived_at IS NULL` 한 줄을 지우면 아카이브 테스트만 red · `p.deleted_at IS NULL` 을 지우면 소프트 삭제 테스트만 red.

---

### Task 2. `countIssuesInStatus` 프로젝트 스코프

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/port/IssueStatusUsagePort.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/outbound/IssueStatusUsageAdapter.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowPublishService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowStatusCompositionService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/adapter/outbound/IssueStatusUsageAdapterIntegrationTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/application/WorkflowPublishServiceIntegrationTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/application/WorkflowStatusCompositionIntegrationTest.kt`]
- depends-on: [1]
- jira: []

**★한 task 로 묶는 이유.** 포트 시그니처가 바뀌므로 호출처 2곳(`WorkflowPublishService.kt:263` ·
`WorkflowStatusCompositionService.kt:135`)과 테스트 스텁 2곳이 **같은 커밋 안에서** 함께 움직여야
컴파일이 선다. 쪼개면 중간 상태가 빌드되지 않는다.

**★신규 테스트 파일.** `IssueStatusUsageAdapter` 에는 지금 테스트가 **0건**이다 — 원시 SQL 이
사는 곳인데 판정이 없다. 이 task 가 만든다.

**RED**:
- 파일: `…/adapter/outbound/IssueStatusUsageAdapterIntegrationTest.kt` (신규)
- 테스트 3개
  ```kotlin
  @Test fun `같은 상태 키를 쓰는 타 프로젝트 이슈는 세지 않는다`()      // F9
  @Test fun `projectIds 가 비면 0 을 돌려준다`()                        // E1 · fail-open 차단
  @Test fun `소프트 삭제된 이슈는 세지 않는다`()                        // E6 회귀 동결
  ```
- 실패 메시지 (예상): `countIssuesInStatus` 가 인자 2개를 안 받는다 (컴파일 실패)

**GREEN**:
- 포트 `fun countIssuesInStatus(statusKey: String, projectIds: Set<UUID>): Long`
- 어댑터에 `.and(projectIdField.`in`(projectIds))` 추가 + **`projectIds.isEmpty()` 면 조기 `return 0L`**
- 호출처 2곳이 Task 1 의 `findProjectRefsByWorkflowId` 결과에서 `id` 집합을 넘긴다
- `WorkflowPublishService.pendingIssueCounts` 는 조회를 **상태마다 반복하지 않고 1회**만 한다(N1)

**REFACTOR**:
- 포트 KDoc 에서 「프로젝트 스코프는 로드맵 PR 7 소관」 문장을 **삭제**한다 — 이 task 가 그것이다. 남겨 두면 다음 사람이 또 미룬다

**검증**: `cd backend && ./gradlew :modules:project-workflow:test --rerun-tasks`

**뮤테이션 짝**: `project_id IN` 절을 지우면 타 프로젝트 테스트만 red · 빈 집합 조기 반환을 지우면 그 테스트만 red.

---

### Task 3. `migrate` 엔드포인트 — 정상 경로 (결선)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowPublishService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowDraftController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/WorkflowDraftDtos.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/application/WorkflowPublishServiceIntegrationTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowDraftControllerMvcTest.kt`]
- depends-on: [1, 2]
- jira: [J1, J6, J7, J8]

**RED**:
- 테스트 3개
  ```kotlin
  @Test fun `정상 매핑이 202 와 bulkOperationId 를 돌려준다`()                    // F1·F2·F3
  @Test fun `요청 DTO 에 projectKeys 필드가 없다`()                              // F6 — 리플렉션 아닌 Jackson 역직렬화로 잰다
  @Test fun `baseVersion 이 저장된 초안과 다르면 409`()                          // 기존 CAS 규칙 승계
  ```
- 실패 메시지 (예상): `POST …/publish/migrate` 404

**★F6 테스트를 리플렉션으로 짜지 말 것.** Kotlin 2.0 이 default 본문을 `DefaultImpls` 로 빼서
리플렉션 단언이 이 저장소에서 항상 통과한 전례가 있다(PR #414 plan 이 실측). **알 수 없는 필드를
실은 JSON 이 거부되는가**를 MockMvc 로 재는 편이 실제 계약을 잰다.

**GREEN**:
- `MigrateRequest(baseVersion: Long, mappings: List<StatusMappingDto>)` · `MigrateResponse(bulkOperationId: UUID)`
- `WorkflowPublishService.migrate(actorId, key, baseVersion, mappings): UUID` — `@Transactional`
- 검증 순서는 스펙 `## 스펙 → F5` 그대로. `projectKeys` 는 Task 1 조회 결과에서만 채운다
- 컨트롤러 `@PostMapping("/publish/migrate")` → 202

**REFACTOR**:
- `publish` 와 `migrate` 의 공통 전처리(`requireLive`→`requireDraft`→`validateDefinition`→CAS)를 private 헬퍼로. **권한 검사는 헬퍼 밖에 남긴다** — 안으로 넣으면 Task 5 가 재는 「첫 줄」 계약이 헬퍼 호출 순서에 숨는다

**검증**: `cd backend && ./gradlew :modules:project-workflow:test --rerun-tasks`

---

### Task 4. `migrate` 매핑 가드 + 프론트 문구 동기화

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowPublishService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowPublishExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/application/WorkflowPublishServiceIntegrationTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowDraftControllerMvcTest.kt`, `apps/web/src/i18n/workflow-editor-labels.ts`]
- depends-on: [3]
- jira: [J7]

**RED**:
- 테스트 4개
  ```kotlin
  @Test fun `제거되지 않는 상태를 fromStatusKey 로 실으면 거부한다`()      // F7
  @Test fun `toStatusKey 가 초안 상태 집합에 없으면 거부한다`()            // F8 · E3 연쇄 차단
  @Test fun `mappings 가 비면 거부한다`()                                  // E2
  @Test fun `같은 fromStatusKey 가 두 번 오면 거부한다`()                  // E4
  ```

**GREEN**:
- 4가지 위반을 `WorkflowMigrationInvalidMappingException` 으로 모으고 핸들러가 400 `WORKFLOW_MIGRATION_INVALID_MAPPING` 으로 매핑
- **하나의 코드로 합치되 `detail` 에 어느 축인지 싣는다** — 코드를 4개로 늘리면 프론트 차집합 가드가 4행을 요구한다

**REFACTOR**:
- 가드 4개를 `requireSoundMappings(key, removed, definition, mappings)` 하나로

**검증**:
- `cd backend && ./gradlew :modules:project-workflow:test --rerun-tasks`
- `cd apps/web && node_modules/.bin/vitest run src/hooks/__tests__/workflow-admin-error.test.ts` ← **pnpm 을 쓰지 않는다**(worktree 에서 auto-install 이 깨진다)

**★프론트가 같은 task 인 이유.** 핸들러에 코드를 더하면 `workflow-admin-error.test.ts` 의
`HANDLER_FILES` **양방향 차집합**이 즉시 red 다. 다른 task 로 미루면 중간 커밋이 red 로 남는다.

**뮤테이션 짝**: F7 가드 · F8 가드 · E2 분기 · E4 분기를 각각 지우면 그 테스트만 red.

---

### Task 5. 권한 검사 순서 계약 (C-10)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/application/WorkflowPublishServiceIntegrationTest.kt`]
- depends-on: [3]
- jira: []

**현행 코드는 이미 만족한다** (`WorkflowPublishService.kt:110-111` 이 `requireLive` 보다 먼저,
`requirePermission` 이 리소스 인자를 안 받는 전역 권한이라 403↔404 누설도 없다). 이 task 는
**그 순서를 판정으로 잠근다** — X4 가 위임한 「위조 차단은 호출자 책임」의 이행 증거다.

**RED**:
- 테스트 2개
  ```kotlin
  @Test fun `권한 없는 actor 의 migrate 는 포트를 부르지 않는다`()   // F5
  @Test fun `권한 없는 actor 의 publish 도 포트를 부르지 않는다`()   // F5
  ```
- **스파이 포트**로 `enqueueStatusMigration` **호출 횟수 0** 을 단언한다

**★403 만 단언하면 안 된다.** 포트를 먼저 부르고 나중에 던져도 403 이다 — 그 테스트는 순서를
재지 못한다. 호출 횟수가 이 계약의 유일한 관찰 가능한 신호다.

**GREEN**: 없다(현행 코드가 통과). **RED 를 먼저 보기 위해** 권한 검사 줄을 일시 이동시켜 red 를
1회 확인하고 되돌린다 — 「가드를 일부러 끊어 red 1회 확인」(저장소 함정 목록).

**REFACTOR**: 스파이를 기존 `StubIssueStatusUsage` 옆 테스트 픽스처로 정리

**검증**: `cd backend && ./gradlew :modules:project-workflow:test --tests '*WorkflowPublishServiceIntegrationTest' --rerun-tasks`

---

### Task 6. 뒤쪽 창 재카운트 + 롤백

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowPublishService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/application/WorkflowPublishServiceIntegrationTest.kt`]
- depends-on: [2]
- jira: []

**RED**:
- 테스트 2개
  ```kotlin
  @Test fun `교체 직후 유입이 있으면 발행이 롤백되고 정의가 그대로다`()   // F10 · E8 · S4
  @Test fun `이관이 일부 FAILED 로 끝나면 재발행이 계속 막힌다`()          // E9
  ```
- 스텁 포트가 **1회차 0 · 2회차 1** 을 돌려주게 해 유입을 흉내낸다

**GREEN**:
```kotlin
val removed = removedStatusKeys(workflowId, definition)   // 교체 전 1회만 계산
requireNoPending(key, removed)
cache.withWriteLock(key) {
    …
    val transitionIds = publishRepository.replaceDefinition(workflowId, definition, statusIds)
    ruleWriter.writeAll(definition, transitionIds)
    requireNoPending(key, removed)                        // ★재카운트 — 같은 집합으로
    …
}
```

**★`requireNoPendingIssues` 를 그냥 재호출하면 항상 통과한다.** 그 함수가 `removedStatusKeys` 를
다시 계산하는데, `replaceDefinition` 뒤에는 `findComposedStatusKeys` 가 **새 편성**을 돌려주므로
차집합이 항상 빈 집합이 된다 — 판정이 있는데 아무것도 안 세는 형태다
(`[[invariant-satisfied-by-helptext-not-logic]]` 과 같은 양식). **교체 전 `removed` 를 재사용**한다.

**REFACTOR**:
- `requireNoPendingIssues(key, workflowId, definition)` 를 `requireNoPending(key, removed)` 로 좁혀 재계산 경로를 **구조적으로 없앤다** — 남겨 두면 다음 사람이 같은 실수를 한다
- KDoc 에 잔여 창(재카운트→COMMIT)을 한계로 적는다

**검증**: `cd backend && ./gradlew :modules:project-workflow:test --rerun-tasks`

**뮤테이션 짝**: 재카운트 한 줄을 지우면 유입 테스트만 red.

---

### Task 7. C-8 배포 순서 문서 + 장부 등재

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-31-workflow-status-migration-wiring.md`, `TODOS.md`, `docs/plan/product/project-workflow.md`]
- depends-on: []
- jira: []

코드 의존이 0 이라 어느 wave 와도 병렬 가능하다.

**RED**: 없음(문서 task). 대신 **검증이 판별식**이다.

**GREEN**:
1. **C-8 배포 절차** — 워커를 새 enum 을 아는 버전으로 먼저 올린 뒤 결선을 배포한다. 롤백 절차 포함. 죽는 지점 `BulkOperationRepository.kt:531-533`. **`TODOS.md:1644` 의 `:345` 는 stale 이므로 같은 커밋에서 고친다.** `FailureReasonCode` 2값이 `:205` 에서 같은 `enumValueOf` 를 타는 것도 적는다(C-8 원문에 없는 축)
2. **부채 143 을 「축소」로 갱신** — 닫았다고 쓰지 않는다. 잔여 창(재카운트→COMMIT)과 완전 폐쇄에 필요한 것(전환 핫패스의 정의 행 잠금 · issue-tracking BC)을 남긴다
3. **부채 144 를 「결선 완료 · 잔여 이월」로 갱신**
4. **PR 10 의 명시적 선행 3건 신규 등재** — X8(`cause` 필터 3 BC) · X9(VIEW 오진) · X10(PR 7 잔여 7건)
5. **신규 등재** — E7 의 아카이브 유령(C5) · 「`checkJiraSection` 이 절 안의 HTML 주석까지 본문으로 세어 면제가 오발동한다」
6. `docs/plan/product/project-workflow.md` §2.7 의 **D4 를 `[x]` 로** · §집계 문장 갱신

**REFACTOR**: 없음

**검증**:
- `node scripts/build-doc-index.mjs && node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` — 456 전건
- `bash scripts/verify-master-plan.sh` (EXIT 4 차단) · **FR 143 불변**

## Plan 메타

- **task 수**: 7 · **예상 wave**: 4
  - wave 1 — Task 1 · Task 7 (병렬. 파일 교집합 0)
  - wave 2 — Task 2
  - wave 3 — Task 3
  - wave 4 — Task 4 · Task 5 · Task 6 (★`WorkflowPublishService.kt` 교집합으로 **자동 직렬화**된다. 병렬로 보이지만 순차로 돈다)
- **★병렬 gradle 금지.** 파일 교집합이 0 이어도 `build/` · `src/generated/jooq` 는 공유한다. PR #414 에서 `NoClassDefFoundError`·`Unresolved reference` 가 났고 마지막 wave 를 순차로 돌려 풀었다. **wave 안에서도 gradle 은 한 번에 하나만** 돈다.
- **구현 규율**: TDD red-first (T2 티어). ui 시각 검증 트랙 **비대상** — `apps/web` 변경은 i18n 문구 1줄뿐이고 화면 변화가 없다.
- **추가 검증**: ktlintCheck · detekt (`--rerun-tasks`) · `node --experimental-strip-types --test` 판별식 456 · doc-index `--check` · `verify-master-plan.sh`
- **커밋 규율**: 공유 worktree 이므로 **pathspec 커밋**(`git commit -- <경로>`)만 쓴다. `git add <경로>` 는 격리가 아니다 — 인덱스에 남은 옆 task 의 stage 를 `git commit` 이 삼킨다.
- **반복 측정**: `--no-build-cache`. 모듈 테스트는 `--rerun-tasks` — 빌드 캐시가 `UP-TO-DATE` 로 몇 주째 안 돌 수 있다.
- **Testcontainers 정리**: 종료 시 `pgrep gradle` 만으로는 부족하다. `docker ps` 를 함께 본다.

**Jira 매핑**: J1→T3 · J6→T3 · J7→T3·T4 · J8→T3(PR 7 이 구현한 규칙 우회를 이 PR 이 호출한다) ·
J4→X5 편차 · J10→X5 편차. **채택 판정 전건이 task 에 물렸다 — 차집합 0.**

**FR·E 커버리지 (차집합 0 확인)**:
F1·F2·F3·F4→T3 · F5→T5 · F6→T1·T3 · F7·F8→T4 · F9→T2 · F10→T6.
E1→T1·T2 · E2·E3·E4→T4 · E5→T1 · E6→T2 · E7→T1·T2 · E8·E9→T6.
C3·C4·C5 및 X8·X9·X10→T7.

## 리뷰 결과 (← /bts-review-plan 채움)
