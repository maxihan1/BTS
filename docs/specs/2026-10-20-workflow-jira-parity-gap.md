# FR-WF Jira 대조 — 워크플로우의 기능·사용성 차이 전수 목록

대상: FR-WF-01 ~ FR-WF-08 (project-workflow BC) 과 이슈 화면의 전환 경험(issue-tracking).
조회일: 2026-10-20 — Jira Cloud 새 워크플로우 편집기 기준. BTS 는 `main` `1ccaa848a` 기준.

**이 문서는 FR 을 만들지 않는다.** 정본 FR 수(146)와 D 마커는 그대로다. 아래 항목 중 무엇을 FR 로 올릴지는
§6 후보표를 보고 Maxi 가 정한다. 승격하는 PR 이 `docs/rules/fr-sync-checklist.md` 전 항목을 맞춘다.

## 읽는 법

| 접두 | 뜻 |
|---|---|
| **B** | 결함 — 스펙·화면에는 있다고 돼 있는데 실제로 동작하지 않는다. Jira 대조 이전의 문제다 |
| **G** | 기능 차이 — Jira 에 있고 BTS 에 없는 능력 |
| **U** | 사용성 차이 — 능력은 있으나 찾고·쓰고·확인하는 방식이 Jira 와 다르다 |
| **A** | BTS 가 Jira 보다 앞서거나 Jira 에 대응이 없는 것 — 되돌리지 않는다 |

- 「근거」 칸의 경로는 저장소 기준이다. **코드 판독**은 읽어서 확인했고 실행하지 않았다는 뜻이다.
- Jira 출처는 §7 의 S 번호다. `(미검증)` 은 공식 문서가 아니라 커뮤니티·티켓에서만 확인된 것이다.
- 이미 다른 문서에 「의도적 편차」로 기록된 항목은 다시 결정하지 않고 그 위치만 가리킨다(§5).

## 1. 결함 (B) — 먼저 볼 것

