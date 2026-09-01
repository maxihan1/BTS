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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
