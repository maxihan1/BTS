# 스프린트 편집·삭제 UI + 보드 부채 정리

> 티어: T2
> slug: sprint-manage-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-01

## Brief

**Maxi 원문.** 「1~3번 한 pr로 처리 가능한거 아니야?」 → 「전부 한 PR」 선택.
가리키는 1~3번은 직전 응답의 목록이다.

1. 스프린트 이름 바꾸기 · 스프린트 지우기 (로드맵 A2)
2. 직전 PR #416 이 남긴 부채 4건 (장부 145~148)
3. 아직 장부에 없는 문제 2건 등재

**classify 결과.** `type=ui` · `agent=frontend-engineer` · `primary_bc=agile-planning` · `tier=T2`(선언).
frontend-engineer 가 기본 담당이지만 **백엔드 task 가 1개 있다** — 그 task 만 backend-engineer 로 지정한다.

**FR.** `FR-BL-02`(백로그 → 스프린트) 의 **D6 프론트 범위 회수**. 신규 FR 없음 → **총수 불변 143**.
로드맵 `~/.claude/plans/playful-cooking-minsky.md` §FR 동기화 표가 A 를 「FR-BD-01-2 회수 +
FR-BL-02 D6 범위 회수 · 불변 143」으로 적었고, 앞의 것은 PR #416 이 회수했다.

**범위 밖.** WIP 제한 편집(로드맵 B) · 보드 설정 화면(B) · P0 판별식(FR-WF-07 D6 대기) ·
PR #417(다른 세션 소관).

## Jira 대조 (전 타입 필수)

`jira-parity-contract.md` §1 5단계 산출물. **§1-0 재사용 grep 을 먼저 돌렸다** —
`docs/plans/2026-08-31-board-crud-recovery.md` 등 34개 문서에 `## Jira 대조` 가 있으나
**스프린트 편집·삭제 조작을 조사한 행은 없다.** 보드 행은 승계 대상이 아니므로 전량 신규 조회했다.
조회일 **2026-09-01** · 전 행 **Jira Cloud**(company-managed).

| # | 원문 인용 | 출처 | Cloud/DC |
|---|---|---|---|
| **J1** | "You can change its name, goal, and start and end dates. **You can only edit the name and goal for a complete sprint.**" | https://support.atlassian.com/jira-software-cloud/docs/edit-a-sprint-in-a-company-managed-project/ | **Cloud** |
| **J2** | "From your space navigation, select the **Backlog** tab. Select more (…) in the upper right corner of the screen, then **Edit sprint**." | https://support.atlassian.com/jira-software-cloud/docs/edit-a-sprint-in-a-company-managed-project/ | **Cloud** |
| **J3** | "When you delete a sprint, the work item it contained are **moved to the next sprint in the list**." / "If you delete an active sprint, those work items will **keep their status** in their new sprint." | https://support.atlassian.com/jira-software-cloud/docs/delete-a-sprint/ | **Cloud** |
| **J4** | "Select **More actions** (…), then **Delete sprint**." (Backlog 탭) | https://support.atlassian.com/jira-software-cloud/docs/delete-a-sprint/ | **Cloud** |
| **J5** | 완료 스프린트는 경로가 다르다 — **Reports → Sprint Report → 스프린트 선택 → More actions → Delete sprint**. "The process for deleting completed sprints is different." | https://support.atlassian.com/jira-software-cloud/docs/delete-a-completed-sprint-in-a-company-managed-project/ | **Cloud** |
| **J6** | "If you have Sprints created just for test and/or have created sprints by mistake, **you cannot simply remove these sprints**." — 단 같은 문서가 "**This article only applies to Atlassian apps on the Data Center platform**" 이라 못박는다. | https://support.atlassian.com/jira/kb/how-to-remove-sprints-from-boards/ | **DC 전용** |

**J6 판정.** 검색 단계에서 「활성 스프린트는 삭제할 수 없다」는 서술이 잡혔으나 **출처가 DC 전용
문서**였다. Cloud 정본(J3)은 *"If you delete an active sprint…"* 로 **활성 삭제를 전제**한다.
→ **Cloud 기준으로 전 상태 삭제 가능**으로 판정한다. BTS `SprintApplicationService.softDelete(:188)`
도 상태 가드가 없어 이미 일치한다 — **삭제 쪽 백엔드 변경 0**.

**조회했으나 원문을 확보하지 못한 것 1건.** 편집·삭제 두 문서 **어느 쪽도 필요한 권한을 명시하지
않았다.** 검색 요약에 "Manage Sprints space permission" 이 보였으나 원문 문장을 확보하지 못했다.
BTS 는 기존 `SprintApplicationService` 가 편집·삭제 모두 `IssuePermission.CREATE` 를 쓰므로
**그대로 둔다**(근거 없는 변경을 하지 않는다). 이 미확보 사실을 기록으로 남긴다.

### 의도적 편차

- **X1 — 삭제된 스프린트의 이슈 행선지.**
  Jira 는 **다음 스프린트로 이동**한다(J3). BTS 는 **백로그로 복귀**한다.
  근거. BTS 에는 Jira 의 board 내 **스프린트 순서** 개념이 없어 「다음」을 정의할 수단이 없다.
  도입하려면 순서 컬럼 + 마이그레이션이라 **T3 승격**이고 이번 범위 밖이다.
  현재 동작은 우연이 아니라 설계다 — `findIssueKeysByProject` 가 soft-deleted 스프린트를
  JOIN 에서 제외하므로 이슈가 백로그 계산에 자동 복귀한다(ADR `fr-bl-02-sprint-issue-association`).
  **확인 다이얼로그 문구가 이 차이를 그대로 말한다** — 「이슈는 삭제되지 않고 백로그로 돌아갑니다」.
