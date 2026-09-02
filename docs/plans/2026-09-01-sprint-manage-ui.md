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

**FR.** `FR-BL-02`(백로그 → 스프린트) 의 **D6 프론트 범위 회수**. 신규 FR 없음 → **이 PR 은 총수 불변**.
로드맵 `~/.claude/plans/playful-cooking-minsky.md` §FR 동기화 표가 A 를 「FR-BD-01-2 회수 +
FR-BL-02 D6 범위 회수 · 불변 143」으로 적었고, 앞의 것은 PR #416 이 회수했다.

> 🛑 **기준값이 143 에서 144 로 바뀌었다 (2026-09-01 갱신).** 이 plan 이 처음 쓰일 때의 정본은
> 143 이었으나 **PR #419 가 FR-BD-04(보드 종류 + 활성 스프린트 보드)를 신설**해 정본은 **144** 다
> (`docs/plan/README.md:131` · `docs/plan/fr-index.md:122`). 위 로드맵 인용문의 「불변 143」은
> **당시 기록이라 원문 그대로 둔다** — 이 PR 이 주장하는 것은 「총수를 바꾸지 않는다」이고
> 그 기준선이 144 로 옮겨간 것뿐이다. 검증에서 확인할 값은 **144** 다.

**범위 밖.** WIP 제한 편집(로드맵 B) · 보드 설정 화면(B) · P0 판별식(FR-WF-07 D6 대기) ·
PR #417(다른 세션 소관) · **FR-BD-04 D6 전량**(보드 종류 선택 UI = PR ② · 스크럼 보드 화면과
백로그 `?board=` 스코프 = PR ③).

**선행 제약 (2026-09-01 추가).** 이 PR 은 **D6 PR ③ 이 머지된 뒤에** 재개한다.
D6 이 백로그를 `?board=` 스코프로 바꾸므로 Task 8 의 `sprint-manage.spec.ts` 를 먼저 쓰면
D6 에서 다시 써야 한다.

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

