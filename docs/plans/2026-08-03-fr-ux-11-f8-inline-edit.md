# FR-UX-11 F8 — 이슈 상세 인라인 편집

> slug: fr-ux-11-f8-inline-edit
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (논리 소속은 personalization — ADR §D2 논리 ≠ 물리)
> 생성: 2026-08-03

## Brief

**사용자 원문.** `/bts fr-ux-11 구현`

**범위 확정 (Maxi 확정 2026-08-03).** FR-UX-11 은 F8 + F9 두 PR 이고 F9 가 F8 에 의존한다
(`docs/design/jira-parity-roadmap.md:60-61` · `~/.claude/plans/ui-ux-sorted-kay.md:88-89` 실측).
**이번 PR 은 F8 만** 담는다. F9(이슈 목록 셀 인라인 편집)는 별도 PR.

**F8 내용.** 이슈 상세 화면에서 제목/본문을 클릭해 편집 진입, Enter 저장, Esc 취소.
정본이 지목한 대상은 `routes/issues.$key.tsx:704-726` · `IssueDescription.tsx:123-144`.
백엔드 변경 0 예상 — 기존 이슈 PATCH API 소비.

**하류 효과.** F8 은 F9 와 §4.8 FR-UX-10 의 F11(상세 액션 단축키)의 **공통 선행**이다.
현재 FR-UX-10 은 D6/D7 이 `[ ]` 로 F11 을 기다리는 상태(PR #336).

**classify 정정 1건.** `classify-task.ts` 초회 실행이 `type=backend`/`backend-engineer` 를
냈다 — 제목에 UI 키워드(`UI_KEYWORDS`)도 경로 패턴(`apps/web/`)도 없어 **신호 0 → backend
기본값**으로 떨어진 것이다. 실제 작업은 `apps/web` 프론트 전용이라 제목을 정정해
`type=ui`/`frontend-engineer`/`slug=fr-ux-11-f8-inline-edit` 로 재분류했다.

## 착수 전 확보한 선행 교훈

- `learnings.md:616` **메타 mutation `setQueryData`(부분 응답)가 본문을 placeholder 로 덮는 플리커**
  (PR #46). PATCH 응답의 `descriptionHtml` 은 항상 null 이라 캐시 전체 교체 시 본문이 사라진다.
  **처방은 invalidate-only 통일.** 인라인 편집은 정확히 이 경로를 다시 밟는다.
- `learnings.md:631` **UI PR 이 E2E 를 미루면 기존 E2E 회귀가 머지 시점에 잠복** (PR #47).
  같은 화면에 저장 버튼이 늘어 `getByRole('button',{name:'저장'})` 이 strict mode violation.
  **이슈 상세는 이미 그 사고가 난 화면이다** — 인라인 편집이 저장 버튼/편집 진입점을 또 늘린다.
- `docs/design/jira-parity-roadmap.md:300` F8 은 **시각/조작 변화 PR** 목록 — 머지 전
  실제 브라우저 확인(라이트/다크 양쪽) 대상이다.

## 도메인 정리

**결론. `/bts-domain`(grill-with-docs) 스킵** — `type == ui` 기존 화면 수정 fast-track 조건 충족.
스킵 조건의 예외인 **신규 도메인 개념이 0** 임을 실측으로 확인했다.

| 판정 항목 | 실측 |
|---|---|
| 신규 엔티티 | **0** — `Issue` 만 다룬다 |
| 신규 라우트 | **0** — `routes/issues.$key.tsx` 기존 화면 |
| 신규 용어 | **0** — glossary 헤딩 전수 grep(`편집`·`인라인`·`낙관`·`버전`·`충돌`·`occ`) **0건 매치**. "인라인 편집"은 UI 상호작용 용어이지 유비쿼터스 언어가 아니다 |
| 기존 결정 충돌 | **0** — `docs/decisions/` 에 F8 전용 ADR 없음 |
| 백엔드 | **0줄** — 기존 `PATCH /issues/{key}` 소비 |

- BC. issue-tracking (물리) / personalization (논리 — ADR §D2 논리 ≠ 물리)
- 관련 ADR. 없음 (신규 ADR 후보는 아래 ★2 충돌 처리 결정 1건)

### ★ 착수 전 실측이 정본을 뒤집었다 — 1건

**정본 서술.** `docs/plan/product/personalization.md` §4.9 —
*"현재 BTS 는 인라인 편집이 **전무**해, 제목 한 글자를 고치려 해도 **폼 화면으로 이동**해야 한다."*

**실측.** 거짓이다. 제목도 본문도 **이슈 상세 화면 안에서 이미 편집된다.**

| 대상 | 현재 상태 | 근거 |
|---|---|---|
| 제목 | `isEditingTitle` 상태 + `Input` + 저장/취소 버튼. **화면 이동 0** | `routes/issues.$key.tsx:698-726` · 핸들러 `:464-480` |
| 본문 | `isEditing` 상태 + `EditMode`(마크다운/미리보기 탭) / `ReadMode`. **화면 이동 0** | `IssueDescription.tsx:123-144` · 핸들러 `:96-110` |
| OCC | **이미 적용** — `expectedVersion: issue.version` 을 mutation 에 전달 | `issues.$key.tsx:476` |

**따라서 F8 의 실제 잔여 범위는 「인라인 편집 신설」이 아니라 3가지다.**

1. **진입 방식** — 지금은 별도 「편집」 **버튼**을 눌러야 한다. F8 계약은 **텍스트 자체를 클릭**해 진입.
2. **Enter 저장** — 현재 0건. 저장은 버튼 클릭만.
3. **Esc 취소** — 현재 0건. 취소는 버튼 클릭만.

이 전복은 #328·#331·#333·#336 에 이어 **5연속**이다 (계열 교훈 `two-lists-never-check-each-other` —
정본 산문과 코드가 서로를 검사하지 않는다). **정정 노트가 아니라 원문을 고친다** — F3(#333) 선례.

### ★★ 신규 충돌면 1건 — Esc 가 이미 임자가 있다

`usePaneEscapeClose`(`issues.$key.tsx:76-92`)가 **pane variant 에서 Escape 를 패널 닫기로 소비**한다.
여기에 「Esc = 편집 취소」를 얹으면 **Esc 한 번에 편집 취소 + 패널 닫기가 동시 발화**한다.

다행히 방어 패턴이 이미 그 자리에 있다 — `if (e.defaultPrevented) return`(`:84`, CONCERNS-2).
Radix DismissableLayer 가 capture 단계에서 `preventDefault()` 하는 것을 bubble 리스너가 존중하는
구조다. **편집 취소도 같은 규약을 따라 `preventDefault()` 해야** 페인이 함께 닫히지 않는다.
이것이 이 PR 의 **결정 사항 1호**이고 spec 의 엣지 케이스로 승계한다.

추가 확인 필요 — F10(#336)이 심은 컨텍스트 단축키(`j`/`k`/`o`/`t`/`[`)의 `shouldIgnoreEvent`
가드가 **편집 중 input/textarea 에서 키를 흘리지 않는지**. 편집 진입면이 늘면 그 가드의
적용 범위도 함께 늘어난다.

## 스펙

전체 스펙. [docs/specs/2026-08-03-fr-ux-11-f8-inline-edit.md](../specs/2026-08-03-fr-ux-11-f8-inline-edit.md)

**핵심 3줄.**
- 제목·본문 **텍스트를 클릭**해 편집 진입 (기존 편집 버튼은 그대로 유지 — 진입 경로 2개)
- 제목은 `Enter` 저장 · 본문은 `Ctrl/Cmd+Enter` 저장(맨 `Enter` 는 줄바꿈), 양쪽 `Esc` 취소
- 백엔드 0 · 마이그레이션 0 · 신규 단축키 레지스트리 0 — 기존 `PATCH` 경로와 OCC 처리를 그대로 승계

**Jira 대조에서 나온 의도적 편차 2건 (스펙 §2-3).**
Jira 의 이 동작에는 **미해결 결함 티켓 2건**이 공개돼 있다 — 본문 클릭이 텍스트 선택을
방해하고([JRA-64389](https://jira.atlassian.com/browse/JRA-64389)), `Esc` 가 확인 없이 작성분을
버린다([JRACLOUD-41814](https://jira.atlassian.com/browse/JRACLOUD-41814) — Atlassian 이 개선 거부를 공표).
그대로 베끼면 결함까지 복제하므로 2건을 편차로 확정했다.
- **D-1.** 본문 `Esc` 는 변경분이 있으면 확인을 거친다.
- **D-2.** 본문 클릭 진입은 텍스트 선택 중이면 발동하지 않는다.

**Maxi 확정 4건.** 범위 F8 만 · 진입 방식 버튼 유지 + 클릭 추가 · 적용 대상 제목+본문 ·
구현 접근 **A(각자 최소 변경, 공용 추상화 선제 도입 안 함)**.

## Brainstorming Check

✅ 통과 (ui 경량 경로 — brainstorming 스킵, `## Jira 대조` + 즉사 계약 §2 교차가 대체).
착수 중 발견 3건(정본 서술 거짓 · Esc 충돌면 · Jira 결함 티켓 2건) 전부 스펙에 반영. **미해결 gap 0.**

## Plan

**Goal.** 이슈 상세의 제목·본문을 텍스트 클릭으로 편집 진입시키고, 제목 `Enter` · 본문 `Ctrl/Cmd+Enter` 저장과 양쪽 `Esc` 취소를 붙인다. 기존 편집 버튼·저장 경로·OCC 처리는 손대지 않는다.

**Architecture.** 구현 접근 A(Maxi 확정) — 제목은 라우트 컴포넌트(`issues.$key.tsx`), 본문은 `IssueDescription.tsx` 각자에 최소 변경. 공용 훅/프리미티브 신설 없음. 키 처리는 전역 단축키 레지스트리가 아니라 **해당 입력 요소의 지역 `onKeyDown`** 이다. `Esc` 는 `preventDefault()` 로 소비해 기존 `e.defaultPrevented` 규약(`issues.$key.tsx:84` · `use-mention-autocomplete.ts:220-248`)에 편승한다.

**Tech Stack.** React 19 · TypeScript 5 strict · vitest + Testing Library · Playwright.

### 파일 구조

| 파일 | 책임 | 변경 성격 |
|---|---|---|
| `apps/web/src/routes/issues.$key.tsx` | 제목 클릭 진입면 + 제목 Input 키 처리 | 수정 |
| `apps/web/src/components/issue/IssueDescription.tsx` | 본문 클릭 진입면 + textarea 키 처리 + 취소 확인 | 수정 |
| `apps/web/src/i18n/ko.ts` | 본문 취소 확인 문구 1건 추가 | 수정 |
| `apps/web/e2e/inline-edit.spec.ts` | S1~S8 시나리오 | 신규 |

---

### Task 1. 제목 — 텍스트 클릭으로 편집 진입

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: []

**착수 전 확인 1건.** `routes/issues.$key.test.tsx` 와 `routes/__tests__/issues.$key.test.tsx` 가 **둘 다 존재**한다. `pnpm vitest run issues` 로 어느 쪽이 실제 수집되는지 확인하고 **활성 파일에만** 작성한다. 비활성 파일은 이 PR 범위 밖이므로 건드리지 않고 발견 사실만 리뷰에 보고한다.

**RED** (동반 테스트 — ui 시각 검증 트랙이라 red-first 순서 강제 없음).
- 파일. 위에서 확인한 활성 테스트 파일

```tsx
it('제목 텍스트를 클릭하면 편집 모드로 진입한다', async () => {
  renderIssueDetail()  // 기존 헬퍼 재사용
  const title = await screen.findByRole('button', { name: 'E2E 테스트용 이슈' })
  await userEvent.click(title)
  expect(screen.getByRole('textbox', { name: '제목 편집' })).toBeInTheDocument()
})

it('제목 heading 의 접근성 이름은 클릭 진입면을 넣어도 그대로다', async () => {
  renderIssueDetail()
  expect(await screen.findByRole('heading', { name: 'E2E 테스트용 이슈' })).toBeInTheDocument()
})

it('수정 권한이 없으면 제목 클릭 진입면이 없다', async () => {
  renderIssueDetail({ canEdit: false })
  expect(screen.queryByRole('button', { name: 'E2E 테스트용 이슈' })).not.toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: 'E2E 테스트용 이슈' })).toBeInTheDocument()
})
```

**GREEN**.
- 파일. `apps/web/src/routes/issues.$key.tsx` — 읽기 모드 제목 렌더(`:726-745`)

heading 요소는 그대로 두고 **안쪽만** 클릭 진입면으로 감싼다. heading role 과 accessible name 이 보존되고(제약 C2), `<button>` 이라 키보드로도 도달·발동된다(FR9).

```tsx
// 제목 본문 — canEdit 이면 클릭 진입 버튼으로 감싼다 (FR1/FR8/FR9, 계약 §2 heading 보존)
const titleContent = canEdit ? (
  <button
    type="button"
    onClick={handleEditStart}
    // hover 는 DESIGN.md §7 상태 토큰을 쓴다 — 라이트 #F1F2F4 / 다크 #A1BDD914 가 이미 정의돼 있어
    // 두 테마가 자동 대응한다. `bg-muted/50` 같은 임의 알파는 §7 배선을 우회하므로 금지 (리뷰 F-3)
    className="text-left w-full rounded-sm hover:bg-(--bg-neutral-hover) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--border-focus)"
  >
    {issue.summary}
  </button>
) : (
  issue.summary
)

{variant === 'pane' ? (
  <h2 ref={paneTitleRef} tabIndex={-1} className="text-2xl font-semibold leading-snug mb-1">
    {titleContent}
  </h2>
) : (
  <h1 className="text-2xl font-semibold leading-snug mb-1">{titleContent}</h1>
)}
```

기존 `✎ 제목 수정` 버튼은 **그대로 둔다**(FR7).

**REFACTOR**.
- `titleContent` 를 컴포넌트 본문 상단 파생값으로 정리하고 위 의도를 한 줄 주석으로 남긴다.

**검증**.
- 유닛. `pnpm vitest run issues.\$key`
- **기존 E2E 동반 실행** (계약 §5 사전 grep 결과). `pnpm exec playwright test issue-crud-happy issue-edit-conflict issue-permission` — **파일 수정 0** 으로 통과해야 한다
- **브라우저 눈확인**. 라이트/다크 양쪽에서 ① 제목 hover 시 배경 강조가 보이는가 ② 포커스 링이 두 테마 모두에서 식별되는가 ③ 제목 글자 크기·굵기가 종전과 동일한가

---

### Task 2. 제목 — `Enter` 저장 · `Esc` 취소

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: [1]

**RED**.

```tsx
it('제목 편집 중 Enter 를 누르면 저장한다', async () => {
  renderIssueDetail()
  await userEvent.click(await screen.findByRole('button', { name: 'E2E 테스트용 이슈' }))
  const input = screen.getByRole('textbox', { name: '제목 편집' })
  await userEvent.clear(input)
  await userEvent.type(input, '바뀐 제목{Enter}')
  await waitFor(() => expect(updateIssueSpy).toHaveBeenCalledWith(
    expect.objectContaining({ summary: '바뀐 제목' }),
  ))
})

it('IME 조합 확정 Enter 는 저장하지 않는다', async () => {
  renderIssueDetail()
  await userEvent.click(await screen.findByRole('button', { name: 'E2E 테스트용 이슈' }))
  const input = screen.getByRole('textbox', { name: '제목 편집' })
  fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
  expect(updateIssueSpy).not.toHaveBeenCalled()
})

it('제목 편집 중 Esc 는 취소하고 원본을 복원한다', async () => {
  renderIssueDetail()
  await userEvent.click(await screen.findByRole('button', { name: 'E2E 테스트용 이슈' }))
  await userEvent.type(screen.getByRole('textbox', { name: '제목 편집' }), '덧붙임')
  await userEvent.keyboard('{Escape}')
  expect(screen.queryByRole('textbox', { name: '제목 편집' })).not.toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: 'E2E 테스트용 이슈' })).toBeInTheDocument()
})

// ★ E1 — pane 이중 발화 가드. preventDefault() 를 지우면 이 테스트가 빨강이어야 한다 (완료 기준 5)
it('pane 에서 편집 중 Esc 는 편집만 취소하고 패널을 닫지 않는다', async () => {
  const onClose = vi.fn()
  renderIssueDetail({ variant: 'pane', onClose })
  await userEvent.click(await screen.findByRole('button', { name: 'E2E 테스트용 이슈' }))
  await userEvent.keyboard('{Escape}')
  expect(screen.queryByRole('textbox', { name: '제목 편집' })).not.toBeInTheDocument()
  expect(onClose).not.toHaveBeenCalled()
})

it('저장 진행 중에는 Enter 가 중복 제출하지 않는다', async () => {
  renderIssueDetail({ updatePending: true })
  await userEvent.click(await screen.findByRole('button', { name: 'E2E 테스트용 이슈' }))
  fireEvent.keyDown(screen.getByRole('textbox', { name: '제목 편집' }), { key: 'Enter' })
  expect(updateIssueSpy).not.toHaveBeenCalled()
})
```

**GREEN**.
- 파일. `apps/web/src/routes/issues.$key.tsx` — 제목 편집 `Input`(`:700-706`)

```tsx
// 제목 편집 키 처리 — 저장/취소는 버튼과 같은 핸들러를 탄다 (FR2/FR3)
function handleTitleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
  // E4 — 한글 IME 조합 확정 Enter 를 저장으로 오인하지 않는다
  if (e.nativeEvent.isComposing || e.keyCode === 229) return

  if (e.key === 'Enter') {
    e.preventDefault()
    if (updateMutation.isPending || !canEdit) return  // E5 중복 제출 차단
    handleEditSave()
  } else if (e.key === 'Escape') {
    // ★ E1 — preventDefault 가 usePaneEscapeClose(:84) 의 defaultPrevented 가드를 세운다.
    //   지우면 pane 이 함께 닫힌다. 위 pane 테스트가 이 줄의 비-공허 증인이다.
    e.preventDefault()
    handleEditCancel()
  }
}
```

```tsx
<Input
  aria-label={issueDetailStrings.titleEditLabel}
  value={editSummary}
  onChange={(e) => setEditSummary(e.target.value)}
  onKeyDown={handleTitleKeyDown}
  className="text-xl font-semibold"
/>
```

**REFACTOR**.
- `handleTitleKeyDown` 을 다른 편집 핸들러 3종(`handleEditStart`/`Cancel`/`Save`) 바로 아래에 모아 둔다.

**검증**.
- 유닛. `pnpm vitest run issues.\$key`
- **뮤테이션 확인 (완료 기준 5)**. `handleTitleKeyDown` 의 `Escape` 분기에서 `e.preventDefault()` 를 임시 제거 → pane 테스트가 **빨강**인지 확인 → `git checkout --` 으로 원복. **원복 전에 GREEN 커밋이 있어야 한다**(교훈 `mutation-test-requires-committed-baseline`)
- **기존 E2E 동반 실행**. `pnpm exec playwright test issue-crud-happy issue-edit-conflict issue-permission`
- **브라우저 눈확인**. 라이트/다크 — ① 제목 편집 중 `Enter` 로 저장되는가 ② `Esc` 로 취소되며 **pane 이 열린 채**인가 ③ 한글 입력 중 조합 확정 `Enter` 가 저장으로 새지 않는가

---

### Task 3. 본문 — 텍스트 클릭으로 편집 진입 (선택·링크 가드 포함)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/IssueDescription.test.tsx`]
- depends-on: []

**RED**.

```tsx
it('본문을 클릭하면 편집 모드로 진입한다', async () => {
  render(<IssueDescription {...baseProps} />)
  await userEvent.click(screen.getByTestId('description-preview-content'))
  expect(screen.getByRole('textbox', { name: '본문 편집' })).toBeInTheDocument()
})

// ★ 편차 D-2 (E2) — Jira 의 공개 결함 JRA-64389 를 복제하지 않는다
it('텍스트를 선택 중이면 본문 클릭이 편집을 열지 않는다', async () => {
  render(<IssueDescription {...baseProps} />)
  vi.spyOn(window, 'getSelection').mockReturnValue({ isCollapsed: false } as Selection)
  fireEvent.click(screen.getByTestId('description-preview-content'))
  expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
})

// E3 — 본문 안 링크 클릭은 링크가 동작하고 편집은 안 열린다
it('본문 안 링크를 클릭하면 편집이 열리지 않는다', async () => {
  render(<IssueDescription {...baseProps} descriptionHtml='<p><a href="/x">링크</a></p>' />)
  await userEvent.click(screen.getByRole('link', { name: '링크' }))
  expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
})

it('수정 권한이 없으면 본문 클릭이 편집을 열지 않는다', async () => {
  render(<IssueDescription {...baseProps} canEdit={false} />)
  await userEvent.click(screen.getByTestId('description-preview-content'))
  expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
})
```

**GREEN**.
- 파일. `apps/web/src/components/issue/IssueDescription.tsx` — `ReadMode`(`:161-195`)

```tsx
// 본문 클릭 진입 (FR4). 두 가지를 배제한다 —
//   E2/편차 D-2. 텍스트 선택 중(드래그로 복사하려는 참)이면 열지 않는다 (Jira JRA-64389 결함 회피)
//   E3.          링크/멘션 클릭은 그 요소가 먼저 동작해야 한다
function handleContentClick(e: React.MouseEvent<HTMLDivElement>) {
  if (!canEdit) return
  if (window.getSelection()?.isCollapsed === false) return
  if ((e.target as HTMLElement).closest('a')) return
  onEditClick()
}
```

```tsx
<div
  data-testid="description-preview-content"
  onClick={handleContentClick}
  className="prose prose-sm max-w-none text-sm text-foreground"
  // biome-ignore lint/security/noDangerouslySetInnerHtml: 백엔드 OWASP 정화 HTML만 허용
  dangerouslySetInnerHTML={{ __html: descriptionHtml }}
/>
```

`descriptionHtml === null` 인 placeholder 문단에도 같은 핸들러를 단다(빈 본문도 클릭해 쓸 수 있어야 한다). 기존 `본문 편집` 버튼은 그대로 둔다(FR7) — 키보드 진입 경로가 여기서 보존되므로 FR9 를 충족한다.

**REFACTOR**.
- `ReadMode` 의 props 에 `onEditClick` 이 이미 있으므로 시그니처 변경 없음. 주석으로 3가지 배제 사유만 남긴다.

**검증**.
- 유닛. `pnpm vitest run IssueDescription`
- **기존 E2E 동반 실행**. `pnpm exec playwright test issue-mention-render issue-crud-happy`
- **브라우저 눈확인**. 라이트/다크 — ① 본문 hover 시 편집 가능 어포던스가 보이는가 ② 본문 텍스트를 드래그 선택해도 편집이 안 열리는가 ③ 본문 안 링크 클릭이 정상 이동하는가
- **★ 모바일 눈확인 (리뷰 F-4 추가)**. 터치 기기에서 **본문 텍스트를 롱프레스로 선택**해 보고, 선택이 편집 진입에 삼켜지지 않는지 확인한다. `Selection.isCollapsed` 가드는 마우스 드래그를 전제로 쓴 것이라 롱프레스 선택은 이벤트 순서가 다를 수 있다. 삼켜지면 **본문 복사가 불가능해지는 회귀**이므로 발견 시 즉시 보고하고 가드를 보강한다

---

### Task 4. 본문 — `Ctrl/Cmd+Enter` 저장 · 맨 `Enter` 는 줄바꿈

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/IssueDescription.test.tsx`]
- depends-on: [3]

**RED**.

```tsx
it('본문 편집 중 Ctrl+Enter 로 저장한다', async () => {
  const onSave = vi.fn()
  render(<IssueDescription {...baseProps} onSave={onSave} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  const textarea = screen.getByRole('textbox', { name: '본문 편집' })
  await userEvent.type(textarea, '새 본문')
  fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true })
  expect(onSave).toHaveBeenCalledWith('새 본문')
})

it('본문 편집 중 맨 Enter 는 저장하지 않는다 (줄바꿈)', async () => {
  const onSave = vi.fn()
  render(<IssueDescription {...baseProps} onSave={onSave} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  fireEvent.keyDown(screen.getByRole('textbox', { name: '본문 편집' }), { key: 'Enter' })
  expect(onSave).not.toHaveBeenCalled()
})

// ★ E10 — 멘션 팝업이 열려 있으면 팝업이 Enter 를 먼저 소비한다
it('멘션 팝업이 열린 상태의 Enter 는 저장으로 새지 않는다', async () => {
  const onSave = vi.fn()
  render(<IssueDescription {...baseProps} onSave={onSave} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  const textarea = screen.getByRole('textbox', { name: '본문 편집' })
  await userEvent.type(textarea, '@ma')            // 멘션 드롭다운 오픈
  await screen.findByRole('listbox')
  fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true })
  expect(onSave).not.toHaveBeenCalled()
})
```

**GREEN**.
- 파일. `apps/web/src/components/issue/IssueDescription.tsx` — `EditMode` 의 `textarea`(`:277-291`)

멘션 훅이 먼저 볼 기회를 주고, **소비했으면 손대지 않는다**. `use-mention-autocomplete.ts:220-248` 이 드롭다운이 열렸을 때만 `preventDefault()` 하므로 이 규약이 성립한다.

```tsx
// 본문 편집 키 처리 (FR5/FR6). 멘션 팝업이 먼저 소비할 기회를 준다 — E10
function handleEditorKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
  mention.onKeyDown(e)
  if (e.defaultPrevented) return          // 팝업이 Enter/Escape 를 가져갔다

  if (e.nativeEvent.isComposing || e.keyCode === 229) return

  // 여러 줄 편집기라 맨 Enter 는 줄바꿈이다. 저장은 수식키를 요구한다 (Jira 동일)
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    if (isSaving) return
    onSave(draftMarkdown)
  }
}
```

```tsx
<textarea
  ref={textareaRef}
  value={draftMarkdown}
  onChange={mention.onChange}
  onKeyDown={handleEditorKeyDown}
  onCompositionStart={mention.onCompositionStart}
  onCompositionEnd={mention.onCompositionEnd}
  onSelect={mention.onSelect}
  onBlur={mention.onBlur}
  aria-label={issueDetailStrings.descriptionEditButton}
  disabled={isSaving}
  className="w-full min-h-[120px] resize-y rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
/>
```

**주의.** `onKeyDown={mention.onKeyDown}` 을 `handleEditorKeyDown` 으로 **교체**하는 것이지 지우는 게 아니다. 멘션 호출을 빠뜨리면 자동완성이 통째로 죽는다.

**REFACTOR**.
- `EditMode` 가 `onSave` 를 인자 없이 호출하던 자리와 시그니처를 맞춘다(현행 `handleSave` 는 `draftMarkdown` 을 클로저로 읽으므로 필요 시 그대로 둔다).

**검증**.
- 유닛. `pnpm vitest run IssueDescription mention`
- **기존 E2E 동반 실행**. `pnpm exec playwright test issue-mention-render` — 멘션 자동완성 회귀가 여기서 잡힌다
- **브라우저 눈확인**. 라이트/다크 — ① `Ctrl+Enter` 저장 ② 맨 `Enter` 가 줄바꿈 ③ `@` 자동완성이 종전대로 뜨고 `Enter` 로 선택되는가

---

### Task 5. 본문 — `Esc` 취소 + 변경분 확인 (편차 D-1)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/IssueDescription.test.tsx`, `apps/web/src/i18n/ko.ts`, `apps/web/src/i18n/ko.test.ts`]
- depends-on: [4]

**RED**.

```tsx
it('변경분이 없으면 Esc 가 즉시 편집을 닫는다 (S8)', async () => {
  render(<IssueDescription {...baseProps} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  fireEvent.keyDown(screen.getByRole('textbox', { name: '본문 편집' }), { key: 'Escape' })
  expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
})

// ★ 편차 D-1 (S7) — Jira 는 확인 없이 버린다(JRACLOUD-41814, Atlassian 개선 거부). BTS 는 확인한다
it('변경분이 있으면 Esc 가 확인을 먼저 띄운다', async () => {
  render(<IssueDescription {...baseProps} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  const textarea = screen.getByRole('textbox', { name: '본문 편집' })
  await userEvent.type(textarea, '아까운 초안')
  fireEvent.keyDown(textarea, { key: 'Escape' })
  expect(screen.getByText(issueDetailStrings.descriptionDiscardConfirm)).toBeInTheDocument()
  expect(textarea).toBeInTheDocument()           // 아직 편집 모드다
})

it('확인을 거부하면 편집 모드와 초안이 유지된다', async () => {
  render(<IssueDescription {...baseProps} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  await userEvent.type(screen.getByRole('textbox', { name: '본문 편집' }), '아까운 초안')
  fireEvent.keyDown(screen.getByRole('textbox', { name: '본문 편집' }), { key: 'Escape' })
  await userEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(screen.getByRole('textbox', { name: '본문 편집' })).toHaveValue(expect.stringContaining('아까운 초안'))
})

it('확인을 수락하면 편집이 닫힌다', async () => {
  render(<IssueDescription {...baseProps} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  await userEvent.type(screen.getByRole('textbox', { name: '본문 편집' }), '버릴 초안')
  fireEvent.keyDown(screen.getByRole('textbox', { name: '본문 편집' }), { key: 'Escape' })
  await userEvent.click(screen.getByRole('button', { name: '확인' }))
  expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
})
```

`ko.test.ts` 에 키 존재 단언 1줄 추가.

```ts
it('descriptionDiscardConfirm 키가 존재한다', () => {
  expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionDiscardConfirm')
})
```

**GREEN**.

`apps/web/src/i18n/ko.ts` — `issueDetailStrings` 에 1건 추가.

```ts
/** 본문 편집 취소 시 작성분 폐기 확인 문구 (편차 D-1) */
descriptionDiscardConfirm: '작성 중인 내용이 사라집니다. 편집을 그만둘까요?',
/** 확인 패널 — 작성분을 버리고 나간다 (기존 '취소'와 문자열을 분리해 중복 회피) */
descriptionDiscardConfirmButton: '편집 그만두기',
/** 확인 패널 — 편집으로 돌아간다 */
descriptionDiscardCancelButton: '계속 편집',
```

**★ 리뷰 F-2 반영 — 문자열 중복을 「둘 중 하나」로 열어두지 않고 원천 제거했다.**
초안은 확인 패널에 기존 `확인`/`취소` 를 재사용했는데, 같은 화면에 `취소` 가 둘이면
① E2E strict mode violation(`learnings.md:631` 실사고 재발면) ② **사용자도 「취소의 취소」가
뭔지 모른다**. 전용 문자열 2건을 새로 두어 두 문제를 한 번에 닫는다.

`IssueDescription.tsx` — 확인 UI 는 **기존 삭제 확인 관례**(`issues.$key.tsx:767-790` 의 인라인 확인 패널 + `확인`/`취소` 버튼)를 따른다. 새 다이얼로그 프리미티브를 도입하지 않는다.

```tsx
const [confirmDiscard, setConfirmDiscard] = useState(false)

// Esc 취소 (FR6). 초안이 원본과 다르면 확인을 거친다 — 편차 D-1
function requestCancel() {
  if (draftMarkdown !== (initialMarkdown ?? '')) {
    setConfirmDiscard(true)
    return
  }
  handleCancel()
}
```

`handleEditorKeyDown` 에 분기 추가.

```tsx
  } else if (e.key === 'Escape') {
    e.preventDefault()          // 상위 pane 닫기와 이중 발화 차단 (E1 과 같은 규약)
    requestCancel()
  }
```

`EditMode` 하단에 확인 패널.

```tsx
{confirmDiscard ? (
  <div className="border border-destructive/40 rounded-md p-3 text-sm text-destructive space-y-2">
    <p>{issueDetailStrings.descriptionDiscardConfirm}</p>
    <div className="flex gap-2">
      <Button
        variant="destructive"
        size="sm"
        onClick={() => { setConfirmDiscard(false); handleCancel() }}
        aria-label={issueDetailStrings.descriptionDiscardConfirmButton}
      >
        {issueDetailStrings.descriptionDiscardConfirmButton}
      </Button>
      <Button variant="outline" size="sm" onClick={() => setConfirmDiscard(false)}>
        {issueDetailStrings.descriptionDiscardCancelButton}
      </Button>
    </div>
  </div>
) : null}
```

**중복 방지 확인 (리뷰 F-2).** 확인 패널 버튼은 `편집 그만두기` / `계속 편집` 이라 화면 어디에도
같은 문자열이 없다. 「숨기거나 컨테이너 한정」 같은 **구현 시 판단을 남기지 않는다** — 애초에
충돌면을 만들지 않는 쪽이 정답이다. 아래 테스트가 이 사실을 고정한다.

```tsx
it('확인 패널이 떠도 「취소」 문자열이 화면에 둘 이상 생기지 않는다', async () => {
  render(<IssueDescription {...baseProps} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  await userEvent.type(screen.getByRole('textbox', { name: '본문 편집' }), '초안')
  fireEvent.keyDown(screen.getByRole('textbox', { name: '본문 편집' }), { key: 'Escape' })
  expect(screen.getAllByRole('button', { name: '취소' })).toHaveLength(1)
})
```

**REFACTOR**.
- `confirmDiscard` 상태를 `isEditing` 이 꺼질 때 함께 초기화한다(편집을 다시 열었을 때 확인 패널이 남지 않게).

**검증**.
- 유닛. `pnpm vitest run IssueDescription ko`
- **기존 E2E 동반 실행**. `pnpm exec playwright test issue-mention-render issue-crud-happy issue-edit-conflict` — 「저장」/「취소」 버튼 중복으로 인한 strict mode violation 이 여기서 잡힌다
- **브라우저 눈확인**. 라이트/다크 — ① 확인 패널의 destructive 색이 두 테마에서 읽히는가 ② 확인 패널이 뜬 동안 버튼이 겹쳐 보이지 않는가

---

### Task 6. E2E + 계약 무손상 기계 확인

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/inline-edit.spec.ts`]
- depends-on: [2, 5]

**RED/구현**. 신규 E2E — 완료 기준 4가 요구하는 S1·S2·S3·S4·S5·S7 을 덮는다.

```ts
// FR-UX-11 F8 E2E — 이슈 상세 인라인 편집 (클릭 진입 · Enter/Ctrl+Enter 저장 · Esc 취소)
import { expect, test } from '@playwright/test'

test.describe('FR-UX-11 F8 인라인 편집', () => {
  test('S1·S2 제목을 클릭해 열고 Enter 로 저장한다', async ({ page }) => { /* ... */ })
  test('S3 제목 편집 중 Esc 가 취소한다', async ({ page }) => { /* ... */ })
  test('S4·S5 본문을 클릭해 열고 Ctrl+Enter 로 저장한다', async ({ page }) => { /* ... */ })
  test('S7 본문 Esc 는 변경분이 있으면 확인을 거친다', async ({ page }) => { /* ... */ })
})
```

각 테스트 본문은 기존 `issue-crud-happy.spec.ts` 의 로그인·이슈 진입 헬퍼를 그대로 재사용한다(신규 헬퍼 금지).

**검증 — 계약 무손상 기계 확인 3종**.

```bash
# NFR1. 단축키 레지스트리 동결 — 무수정 green (완료 기준 3)
git diff --exit-code apps/web/src/components/keyboard-shortcuts/
pnpm vitest run shortcuts

# NFR2. 기존 E2E 4종 파일 수정 0 (완료 기준 2)
git diff --exit-code apps/web/e2e/issue-crud-happy.spec.ts apps/web/e2e/issue-edit-conflict.spec.ts \
  apps/web/e2e/issue-permission.spec.ts apps/web/e2e/issue-mention-render.spec.ts

# NFR6. 백엔드 0줄 · 의존성 0 (완료 기준 8)
git diff --exit-code backend/ package.json apps/web/package.json
```

- 전체 스위트. `pnpm typecheck && pnpm lint && pnpm vitest run && pnpm build`
- 전체 E2E. `pnpm exec playwright test`
- **브라우저 눈확인 최종**. 라이트/다크 양쪽에서 S1~S8 을 손으로 한 번 훑고 결과를 PR 본문에 기록(계약 §6)

---

### Task 7. 정본 동기화 — §4.9 서술 정정 + F8 진척 반영

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/plan/README.md`, `docs/design/jira-parity-roadmap.md`, `docs/INDEX.md`, `docs/INDEX-fr.md`, `docs/INDEX-recent.md`]
- depends-on: [6]

**★ 리뷰 F-1 로 추가된 task.** 초안 6개 task 어디에도 정본 정정이 없었다. CLAUDE.md
§명세/범위 변경 시 전수 동기화 위반이고, `verify-master-plan.sh` 가 종료 4 로 머지를 막는다.

**작업 1 — §4.9 산문 정정.** `docs/plan/product/personalization.md` §4.9 의
*"현재 BTS 는 인라인 편집이 **전무**해, 제목 한 글자를 고치려 해도 폼 화면으로 이동해야 한다"* 는
실측으로 거짓이다. **정정 노트가 아니라 원문을 고친다**(F3 #333 선례).

```
현재 BTS 는 제목·본문을 이슈 상세 화면 안에서 편집할 수 있지만 **진입이 버튼 클릭 한 경로뿐**이고
`Enter` 저장·`Esc` 취소가 없다. 지라를 쓰던 손이 텍스트를 클릭했을 때 아무 일도 일어나지 않는다.
```

**작업 2 — F8 완료 기록.** §4.9 D 마커 판정. **D6/D7 은 `[ ]` 유지** — F9(목록 셀 편집)가
남아 D6 의 "F9 목록 셀 3종"이 미충족이다. FR-UX-10 F10(#336)이 같은 이유로 D6/D7 을
열어둔 것과 동형이다. D1·D2 는 이 PR 의 도메인 정리·스펙으로 닫고, D3·D4·D5 는 "없음 확정".

**작업 3 — 로드맵 상태.** `docs/design/jira-parity-roadmap.md:39` 의 FR-UX-11 행을
`⬜ 미착수` → `🔶 진행(F8 완료 · F9 잔여)` 로. F8 행에도 PR 번호 기록.

**작업 4 — 카운트·인덱스.**

```bash
# D 마커 실측 (README 의 935/30 갱신용)
grep -rc '^- \[x\] D' docs/plan/product/*.md | awk -F: '{s+=$2} END {print "완료", s}'
grep -rc '^- \[ \] D' docs/plan/product/*.md | awk -F: '{s+=$2} END {print "미완", s}'

node scripts/build-doc-index.mjs      # MEMORY.md · docs/INDEX*.md 재생성 (직접 수정 금지)
node scripts/build-dashboard.mjs      # docs/progress.html
```

**검증**.

```bash
bash scripts/verify-master-plan.sh    # 종료 0 필수 — 4 면 머지 차단
node scripts/build-doc-index.mjs --check
```

- 동기화 9종 체크리스트(`docs/rules/fr-sync-checklist.md`)를 항목별로 짚고 결과를 PR 본문에 남긴다
- **FR 수는 불변 139** — FR 추가·삭제가 아니라 기존 FR 의 서술 정정 + 진척 반영이다

---

## Plan 메타

- task 수. **7**
- wave 예상. **5** — wave1 [T1, T3] 병렬 · wave2 [T2, T4] · wave3 [T5] · wave4 [T6] · wave5 [T7]
  (제목 파일과 본문 파일은 교집합 0 이라 병렬, 같은 파일끼리는 `depends-on` 직렬)
- 예상 시간. 직렬 약 25분 / wave 적용 시 약 15분
- 구현 규율. **ui 시각 검증 트랙** (red-first 순서 면제 · 기존 E2E 동반 실행 필수 · 브라우저 눈확인 필수)
- 추가 검증. typecheck · lint · vitest · playwright · 계약 무손상 `git diff --exit-code` 3종
- **뮤테이션 검증 1건**. T2 의 `Esc` `preventDefault()` 제거 → pane 테스트 빨강 확인 → 원복
  (GREEN 커밋 선행 필수 — `mutation-test-requires-committed-baseline`)

### Self-review 결과

- **스펙 커버리지.** FR1→T1 · FR2/FR3→T2 · FR4→T3 · FR5→T4 · FR6→T5 · FR7→T1·T3(버튼 불변) ·
  FR8→T1·T3 · FR9→T1(제목 button) ·T3(기존 버튼 보존) · FR10→T2·T5.
  E1→T2 · E2/E3→T3 · E4→T2·T4 · E5→T2 · E9→T4 · E10→T4 · D-1→T5 · D-2→T3.
  NFR1/NFR2/NFR6→T6 기계 확인 · NFR3(invalidate-only)→**변경 없음**(기존 경로 그대로라 신규 위험 0) ·
  NFR4→T1·T3 · NFR5→전 task 눈확인 항목.
- **미커버 1건 자인.** E6(409 중 Esc)·E7(권한 없는 클릭)·E8(빈 제목 저장)은 기존 경로를 그대로
  타므로 신규 코드가 없다. E7 은 T1·T3 에 테스트가 있고, **E6·E8 은 전용 테스트를 두지 않는다** —
  기존 `issue-edit-conflict.spec.ts` 동반 실행이 증인이다. 이 판단을 리뷰에서 확인받는다.
- **타입 일관성.** `handleTitleKeyDown`(T2) · `handleContentClick`(T3) · `handleEditorKeyDown`(T4) ·
  `requestCancel`(T5) — 이름 충돌 없음. `descriptionDiscardConfirm`(T5) 는 `ko.ts` 정의와 사용처가 일치.

## 리뷰 결과

### plan-design-review (2026-08-03)

**초기 평점 6/10.** 상호작용 명세(FR·엣지)는 촘촘하나 **시각 결정이 한 줄**이고, 문자열
충돌을 「구현 시 판단」으로 미뤘으며, 정본 동기화 task 가 통째로 빠져 있었다.
**보강 후 9/10** — 남은 1점은 모바일 롱프레스 선택 거동으로, 코드가 아니라 **실기기 확인**으로만
닫히므로 T3 눈확인 항목으로 이관했다.

**BLOCKER 0건 · 수정 4건 반영 · 통과 3건.**

| # | 지적 | 판정 | 조치 |
|---|---|---|---|
| **F-1** | 정본 정정 task 누락 | **결함 확정** | **Task 7 신설.** CLAUDE.md §전수 동기화 위반이고 `verify-master-plan.sh` 가 종료 4 로 머지를 차단했을 것이다 |
| **F-2** | 확인 패널 `취소` 문자열 중복을 「둘 중 하나 적용」으로 개방 | **결함 확정** | 문자열 분리(`편집 그만두기`/`계속 편집`)로 **충돌면 자체를 제거**. 구현자에게 판단을 넘기지 않는다. 중복 0 을 고정하는 테스트 추가 |
| **F-3** | `hover:bg-muted/50` 임의 알파 | **결함 확정** | DESIGN.md §7 `--bg-neutral-hover` 로 교체. 라이트 `#F1F2F4` / 다크 `#A1BDD914` 가 이미 정의돼 **두 테마 자동 대응**. focus 링도 `--border-focus` 로 |
| **F-4** | 모바일 텍스트 선택 미검토 | **결함 확정(신규 발견)** | `isCollapsed` 가드는 마우스 드래그 전제다. 롱프레스 선택이 편집 진입에 삼켜지면 **본문 복사 불가 회귀**. T3 에 모바일 실기기 확인 항목 추가 |
| F-5 | `h1` 안 `button` 이 계약 §2 를 깨나 | **통과** | heading 의 접근성 이름은 내부 텍스트에서 계산되므로 `getByRole('heading',{name})` 보존. 이름 없는 `getByRole('button')` E2E 사용처 **전수 grep 0건**이라 버튼 증가도 무해 |
| F-6 | E6·E8 전용 테스트 미배치 | **통과** | 실측 보강 — 제목 `Input` 이 `<form>` 안에 **있지 않다**(grep 0건). `Enter` 가 form submit 부작용을 만들지 않으므로 기존 저장 경로와 완전 동일하다. 자인 근거가 성립 |
| F-7 | FR9 본문 키보드 진입 | **통과** | WCAG 2.1.1 은 **동등한 키보드 경로**를 요구하지 동일 요소를 요구하지 않는다. `본문 편집` 버튼 보존으로 충족. 근거를 스펙에 명시 |

**디자인 관점 추가 관찰 2건 (수정 아님, 기록).**
- 진입 경로가 2개(버튼 + 텍스트)라 **시각 신호가 중복**된다. Jira 는 버튼이 없다. 다만 Maxi 가
  E2E 무손상·키보드 경로 보존을 이유로 확정한 사항이라 이번 범위에서 유지한다. F9·F11 이
  끝나 진입면이 안정되면 **버튼 제거를 별도 정리 PR 로** 검토할 여지가 있다.
- 빈 본문 placeholder 도 클릭 진입 대상으로 잡혀 있다(T3). 「쓸 게 없다」가 아니라 「여기를
  눌러 쓰라」는 신호가 되므로 빈 상태 설계로도 옳다.

**게이트 정책 판정.** `type == ui` 이나 **task 7개 > 3** 이라 ui 소규모 조건 미충족 —
**게이트 1 사용자 정지 유지.**