- **X2 — 진입 위치.**
  Jira 는 백로그 탭 **우상단 `•••` 하나**다(J2·J4). BTS 는 **스프린트 칸 헤더마다 `⋯`** 를 둔다.
  근거. BTS 백로그는 F15 로 세로 스택이 되어 **여러 스프린트가 동시에 보인다**
  (`SprintColumnHeader.tsx`). 전역 `•••` 하나로는 대상 스프린트를 지정할 수단이 없다.
- **X3 — 완료 스프린트 삭제 경로.**
  Jira 는 **Sprint Report** 화면에서 지운다(J5). BTS 는 같은 `⋯` 메뉴에서 지운다.
  근거. BTS 에 Sprint Report 화면이 없다(번다운 화면만 있다). 없는 화면을 이 PR 에서
  새로 만들지 않는다. 경로를 하나로 두는 것이 X2 와도 일관된다.

## 도메인 정리

**BC.** `agile-planning` 단독. 다른 BC 는 건드리지 않는다.

**영향 엔티티.** `Sprint`(`SprintStatus` = PLANNED / ACTIVE / COMPLETED) · `sprint_issues` 조인.
**신규 엔티티·용어 0건** — glossary 갱신 불필요.

**관련 ADR.** `docs/decisions/2026-06-24-fr-bl-02-sprint-issue-association.md`.
이 ADR 이 `sprint_issues` 를 **`issue_key` 기반 조인**으로 못박았고 `UNIQUE(issue_key)` 전역 제약을
키 재사용 금지의 보강으로 설명한다. **이번 변경은 이 결정을 건드리지 않는다** — 삭제 후 이슈가
백로그로 돌아가는 동작이 이 설계의 자연스러운 귀결이다(아래 X1).

**기존 결정과의 충돌.** 없음.

