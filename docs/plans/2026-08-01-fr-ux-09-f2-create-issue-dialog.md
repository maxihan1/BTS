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

## Plan

### 분해 전 구조 판단 — 순환 import 차단 (T4 의 존재 이유)

`CreateIssueDialog` 는 폼을 감싸야 하고, `routes/issues.new` 는 모달을 감싸야 한다.
폼이 지금처럼 `routes/issues.new.tsx` 안에 있으면
**`routes/issues.new` → `CreateIssueDialog` → `routes/issues.new`** 순환이 된다.
→ `IssueCreateForm` 을 **`components/issue/IssueCreateForm.tsx` 로 먼저 추출**한다 (T4, 순수 이동).

### Task 1. `createIssue` 가 신규 5필드를 싣는다 (`assigneeId` 3-state 포함)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: []

**RED**. `apps/web/src/api/issues.test.ts`
- `typeId`·`description`·`priority`·`labels` 를 넘기면 요청 본문에 그대로 실린다
- **3-state 3케이스** — `assigneeId` 미전달이면 본문에 **키 자체가 없다** / `null` 이면 `null` 이 실린다 / 값이면 그 값이 실린다
- 실패 예상: `CreateIssueInput` 에 해당 필드 없음 → 타입 에러 + 본문 단언 실패

**GREEN**. `CreateIssueInput` 에 5필드 추가(`assigneeId?: string | null`),
`createIssue` 가 `'assigneeId' in input` 으로 키 존재를 판별해 본문 구성.
`securityLevelId`·`customFields` 의 기존 `!== undefined` 관례를 따른다.

**REFACTOR**. 3-state 판별을 KDoc 으로 명시 — *"`undefined`=자동 배정 유지 / `null`=미할당 확정 / 값=지정"*.

**검증**. `pnpm test src/api/issues.test.ts` · `pnpm typecheck`

---

### Task 2. MSW 생성 핸들러가 신규 5필드를 실제로 반영한다 (FR-14, BLOCKER 처방)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/__tests__/create-issue-handler.test.ts`]
- depends-on: []

**RED**. 신규 `mocks/__tests__/create-issue-handler.test.ts`
- `typeId`·`description`·`priority`·`labels` 를 보내면 **응답에 그 값**이 온다 (지금은 fixture 값이 온다)
- `assigneeId` 키를 **안 보내면** 기존 자동배정 시뮬레이션 결과가 온다
- `assigneeId: null` 을 보내면 응답 `assigneeId` 가 **`null`** 이다 (자동배정이 돌지 않는다)
- `assigneeId: '<값>'` 을 보내면 **그 값**이 온다
- `assigneeId: '__NOT_FOUND__'` 이면 **422 `ASSIGNEE_NOT_FOUND`**
- 실패 예상: 핸들러가 5필드를 읽지 않아 전부 fixture 값 반환

**GREEN**. 요청 본문 타입에 5필드 추가. `'assigneeId' in body` 로 3-state 분기 —
키가 있을 때만 요청 값을 쓰고, 없을 때만 `resolvedAssigneeId` 시뮬레이션을 돌린다.
응답을 fixture 스프레드가 아니라 요청 값 기반으로 구성.

**REFACTOR**. 3-state 분기에 *"백엔드 `JsonNullable` 계약 대응"* 주석 + sentinel 상수 export.

**검증**. `pnpm test src/mocks/__tests__/create-issue-handler.test.ts`

---

### Task 3. `LabelChipsEditor` 추출 (저장 버튼 없는 순수 편집기)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/meta/LabelChipsEditor.tsx`, `apps/web/src/components/issue/meta/IssueLabelsEdit.tsx`, `apps/web/src/components/issue/meta/__tests__/LabelChipsEditor.test.tsx`]
- depends-on: []

