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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