**★ 정본에서 발견한 것.** `docs/plan/product/agile-planning.md` §3.2 의 FR-BL-02 는
`D4. 백엔드 — 스프린트 CRUD + start/complete + 이슈 할당/해제 API` **`[x]`** (PR #182) ·
`D6. 프론트 UI — @dnd-kit/core 백로그 ↔ 스프린트 보드(드래그 5시나리오 + 라이프사이클 + 권한 게이팅)`
**`[x]`** (PR #183) 인데, **편집·삭제 UI 는 D6 이 소비하지 않았다.** D4 의 「스프린트 CRUD」가
집합 약어로 완료를 덮은 자리라 PR #416 의 보드 「CRUD」와 **같은 양식**이다
(메모리 `set-word-hides-partial-implementation`). 이번 PR 이 D6 각주로 회수 사실을 남긴다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

- **S1.** Given 계획됨(PLANNED) 스프린트가 있고 내게 권한이 있다
  When 스프린트 칸 헤더의 `⋯` → 「스프린트 편집」을 누르고 이름을 고쳐 저장한다
  Then 칸 헤더의 이름이 바뀌고 목록이 갱신된다.
- **S2.** Given 완료됨(COMPLETED) 스프린트가 있다
  When 편집 창을 연다
  Then **날짜 입력이 잠겨 있고** 이름·목표만 고칠 수 있다.
- **S3.** Given 이슈 3건이 담긴 스프린트가 있다
  When `⋯` → 「스프린트 삭제」 → 확인
  Then 스프린트 칸이 사라지고 **이슈 3건이 백로그에 나타난다**.
- **S4.** Given 삭제 요청이 서버에서 실패한다
  When 확인을 눌렀다
  Then 확인 창이 **열린 채** 사유를 창 안에 보여주고, 다시 시도하거나 취소할 수 있다.
- **S5.** Given 내게 스프린트 조작 권한이 없다
  When 스프린트 칸 헤더를 본다
  Then `⋯` 메뉴에 편집·삭제 **항목이 렌더되지 않는다**(비활성이 아니라 부재).

### 기능 요구사항 (FR)

`FR-BL-02` D6 범위 회수. 신규 FR 번호를 만들지 않는다.

- **FR-1.** 스프린트 칸 헤더 `⋯` 메뉴에서 **이름·목표·시작일·종료일**을 편집한다 (PLANNED · ACTIVE).
- **FR-2.** **COMPLETED 스프린트는 이름·목표만** 편집 가능하고 날짜는 잠긴다 (J1).
  프론트 잠금만으로 흉내내지 않고 **백엔드가 거부**한다.
- **FR-3.** 스프린트 칸 헤더 `⋯` 메뉴에서 스프린트를 **삭제**한다 (전 상태).
- **FR-4.** 삭제 후 그 스프린트의 이슈는 **백로그에 나타난다** (X1).
- **FR-5.** 권한 미보유 시 편집·삭제 항목을 **렌더하지 않는다** (비활성 아님).
  보드 쪽 PR #416 과 같은 규율.

### 비기능 요구사항 (NFR)

- **NFR-1.** 편집 폼은 **신규 작성 금지** — `StartSprintDialog` 의 폼을 추출해 공유한다.
  그 폼이 이미 풀어 둔 **부분 저장 유실 방어**(`StartSprintDialog.tsx:75` 주석 — 재시도가
  `endDate: null` 로 남의 저장분을 지우는 문제)를 **함께 옮기고 테스트로 고정**한다.
- **NFR-2.** 다이얼로그는 `components/ui/confirm-dialog.tsx` 재사용. `title` 은 **화면 내 고유**
  (Playwright `getByRole('dialog', { name })` strict mode 즉사 방지 — 계약 §2).
- **NFR-3.** 목록 갱신은 **invalidate-only**. `setQueryData` 로 손으로 깁지 않는다.
- **NFR-4.** 터치 타깃 `min-h-11`(44px) 유지 — 헤더 기존 규율.

### API 인터페이스 (REST)

**신규 엔드포인트 0건.** 둘 다 이미 있다.

- `PATCH /api/v1/sprints/{id}` — **계약만 좁힌다.**
  `status == COMPLETED` 이고 `startDate` 또는 `endDate` 가 **present** 면 **400**.
  `JsonNullable` 3-상태(absent / explicit-null / value)를 쓰므로 「보내지 않음」과
  「null 로 지움」이 구분된다 — absent 는 통과, present 는 거부다.
  에러 코드는 기존 `AGILE_*` 계열을 따르고 **named exception** 으로 던진다
  (PR #416 리뷰 지적 3 — `IllegalArgumentException` 을 통째로 400 에 매핑하지 않는다).
- `DELETE /api/v1/sprints/{id}` — **백엔드 무변경.** 프론트 소비만 추가한다.

### 데이터 모델 변경

**없음. 마이그레이션 0건.** `sprints` · `sprint_issues` 스키마 무변경.

### 엣지 케이스

- **E-1.** COMPLETED 스프린트에 날짜를 보내면 400 — **프론트가 아니라 백엔드가** 거부한다.
- **E-2.** 편집 중 다른 세션이 같은 스프린트를 고치면 기존 `version` 낙관적 락이 409 를 낸다.
  이번 PR 이 그 경로를 새로 만들지 않는다 — 기존 동작을 깨지 않는지만 확인한다.
- **E-3.** 삭제 실패 시 확인 창이 **열린 채** 창 안에 사유를 보여준다 (`ConfirmDialog.error`).
- **E-4.** 삭제 중(`confirming`) 에는 취소·Esc·오버레이 전부 잠긴다 — 실패가 갈 곳을 보장한다.
  **여기에 상한이 필요하다** — 장부 145 가 그 결함이고 이번 PR 이 함께 닫는다.
- **E-5.** 이슈 0건 스프린트 삭제 — 확인 문구가 「이슈는 백로그로」를 말하는데 옮길 이슈가 없다.
  문구가 거짓이 되지 않게 **개수에 따라 갈라 쓴다**.
- **E-6.** 마지막 남은 스프린트를 지우면 백로그만 남는다. 빈 상태가 깨지지 않아야 한다.

### 제약 조건

- **BC 격리.** `agile-planning` 만. 다른 BC 는 pgmq 이벤트로도 건드리지 않는다.
- **즉사 계약(§2) 교차.** 다이얼로그 접근성 이름 고유 · `getByRole('combobox')` 류
  **이름 없는 셀렉터 다중 매치** 주의 — 착수 전 사전 grep 필수(§5).
- **재사용 자산(§4) 소비.** 새로 만들지 않는다 — `dropdown-menu.tsx` · `confirm-dialog.tsx` ·
  `StartSprintDialog` 의 폼 · `useProjectPermissions` · `api/backlog.ts:337 updateSprint`.

### 측정 가능한 완료 기준

1. `⋯` → 「스프린트 편집」으로 PLANNED 스프린트 이름이 바뀐다 (E2E).
2. COMPLETED 스프린트에 날짜를 보내면 **백엔드가 400** 을 낸다 (백엔드 테스트, red-first).
3. `⋯` → 「스프린트 삭제」 후 그 스프린트의 이슈가 **백로그에 나타난다** (E2E).
4. 권한 없는 사용자에게 두 항목이 **DOM 에 없다** (단위 테스트).
5. 부채 145~148 이 장부에서 `⬜` → `✅` 로 바뀌고 각 항목의 재현 테스트가 있다.
6. 기존 `backlog-*` E2E 전수 통과 · `pnpm verify` · `./gradlew :modules:agile-planning:test` EXIT=0.


## Sanity Check

스펙을 스스로 흔들었다. **gap 4건 발견 · 전부 1회 보강했다.** Maxi 결정이 필요한 것은 없다.
실측이 필요했던 3건은 아래 「실측으로 닫은 가정」에서 닫았다.

### ❓ 발견 1 — COMPLETED 스프린트의 날짜 입력을 어떻게 막나 (모호)

스펙 FR-2 가 「이름·목표만 편집 가능」이라 적었을 뿐 **UI 형태를 안 정했다.** J1 원문도
*"You can only edit the name and goal for a complete sprint"* 까지만 말하고 화면을 말하지 않는다.

**보강.** 날짜 입력을 **비활성(disabled) + 사유 문구**로 둔다. 숨기지 않는다.

**FR-5(권한 없으면 렌더하지 않는다)와 규율이 갈리는 것이 의도다.**
권한 부재는 「그런 조작이 있다는 사실 자체를 알 필요가 없다」이고, 상태 제약은
「있는 조작이 지금은 안 되는 이유를 알아야 한다」다. 숨기면 사용자가 **입력칸이 사라진 이유**를
모른 채 다른 곳을 찾는다.

### ❓ 발견 2 — 스프린트 삭제의 응답 지연 처리가 스펙에 없었다 (누락)

E-4 가 「`confirming` 중에는 닫힘 경로가 전부 잠긴다」를 적었지만 **상한을 안 적었다.**
보드 쪽에서 같은 자리가 이미 부채로 등재돼 있다 — 장부 **145**(상한 없음) · **146**(타임아웃이
이미 나간 요청을 취소하지 않음).

**보강. 145 와 146 을 한 기전으로 닫는다.**
현재 `withDeleteTimeout`(`apps/web/src/hooks/use-boards.ts:227`)은 `Promise.race` 라
**시간만 재고 요청은 그대로 살아 있다.** `AbortController` 로 실제 취소를 배선하면
요청이 끊기고 → 실패가 즉시 도착하고 → 창이 곧바로 풀린다. **두 부채가 같은 수정으로 닫힌다.**
헬퍼는 보드 전용에서 **공용 자리로 옮겨** 스프린트 삭제가 같은 것을 쓴다 — 사본을 만들지 않는다.

### ❓ 발견 3 — 이슈 0건 스프린트의 확인 문구 (엣지 미커버)

E-5 가 「개수에 따라 갈라 쓴다」로 남겨 두고 문구를 안 정했다.

**보강.** 이슈가 1건 이상이면 「이슈 N건은 삭제되지 않고 백로그로 돌아갑니다」,
**0건이면 그 문장을 아예 넣지 않는다.** 옮길 것이 없는데 옮긴다고 적으면 문구가 거짓이 된다.

### ❓ 발견 4 — 삭제된 스프린트의 번다운 딥링크 (엣지 미커버)

스프린트를 지우면 헤더의 번다운 링크는 함께 사라지지만, **주소를 직접 친 경우**가 남는다.

**보강 = 범위 밖 명시.** 이번 PR 이 만드는 경로가 아니고(번다운 라우트는 FR-RP-01 소관),
soft delete 라 데이터도 남아 있다. **이 PR 에서 건드리지 않는다**고 적어 둔다 —
「조회했고 범위 밖으로 뒀다」와 「안 봤다」는 다른 기록이다.

### 실측으로 닫은 가정 3건

| 가정 | 실측 결과 |
|---|---|
| 프론트 `updateSprint` 가 시작 다이얼로그 안에서만 쓰인다 | **맞다.** 호출처는 `StartSprintDialog.tsx:370` 하나뿐(`useUpdateSprint` 경유) |
| 낙관적 락 `version` 을 프론트가 이미 들고 있다 | **맞다.** `SprintMeta` 에 `version: z.number().int()` 가 있고 `updateSprint` 가 **필수**로 싣는다(`api/backlog.ts:156·338`) |
| 편집·삭제 권한이 둘 다 `IssuePermission.CREATE` | **맞다.** `update(:146)`·`softDelete(:191)` 이 같은 `loadSprintWithPermission(..., CREATE)` 를 쓴다 |


## Plan

### Task 1. 백엔드 — COMPLETED 스프린트의 날짜를 잠근다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/SprintApplicationService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/SprintExceptions.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/SprintExceptionHandler.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/SprintApplicationServiceTest.kt`]
- depends-on: []
- jira: [J1]

**RED**:
- COMPLETED 스프린트에 `endDate` 를 실어 `update` 하면 **400** 이어야 하는데 지금은 **200 이고 날짜가 바뀐다**.
- ★ **컴파일 red 로 끝내지 않는다** — 새 예외 클래스가 없어서 나는 컴파일 실패는
  「결함이 실재했다」를 증명하지 못한다 (메모리 `compile-red-is-not-behavior-red`).
  단언은 **응답 코드와 저장된 날짜 두 축**을 본다.

**GREEN**:
- `update` 안에서 `existing.status == COMPLETED` 이고 `startDate` 또는 `endDate` 가
  `isPresent` 면 `SprintDateLockedException` 을 던진다.
- ★ `JsonNullable` 3-상태를 지킨다 — **absent 는 통과**, present(값이든 null 이든)만 거부한다.
  「보내지 않음」과 「null 로 지움」을 뭉개면 이름만 고치는 요청까지 막힌다.
- 핸들러에 `SprintDateLockedException` → 400 매핑 추가. ★ `IllegalArgumentException` 을
  통째로 매핑하지 않는다 (PR #416 리뷰 지적 3 과 같은 자리).

**REFACTOR**: 판정을 private 함수로 빼고 KDoc 에 J1 원문 인용을 남긴다.

**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests '*SprintApplicationServiceTest*'`
+ **비-공허 확인** — GREEN 선커밋 뒤 판정을 끊어 red 1회를 눈으로 본다.

---

### Task 2. 공용 삭제 타임아웃을 실제 취소로 바꾼다 (장부 145 · 146 동시)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/delete-timeout.ts`, `apps/web/src/lib/delete-timeout.test.ts`, `apps/web/src/hooks/use-boards.ts`, `apps/web/src/hooks/use-boards.test.tsx`, `apps/web/src/api/boards.ts`]
- depends-on: []

**RED**:
- 타임아웃이 지난 뒤에도 **원래 요청이 살아 있다**(장부 146). `AbortSignal` 이 실제로
  `abort` 되는지를 단언하는 테스트가 없다 — 그 테스트를 먼저 쓴다.
- 확인 창이 상한 없이 잠긴다(장부 145) — 취소가 배선되면 실패가 즉시 도착해 창이 풀린다.

**GREEN**:
- `withDeleteTimeout` 을 `hooks/use-boards.ts:227` 에서 `lib/delete-timeout.ts` 로 옮긴다.
  **사본을 만들지 않는다** — 스프린트 삭제가 같은 것을 쓴다.
- `Promise.race` 를 `AbortController` 로 교체하고 `fetch` 에 `signal` 을 넘긴다.
  `deleteBoard` 시그니처가 `signal` 을 받도록 확장한다.

**REFACTOR**: 상수명을 보드 전용(`DELETE_BOARD_TIMEOUT_MS`)에서 공용으로 바꾸고 재export 로 호환 유지.

**검증**:
- `pnpm --filter web test -- delete-timeout use-boards`
- 기존 E2E: `apps/web/e2e/board-manage.spec.ts` (삭제 경로가 그대로 도는지)
- 눈확인: 보드 삭제 확인 창 — 응답 지연 시 상한 뒤 사유가 창 안에 뜬다 (라이트/다크)

---

### Task 3. 프론트 API · 훅 — deleteSprint · useDeleteSprint

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/backlog.ts`, `apps/web/src/api/backlog.test.ts`, `apps/web/src/hooks/use-backlog.ts`, `apps/web/src/hooks/use-backlog.test.tsx`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [2]
- jira: [J3]

**RED**: `deleteSprint` 가 없다. `DELETE /api/v1/sprints/{id}` 호출 · 404·403 매핑 · 성공 시
스프린트 목록과 백로그 목록 **양쪽** 무효화를 단언한다.

**GREEN**:
- `deleteSprint(sprintId, signal)` 추가. 기존 `updateSprint(:337)` 의 에러 매핑 관례를 따른다.
- `useDeleteSprint` — Task 2 의 공용 타임아웃을 씌우고 **invalidate-only**.
  ★ **무효화 키는 하나뿐이다** — `backlogKeys`(`use-backlog.ts:32-39`)에 `detail` 만 있고
  스프린트와 백로그 이슈가 **같은 `fetchBacklog(projectKey)` 응답**에서 온다.
  `invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })` 한 번이 양쪽을 덮는다.
  **별도의 스프린트 키를 찾지 말 것** — 없다(리뷰 E2).
- MSW 핸들러 + 실패 픽스처.

**REFACTOR**: 무효화 키 목록을 상수로.

**검증**: `pnpm --filter web test -- backlog use-backlog`

---

### Task 4. SprintForm 추출 + EditSprintDialog

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/SprintForm.tsx`, `apps/web/src/components/backlog/SprintForm.test.tsx`, `apps/web/src/components/backlog/EditSprintDialog.tsx`, `apps/web/src/components/backlog/EditSprintDialog.test.tsx`, `apps/web/src/components/backlog/StartSprintDialog.tsx`, `apps/web/src/components/backlog/StartSprintDialog.test.tsx`, `apps/web/src/i18n/backlog-labels.ts`]
- depends-on: []
- jira: [J1]