| # | 무엇이 안 되나 | 근거 | Jira 는 | 영향 |
|---|---|---|---|---|
| B1 | 후처리 5종 중 **`CALL_WEBHOOK` 만 실제로 동작**한다. `SET_FIELD` 의 `fieldChanges` 는 issue-tracking 이 적용하지 않고, `NOTIFY`·`ADD_WATCHER`·`RUN_AUTOMATION` 이 내는 이벤트는 웹훅 워커가 읽고 버린다 | `fieldChanges` 는 issue-tracking `main` 에서 참조 0 (`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/TransitionPlan.kt` 정의 · 테스트만) · `NotificationRequested`·`WatcherAdded`·`AutomationRequested` 는 `WebhookDispatchWorker` 가 지원 타입이 아니라며 delete 한다 · `TransitionRuleGuard.checkPostAction` 이 type 을 허용목록으로 거르지 않아 API 로는 이 type 들을 저장할 수 있다 — 코드 판독 | 「Perform actions」 규칙은 전환 때 실행된다 [S2][S20] | FR-WF-03 은 「PostAction 계산만(GAP-2)」이라 적었고(`docs/plan/product/project-workflow.md` §2.3), FR-NT-05 스펙은 나머지 3종을 「미래 FR 소비처」로 남겼으나(`docs/specs/2026-06-14-fr-nt-05-transition-event-publish.md`) **그 FR 은 없다**. product §2.3 에는 기록이 없다 |
| B2 | `RequiredField` 검증기는 **`summary`·`resolution` 두 필드만** 볼 수 있다. 그런데 편집 화면은 필드명을 자유 입력으로 받는다 | 엔진 입력이 두 필드뿐 — `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt` `availableTransitions`·전환 실행부의 `mapOf("summary" …, "resolution" …)` · 입력 칸 `apps/web/src/components/workflow/ValidatorFormDialog.tsx` | 「Field required」는 임의 필드를 고른다 [S20] | 다른 필드명을 넣으면 그 전환이 항상 막힐 것으로 본다 — 코드 판독, 미실행 |
| B3 | **화면에서 새로 만든 워크플로우는 발행할 수 없다.** 생성 경로가 상태만 넣고 INITIAL 전환을 만들지 않는데, 편집기는 INITIAL 을 고를 수 없고 발행은 INITIAL 이 정확히 1개여야 한다 | 생성 `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowCommandService.kt` (`insertWorkflow` → `attachStatuses`) · 폼 `apps/web/src/components/workflow/editor/TransitionFormDialog.tsx` 「사용자가 고를 수 있는 전환 종류 — INITIAL 은 없다」 · 발행 `WorkflowPublishService.kt` `requireExactlyOneInitial` | 새 워크플로우는 처음부터 Create 전환을 가진다 [S7][S20] | MSW 목은 생성 때 INITIAL 을 넣어(`apps/web/src/mocks/workflow-admin-handlers.ts` 「V207 ⑨ 백필과 같다」) 유닛에서 안 보인다. V207 백필은 마이그레이션 시점의 기존 행에만 적용된다 |
| B4 | 전환 규칙 편집 화면(`/workflows/$key`)으로 가는 **링크가 앱 안에 없다.** 시스템 관리자에게만 보이고, 초안·발행 바깥에서 라이브 규칙을 바로 고친다 | 링크 grep 0 (라우트 정의 `apps/web/src/router.ts` 만) · `ValidatorConfigSection.tsx` `isSystemAdmin` 게이트 · 편집기는 규칙을 다루지 않는다(`apps/web/src/lib/workflow-draft.ts`) | 규칙은 편집기 안 전환 상세 패널에서 고치고 「Update workflow」로 함께 반영된다 [S2][S6] | FR-WF-08 로 프로젝트 관리자가 워크플로우를 소유해도 **화면에서는 그 규칙을 못 고친다**. API 는 소유 스코프 `MANAGE_SCHEME` 을 이미 허용한다(`ManageSchemeGuard.requireForWorkflow`) — 막는 것은 화면 게이트뿐이다. 초안이 열린 동안 규칙을 고치면 다음 발행이 덮는다 — 규칙 CRUD(`ValidatorAdminService`·`PostActionAdminService`)가 workflow `version` 을 올리지 않아 발행의 anchor 검사가 못 잡고, `DraftRuleWriter.writeAll` 이 초안 규칙을 다시 심는다(코드 판독 · DB trigger 의 version 증가 여부는 미확인) |
| B5 | 프로젝트의 스킴을 바꾸거나 스킴 매핑을 고쳐도 **이슈 상태를 옮기지 않는다.** 이슈가 새 워크플로우에 없는 상태에 남을 수 있다 | `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/application/WorkflowSchemeApplicationService.kt` `assignToProject` 는 저장 + `WorkflowSchemeAssignedEvent` 를 `q_workflow_scheme_events` 에 넣을 뿐이고, 그 큐의 `main` 소비자는 0이다. `addMapping`·`deleteMapping` 은 이벤트도 내지 않는다 | 스킴 연결·전환 시 사라지는 상태의 이슈마다 대상 상태를 고르게 한다(`mappingsByIssueTypeOverride`) [S38][S20] | 어디에도 편차로 기록되지 않았다 |
| B6 | **새 상태를 만드는 화면이 없다.** 상태 카탈로그 API 는 있지만 피커는 기존 상태만 고른다. `useCreateStatus` 훅은 정의만 있고 호출 0 | `apps/web/src/hooks/use-workflows-admin.ts` · `StatusPickerDialog.tsx` · 쓰기 권한은 전역 스코프 `CREATE` — 사실상 SYSTEM_ADMIN (`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/application/StatusCommandService.kt`) | 편집기 「Add status」에서 「Create a new status」로 이름·범주를 바로 만든다 [S4] | 프로젝트 관리자는 카탈로그에 없는 상태가 필요하면 시스템 관리자에게 부탁해야 하고, 그 관리자도 화면이 없다 |

## 2. 기능 차이 (G)

### 상태·전환

| # | Jira Cloud | BTS 지금 | 출처 |
|---|---|---|---|
| G1 | **공유 전환** — 전환 하나가 여러 출발 상태를 가진다(규칙·속성 1벌) | 출발 상태 1개 또는 `GLOBAL` (`WorkflowTransition.kt`) | [S2][S10] |
| G2 | **전환 속성** — `opsbar-sequence`(전환 버튼 순서), `jira.field.resolution.include/exclude`(전환별 해결 값 제한), `jira.i18n.*` | 없음 | [S15][S7] |
| G3 | **상태 속성** — `jira.issue.editable=false`(그 상태에서 편집 잠금), 권한 제한 속성 | 없음 | [S15] |
| G4 | **사용처 조회** — 상태·워크플로우를 어느 프로젝트·이슈 유형이 쓰는지 | 없음 | [S20] |
| G5 | **버전 이력** — 지난 버전 조회(60일) | `workflow_publications` 에 쌓기만 하고 조회 API·UI 없음 | [S20] |
| G6 | **한도** — 워크플로우당 상태 200, 스킴당 워크플로우 150 (2026-09 부터 단계 적용) | 한도 없음. 규칙 config JSONB 크기 제한도 없음(`TODOS.md` 부채) | [S24] |

