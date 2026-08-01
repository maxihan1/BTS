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

## 스펙

전체 스펙. [docs/specs/2026-08-01-fr-ux-09-f2-create-issue-dialog.md](../specs/2026-08-01-fr-ux-09-f2-create-issue-dialog.md)

**핵심 3줄.**
- 상단바 **만들기** 를 누르면 URL 변경 없이 생성 모달이 열리고, 프로젝트·유형·제목·본문·담당자·우선순위·라벨을 **1회 제출**로 확정한다 (`POST /issues` 1회 · 후속 `PATCH` 0회).
- 담당자를 **안 건드리면 자동 배정 유지**(키 생략), **해제하면 미할당 확정**(`null`) — 화면상 똑같이 비어 보이지만 서버 동작이 다르므로 안내 문구를 노출한다.
- `/issues/new` 는 딥링크 계약이라 유지하되 **같은 모달을 열린 상태로** 렌더한다. 폼 컴포넌트는 한 벌(`IssueCreateForm`)만 둔다.

**Maxi 확정 3건 (2026-08-01).**
- **D-2 라벨.** `IssueLabelsEdit` 에서 `LabelChipsEditor` 를 **추출**한다. 상세 화면은 `편집기 + 저장버튼` 래퍼로 동작 무변경 (판정식 = 기존 테스트 무수정 green).
- **D-3 딥링크.** `/issues/new` 라우트가 **모달을 연다**. 폼 1벌 · 사용자가 보는 화면 1개.
- **D-4 레이아웃.** **단일 컬럼** (기존 Dialog 20곳 관행), 폭 `max-w-md` → `max-w-xl`.

**착수 전 실측이 뒤집은 전제 2건.**
1. `routes/issues.new.tsx` 는 **이미 `IssueCreateForm`(라우터 비의존) + 라우트 어댑터로 분리**돼 있다 (파일 1행 주석). 모달화는 재작성이 아니라 **감싸기**다.
2. 기존 폼은 2필드가 아니라 **5종 + 커스텀 필드 N개** — 컴포넌트 다중선택(FR-CM-03) · 보안등급(FR-PM-06) · 커스텀 필드(FR-IS-10) · URL 제목 프리필(FR-UX-04). F2 의 5필드를 더하면 **총 10종 + N**.

## Brainstorming Check

✅ 통과 (1회 iteration) — **BLOCKER 1건 + 관찰 4건** 반영.

- 🔴 **B-1 (BLOCKER).** MSW `createIssueHandler`(`mocks/issue-handlers.ts:418`)가 신규 5필드를 **전부 무시**한다. 응답이 fixture 스프레드라 요청과 무관하게 같은 값이 돌아오고, `assigneeId` 는 mock 자체의 자동배정 결과로 **덮인다**. → *"5필드가 반영됐다"* 단언이 **프론트가 아무것도 안 보내도 통과**하는 가짜 그린. 처방을 **FR-14** 로 승격 (learnings 2026-06-25 동형).
- **B-2 (선재).** 제목 상한이 프론트 500 · 백엔드 200 으로 어긋나 있다 — 201~500자는 프론트 통과 후 400. 폼을 만지는 김에 200 으로 정렬.
- **B-3.** `IssueTypeSelect` 는 빈 `availableTypes` 를 방어하지 않는다 (로딩 가드 필요).
- **B-4.** 프로젝트를 바꿔도 이전 프로젝트의 컴포넌트·커스텀 필드가 남는다 (기존 폼도 보유 — 함께 닫는다).
- **B-5.** `custom-fields.spec.ts:328-330` 이 `history.pushState` 로 라우트를 바꾼다. 모달 전환 후 동작 여부는 **추론 말고 실행으로** 확인.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