**RED**(동반 테스트):
- COMPLETED 스프린트에서 **날짜 입력이 `disabled`** 이고 사유 문구가 보인다 (Sanity ❓1).
- **부분 저장 유실 방어가 옮겨졌다** — 재시도가 `endDate: null` 로 남의 저장분을 지우지 않는다
  (`StartSprintDialog.tsx:75` 주석이 이미 푼 문제). ★ 이 단언을 빼면 추출이 방어를 조용히 흘린다.

**GREEN**:
- `StartSprintDialog` 의 폼을 `SprintForm` 으로 추출해 **공유**한다. 신규 폼을 만들지 않는다(NFR-1).
- ★ **추출 후 크기를 잰다.** `StartSprintDialog.tsx` 는 지금 **534줄**로 §2.2 상한의 2.6배다.
  폼을 뺀 뒤에도 200줄을 넘으면 **그 사실을 게이트 2 요약에 싣는다** — 이 PR 이 상한 위반을
  새로 만들지는 않지만, 줄었는지 늘었는지는 기록에 남긴다(리뷰 E3).
- `EditSprintDialog` 는 그 폼 + `useUpdateSprint`(기존) + `version` 낙관적 락을 쓴다.
- ★★ **mutation 응답을 버리지 않는다.** `useUpdateSprint` KDoc 이 못박는다 —
  *"재시도가 낡은 `version` 으로 409 를 받지 않으려면 호출자가 이 mutation 의 응답
  SprintMeta 로 자기 기준값과 `version` 을 갱신해야 한다. 여기서 invalidate 를 하더라도
  재조회는 비동기라 그 사이의 재시도를 막아주지 못한다."* 저장 실패 → 재시도가 정상 경로이므로
  **이 계약을 지키는 테스트를 동반한다**(리뷰 E4).