### 규칙

| # | Jira Cloud | BTS 지금 | 출처 |
|---|---|---|---|
| G7 | **조건(Restrict transition) 묶음** — 「모두 만족 / 하나라도」 그룹, 중첩 가능 | 조건이라는 분류가 따로 없고 `type` 이 `phase` 를 정한다(의도적 갭 — §5). **묶음은 없다** | [S17][S20] |
| G8 | **조건 종류** — 사람 제한(담당자·보고자·역할·그룹·권한·사용자 필드), 필드 값 비교, 이전 상태 통과 여부, 하위 작업 상태, 직무 분리, 전원 차단 | `permission-check` · `not-status-category` (`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/DefaultWorkflowValidatorFactory.kt`) | [S20][S2] |
| G9 | **검증기 종류** — 필수 필드(임의 필드), 필드 변경됨, 단일 값, 정규식, 날짜 비교·범위, 상위 항목 상태, 이전 상태 | `RequiredField`(B2 제약) · `permission-check` · `not-status-category`. 4 대 9 비교와 후속 FR 후보 2건은 이미 기록 — `docs/plans/2026-08-25-backend-workflow-transition-rule-crud.md` | [S20][S2] |
| G10 | **후처리 종류** — 필드 갱신(해결 값 설정·비우기 포함), 담당자 지정, 필드 값 복사(상위 포함), 보안 수준, 웹훅, AI 에이전트 실행 | 엔진 5종 중 웹훅만 동작(B1). 화면에서 추가할 수 있는 것도 웹훅뿐(`PostActionFormDialog.tsx`) | [S20][S2][S14] |
| G11 | **전환 화면** — 전환 때 필드 입력 대화상자(필수 표시), 「빈 필드 채우라고 알림」 | 없음. DONE 범주로 갈 때 해결 모달을 프론트가 하드코딩해 띄울 뿐이다(`routes/issues.$key.tsx`) | [S2][S25][S20] |
| G12 | **개발 도구 트리거** — 브랜치·커밋·PR 이벤트가 전환을 일으킨다 | 없음 | [S8][S19] |
| G13 | **규칙 순서** — 순서가 의미를 가진다(재오픈 때 해결 값 비우기 등) | `displayOrder` 필드뿐, 끌어서 바꾸기는 범위 밖으로 기록됨 | [S6] |

### 스킴·권한

| # | Jira Cloud | BTS 지금 | 출처 |
|---|---|---|---|
| G14 | **활성 스킴 편집 = 초안 스킴 → 발행 + 이슈 이관** | 매핑 추가·삭제가 즉시 반영, 이관 없음(B5) | [S37][S20] |
| G15 | **비공유 워크플로우의 규칙도 「Edit workflows」 권한자가 고친다** | 규칙 화면이 SYSTEM_ADMIN 전용(B4) — FR-WF-08 의 「공유 여부가 기준」 불변식과 어긋난다 | [S39][S40] |
| G16 | 해결(resolution) 필드를 **전환 규칙으로** 설정·비우고 전환별 허용 값을 제한한다 | DONE 범주면 프론트가 해결 모달을 띄우고, 백엔드는 시드 YAML 의 `RequiredField{resolution}` 으로 강제(ADR `2026-06-03-resolution-required-on-done-transition.md`) | [S42][S15] |

## 3. 사용성 차이 (U)

### 편집기