**RED**. 신규 `__tests__/LabelChipsEditor.test.tsx`
- 라벨을 추가하면 **저장 버튼 없이** `onChange(labels)` 가 즉시 발화한다
- 칩 제거 시에도 `onChange` 발화
- 검증 4종 유지 — 공백 trim · 50자 초과 거부 · 20개 초과 시 입력 비활성 · 중복 거부
- 컴포넌트 안에 **`저장` 이름을 가진 버튼이 없다** (`queryByRole('button', { name: '저장' })` 이 null)
- 실패 예상: `LabelChipsEditor` 없음

**GREEN**. `IssueLabelsEdit` 의 칩 편집 로직을 `LabelChipsEditor(value, onChange, disabled)` 로 이동.
`IssueLabelsEdit` 는 `LabelChipsEditor` + 저장 버튼 래퍼로 재구성 —
로컬 chips 를 들고 있다가 버튼 클릭 시 `onSave(chips)`.

**REFACTOR**. `MAX_LABELS`·`MAX_LABEL_LENGTH` 를 `LabelChipsEditor` 소유로 옮기고 export.

**🔒 판정식 (회귀 0 증명)**. **`__tests__/IssueLabelsEdit.test.tsx` 를 한 줄도 고치지 않고 green.**
`git diff --stat` 에 그 파일이 나타나면 BLOCKED.

**검증**. `pnpm test src/components/issue/meta` · `git diff --name-only | grep IssueLabelsEdit.test` 이 공집합

---

### Task 4. `IssueCreateForm` 파일 추출 (순수 이동, 동작 0 변경)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueCreateForm.tsx`, `apps/web/src/routes/issues.new.tsx`, `apps/web/src/routes/issues.new.test.tsx`]
- depends-on: []

**RED**. 기존 `routes/issues.new.test.tsx` 의 import 경로를 새 위치로 바꾼 상태에서 **먼저 실패**시킨다
(파일 부재 → 모듈 해석 실패).

**GREEN**. `IssueCreateForm` + 폼 스키마 + 헬퍼(`sanitizeInitialSummary` 등)를
`components/issue/IssueCreateForm.tsx` 로 이동. `routes/issues.new.tsx` 에는
`IssueCreateRouteAdapter` 만 남기고 폼을 import 한다. `router.ts` 의 import 는 어댑터라 무변경.

**REFACTOR**. 파일 첫 줄 한국어 헤더 주석 갱신.

**🔒 판정식**. 이 task 는 **동작을 바꾸지 않는다**. `issues.new.test.tsx` 는
**import 경로 한 줄 외 변경 0** 으로 green.

**검증**. `pnpm test src/routes/issues.new.test.tsx` · `pnpm typecheck`

---

### Task 5. 폼 확장 (1/2) — 프로젝트 셀렉터 · 이슈 유형 · 본문 · 제목 상한 200

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueCreateForm.tsx`, `apps/web/src/components/issue/__tests__/IssueCreateForm.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [1, 4]

**RED**. `__tests__/IssueCreateForm.test.tsx`
- 프로젝트가 **셀렉터**로 렌더되고 활성 프로젝트가 기본 선택된다 (자유 텍스트 아님)
- 이슈 유형 셀렉터가 렌더되고 기본값이 `key === 'task'` 인 항목이다
- 본문(textarea)이 렌더되고, 값이 제출 본문 `description` 에 실린다
- 유형 목록 로딩 중에는 셀렉터가 **비활성**이다 (B-3)
- 제목 **201자**가 폼 검증에서 거부된다 (B-2 선재 결함 정렬 — 기존 상한 500)
- 프로젝트를 바꾸면 `componentIds`·`customFields` 가 **초기화**된다 (E1/B-4)

**GREEN**. `useProjects(false)` + `use-resolved-active-project` 로 셀렉터 구성 ·
`fetchIssueTypes()` 쿼리 + `meta/IssueTypeSelect` 재사용 · `ui/textarea` 로 본문 ·
`SUMMARY_MAX_LENGTH` 500 → **200** · 프로젝트 변경 `useEffect` 초기화.

**REFACTOR**. 문자열을 `i18n/ko.ts` 로. 본문 칸에 *"비워두면 프로젝트 템플릿이 채워집니다"* 안내.