- ★ **다이얼로그 이름을 화면 내 고유로** 준다. `backlog.spec.ts:524·529` 가
  `getByRole('dialog', { name: backlogLabels.startSprint, exact: true })` 로 잡고 있어
  이름이 겹치면 strict mode 로 즉사한다(즉사 계약 §2).
  ★★ **동등이 아니라 부분문자열이 위험하다.** 계약 §2 의 `검색` ↔ `전역 검색` 선례가 같은 양식이고,
  그 면제는 **`exact: true` 유지를 조건으로** 승인됐다. 새 이름이 기존 두 이름의 부분문자열이거나
  그 반대가 되지 않는지 확인하고, 새 셀렉터에도 `exact: true` 를 단다(리뷰 D2).
- ⚠️ **`StartSprintDialog.test.tsx:317·372·499` 는 이름 없는 `screen.getByRole('dialog')`** 다.
  추출로 렌더 트리가 바뀌면 다중 매치가 날 수 있다 — **이 3줄을 이름 있는 셀렉터로 좁힌다.**

**REFACTOR**: 라벨을 `backlog-labels.ts` 로 모은다.

**검증**:
- `pnpm --filter web test -- SprintForm EditSprintDialog StartSprintDialog`
- 기존 E2E: `apps/web/e2e/backlog.spec.ts` (스프린트 시작·완료 경로)
- 눈확인: 편집 창 — PLANNED(날짜 열림) · COMPLETED(날짜 잠김 + 사유) 라이트/다크