| # | Jira Cloud 에서 하는 법 | BTS 에서 하는 법 | 출처 |
|---|---|---|---|
| U1 | **규칙은 편집기 안에서** — 전환 선택 → 상세 패널 「Rules」 Add → 범주(Restrict / Request input / Validate / Perform) → 규칙 고르기 → 설정 | 링크 없는 별도 페이지에서 네이티브 `<select>` 로 전환을 고르고, 다이얼로그에서 설정 값을 **자유 텍스트**로 입력한다(필드·권한 피커 없음) | [S2][S6][S16] |
| U2 | 다이어그램에서 **상태 추가·삭제, 전환 추가·삭제를 모두** 한다 | 캔버스에서는 노드 이동과 핸들 끌기 → 전환 다이얼로그, 엣지 클릭 → 편집만 된다. 상태 만들기·노드/엣지 삭제·엣지 다시 잇기는 안 된다. 연결 핸들은 마우스 오버에만 나타나 터치에서 못 쓴다(`StatusNode.tsx`). xyflow 기본 Backspace 삭제가 초안과 어긋날 위험이 있다(미검증) | [S2] |
| U3 | 상태 상세의 **「모든 상태에서 이 상태로 전환 허용」 체크박스** | 전환 폼에서 종류 `GLOBAL` 라디오를 골라야 한다. `GLOBAL` 은 다이어그램에 선이 아니라 옆 목록으로 나온다 | [S4][S3] |
| U4 | **실행 취소** — Ctrl/Cmd+Z (team-managed 문서화) | 없음. 되돌리기는 초안 통째 폐기뿐 | [S13] |
| U5 | **공유 영향 표시** — 편집기 머리에 「이 워크플로우를 쓰는 스페이스」, 「모든 스페이스에 영향」 배너 | 편집기·목록 어디에도 사용처가 안 보인다(G4) | [S2][S4][S18] |
| U6 | 발행 전 검증이 **요소 단위**(상태·전환·규칙·속성)로 오류·경고를 가리킨다 | 로컬 차단 2종(상태 0개 · INITIAL ≠ 1)과 미리보기의 「제거되는 상태·이슈 수」뿐. 추가·변경 diff 를 안 보여 준다(`PublishDialog.tsx`) | [S20] |
| U7 | 상태를 지우면 **발행 때 이관 모달**에서 갈 곳을 고른다 | 상태 삭제 확인창이 제목만 있고, 함께 지워지는 전환·남은 이슈 수를 말하지 않는다(`WorkflowEditorDialogs.tsx`) | [S11][S18] |
| U8 | 이관 모달의 「New status」는 새 워크플로우의 상태 중에서 고른다(이번에 추가한 상태 포함 여부는 미검증) | 이번 초안에서 **새로 추가한 상태는 이관 대상으로 못 고른다**(가드 F16, `apps/web/src/lib/workflow-draft.ts`) | [S11] |
| U9 | **복제** — 이름·설명을 입력받는다 | 한 번 클릭, key `<key>-copy`·이름 「(사본)」 고정. 두 번째 복제는 409 | [S21] |
| U10 | **목록** — 이름·범위·활성·프로젝트로 검색·필터, 정렬 | 열 4개(이름·key·상태 수·전환 수). 검색·필터·사용처 없음 | [S20][S21] |
| U11 | 편집기 진입 — 전역 설정 목록 •••→Edit, 또는 프로젝트 설정 → 작업 유형 → Edit workflow, 또는 **이슈 상태 드롭다운 → View workflow** | 전역 `/admin/workflows`, 프로젝트 `/projects/$p/settings/workflows`. 이슈에서 워크플로우로 가는 길이 없다 | [S3][S9][S25] |

### 이슈 화면 (전환을 쓰는 사람)

| # | Jira Cloud | BTS | 출처 |
|---|---|---|---|
| U12 | 상태 드롭다운이 가능한 전환을 `opsbar-sequence` → 범주 순으로 보여 준다. 루프 전환은 별도 버튼 또는 「More actions」 | 네이티브 `<select>` 에 전환 이름. **고르는 즉시 실행**된다(`IssueStateTransition.tsx` · DONE 범주는 해결 모달을 거친다). 순서 지정 수단이 없다(G2) | [S25][S15] |
| U13 | 전환 화면이 있으면 대화상자에서 필드를 받고 필수는 별표 | 해결 모달 하드코딩 1종(G11) | [S25] |
| U14 | 막힌 전환은 「Unable to transition … Check if all transition conditions are met.」, 성공·실패 토스트 | EXECUTION 실패 시 「현재 상태에서 허용되지 않는 전환입니다.」 일반 토스트 — **검증기가 준 메시지를 안 보여 준다**(`apps/web/src/hooks/use-issue-transitions.ts`) | [S25] |
| U15 | 마지막 하위 작업이 Done 이 되면 상위 항목을 옮길지 묻는다 | 없음 | [S25] |