**검증**. `pnpm test src/components/issue/__tests__/IssueCreateForm.test.tsx`

---

### Task 6. 폼 확장 (2/2) — 담당자(3-state) · 우선순위 · 라벨

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueCreateForm.tsx`, `apps/web/src/components/issue/__tests__/IssueCreateForm.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [3, 5]

**RED**. 같은 테스트 파일에 추가
- **담당자를 한 번도 안 건드리면** 제출 본문에 `assigneeId` **키가 없다** (S3)
- **해제 버튼을 누르면** 본문에 `assigneeId: null` 이 실린다 (S4)
- 사용자를 고르면 그 id 가 실린다
- 담당자 칸에 *"비워두면 자동으로 배정됩니다"* 안내가 보인다 (FR-6)
- 우선순위 기본값 **3**, 바꾸면 그 값이 실린다
- 라벨을 추가하면 본문 `labels` 에 실리고, **폼 안에 `저장` 버튼이 없다**
- 422 `ASSIGNEE_NOT_FOUND` 응답 시 담당자 칸 옆에 에러가 보인다

**GREEN**. 폼 상태 `assigneeId: string | null | undefined` (초기값 `undefined`) ·
`meta/IssueAssigneeSelect`(`currentAssignee=null`·`canEdit`) + `useUsers(debounced)` ·
`meta/IssuePrioritySelect`(기본 3) · `LabelChipsEditor` · 422 에러 매핑.

**REFACTOR**. 3-state 초기값이 `null` 이 아니라 `undefined` 인 이유를 주석으로 못박는다
(뒤집으면 자동 배정이 조용히 꺼진다).

**검증**. `pnpm test src/components/issue/__tests__/IssueCreateForm.test.tsx`

---

### Task 7. `CreateIssueDialog` 신설 (제어 컴포넌트)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/CreateIssueDialog.tsx`, `apps/web/src/components/issue/__tests__/CreateIssueDialog.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [6]

**RED**. `__tests__/CreateIssueDialog.test.tsx`
- `open={false}` 면 아무것도 렌더하지 않는다
- `open` 이면 `getByRole('dialog', { name: '새 이슈 만들기' })` 가 **단건** 매칭 (NFR-1)
- 취소/Esc 가 `onOpenChange(false)` 를 부른다
- **닫았다 다시 열면 폼이 초기화**된다 (E3)
- 생성 성공 시 `onOpenChange(false)` + `onCreated(issue)` 발화
- **URL 을 읽거나 바꾸지 않는다** — 라우터 훅 import 0 (FR-1, F3 재사용 조건)

**GREEN**. `Dialog`/`DialogContent className="max-w-xl"`/`DialogTitle`/`DialogFooter` 조합.
본문 래퍼 `max-h-[60vh] overflow-y-auto`, `DialogFooter` 는 스크롤 밖 (NFR-2).
`open` 변화 시 `key` 를 바꿔 폼을 리마운트해 초기화.

**REFACTOR**. `aria-describedby={undefined}` 등 기존 20개 모달 관례 정렬.

**검증**. `pnpm test src/components/issue/__tests__/CreateIssueDialog.test.tsx` ·
`grep -c "tanstack/react-router" CreateIssueDialog.tsx` 가 **0**

---

### Task 8. 진입점 2곳 배선 — 딥링크 라우트 + 상단바

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.new.tsx`, `apps/web/src/routes/issues.new.test.tsx`, `apps/web/src/components/layout/TopBar.tsx`, `apps/web/src/components/layout/__tests__/TopBar.test.tsx`]
- depends-on: [7]

**RED**.
- `issues.new.test.tsx` — 라우트 어댑터가 `CreateIssueDialog` 를 `open` 으로 렌더하고,
  닫으면 `/issues` 로 navigate 한다 (FR-11)
- `TopBar.test.tsx` — 만들기 클릭 시 **navigate 가 호출되지 않고**(URL 불변) 모달이 열린다 (FR-12)

**GREEN**. 라우트 어댑터를 모달 마운트로 교체 · `TopBar` 에 로컬 `open` 상태 + `CreateIssueDialog` 렌더.

**REFACTOR**. `c` 단축키·커맨드 팔레트는 **현행 navigate 유지**임을 주석으로 명시
(`shortcuts.ts` 무변경이 §4.8 4중 계약 조건).

**🔒 판정식**. `shortcuts.test.ts` **무수정 green**.

**검증**. `pnpm test src/routes/issues.new.test.tsx src/components/layout src/components/keyboard-shortcuts`

---

### Task 9. E2E — 신규 시나리오 + `/issues/new` 의존 4파일 회귀

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-create-dialog.spec.ts`]
- depends-on: [8]