---

### Task 5. SprintColumnHeader `⋯` 메뉴 — 편집 · 삭제 + 권한 게이팅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/SprintColumnHeader.tsx`, `apps/web/src/components/backlog/SprintColumnHeader.test.tsx`, `apps/web/src/components/backlog/SprintColumn.tsx`, `apps/web/src/components/backlog/SprintColumn.test.tsx`, `apps/web/src/i18n/backlog-labels.ts`]
- depends-on: [3, 4]
- jira: [J2, J3, J4, J5]

**RED**(동반 테스트):
- 권한이 있으면 `⋯` 메뉴에 「스프린트 편집」·「스프린트 삭제」가 있다.
- **권한이 없으면 두 항목이 DOM 에 없다**(비활성이 아니라 부재 — FR-5).
- 삭제 확인 문구가 **이슈 개수에 따라 갈린다** — 1건 이상이면 「이슈 N건은 삭제되지 않고
  백로그로 돌아갑니다」, **0건이면 그 문장이 없다**(Sanity ❓3).
- 삭제 실패 시 창이 **열린 채** 사유를 창 안에 보여준다(E-3).

**GREEN**:
- `dropdown-menu.tsx` 재사용. `confirm-dialog.tsx` 는 **제어 컴포넌트 계약**대로 쓴다 —
  `onConfirm` 은 닫지 않고, **성공했을 때만** 소비자가 `onOpenChange(false)` 한다.
- 권한은 `useProjectPermissions` 로 `CREATE` 를 본다(실측 결과 편집·삭제 동일).

**REFACTOR**: **분리는 판단이 아니라 요구다.** `SprintColumnHeader.tsx` 는 지금 **162줄**이고
`⋯` 메뉴·다이얼로그 상태·권한 분기를 얹으면 200줄(`DEVELOPMENT.md §2.2`)을 넘는다.
이 파일 자신의 KDoc 이 *"`SprintColumn` 이 200줄을 넘어서였다"* 를 분리 사유로 적고 있다 —
같은 기준을 적용해 메뉴를 `SprintActionsMenu.tsx` 로 뺀다(리뷰 E3).

**검증**:
- `pnpm --filter web test -- SprintColumnHeader SprintColumn`
- 기존 E2E: `apps/web/e2e/backlog.spec.ts` · `apps/web/e2e/sprint-burndown.spec.ts`
  (헤더에 번다운 링크가 있어 같은 줄을 건드린다)
- 눈확인: 스프린트 헤더 `⋯` — 권한 있음/없음 · PLANNED/ACTIVE/COMPLETED · 라이트/다크

---

### Task 6. 「새 보드」 다이얼로그 여백 (장부 147)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/CreateBoardForm.tsx`, `apps/web/src/components/board/CreateBoardForm.test.tsx`]
- depends-on: []

**RED**(동반 테스트): `showEmptyStateIntro={false}` 일 때 빈 상태용 여백 클래스가 붙지 않는다.

**GREEN**: 여백을 `showEmptyStateIntro` 에 묶는다. 다이얼로그 안에서는 축소.

**검증**:
- `pnpm --filter web test -- CreateBoardForm`
- 눈확인: 보드 스위처 → 「보드 만들기」 다이얼로그 여백 — 라이트/다크

---

### Task 7. classify-task 의 죽은 지역 변수 제거 (장부 148)

**메타**.
- agent: `frontend-engineer`
- files: [`scripts/workflow/classify-task.ts`]
- depends-on: []

**RED**: 없음 — 순수 dead code 제거다. 기존 `classify-task.test.ts` 전량 통과가 안전망이다.

**GREEN**: `detectType` 의 `const lower = raw.toLowerCase()` 를 지운다(본문은 전부 `stripped` 를 쓴다).

**검증**: `node --experimental-strip-types --test scripts/workflow/classify-task.test.ts`

---