## 4. BTS 가 앞서거나 Jira 에 대응이 없는 것 (A) — 유지

| # | BTS | Jira Cloud |
|---|---|---|
| A1 | 초안 **자동 저장**(800ms 디바운스 + 페이지 이탈 시 저장, `apps/web/src/hooks/use-workflow-draft.ts`) | 새 편집기는 초안이 없고 「Update workflow」가 곧 발행이다. 자동 저장은 문서에 없다 [S2][S28] |
| A2 | 동시 발행 충돌 배너 — 먼저 발행한 사람이 있으면 알려 준다 | API 의 `version` 낙관적 동시성만 문서화, 화면 동작은 문서에 없다 [S20] |
| A3 | 이관 **진행률·실패 목록·새로고침 복원** | 임시 워크플로우 방식, 진행 화면은 문서에 없다 [S12] — `docs/plans/2026-09-02-fr-wf-07-d6b-pr-10b.md` 「대응 없음」 |
| A4 | 표준 워크플로우를 직접 고치고 **「기본값으로 되돌리기」** | 시스템 워크플로우는 읽기 전용이라 복사해서 고친다 [S1] |
| A5 | 상태 카테고리 열 자동 배치 | 문서화된 자동 배치 없음 — `docs/plans/2026-09-03-xyflow-fr-wf-07-d8-pr-9.md` 「대응 없음 A2」 |

## 5. 이미 기록된 의도적 편차 — 다시 정하지 않는다

| 편차 | 기록 위치 |
|---|---|
| 상세 편집이 사이드바가 아니라 다이얼로그 · 텍스트/다이어그램 토글 대신 3탭 · 좌표를 초안에 저장 | `docs/plans/2026-09-03-xyflow-fr-wf-07-d8-pr-9.md` X1~X3 |
| 조건/검증기 탭 대신 `phase` 배지 · 후처리 UI·끌어서 순서 바꾸기 범위 밖 | `docs/plans/2026-08-26-workflow-transition-rule-editor.md` 「의도적으로 유지하는 갭」 |
| `CustomExpression` 비노출 · Jira 검증기 9 대 BTS 4 | `docs/plans/2026-08-25-backend-workflow-transition-rule-crud.md` |
| 발행과 이관이 두 번 조작 · 스페이스/작업 유형별 예외 매핑 없음 · 보드 열 경고 무조건 표시 | `docs/plan/product/project-workflow.md` §2.7 각주 · `docs/plans/2026-09-02-fr-wf-07-d6b-pr-10b.md` X1~X5 |
| 스킴 개념 유지(team-managed 에는 없음), 소유만 프로젝트로 | `docs/specs/2026-09-08-project-owned-workflows.md` 「대응 없음」 |

## 6. FR 승격 후보 — Maxi 결정

묶음은 「한 PR = 한 BC」와 사용자가 체감하는 단위로 나눴다. 우선순위는 제안이다.

| 순 | 후보 | 묶는 항목 | 관련 FR | BC | 성격 |
|---|---|---|---|---|---|
| 1 | 새 워크플로우가 발행되게 | B3 | FR-WF-04 · FR-WF-05 | project-workflow | **버그 fix** — FR 아님. 생성 때 INITIAL 1건을 심는다 |
| 2 | `RequiredField` 필드 정합 | B2 | FR-WF-03 · FR-WF-06 | issue-tracking + apps/web | 버그 fix — 엔진 입력 확장 또는 필드명 입력을 지원 필드로 제한 |
| 3 | 규칙을 편집기 안으로 | B4 · U1 · G7 · G13 · G15 | FR-WF-06 · FR-WF-07 · FR-WF-08 | project-workflow + apps/web | FR 후보 — 초안·발행에 규칙 포함, 프로젝트 소유면 화면 게이트를 API 와 맞춘다 |
| 4 | 스킴 변경 시 상태 이관 | B5 · G14 | FR-WF-02 · FR-WF-07 | project-workflow + issue-tracking | FR 후보 — FR-WF-07 이관 마법사 재사용 |
| 5 | 후처리 런타임 완성 | B1 · G10 · G16 | FR-WF-03 · FR-WF-06 · FR-NT-05 | issue-tracking · notification · automation | FR 후보 — BC 별로 나눠야 한다 |
| 6 | 상태 만들기 화면 | B6 | FR-WF-04 | project-workflow + apps/web | FR 후보 — 편집기 상태 피커에서 바로 만들기 |
| 7 | 이슈 화면 전환 경험 | U12 · U13 · U14 · G11 · G2(순서) | FR-WF-01 · FR-WF-05 | issue-tracking + apps/web | **승격** (Maxi 2026-10-22) — FR-WF-01 D8~D11 |
| 8 | 사용처·목록·영향 표시 | G4 · U5 · U9 · U10 · U7 | FR-WF-04 · FR-WF-07 | project-workflow + apps/web | **기각** (Maxi 2026-10-22) — 필요가 생기면 다시 본다 |
| 9 | 조건·검증기 종류 확장 | G8 · G9 | FR-WF-06 | project-workflow | **기각** (Maxi 2026-10-22) — 필요가 생기면 다시 본다 |
| — | 낮음 / 보류 | G3 · G5 · G6 · G12 · U2 · U3 · U4 · U6 · U8 · U11 · U15 | — | — | 필요가 생기면 다시 본다 |