> 🛑 **앵커가 이동했다 (2026-09-01 갱신 · PR #421).** 이 task 의 대상 3파일을 #421 이 먼저 고쳤다.
> 착수 전에 **현재 파일을 다시 읽고** 삽입 지점을 잡는다.
> - `SprintApplicationService.create()` — 생성자에 `boardApplicationService` 가 붙고
>   `boardId: UUID? = null` 파라미터와 `ensureScrumBoard` 폴백이 생겼다.
> - `SprintApplicationService.update()` — 복사 생성자에 `boardId = existing.boardId` 행이 추가됐다.
>   **이 task 의 날짜 잠금 판정은 그 복사보다 앞**에 들어간다.
> - `SprintApplicationService.start()` — `findActiveByBoard` 활성 1개 가드가 이미 있다.
> - `SprintExceptions.kt` · `SprintExceptionHandler.kt` — `SprintAlreadyActiveException`(409)과
>   그 핸들러가 이미 들어와 있다. 아래 GREEN 의 「전용 핸들러 필수」에 **코드 선례가 생겼다**.
>
> 🛑 **앵커가 또 이동했다 (2026-09-02 갱신 · PR #424).** 착수 전 재확인은 이 상태를 기준으로 한다.
> - 생성자에 **`boardRepository: BoardRepository` 가 6번째로 추가**됐다(`:63`). 테스트의
>   `makeService` 헬퍼도 그 인자를 받으며 기본값이 있으므로 **기존 호출은 안 바꿔도 된다.**
> - `create` 는 `resolveTargetBoard(projectKey, boardId)`(`:108` · 본체 `:423`)로 **보드 소속을
>   검증**한다. 타 프로젝트·소프트 삭제 보드는 404 다. 이 task 는 `update` 만 건드리므로 무관하지만,
>   **같은 파일의 예외 규약 선례**로 읽을 것 — `resolveTargetBoard` KDoc 이 「왜 `ResponseStatusException`
>   이고 `BoardNotFoundException` 이 아닌가」를 핸들러 `assignableTypes` 로 설명한다.
>   `SprintDateLockedException` 도 **같은 이유로 전용 핸들러가 필수**다.
> - **삽입 지점** — `fun update` 는 `:154`, 복사 생성자의 `boardId = existing.boardId` 는 `:174`.
>   날짜 잠금 판정은 **`:174` 보다 앞**, 즉 `update` 본문 진입 직후 권한 판정 뒤에 들어간다.

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
  ★★ **전용 핸들러는 선택이 아니다.** #421 의 `SprintAlreadyActiveException` KDoc 이 그 이유를
  명문화했다 — *"이 타입이 `ResponseStatusException` 을 상속하므로 핸들러가 없으면 상태 전파
  핸들러가 잡아 errorCode 를 `AGILE_CONFLICT` 로 덮어쓴다."* 같은 자리에 같은 함정이 있다.
  그 예외·핸들러 쌍을 **형식 선례로 그대로 따른다**.

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
- `withDeleteTimeout` 을 `hooks/use-boards.ts:253` 에서 `lib/delete-timeout.ts` 로 옮긴다.
  (앵커 갱신 2026-09-02 — 계획 작성 시 `:227` 이었고 #424 가 26줄 밀었다. 호출부는 `:287`.)
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
- files: [`apps/web/src/api/backlog.ts`, `apps/web/src/api/backlog.test.ts`, `apps/web/src/hooks/use-backlog.ts`, `apps/web/src/hooks/use-backlog.test.tsx`, `apps/web/src/mocks/backlog-handlers.ts`]
- depends-on: [2]
- jira: [J3]

> 🛑 **무효화 지시가 뒤집혔다 (2026-09-02 갱신 · PR #424).** 아래 종전 ★ 문단은 **틀렸고 위험하다.**
> 그대로 쓰면 컴파일도 안 되고, 컴파일을 통과시키려 인자를 맞추면 **#424 가 잡은 BLOCKER 를 재현**한다.
>
> | | 계획 작성 시점 | 지금(실측) |
> |---|---|---|
> | `backlogKeys` | `detail` 하나뿐 · 1인자 | `detail(projectKey, boardId)` **2인자 필수** + `project(projectKey)` **접두 키** |
> | 무효화 방법 | `detail` 완전 일치 1회 | `project` **접두** + `boardKeys.all` |
> | 파일 앵커 | `use-backlog.ts:32-39` | `backlogKeys` `:40-67` · `updateSprint` `api/backlog.ts:360`(계획 `:337`) |
> | MSW 파일 | `mocks/handlers.ts` | **`mocks/backlog-handlers.ts`** (`resolveBoardScope` `:241`) |
>
> **`detail` 로 무효화하지 마라.** 그 키는 **읽기 전용**이고 보드마다 값이 다르다 — 완전 일치라
> 지금 보고 있지 않은 보드의 백로그 캐시가 **조용히 낡은 채로 남는다**. #424 의 BLOCKER-1 이
> 정확히 이 양식이었다(409 복구가 무음으로 멈춤). `backlogKeys` KDoc 이 *"🛑 조회에 쓰지 마라"* ·
> *"무효화 전용"* 으로 두 키의 용도를 이미 갈라 놨다.

**RED**: `deleteSprint` 가 없다. `DELETE /api/v1/sprints/{id}` 호출 · 404·403 매핑 · 성공 시
스프린트 목록과 백로그 목록 **양쪽** 무효화를 단언한다.
★ **보드 축을 단언에 넣는다** — 지금 보고 있는 보드가 아닌 보드의 백로그도 무효화되는지 본다.
그 단언이 없으면 완전 일치로 되돌려도 red 가 안 난다.

**GREEN**:
- `deleteSprint(sprintId, signal)` 추가. 기존 `updateSprint`(`api/backlog.ts:360`)의 에러 매핑 관례를 따른다.
- `useDeleteSprint` — Task 2 의 공용 타임아웃을 씌우고 **invalidate-only**.
  ★ **기존 헬퍼를 쓴다. 새 무효화 코드를 쓰지 마라** —
  `invalidateAfterSprintTransition(queryClient, projectKey)`(`use-backlog.ts:281`)가
  `backlogKeys.project` 접두 + `boardKeys.all` 을 이미 함께 덮는다. `useStartSprint`(`:308`)와
  `useCompleteSprint` 가 같은 것을 쓰므로 **세 상태 전환의 무효화 규약이 한 자리에 남는다** —
  사본을 만들면 그 순간 갈라진다.
  ★ **보드도 함께 무효화돼야 한다.** 스프린트를 지우면 그 스프린트를 그리던 스크럼 보드가
  즉시 바뀌어야 하는데, `useBoard` 는 `staleTime: 30_000` 이라 보드 키를 안 건드리면
  **최대 30초간 없는 스프린트를 보여준다**(#424 가 `useStartSprint` 에서 고친 것과 같은 결함).
- MSW 핸들러 + 실패 픽스처. ★ `backlog-handlers.ts` 는 이제 `?board=` 를 읽는다(`resolveBoardScope :241`) —
  삭제 핸들러도 그 스코프 규약 안에서 동작해야 한다.

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
- ★ **추출 후 크기를 잰다.** `StartSprintDialog.tsx` 는 지금 **553줄**로 §2.2 상한의 2.8배다.
  (실측 갱신 2026-09-02 — 계획 작성 시 534줄이었고 #424 가 +19 했다.)
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
- ⚠️ **`StartSprintDialog.test.tsx:339·394·557` 은 이름 없는 `screen.getByRole('dialog')`** 다.
  (앵커 갱신 2026-09-02 — 계획 작성 시 `:317·372·499`. `backlog.spec.ts:524·529` 는 **불변 확인**.)
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

> ⚠️ **파일이 재작성됐지만 부채는 살아 있다 (2026-09-02 실측 · PR #422).** `CreateBoardForm.tsx` 는
> 2단계 마법사가 되며 **164 → 360줄**이 됐고, #422 는 인트로 **문구**만 `showEmptyStateIntro &&
> step === 'type'`(`:269`)으로 조건부화했다. 그런데 여백을 만드는 것은 그 문구가 아니라
> **바깥 컨테이너**(`:268` `min-h-48 gap-6 p-8 justify-center`)이고 그것은 **여전히 무조건** 붙는다.
> 장부 147 은 유효하다 — 「해소됐겠지」로 지우지 말 것.

**RED**(동반 테스트): `showEmptyStateIntro={false}` 일 때 빈 상태용 여백 클래스가 붙지 않는다.

**GREEN**: 컨테이너(`:268`)의 여백을 `showEmptyStateIntro` 에 묶는다. 다이얼로그 안에서는 축소.
★ **2단계 양쪽을 본다** — `step === 'type'` 과 `step === 'name'` 에서 각각 확인한다.
문구는 1단계에만 뜨므로 2단계에서 여백만 남는 경로가 따로 있다.

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

> ✅ **홀드 해소 (2026-09-02).** 이 task 가 기다리던 FR-BD-04 D6 PR ③ 이 머지됐다(#424 `bb8d2945d`).
> 이제 백로그는 `?board=<id>` 스코프이며 **그 위에서 한 번만 쓴다.**
>
> **그대로 재사용할 자산 2종** — 둘 다 이번 대기 중에 생겼고, 복제하지 말고 import 한다.
> - `apps/web/e2e/fixtures/board-helpers.ts`(#422) — `selectBoardType` · `goToBoardNameStep`.
>   보드를 만들어 두고 시작해야 하는 시나리오가 이것을 쓴다.
> - `apps/web/e2e/scrum-board.spec.ts`(#424) — 「한 줄기」 구조와 **`page.waitForRequest` 로
>   재요청을 직접 관측**하는 양식. 삭제 뒤 백로그·보드가 실제로 다시 불렸는지를 렌더 단언과
>   **별개 축으로** 잡는다. ★ 판정식은 `pathname` **완전 일치**로 쓴다 — 느슨한 `includes` 는
>   기본 보드 조회가 대신 만족시켜 관측점이 증발한다(#424 실측).
>
> ★ **진입 URL 에 `?board=` 를 명시한다.** 생략하면 서버가 기본 보드로 폴백하는데 **응답에 그
> 사실이 없어**(#424 C5) 어느 보드를 보고 있는지 스펙이 확정하지 못한다.

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
- **미등재 2건 신규 등재** — ① `sprint_issues` 고아 행 위생 ② `BoardRepository.kt` **439줄**
  (착수 시 실측으로 다시 센다 — 이 plan 작성 시점엔 308줄이었고 **PR #421 이 +131 했다**).
  ②를 등재하려면 `scripts/build-dashboard.mjs` 의 `AREA_CATEGORIES`(:296)에
  **`agile-planning` 영역이 없다** — 한 줄 추가한다.
- `docs/plan/product/agile-planning.md` §3.2 **FR-BL-02 D6 에 각주** — 「D6 이 `[x]` 였으나
  편집·삭제 UI 는 소비하지 않았고 이 PR 이 회수했다」. **집합 약어로 완료를 덮지 않는다**
  (메모리 `set-word-hides-partial-implementation`).
- `node scripts/build-doc-index.mjs` 재실행.

**검증**: `node --experimental-strip-types --test scripts/**/*.test.ts` (장부 판별식 포함)
· `bash scripts/verify-master-plan.sh` EXIT=0 · FR 총수 **144 불변** 확인

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

## 게이트 1 결과 — 🛑 보류 (2026-09-01)

**Maxi 판정. 「멈추고 보드 설계부터」.**

### 왜 멈췄나 — 리뷰가 못 잡은 것을 Maxi 가 잡았다

게이트 1 에서 Maxi 가 물었다. *"지라 클라우드에서는 스크럼으로 스프린트를 표현하고 있고 백로그에
있는걸 스프린트로 넘길수 있고 스프린트를 시작하면 보드로 표현 되는데 지금 BTS에서는 다르게
동작하는거 같네."* **실측 결과 그 말이 맞다.**

| 축 | Jira Cloud | BTS 실측 |
|---|---|---|
| 보드 종류 | 생성 시 **Scrum / Kanban 선택** | **종류 없음** — `V500__boards.sql` 에 type 컬럼 부재, 주석이 "칸반 보드 Aggregate" |
| 활성 스프린트 | 스크럼 보드는 **시작된 스프린트 것만** 표시 | 보드가 스프린트를 **모른다** |
| 보드 그리는 코드 | 카드 조건에 `is in an active sprint (for Scrum boards)` 포함 | `BoardCardPlacement`·`BoardApplicationService`·`BoardRepository`·`BoardController` **4파일 전부 `sprint` 언급 0건** |
| 보드의 정체 | **저장된 필터의 뷰** (JQL) | `project_key` 문자열 고정 |

**Jira 원문(2026-09-01 조회 · Cloud).**
- *"Your board only displays work items once you've started the sprint, and the board displays
  only the work items added to the sprint you started."*
  — https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/
- 카드가 활성 스프린트 보드에 뜨는 조건 3개 중 하나가 *"is in an active sprint (for Scrum boards)"*.
  *"Active sprints are only available on Scrum boards."*
  — https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/
- 보드 생성 시 *"Create a Scrum board"* 또는 *"Create a Kanban board"* 를 고른다.
  — https://support.atlassian.com/jira-software-cloud/docs/create-a-board-based-on-filters/

→ **BTS 는 「칸반 보드」와 「스프린트 백로그」가 서로를 모르는 두 기능이다.** Jira 는 한 흐름이다.
이 갭은 로드맵 A~D **어디에도 없었다.** 선행 플랜의 조작감 갭 표가 「Scrum/Kanban 타입 부재 → 3단계」로
한 줄 적어 뒀으나 **PR C·D 의 실제 범위에 들어가지 않아 담당자가 없는 상태**였다
([[two-lists-never-check-each-other]] 의 양식).

### 함께 확인한 것 — 다수 보드는 이미 된다 ✅

Maxi 의 두 번째 질문(「프로젝트에 다수 보드가 존재할 수 있는 구조여야 한다」)은 **충족돼 있다.**
- `V500__boards.sql` 주석 — *"한 프로젝트에 여러 보드를 둘 수 있다(명시적 CRUD)"*.
  `boards.project_key` 에 UNIQUE 없음, 부분 인덱스만.
- `BoardApplicationService.createBoard(:101)` 에 개수 제한 가드 없음(워크플로우 스킴 미할당만 422).
- **E2E 로 검증돼 있다** — `apps/web/e2e/board-manage.spec.ts` S1 「두 번째 보드 생성 → 전환」,
  S4 「두 번째 보드 삭제」 (PR #416 산출물).

### 확정된 결정 1건 (다음 착수 시 그대로 쓴다)

**E1 해소 — 「요청을 진짜로 끊는다」.** `ApiFetchOptions`(`api/client.ts:82`)에 선택 필드
`signal` 을 더하고 `fetchOptions` 에 넘긴다. 선택 필드라 **기존 호출자 전량 무변경**이고,
부채 145(창 잠금 상한)·146(요청 미취소)이 **한 기전으로 닫힌다**.

### 이 PR 의 상태

**보류. worktree 와 Draft PR #418 은 유지한다.** 스펙·Jira 대조·task 9개·리뷰 6건은
보드 설계가 끝난 뒤 **그대로 재사용한다** — 스프린트 편집·삭제 조작 자체는 Jira 도 백로그 화면에서
하므로(J2·J4) **보드 종류와 무관하게 유효**하다. 다시 쓸 때 바뀔 수 있는 것은
「스프린트가 어디에 그려지는가」뿐이고 그것이 곧 보드 설계의 산출물이다.

### 다음 작업

~~**보드 모델 ADR**~~ — **완료됐다.** `docs/adr/2026-09-01-board-type-and-active-sprint.md`(PR #419 채택)
+ FR-BD-04 신설. 스키마·백엔드(D1~D5)는 **PR #421 머지**(`cfa7c920c`).

---

## 홀드 사유 갱신 (2026-09-01 · 2차)

**기다리던 것이 왔고, 다른 것을 기다리게 됐다.**

원래 대기 대상이던 보드 모델 ADR(#419)과 스키마·백엔드(#421)는 둘 다 머지됐다.
그런데 그 ADR 이 **FR-BD-04 D6** 에서 백로그 화면의 의미를 바꾼다 —
`/projects/$projectKey/backlog` 가 **프로젝트 단위에서 보드 단위(`?board=<id>`)로** 스코프된다
(ADR §D2 · 「결과」 절). 그리고 ADR 「깨질 수 있는 것」 목록에 **`backlog.spec.ts` 가 명시**돼 있다.

이 PR 의 **Task 5**(`SprintColumnHeader` `⋯` 메뉴)와 **Task 8**(`sprint-manage.spec.ts`)이
바로 그 백로그 화면 위에서 돈다. 지금 넣으면 **T8 E2E 를 D6 에서 다시 써야 한다.**

**따라서 대기 대상을 바꾼다 — 보드 모델 설계(완료) → FR-BD-04 D6 (PR ③ 백로그 `?board=` 스코프).**

- **D6 PR ②**(보드 생성 종류 선택 UI)는 백로그를 안 건드리므로 이 PR 과 무관하다.
- **D6 PR ③**(스크럼 보드 화면 + 백로그 `?board=` 스코프)이 머지되면 그 위에서 재개한다.
- 재개 시 **`git rebase origin/main` 을 한 번만** 한다. 지금 안 하는 이유는 D6 두 PR 이
  들어오면 어차피 다시 밀리기 때문이다. 충돌은 자동 생성 INDEX 3종뿐이고
  **병합하지 않고 `node scripts/build-doc-index.mjs` 재생성으로 덮는다**(자동 생성 파일 규율).

**재확인한 것 (origin/main 실측 · 2026-09-01).** 프론트 Task 2~8 은 **재작성 불필요**하다 —
`StartSprintDialog.tsx` 534줄 · `SprintColumnHeader.tsx` 162줄 · `withDeleteTimeout` @ `use-boards.ts:227`
· `api/client.ts:82` 의 `signal` 부재(E1 미해결 유효) · `backlogKeys` 의 `detail` 단일 키.
전부 이 plan 이 기록한 그대로다. #421 은 `apps/web/` 아래를 **한 파일도 건드리지 않았다.**

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | not run | type=ui 라 라우팅 대상 아님 |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | skipped | 중첩 codex 토큰 비용(스킬 자체 경고)으로 미실행 — 생략 사실을 여기 남긴다 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | ⚠️ CONCERNS | 4 issues, 0 critical gaps (E1 미해결) |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | ✅ CLEAR | 2 issues (D2 반영 · D1 주의) — 패리티 계약 §2~§6 으로 대체 실행 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | not run | 라우팅 대상 아님 |

- **VERDICT:** DESIGN CLEARED · ENG CONCERNS (E1 결정 대기) — 게이트 1 에서 E1 을 정하면 구현 착수 가능. BLOCKER 0.

- **GATE 1:** 🛑 보류 — **FR-BD-04 D6 선행**(2026-09-01 2차 갱신). 1차 대기 대상이던 보드 모델 설계는
  ADR #419 채택 · 스키마 백엔드 #421 머지로 **해소됐고**, 그 ADR 이 백로그를 `?board=` 스코프로
  바꾸므로 **D6 PR ③ 뒤로** 미룬다(T8 E2E 이중 작성 회피). E1 은 `apiFetch` 확장으로 확정 해소.

NO UNRESOLVED DECISIONS

---

## 재개 (2026-09-02) — 홀드 해소 + 앵커 전수 재측정

**기다리던 것이 왔다.** FR-BD-04 D6 PR ③ 이 머지됐다(#424 `bb8d2945d` · 2026-09-02T04:08:56Z).
백로그가 `?board=<id>` 스코프로 바뀌었고, 이 PR 의 Task 5·8 이 그 위에서 돈다.
`git rebase origin/main` 1회 완료(충돌은 예측대로 자동 생성 INDEX 3종뿐 · 병합하지 않고
`build-doc-index.mjs` 재생성으로 덮었다).

### 실측 재측정 — 전수 대조

계획이 기록한 값을 **하나도 믿지 않고 다시 쟀다.** 「재확인했다」가 아니라 「무엇이 바뀌었는지」가
아래 표다.

| 항목 | 계획 기록 | 실측(2026-09-02) | 판정 |
|---|---|---|---|
| `api/client.ts` 의 `signal` | 부재 | **여전히 0건** | ✅ E1 결정 유효 |
| `SprintColumnHeader.tsx` | 162줄 | **162줄** | ✅ 불변 |
| `BoardRepository.kt` | 439줄 | **439줄** | ✅ 불변 |
| `backlog.spec.ts` dialog 셀렉터 | `:524·529` | **`:524·529`** | ✅ 불변 |
| `StartSprintDialog.tsx:75` 부분저장 주석 | `:75` | **`:75`** | ✅ 불변 |
| `useProjectPermissions` | 존재 | **존재**(`use-project-permissions.ts:31`) | ✅ 불변 |
| `withDeleteTimeout` | `use-boards.ts:227` | **`:253`** | ⚠️ 앵커 이동 |
| `StartSprintDialog.tsx` | 534줄 | **553줄** | ⚠️ +19 |
| `StartSprintDialog.test.tsx` 무명 dialog | `:317·372·499` | **`:339·394·557`** | ⚠️ 앵커 이동 |
| `updateSprint` | `api/backlog.ts:337` | **`:360`** | ⚠️ 앵커 이동 |
| `CreateBoardForm.tsx` | 164줄 · 여백 무조건 | **360줄** · 문구만 조건부 · **여백은 여전히 무조건** | ⚠️ 재작성됐으나 **장부 147 유효** |
| **`backlogKeys`** | **`detail` 하나 · 1인자** | **`detail(pk, boardId)` 2인자 필수 + `project` 접두** | 🛑 **지시가 틀림** |
| **Task 3 MSW 파일** | **`mocks/handlers.ts`** | **`mocks/backlog-handlers.ts`**(`resolveBoardScope :241`) | 🛑 **경로가 틀림** |
| `SprintApplicationService` 생성자 | 5인자 | **6인자**(`boardRepository` 추가 `:63`) | ⚠️ `update` `:154` · 복사 `:174` |

### 🛑 가장 중요한 정정 — Task 3 의 무효화

계획은 *"무효화 키는 하나뿐이다 … `backlogKeys.detail(projectKey)` 한 번이 양쪽을 덮는다"* 라고
적었다. **지금 그대로 쓰면 컴파일이 안 되고, 인자를 맞춰 통과시키면 #424 가 잡은 BLOCKER 를
그대로 재현한다** — `detail` 은 보드마다 값이 다른 **완전 일치** 키라서, 지금 보고 있지 않은
보드의 백로그 캐시가 조용히 낡은 채로 남는다.

처방은 **새 코드를 쓰지 않는 것**이다. `invalidateAfterSprintTransition(queryClient, projectKey)`
(`use-backlog.ts:281`)가 `backlogKeys.project` **접두** + `boardKeys.all` 을 함께 덮고,
`useStartSprint`·`useCompleteSprint` 가 이미 그것을 쓴다. `useDeleteSprint` 도 같은 것을 쓰면
**세 상태 전환의 무효화 규약이 한 자리에 남는다.**

`boardKeys.all` 이 함께 필요한 이유도 #424 가 실측했다 — `useBoard` 의 `staleTime` 이 30초라
보드 키를 안 건드리면 **스프린트를 지운 뒤에도 최대 30초간 없는 스프린트를 보여준다.**

### task 별 영향 요약

| task | 영향 | 조치 |
|---|---|---|
| T1 백엔드 날짜 잠금 | 생성자 6인자 · 삽입 지점 이동 | 앵커 갱신 ✅ · 범위 불변 |
| T2 삭제 타임아웃 | 앵커 26줄 이동 | 앵커 갱신 ✅ · 범위 불변 |
| **T3 deleteSprint** | **무효화 지시가 틀림 · MSW 경로가 틀림** | **본문 재작성 ✅** |
| T4 SprintForm 추출 | 원본 +19줄 · 테스트 앵커 이동 | 숫자·앵커 갱신 ✅ · 범위 불변 |
| T5 `⋯` 메뉴 | 대상 파일 불변(162줄) | 무변경 |
| T6 다이얼로그 여백 | 파일 재작성(360줄)됐으나 **부채 유효** | 주의 블록 추가 ✅ |
| T7 죽은 변수 | 무관 | 무변경 |
| **T8 E2E** | **홀드 사유 해소** · `?board=` 위에서 씀 | **재사용 자산 2종 명시 ✅** |
| T9 문서 동기화 | `BoardRepository` 439줄 **불변**(재측정) | 무변경 |

**task 수 9 · wave 4 불변. 신규 FR 0 · 총수 144 불변.**

### 게이트 1 재진입 사유

게이트 1 은 **보류**였지 승인이 아니었다. 보류 사유(D6 선행)가 사라졌고 계획이 위와 같이
갱신됐으므로 게이트 1 을 다시 받는다. 리뷰 렌즈 2종(design ✅ CLEAR · eng ⚠️ CONCERNS)의
판정은 그대로 유효하다 — E1 은 게이트 1 에서 확정 해소됐고, 이번 갱신은 **앵커 정정과
Task 3 의 무효화 규약 교체**이며 둘 다 #424 의 머지된 리뷰 결론에서 직접 나왔다.

---

## 구현 중 이탈 기록 (2026-09-02)

### 이탈 1 — Task 5 가 허용 파일 밖 `BacklogBoard.tsx` 를 수정했다 (+13줄)

**무엇.** T5 의 `files` 메타 5개 밖인 `apps/web/src/components/backlog/BacklogBoard.tsx` 에
`boardId` 를 흘리는 배선을 더했다. `BacklogStackProps` → `SprintColumn` → `SprintColumnHeader` →
`SprintActionsMenu` → `EditSprintDialog` 로 이어지는 전 구간이 `string | undefined` **필수 prop** 이라
중간에서 빠뜨리면 `tsc` 가 잡는다.

**왜 불가피했나.** `EditSprintDialog` 는 `boardId` 를 **선택 prop 이 아니라 필수**로 요구한다 —
409 복구가 보드 스코프 캐시를 **완전 일치 키**로 읽기 때문이다(T4 가 확정한 계약). 그런데
`?board=` 는 `routes/projects.$projectKey.backlog.tsx:92` 의 `useSearch({strict:false})` 에서
`BacklogPage` → `BacklogBoard` 까지만 와 있었고, **T5 의 허용 5파일 안에는 그 출처가 없었다.**

대안 2개를 실측으로 배제했다.
- **`SprintColumn` 이 `useSearch` 를 직접 읽기** — `BacklogBoard.test.tsx:23` 이
  `@tanstack/react-router` 를 `Link` 하나로 모킹해 그 파일이 통째로 깨진다. 그 파일도 허용 밖이라
  고칠 수 없다.
- **선택 prop + `undefined` 기본값** — 409 복구가 조용히 깨진다. 실패가 아니라 오판이라 어떤
  가드에도 안 걸린다(#424 BLOCKER-1 과 같은 양식).

**근본 원인은 이 plan 이다.** T5 의 `files` 메타는 **#424(백로그 `?board=` 스코프) 머지 전에** 쓰였고,
위 「재개」 절의 재측정 표가 T5 를 **「대상 파일 불변(162줄)」로만 확인하고 넘어갔다** — 줄수는
안 변했지만 **그 파일이 받아야 하는 데이터가 늘었다는 것**은 재지 않았다. 다음 재측정은
「파일이 그대로인가」가 아니라 **「그 파일에 필요한 입력이 그대로인가」**를 물어야 한다.

**판정 요청.** 되돌리려면 `boardId` 를 포기해야 하므로 되돌리지 않았다. 게이트 2 에서 확인받는다.

### 이탈 2 — Task 5 의 `refactor:` 커밋이 「코드 이동」이 아니다

`SprintActionsMenu.tsx` 분리는 **GREEN 안에서 최종형으로** 냈다(헤더에 100줄을 넣었다 빼는 왕복을
만들지 않았다). `refactor:` 커밋은 대신 **비-공허 확인에서 실측 적발한 `M1-2` 의 무판별력**을 고쳤다 —
`queryByRole('menuitem')` 이 **닫힌 메뉴에서도 null** 이라 권한 분기를 무력화해도 초록이었다.
`toBeEmptyDOMElement()` 를 더해 같은 변이에서 red 2건을 확인했다.
계획 REFACTOR 의 **요구**(메뉴가 별도 파일에 산다)는 충족했고 커밋 **형태**만 템플릿과 다르다.

### 범위 밖으로 남긴 것 (이 PR 이 만들지 않았다)

- **destructive 버튼 대비 4.49:1**(라이트). `ui/button.tsx:20` 의 공용 `destructive` variant +
  `ConfirmDialog` 속성이고, 보드 삭제 창(#416)이 이미 같은 값으로 운영 중이다. 고치려면
  `DESIGN.md` 패치 + 전 소비처 영향이라 이 PR 범위를 넘는다. 메뉴 항목(5.19/5.50)·설명문(5.08/4.98)은 통과.
- **`SprintColumnHeader.tsx` 가 196줄**로 200 에 근접했다. 다음에 헤더에 무엇이든 얹으면 또 갈라야 한다.
- **`lint-staged` 글로브가 `scripts/**/*.ts` 를 물지 않는다**(T7 실측). 커밋 훅 린트가 판별식 표면인
  `scripts/workflow/` 를 통째로 안 본다는 뜻이다. 이 PR 이 만든 것이 아니고 표면도 다르다.
- **Radix 잠금 잔상** — 메뉴에서 연 다이얼로그를 Esc 로 닫아도 `body { pointer-events: none }` 이
  남는 경우가 있다(T5 실측). T8 이 한 테스트에서 다이얼로그를 연속으로 두 번 열면 두 번째 클릭이
  인터셉트로 타임아웃한다. 우회는 `page.reload()`.

### 이탈 3 — Task 9 가 허용 파일 밖 `todos-plain-language-contract.test.ts` 를 수정했다 (+4줄)

**무엇.** `scripts/build-dashboard.mjs` 의 `AREA_CATEGORIES` 에 `agile-planning` 을 더하자
`scripts/workflow/todos-plain-language-contract.test.ts:293` 의 「영역 → 카테고리 배정이 기대와
정확히 일치한다」가 **단독으로 red** 였다(그 1건 외 478 pass). `EXPECTED` 에 같은 배정을 더했다.

**왜 불가피했나.** 그 판별식은 `AREA_CATEGORIES` 를 대조하는 **의도적인 두 번째 목록**을 들고 있고,
자기 주석이 요구한다 — *"기대값을 검사 대상 상수에서 읽으면 동어반복이다 … 배정은 사람 판단이라
기계 오라클이 없으므로 두 번째 목록을 여기 두고 양방향 대조한다. 표를 의도적으로 바꾸려면
이 목록도 같은 커밋에서 고쳐라."* 한쪽만 고치면 통과할 방법이 없다.

**이탈 1 과 원인이 같다.** `files` 메타가 **대상 파일만 적고 그것을 짝으로 검사하는 판별식을
빠뜨렸다.** 다음 plan 은 `files` 를 확정할 때 「이 파일을 읽는 판별식이 있는가」를 함께 물어야 한다
(`grep -rl '<파일명>' scripts/workflow/*.test.ts` 한 줄이면 난다).

### 🛑 게이트 2 판정 요청 — 장부 145 의 잔여 표면

145 를 `✅` 로 닫았으나 **그 항목이 적은 표면 전부가 닫힌 것은 아니다.** 145 의 「방치하면」이
*"`ConfirmDialog` 를 쓰는 **다른 소비처는 상한이 없어** …"* 를 적는데, 이 PR 이 상한을 붙인
삭제 경로는 **보드·스프린트 둘뿐**이다. 나머지 **7곳**은 그대로다(`grep -rl ConfirmDialog apps/web/src` 전수).

`SlackChannelMappingList` · `ValidatorConfigSection` · `ResetToDefaultDialog` ·
`WorkflowEditorDialogs` · `GitWebhookSection` · `AutomationRuleList` · `admin.workflows`

★ **2026-09-02 정정 (코드 리뷰 CONCERNS-1).** 종전 목록은 `projects.$projectKey.board` 를 8번째로
넣었는데 **그것이 바로 보드 삭제 창**이고 `useDeleteBoard` 의 상한을 이미 받는다(그 파일의
`ConfirmDialog` 는 1개뿐이고 `:370` KDoc 이 「갇히지 않는 근거는 `useDeleteBoard` 의 타임아웃이다」로
적고 있다). 즉 **자기 문장과 모순**이었다 — 같은 문단이 「상한을 갖는 삭제 경로는 보드와 스프린트」라
적으면서 보드를 잔여에도 넣었다. 잔여는 **7곳**이다.

T9 는 **집합 약어로 덮지 않고** 해소 본문에 잔여를 전수 열거하고 「전부 붙였다가 아니라
**붙일 자리가 생겼다**」로 적었다(`set-word-hides-partial-implementation`).
**잔여를 별도 장부 항목으로 올릴지는 Maxi 판단이다** — 지시가 신규 등재 2건이라 T9 는
세 번째 항목을 만들지 않았다.

---

## 코드 리뷰 결과 반영 (2026-09-02)

렌즈 2종 — `code-reviewer`(절대 규칙 + 보안) **BLOCKER 1 · CONCERNS 4** · 구조·안전성 렌즈 **critical 0**.

### 🛑 BLOCKER-1 — 장부 145 의 회귀 (확인 창이 **영원히** 잠긴다)

`withDeleteTimeout` 이 `Promise.race` 를 버리고 **abort 전파 하나에만** 의존하게 됐는데,
`apiFetch` 의 401 경로 `await doRefresh()`(`api/client.ts:131`)는 abort 를 관측하지 않는다
(`doRefresh` 내부 fetch 에 `signal` 이 없다 — 그리고 **없는 것이 맞다.** `refreshPromise` 는 전역 공유
lock 이라 한 요청의 abort 가 다른 요청들의 refresh 까지 죽인다).

토큰 만료 + refresh 가 멈춘 연결 = 10초에 `abort()` 는 불리지만 **아무것도 reject 하지 않아**
`isPending` 이 영원히 참이고 `ConfirmDialog` 가 취소·Esc·오버레이·X 를 무기한 잠근다.

★ **이 파일 자신의 KDoc(`:42-43`)이 이 결함을 이미 서술하고 있었다** —
*"이어주지 않으면 abort 가 아무 일도 하지 않아 반환된 Promise 가 영원히 pending 으로 남는다."*
`apiFetch` 는 signal 을 받으므로 「이어줬다」는 형식은 충족했지만, **그 내부에 signal 을 관측하지
않는 await 구간이 있다**는 것을 아무도 재지 않았다.

**왜 놓쳤나.** 146(요청 실제 취소)을 닫으면서 145(상한 뒤 조작권 반환)의 보장을 **abort 가 닿는
구간으로 좁혔는데**, 두 장부 항목을 「한 기전으로 동시에 닫았다」고 적어 그 축소가 기록에서 사라졌다.
**한 커밋이 두 항목을 닫는다고 적을 때는 각 항목의 보장 범위가 그대로인지를 따로 재야 한다.**

처방 — `withDeleteTimeout` 이 abort **와 거절을 함께** 낸다. 146 은 유지되고 145 가 어느 구간에
걸려 있든 성립한다.

### CONCERNS 처리

| # | 지적 | 처리 |
|---|---|---|
| C-1 | 장부 145 잔여 목록이 **자기 문장과 모순** — `projects.$projectKey.board` 는 **바로 그 보드 삭제 창**이고 `useDeleteBoard` 상한을 이미 받는다 | ✅ `TODOS.md` · plan 양쪽에서 목록을 **7곳**으로 정정. 그 파일의 `ConfirmDialog` 가 1개뿐이고 `:370` KDoc 이 상한을 근거로 적고 있음을 실측 확인 |
| C-2 | `AGILE_SPRINT_DATE_LOCKED` 를 관측하는 유일한 테스트가 green 뒤에 붙었고 **비-공허 확인 기록이 없다** | ✅ **아래에 기록.** 뮤테이션은 이미 돌렸고 누락된 것은 기록이었다 |
| C-3 | `EditSprintDialog` 의 `applyServerResponse` 가 **관측 불가**하고 그 KDoc 근거가 사실이 아니다 | ✅ KDoc 정정(호출은 유지). 근거를 「폼 계약의 대칭 유지」로 바꾸고, 재시도를 실제로 지키는 것이 `recoverFromConflict` 임을 명시 |
| C-4 | `client.ts` 의 `signal` 이 SEC_FE 표면인데 `client.test.ts` 에 단언 0건 | ✅ BLOCKER-1 수정과 같은 묶음에서 2건 추가 |

### C-2 의 비-공허 확인 (누락됐던 기록 · GREEN 선커밋 `2dee9a0bf` 뒤 수행)

| 뮤테이션 | 결과 |
|---|---|
| `SprintExceptionHandler.kt` 의 `@ExceptionHandler(SprintDateLockedException::class)` **등록만 제거** | **EXIT=1 · `33 tests completed, 1 failed`** — `PATCH sprints id COMPLETED 스프린트의 기간을 바꾸면 400 AGILE_SPRINT_DATE_LOCKED를 반환한다()` **1건만** red |

즉 그 단언은 「400 이 나는가」가 아니라 **「상태 전파 핸들러가 errorCode 를 `AGILE_VALIDATION_FAILED`
로 덮어쓰지 않는가」**를 잰다. 서비스 계층 테스트 4건은 이 뮤테이션에 전부 초록으로 남았다 —
리뷰어가 지적한 「장식 애노테이션」 위험이 실재했고 이 테스트가 그것을 막는다.
`git checkout --` 원복 후 `git diff HEAD -- backend/` 0줄 확인.

### 이탈 3건 판정 (리뷰어)

- **이탈 1**(`BacklogBoard.tsx` +13줄) ✅ **정당.** 배제 근거 2개를 리뷰어가 독립 실측으로 확인했다.
  ⚠️ **기록 보완 필요** — 13줄 중 3줄은 `boardId` 가 아니라 **`canManageSprint` 배선**(`:740-742`)이고
  그것이 FR-5 권한 게이트의 실제 결선이다. 이탈 기록이 `boardId` 만 적었다. 아래에 보완한다.
- **이탈 2**(판별식 `EXPECTED` +4줄) ✅ **정당.** 주석이 실제로 같은 커밋 수정을 요구하고
  `assert.deepEqual` 이 완전 일치라 한쪽만 고치면 통과할 방법이 없다.
- **이탈 3**(`refactor:` 가 코드 이동이 아님) ✅ **위반 아님.** 요구(별도 파일에 산다)는 충족했고,
  왕복을 안 만든 것이 §1.16 에 오히려 부합한다.

### 이탈 1 기록 보완

`BacklogBoard.tsx` 의 +13줄은 **두 축**이다.
1. **`boardId` 배선** — `BacklogStackProps` → `SprintColumn` → `SprintColumnHeader` → `SprintActionsMenu` → `EditSprintDialog`.
2. **`canManageSprint` 배선**(`:740-742`) — **FR-5 권한 게이트의 실제 결선.** 이것이 없으면
   `SprintActionsMenu` 의 `if (!canManage) return null` 이 무엇을 받을지가 정해지지 않는다.

「권한 게이트가 어느 커밋에서 결선됐나」를 찾는 다음 사람이 이 기록으로 도달할 수 있어야 한다.

### 크기 실측 (plan Task 4 가 게이트 2 요약에 실으라고 요구한 값)

| 파일 | 이전 | 이후 | 컴포넌트 본체 |
|---|---|---|---|
| `StartSprintDialog.tsx` | 553줄 | **251줄** (−302) | 143줄 |
| `SprintForm.tsx` | — | 472줄 (신규) | 137줄 |
| `EditSprintDialog.tsx` | — | 166줄 (신규) | 83줄 |
| `SprintColumnHeader.tsx` | 162줄 | **196줄** | — |
| `SprintActionsMenu.tsx` | — | 194줄 (신규) | — |

§2.2 의 **컴포넌트 200줄** 상한은 전부 충족. 이 PR 이 상한 위반을 새로 만들지 않았다.