### Task 8. E2E — sprint-manage.spec.ts

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/sprint-manage.spec.ts`]
- depends-on: [5]
- jira: [J2, J4]

**RED**(동반 테스트):
- 편집 → 이름이 헤더에서 바뀐다.
- 삭제 → 스프린트 칸이 사라지고 **그 이슈가 백로그에 나타난다**(FR-4 · X1).
  ★ 「칸이 사라졌다」만 보면 이슈 행선지를 검사하지 않는다 — 백로그 쪽을 반드시 단언한다.
- COMPLETED 스프린트 편집 창에서 날짜가 잠겨 있다.

**GREEN**: `board-manage.spec.ts` 의 구조를 복제한다(같은 조작 양식).

**검증**: `pnpm --filter web test:e2e -- sprint-manage backlog`

---

### Task 9. 문서 동기화 — 장부 · TODOS · 정본 각주 · 대시보드 영역

**메타**.
- agent: `frontend-engineer`
- files: [`TODOS.md`, `docs/plans/2026-08-12-debt24-master.md`, `docs/plan/product/agile-planning.md`, `scripts/build-dashboard.mjs`, `docs/INDEX.md`, `docs/INDEX-fr.md`, `docs/INDEX-recent.md`]
- depends-on: [1, 2, 5, 6, 7]

**GREEN**:
- 장부 **145~148 을 `⬜` → `✅`** 로 바꾸고 `TODOS.md` 의 같은 항목도 닫는다.
  ★ **번호는 표 맨 끝을 직접 확인해서** 붙인다 — `debt-ledger-mapping.test.ts:92-97` 이
  `cells[1]`(번호)을 안 읽어 충돌해도 초록이다(장부 51번 · `partial-column-parser-lets-unread-column-rot`).
- **미등재 2건 신규 등재** — ① `sprint_issues` 고아 행 위생 ② `BoardRepository.kt` 308줄.
  ②를 등재하려면 `scripts/build-dashboard.mjs` 의 `AREA_CATEGORIES`(:296)에
  **`agile-planning` 영역이 없다** — 한 줄 추가한다.
- `docs/plan/product/agile-planning.md` §3.2 **FR-BL-02 D6 에 각주** — 「D6 이 `[x]` 였으나
  편집·삭제 UI 는 소비하지 않았고 이 PR 이 회수했다」. **집합 약어로 완료를 덮지 않는다**
  (메모리 `set-word-hides-partial-implementation`).
- `node scripts/build-doc-index.mjs` 재실행.

**검증**: `node --experimental-strip-types --test scripts/**/*.test.ts` (장부 판별식 포함)
· `bash scripts/verify-master-plan.sh` EXIT=0 · FR 총수 **143 불변** 확인

---

## Plan 메타

- **task 수**: 9 · **예상 wave**: 4
  (w1 = T1·T2·T6·T7 병렬 / w2 = T3·T4 / w3 = T5 / w4 = T8·T9)
- **구현 규율**: TDD red-first (T2) — `test:` 커밋 → `feat:` 커밋 순서가 로그에서 대조된다.
  ui task 는 시각 검증 트랙(동반 테스트 + 눈확인).
- **추가 검증**: typecheck · ktlint · detekt · vitest · playwright · 판별식 · `verify-master-plan.sh`
- **Jira 매핑**: J1→T1·T4 · J2→T5·T8(편차 X2) · J3→T3·T5(편차 X1) · J4→T5·T8 ·
  J5→T5(편차 X3) · **J6 은 구현 대상이 아니다** — DC 전용 문서라 Cloud 판정에서 배제한 근거 행이고,
  그 판정 결과(전 상태 삭제 가능)가 T5 에 반영돼 **차집합 0**.

## 리뷰 결과

렌즈 2종(`type=ui` → design + eng). **지적 6건 · 5건 plan 반영 완료 · 1건 게이트 1 로 올림.**
모든 지적은 pre-emit 검증 게이트를 통과했다 — **근거 줄을 인용하지 못한 지적은 올리지 않았다.**

### 렌즈 1 — plan-eng-review

**E1 [P1] (confidence 10/10) · `apps/web/src/api/client.ts:82·105-110` — 🛑 미해결, 게이트 1**

```ts
const { method = 'GET', body, headers: extraHeaders } = options
...
const fetchOptions = { method, credentials: 'include' as const, body: ... }
```

`ApiFetchOptions` 는 `method`·`body`·`headers` **셋만** 분해하고 `fetchOptions` 는 `signal` 을
설정하지 않는다. **지금 구조로는 `AbortSignal` 이 `fetch` 까지 못 간다.**
Task 2 의 GREEN(「`fetch` 에 `signal` 을 넘긴다」)은 **공용 `apiFetch` 를 고쳐야** 성립하고,
그 파일은 앱의 **모든 API 모듈**이 쓴다. task 가 적은 「헬퍼 이전」보다 폭발 반경이 훨씬 크다.
→ **선택이 필요하다. 아래 게이트 1.**

**E2 [P1] (confidence 10/10) · `apps/web/src/hooks/use-backlog.ts:32-39` — ✅ 반영**

```ts
export const backlogKeys = { detail: (projectKey: string) => ['backlog', projectKey] as const }
```

키가 **하나뿐이다.** Task 3 이 「스프린트 목록과 백로그 목록 **양쪽** 무효화」라 적었는데
무효화할 두 번째 키가 **존재하지 않는다.** 구현자가 없는 키를 찾아 헤맬 자리였다.
→ Task 3 을 「`detail` 한 번이 양쪽을 덮는다 · 별도 스프린트 키를 찾지 말 것」으로 고쳤다.

**E3 [P2] (confidence 9/10) · `SprintColumnHeader.tsx`(162줄) · `StartSprintDialog.tsx`(534줄) — ✅ 반영**

`SprintColumnHeader` 자신의 KDoc 이 *"`SprintColumn` 이 200줄(`DEVELOPMENT.md §2.2`)을
넘어서였다"* 를 분리 사유로 적는데, 162줄에 `⋯` 메뉴·다이얼로그 상태·권한 분기를 얹으면
같은 선을 넘는다. Task 5 REFACTOR 가 「보고 판단」이라 **자기가 인용한 규칙을 자기가 면제**하고 있었다.
→ `SprintActionsMenu.tsx` 분리를 **요구**로 바꿨다. `StartSprintDialog` 534줄은 추출 후 재측정해
게이트 2 요약에 싣도록 했다.

**E4 [P2] (confidence 10/10) · `use-backlog.ts` `useUpdateSprint` KDoc — ✅ 반영**

> *"재시도가 낡은 `version` 으로 409 를 받지 않으려면 호출자가 이 mutation 의 응답 SprintMeta 로
> 자기 기준값과 `version` 을 갱신해야 한다. 여기서 invalidate 를 하더라도 재조회는 비동기라
> 그 사이의 재시도를 막아주지 못한다."*

Task 4 가 「`version` 낙관적 락을 쓴다」까지만 적고 **응답 흡수 의무를 안 적었다.**
저장 실패 → 재시도가 정상 경로인 다이얼로그라 빠지면 **재시도가 409** 로 죽는다.
→ 계약을 Task 4 GREEN 에 명시하고 동반 테스트를 요구했다.

### 렌즈 2 — 디자인 (`docs/design/jira-parity-contract.md` §2~§6)

gstack `plan-design-review` 대신 **프로젝트 정본인 패리티 계약**으로 돌렸다.
이 변경의 디자인 판정 축(즉사 계약 · 시각 정본 · 재사용 자산 · 눈확인)이 전부 그 문서에 있고,
`## Jira 대조` 는 이미 §1 절차로 채웠다. **생략이 아니라 대체이며 그 사실을 여기 남긴다.**

