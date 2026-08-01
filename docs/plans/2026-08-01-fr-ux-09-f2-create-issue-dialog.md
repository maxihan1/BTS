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

## 도메인 정리

- **BC.** `issue-tracking` — 이번 PR 은 **프론트 소비 전용**. 백엔드 변경 0 예상
  (생성 API 5필드가 이미 열려 있고 이 PR 이 그걸 보내기만 한다).
- **영향 엔티티.** Issue · IssueType · (담당자·우선순위·라벨은 Issue 의 필드/관계).
  신규 엔티티 0 · 마이그레이션 0.
- **새 용어.** **없음.** 담당자(Assignee) · 이슈 타입 · 라벨 · 우선순위 모두
  `Maxi_wiki/BTS/glossary.md` 등재 완료 (담당자 항목은 #328 에서 3-state 서술까지 갱신됨).
  → glossary 갱신 불필요.
- **기존 결정 충돌.** 없음. 다만 **상속 계약 5건**을 프론트가 지켜야 한다 (ADR §맥락 참조) —
  `assigneeId` 3-state · `description` 공백 시 서버 템플릿 대체 · `priority` 기본 3 ·
  라벨 3제약(개수 20·길이 50·공백-only 금지) · 미존재 사용자 422 `ASSIGNEE_NOT_FOUND`.
- **범위 변경 (Maxi 확정 2026-08-01).** 정본 §4.7 은 담당자/우선순위/라벨을 **F3** 에 두었으나
  이번 PR(F2)로 이관한다. 정본 서술 동기화가 이 PR 의 필수 산출물
  (`docs/rules/fr-sync-checklist.md` 9종 + `scripts/verify-master-plan.sh`).
- **관련 ADR.** [2026-08-01-fr-ux-09-f2-create-issue-dialog](../decisions/2026-08-01-fr-ux-09-f2-create-issue-dialog.md) (신설) ·
  [2026-07-31-fr-ux-09-b1-create-issue-fields](../decisions/2026-07-31-fr-ux-09-b1-create-issue-fields.md) (선행, 계약 상속원)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
