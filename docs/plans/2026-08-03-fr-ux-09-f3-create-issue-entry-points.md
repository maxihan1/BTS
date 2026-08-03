# FR-UX-09 F3 — 보드/백로그/스프린트 컬럼 이슈 생성 진입점

> slug: fr-ux-09-f3-create-issue-entry-points
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning (프론트 전용 — `apps/web`)
> 생성: 2026-08-03

## Brief

**사용자 원문.** FR-UX-09 F3 — 보드/백로그/스프린트 컬럼 3곳(`BoardColumn` · `BacklogColumn` · `SprintColumn`)에
이슈 생성 모달(`CreateIssueDialog`) 진입점 연결. FR-UX-09 의 D6/D7 을 닫는 마지막 조각.

**classify 결과.** `type=ui` · `agent=frontend-engineer` · `primary_bc=agile-planning`.
자동 slug `fr-ux-09-f3-3` 은 「3곳」의 숫자가 잘려 붙은 무의미 토큰이라
형제 PR 명명(`ui/fr-ux-09-f2-create-issue-dialog`)에 맞춰 교체함.

**선행 컨텍스트 (PR #331, F2).**

- `CreateIssueDialog` 는 완성돼 있고 **라우터 훅 import 0** 이다 — F3 이 URL 변경 없이 열 수 있는 **설계 조건**이며 #331 에서 검증됨.
- 폼은 `components/issue/create/` 로 분해돼 있다 — `IssueCreateForm`(196) ·
  `IssueCreateBasicFields`(113) · `IssueCreateAssignmentFields`(46) · `IssueCreateExtraFields`(66) +
  `issue-create-schema.ts` · `use-assignee-picker.ts` · `use-issue-create-defaults.ts`.
- D-8 **성공 후 이동은 진입 경로가 정한다** — 딥링크(`/issues/new`)는 상세로 이동, 상단바는 제자리 + 토스트.
  **F3 의 3개 진입점이 어느 쪽인지는 스펙 단계에서 확정한다.**

**착수 전 필독 (되돌리지 말 것 4종 — 메모리 `fr-ux-09-f2-create-issue-dialog-done`).**

1. `assigneeIntent` 초기값 **`undefined`** — `null` 로 바꾸면 모든 생성이 서버 자동 배정을 조용히 끈다
2. 폼에 `IssueLabelsEdit` 넣기 — 내장 「저장」이 「이슈 생성」과 겹쳐 E2E strict mode 파손
3. `CreateIssueDialog` 에 **라우터 훅 도입 금지** — import 0 이 F3 의 설계 조건
4. 표시용 담당자를 검색 결과 `find()` 로 되돌리기 — B-2 재발

**판별식 2개 (F2 게이트 2가 남긴 것).**

- **B-1.** 기존 컴포넌트를 `<form>` 안으로 처음 옮길 때, 안의 텍스트 input 전수에 Enter 의미를 물어라.
- **B-2.** 기존 화면의 컴포넌트를 새 화면에 재사용할 때, 감싸는 쪽 코드의 `버그 수정`·`회귀 방지` 주석을 먼저 읽어라.

**인접 TODO 1건.** `apps/web — 이슈 상세 담당자 셀렉터가 검색 전 사용자 전량 노출` (`TODOS.md` L1611~).
F3 과 같은 영역이라 **묶어서 처리하면 중복이 적다** — 스펙 단계에서 포함 여부 결정.

## 도메인 정리

- **BC**. 논리 = `agile-planning`(보드·백로그·스프린트 화면) / 물리 = `apps/web` **프론트 전용**.
  FR-UX-05 D4 · FR-UX-06 D5 · FR-UX-07 ADR §D2 의 「논리 ≠ 물리」 선례 승계.
- **영향 엔티티**. 신규 0. 기존 `Issue`(issue-tracking 소유) 생성 + `Sprint` 배정(agile-planning) 소비만.
- **새 용어**. **0건** — 보드·백로그·스프린트·이슈 전부 `glossary.md` 에 이미 있다.
  glossary 갱신 0 · `domain/agile-planning.md` 갱신 0.
- **관련 ADR**. [2026-08-03-fr-ux-09-f3-create-issue-entry-points](../decisions/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md) (신규, D-1~D-5) ·
  [2026-08-01 F2](../decisions/2026-08-01-fr-ux-09-f2-create-issue-dialog.md) ·
  [2026-07-31 B1](../decisions/2026-07-31-fr-ux-09-b1-create-issue-fields.md)

### 착수 전 실측이 뒤집은 것 2건

1. **생성 계약에 `sprintId` 도 상태(`stateKey`)도 없다.** `CreateIssueRequest.kt` 가 받는 건
   `projectKey`·`typeId`·`summary`·`description`·`componentIds`·`securityLevelId`·`customFields`·
   `assigneeId`(3-state)·`priority`·`labels` 뿐이다. **「칸에서 만들면 그 칸에 생긴다」는 1회 제출로 불가능**이다.
2. **정본 §4.7 F3 의 산문과 파일 목록이 서로 다르다.** 산문은 「보드 컬럼 · 백로그 섹션 · **목록 헤더**」,
   파일 목록은 `BoardColumn`·`BacklogColumn`·**`SprintColumn`**. F2 ADR D-1 이 후자로 확정했으므로 **산문이 stale**.

### 확정 결정 (ADR D-1~D-5)

| # | 결정 | 요지 |
|---|---|---|
| D-1 | **컨텍스트 반영은 스프린트만** (2026-08-03 Maxi 확정, 3안 중 B) | 백로그는 기본 동작이 곧 정답 · 보드는 계약상 불가 |
| D-2 | 스프린트는 **생성 후 `assignToSprint` 2회 호출** | 2차 실패 시 **이슈는 온전**하므로 되돌리지 않고 부분 성공을 명시 노출. 🛑 2차 실패를 1차 실패처럼 다루면 중복 이슈가 생긴다 |
| D-3 | **보드 진입점은 컬럼별이 아니라 보드 헤더 1곳** | 지키지 못할 약속은 하지 않는다. 정본 §4.7 원문을 **정정 노트가 아니라 원문 자체로** 고친다 |
| D-4 | 프로젝트 프리필은 **기존 경로에 위임**(신규 배선 0) | `useTrackActiveProject`(ShellLayout) → 활성 프로젝트 → `useDefaultProjectSelection`. **명시 전달(`initialProjectKey`) 전환 여부는 스펙 단계 결정** |
| D-5 | 이 PR 이 **FR-UX-09 완주** | D1·D3·D6·D7 → `[x]`. FR 카운트 139 불변, 진척 열은 머지 직전 재실측 |

### 기존 결정 충돌

**1건.** F2 ADR D-1 의 「F3 = 진입점 3곳 **배선만**」을 D-2·D-3 이 넘어선다.
조용한 확장이 아니라 **명시적 승계·정정** — 근거는 위 실측 1번, 2026-08-03 Maxi 확정으로 채택.
그 외 충돌 없음 (B1 ADR 의 3-state·알림 REST 한정은 그대로 유지).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