## 7. Jira 출처

| # | URL |
|---|---|
| S1 | https://support.atlassian.com/jira-cloud-administration/docs/work-with-issue-workflows/ |
| S2 | https://support.atlassian.com/jira-cloud-administration/docs/what-is-the-new-workflow-editor/ |
| S3 | https://support.atlassian.com/jira-cloud-administration/docs/create-workflow-transitions/ |
| S4 | https://support.atlassian.com/jira-cloud-administration/docs/add-statuses-to-a-jira-workflow/ |
| S6 | https://support.atlassian.com/jira-cloud-administration/docs/edit-an-issue-workflow/ |
| S7 | https://support.atlassian.com/jira-cloud-administration/docs/configure-advanced-issue-workflows/ |
| S8 | https://support.atlassian.com/jira-cloud-administration/docs/configure-workflow-triggers/ |
| S9 | https://support.atlassian.com/jira-software-cloud/docs/manage-how-work-flows-in-your-team-managed-project/ |
| S10 | https://support.atlassian.com/jira-work-management/docs/manage-transitions-in-team-managed-projects/ |
| S11 | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ |
| S12 | https://support.atlassian.com/jira-cloud-administration/docs/what-is-a-temporary-workflow/ |
| S13 | https://support.atlassian.com/jira-software-cloud/docs/create-edit-and-delete-statuses-in-team-managed-projects/ |
| S14 | https://support.atlassian.com/jira-software-cloud/docs/available-workflow-rules-in-team-managed-projects/ |
| S15 | https://support.atlassian.com/jira-cloud-administration/docs/use-workflow-properties/ |
| S16 | https://support.atlassian.com/jira-software-cloud/docs/add-or-remove-workflow-rules-in-team-managed-projects/ |
| S17 | https://support.atlassian.com/jira-software-cloud/docs/group-workflow-rules-to-further-restrict-your-transitions/ |
| S18 | https://support.atlassian.com/jira-cloud-administration/docs/remove-a-status-from-a-workflow/ |
| S19 | https://support.atlassian.com/jira-cloud-administration/docs/understand-workflow-triggers/ |
| S20 | https://developer.atlassian.com/cloud/jira/platform/rest/v3/ (workflows · workflow schemes · workflow statuses · issues) |
| S21 | https://support.atlassian.com/jira-cloud-administration/docs/add-and-delete-an-issue-workflow/ |
| S24 | https://support.atlassian.com/jira-cloud-administration/docs/data-limits-and-guardrails/ |
| S25 | https://support.atlassian.com/jira-software-cloud/docs/transition-an-issue/ |
| S28 | https://support.atlassian.com/jira/kb/new-workflow-editor-doesnt-save-transition-changes-if-draft-exists-in-old-workflow-editor/ |
| S37 | https://support.atlassian.com/jira-cloud-administration/docs/configure-workflow-schemes/ |
| S38 | https://support.atlassian.com/jira-cloud-administration/docs/add-edit-and-delete-an-issue-workflow-scheme/ |
| S39 | https://support.atlassian.com/jira-cloud-administration/docs/permissions-for-company-managed-projects/ |
| S40 | https://support.atlassian.com/jira/kb/use-the-edit-workflows-permission-in-company-managed-projects-in-jira-cloud |
| S42 | https://support.atlassian.com/jira-cloud-administration/docs/configure-resolutions-in-a-jira-workflow/ |