**D2 [P1] (confidence 9/10) · 계약 §2 `role="dialog"` 고유 label — ✅ 반영**

실측 명령을 돌렸다. `backlog.spec.ts:524·529` 가
`getByRole('dialog', { name: backlogLabels.startSprint, exact: true })` 를 쓴다.
위험은 **동등이 아니라 부분문자열**이다 — 계약 §2 의 `검색` ↔ `전역 검색` 선례가 같은 양식이고
그 면제는 **`exact: true` 유지를 조건으로** 승인됐다.
→ Task 4 에 부분문자열 검사와 `exact: true` 를 명시했다.

**D1 [P3] (confidence 7/10) · 계약 §4 재사용 자산 표 — ⚠️ 주의, 이번 PR 범위 밖**

§4 표는 「드롭다운/스위처 → `components/ui/popover.tsx`」라 적는데,
PR #416 의 보드 `⋯` 메뉴는 `dropdown-menu.tsx` 를 썼다(같은 조작 · 다른 프리미티브).
이 plan 도 #416 을 따라 `dropdown-menu.tsx` 를 쓴다 — **일관성이 맞다.**
다만 **§4 표가 낡았을 가능성**이 있다. 계약 문서 수정은 이 PR 의 관심사가 아니므로
**게이트 2 요약에 올려 Maxi 판단**을 받는다. 조용히 덮지 않는다.

### 렌즈별 판정

| 렌즈 | 결과 | BLOCKER |
|---|---|---|
| plan-eng-review | ⚠️ 주의 — E1 미해결(게이트 1) · E2·E3·E4 반영 | **0** |
| 디자인 (패리티 계약) | ✅ 통과 — D2 반영 · D1 주의 | **0** |

**BLOCKER 0건.** E1 은 BLOCKER 가 아니라 **선택**이다 — 세 경로 모두 성립하고,
어느 것이든 이 PR 이 진행된다. 그래서 중단이 아니라 게이트 1 로 올린다.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | not run | type=ui 라 라우팅 대상 아님 |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | skipped | 중첩 codex 토큰 비용(스킬 자체 경고)으로 미실행 — 생략 사실을 여기 남긴다 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | ⚠️ CONCERNS | 4 issues, 0 critical gaps (E1 미해결) |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | ✅ CLEAR | 2 issues (D2 반영 · D1 주의) — 패리티 계약 §2~§6 으로 대체 실행 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | not run | 라우팅 대상 아님 |

- **VERDICT:** DESIGN CLEARED · ENG CONCERNS (E1 결정 대기) — 게이트 1 에서 E1 을 정하면 구현 착수 가능. BLOCKER 0.

**UNRESOLVED DECISIONS:**
- E1 — 삭제 타임아웃의 실제 취소를 어떻게 배선할 것인가 (`apiFetch` 확장 / 타임아웃 제거 / 부채 145·146 이연)
