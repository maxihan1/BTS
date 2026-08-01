# FR-UX-09 F2 — 이슈 생성 모달 (CreateIssueDialog)

> slug: fr-ux-09-f2-create-issue-dialog
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking
> 생성: 2026-08-01

## Brief

**사용자 원문.** FR-UX-09 F2 — 이슈 생성 모달(CreateIssueDialog) 구현. 프로젝트 셀렉터·이슈
유형·제목/본문에 더해 담당자·우선순위·라벨을 한 화면에서 채우고, #328 로 열린 `POST /issues`
확장 API 에 1회 제출한다. F3 진입점 3곳은 후속 PR.

**classify 결과.** `type=ui` · `agent=frontend-engineer` · `primary_bc=issue-tracking` ·
`slug=fr-ux-09-f2-create-issue-dialog`

**정본.** `docs/plan/product/personalization.md` §4.7 D2/D6

**선행 PR.** #328 (FR-UX-09 B1 — 백엔드 `POST /issues` 가 `assigneeId`/`priority`/`labels` 수용)

**착수 전 확인된 제약 (체크포인트 인계).**
- 신규 다이얼로그는 **고유 `aria-label` 필수** — `role="dialog"` 가 e2e 에 164 발생
- `routes/issues.new` 는 **딥링크 계약이라 유지** (모달로 대체 아님)
- 필드 컨트롤은 `components/issue/meta/` **8종 재사용**

**main 실측 (worktree 생성 시점).**
- `CreateIssueDialog` 부재 (grep 0건)
- `apps/web/src/routes/issues.new.tsx` + `issues.new.test.tsx` 존재
- `apps/web/src/components/issue/meta/` — `IssueAssigneeSelect` · `IssuePrioritySelect` ·
  `IssueLabelsEdit` · `IssueTypeSelect` · `IssueImpactSelect` · `IssueEnvironmentEdit` ·
  `IssueCustomFieldsEdit` · `IssueStateTransition` (+ `AssigneeUserList`)
- `apps/web/src/components/ui/dialog.tsx` 프리미티브 존재

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