**RED→GREEN**. 신규 `e2e/issue-create-dialog.spec.ts`
- S1. 상단바 만들기 → **URL 불변** + 모달 열림
- S2. 7필드 입력 → 만들기 → **`POST /issues` 1회 · 후속 `PATCH` 0회** 를 네트워크로 단언 (C6)
- S5. `/issues/new` 직접 진입 → 같은 모달 · 닫으면 `/issues`

**회귀 확인 (수정 금지, 실행만)**. `keyboard-shortcuts.spec.ts` · `keymap.spec.ts` ·
`custom-fields.spec.ts`(특히 `:328-330` `history.pushState` 경로 — B-5) · `issue-ui-regression.spec.ts`.
**수정이 필요하면 사유를 PR 본문에 명시**한다 (learnings 2026-05-31).

**검증**. `pnpm exec playwright test issue-create-dialog keyboard-shortcuts keymap custom-fields issue-ui-regression`

---

### Task 10. 정본 동기화 9종 + verify

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `CHANGELOG.md`, `Maxi_wiki/BTS/history.md`]
- depends-on: []

**작업**. `docs/rules/fr-sync-checklist.md` 9종을 **전수 확인**한다 (대부분 "변경 없음 확인"이다).
- ① `fr-index.md` — FR ID·카운트 **불변** 확인만
- ② `docs/sdd/` — 변경 없음 확인만
- ③ **`docs/plan/product/personalization.md` §4.7** — F2/F3 서술을 ADR D-1 대로 정정
  (담당자·우선순위·라벨을 F2 로 이동) + D2/D6 체크박스
- ④ `docs/plan/README.md` — 진척 열 갱신 (D 마커 실측 재계산)
- ⑤ `CLAUDE.md` — D 마커 카운트 동기화
- ⑥ ADR·spec·plan — 이미 작성됨, 상호 링크 확인
- ⑦ Obsidian 미러 — 머지 시 hook 이 처리, `history` 한 줄
- ⑧ 자동 메모리 — `node scripts/build-doc-index.mjs` 재생성
- ⑨ `CHANGELOG.md` `[Unreleased]`

**🔒 판정식**. `bash scripts/verify-master-plan.sh` **EXIT 0** (종료 4 면 머지 차단).

**검증**. `bash scripts/verify-master-plan.sh; echo $?`

## Plan 메타

- **task 수**. 10
- **wave 추정**. 6 — W1 `[T1, T2, T3, T4, T10]` · W2 `[T5]` · W3 `[T6]` · W4 `[T7]` · W5 `[T8]` · W6 `[T9]`
  (T5~T9 는 같은 폼 파일을 순차로 키우므로 직렬이 불가피하다)
- **예상 시간**. 직렬 기준 약 40분, wave 병렬 적용 시 약 25분
- **TDD 강제**. yes (`test:` 커밋이 `feat:` 커밋보다 먼저)
- **회귀 판정식 4종 (무수정 green 이어야 함)**.
  `IssueLabelsEdit.test.tsx` · `issues.new.test.tsx`(T4 시점) · `shortcuts.test.ts` · `/issues/new` 의존 E2E 4파일
- **추가 검증**. `pnpm typecheck`(vitest 는 타입을 안 본다) · `pnpm lint` · `verify-master-plan.sh`
- **백엔드 변경**. 0 (BC 격리 유지)

## 리뷰 결과 (← /bts-review-plan 채움)
