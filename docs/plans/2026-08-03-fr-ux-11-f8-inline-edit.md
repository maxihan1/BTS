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
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`, `apps/web/eslint.config.js`, `apps/web/src/components/__tests__/button-primitive-usage.test.ts`]
- depends-on: []

**★ files 2건 확장 (2026-08-03, 구현 중 발견).** 초안은 앞의 2개만 선언했는데 **구현이 BLOCKED**
됐다. FR-UX-06 PR22 가 심은 **원시 `<button>` 금지 이중 락**(ESLint `no-restricted-syntax` +
`button-primitive-usage.test.ts` 전수 비교)이 신규 `<button>` 을 차단한다. 사전 grep 이
E2E·유닛 어서션만 훑고 **lint 규칙은 안 봤던 것**이 원인이다.

**해제가 정당한 근거 — 프로젝트 자체 판정식과 동형 선례.**
- `eslint.config.js:49` 판정식. `OUT ⟺ role="…" 보유 OR text-left 보유 OR justify-start 보유`.
  본 건은 `text-left w-full` 보유로 **OUT 확정**.
- 동형 선례 실측. `DashboardTile.tsx:147` 이 *"타일 제목 **인라인 편집 트리거**로 flex-1 text-left
  truncate 가 필요하고, Button 의 justify-center 와 충돌한다"* 사유로 `PR22 OUT — P6` 등재.
  본 건은 **이슈 제목 인라인 편집 트리거**로 완전 동형이다.
- 대안(Button 프리미티브 흡수)은 무력화 클래스 10개가 필요해 판정식과 정면 충돌 → 기각.

**따라서 2줄을 등재한다.** `eslint.config.js` P6 그룹에 `'src/routes/issues.$key.tsx',` ·
`button-primitive-usage.test.ts` 의 `EXPECTED_OUT` 에 `'routes/issues.$key.tsx::P6',`.
발생 지점 위에 `// PR22 OUT — P6 <사유>` 주석을 단다(가드가 주석 실재를 전수 비교한다).

**착수 전 확인 — 해소됨 (2026-08-03 실측).** `routes/issues.$key.test.tsx`(2119줄) 와
`routes/__tests__/issues.$key.test.tsx`(218줄) 는 **중복이 아니라 목적이 다른 두 파일**이고
**둘 다 활성**이다 — `vitest.config.ts:17` 의 exclude 가 `e2e/**`·`node_modules/**` 뿐이라
양쪽 모두 수집된다. 전자는 상세 페이지 일반 테스트, 후자는 **FR-PM-02 권한별 편집 버튼 disabled**
전용이다.

**따라서 신규 테스트는 주 파일 `routes/issues.$key.test.tsx` 에 작성한다.**
`__tests__/` 쪽은 권한 전용 파일이므로 이 PR 에서 건드리지 않는다.

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
  await userEvent.click(screen.getByRole('button', { name: '계속 편집' }))
  expect(screen.getByRole('textbox', { name: '본문 편집' })).toHaveValue(expect.stringContaining('아까운 초안'))
})

it('확인을 수락하면 편집이 닫힌다', async () => {
  render(<IssueDescription {...baseProps} />)
  await userEvent.click(screen.getByRole('button', { name: '본문 편집' }))
  await userEvent.type(screen.getByRole('textbox', { name: '본문 편집' }), '버릴 초안')
  fireEvent.keyDown(screen.getByRole('textbox', { name: '본문 편집' }), { key: 'Escape' })
  await userEvent.click(screen.getByRole('button', { name: '편집 그만두기' }))
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

## 구현 기록

### wave 1 (2026-08-03) — Task 1 · Task 3 병렬

**Task 3 (본문 클릭 진입) — PASS.** `feat: de9e08514` · `test: 5e2445b5f`.
`git show --stat` 으로 각 커밋이 **선언 파일 1개씩만** 담았고 docs 오염 0 임을 controller 가 독립 확인했다.
가드 3종(`isCollapsed`·`closest('a')`·`canEdit`)을 **하나씩 제거해 각각 1건만 빨강**임을 보인
뮤테이션 확인이 붙어 공허 가드가 아님이 증명됐다.

**Task 1 (제목 클릭 진입) — BLOCKED → 해소.** 아래 ★발견 1건 참조.

### wave 2 (2026-08-03) — Task 4

**Task 4 (본문 `Ctrl/Cmd+Enter` 저장) — PASS.** `feat: 4389eb699` · `test: 66f16ce74`.
동반 테스트 5종 + 뮤테이션 5종. 지시받지 않은 `issue-mention-autocomplete` E2E 까지 돌려
**실브라우저 증인**을 확보했다(`S2 @al → ArrowDown → Enter → @alice 삽입 (줄바꿈 없음)`).

**Task 2 (제목 `Enter` 저장 · `Esc` 취소) — PASS.** `feat: 219f15a0e` · `test: ee99545d5`.

**★ 뮤테이션 검증 성공 (완료 기준 5 충족).** `Escape` 분기의 `e.preventDefault()` **한 줄만**
제거하니 `F8-T2-4` pane 테스트가 빨강 —
`expected "vi.fn()" to not be called at all, but actually been called 1 times`.
**E1 이중 발화가 그대로 재현**됐고 나머지 4종은 초록 유지(격리 정확). 원복 후 79 passed 복귀.
가드가 공허하지 않음이 기계적으로 증명됐다.

**★ 한글 IME 를 실제로 조합해 확인.** CDP `Input.imeSetComposition` 으로 진짜 조합 상태를 만들어
확정 `Enter` 의 실제 이벤트가 `keyCode 229` **와** `isComposing: true` 를 **둘 다** 갖는 것을
관측했다(추정이 아니다). 조합 확정 시 PATCH 0건 · 조합 종료 후 진짜 `Enter` 는 PATCH 1건 —
**공허 방지 짝**까지 확인. 한국어 사용자에게 "글자를 다 치기 전에 저장되는" 사고를 막는 지점이다.

**★ 타입 함정 1건 회피.** 이 파일에 `React` 네임스페이스 import 가 없는데 `KeyboardEvent` 를
이름 그대로 들이면 **`usePaneEscapeClose:81` 이 쓰는 전역 DOM `KeyboardEvent` 를 모듈 스코프에서
가려** `document.addEventListener` 리스너 타입이 조용히 바뀐다. `KeyboardEvent as
ReactKeyboardEvent` 별칭으로 받았다(`:4` 실측 확인).

### wave 3 (2026-08-03) — Task 5

**Task 5 (본문 `Esc` 취소 + 확인) — PASS.** `feat: afb17def1` · `test: e72b71bc1`.
테스트 6+4종. 요청 5종에 더해 **멘션 팝업이 열린 `Escape`** 가드를 자발적으로 추가했다 —
`Escape` 취소가 붙은 뒤로는 자동완성을 물리려던 `Esc` 가 **취소 확인 패널을 띄우는** 새 회귀면이
생기는데 기존 Escape 테스트는 값 유지만 보므로 못 잡는다. `ko.test.ts` 에는 키 존재 3건 +
**문자열 충돌 금지 단언**까지 넣었다.

**뮤테이션 4종.** 변경분 비교를 `false` 고정 → 4건 빨강(셋이 「패널이 뜬다」를 공유 전제로 함) ·
`true` 고정 → 정확히 1건 · `계속 편집` → `취소` 로 되돌려 **F-2 충돌을 재도입** → 3건 빨강.
마지막 것이 특히 좋다 — 리뷰가 잡은 결함이 재발하면 잡힌다는 증인이다.

### ★ Task 5 가 자기 미커버를 자인했고, 검증 결과 실제 결함이었다

`IssueDescription.tsx` 의 `Escape` 분기에서 `e.preventDefault()` 를 지워도 **그 파일 43건이 전부
초록**이다. controller 가 검증한 결과 **방어는 실제로 필요**하다 —
`IssueDescription` 은 `issues.$key.tsx:799` 에서 렌더되어 **pane 안에 있고**,
`usePaneEscapeClose` 는 `document.addEventListener`(`:91`)로 전역에 달려 **본문 textarea 의
Escape 도 bubble 해서 도달**한다. 제목과 똑같은 이중 발화 위험인데 **컴포넌트 단위 테스트에는
pane 컨텍스트가 없어 구조적으로 못 잡는다.**

**→ 보완 완료. `test: 65a10d7e0`** (`issues.$key.test.tsx` +59, 프로덕션 코드 접촉 0).
라우트 테스트의 `vi.mock` **7건을 전수 확인**해 `IssueDescription` 이 목이 아님을 먼저 실측했다 —
실제 컴포넌트가 렌더되므로 Esc 경로가 실제로 탄다(E2E 로 넘길 필요 없음).
증인 `F8-T5G-1` 은 **변경분 없는 경로**로 가드를 고립시켰다 — 변경분이 있으면 확인 패널이 떠서
편집이 닫히지 않아 두 단언을 함께 세울 수 없기 때문이다(`IssueDescription.tsx:335` 실측 근거).

**뮤테이션 빨강 확인.** `IssueDescription.tsx:324` 의 `e.preventDefault()` 한 줄 제거 →
`expected "vi.fn()" to not be called at all, but actually been called 1 times`.
본문 Esc 의 이중 발화가 그대로 재현됐다. 원복 후 `issues.$key` 80 passed · `IssueDescription`
43 passed(타 담당자 스위트 무회귀).

**자기 코드의 미커버를 스스로 신고한 것이 이 wave 의 가장 좋은 처신이다** — 가짜 초록을
남기는 것보다 낫다.

### ★ 병렬 wave 에서 뮤테이션 원복 방법 — controller 지시가 위험했다

controller 는 `git checkout -- <파일>` 로 원복하라고 지시했다. **그 파일은 다른 에이전트가
동시 작업 중이었고, checkout 은 그 사이 들어온 미커밋 변경을 조용히 파괴한다.**
담당 에이전트가 위험을 인지하고 더 안전한 절차로 바꿨다.

1. 뮤테이션 **전** `git status --porcelain -- <파일>` 로 HEAD 동일(= 타 작업 없음) 확인
2. 역방향 Edit 으로 **정확히 그 한 줄만** 되돌림 — 내용이 바뀌었으면 Edit 이 **실패로 멈추지**,
   덮어쓰지 않는다
3. `git diff --stat -- <파일>` 무출력으로 바이트 단위 동일 확인

교훈 `mutation-test-requires-committed-baseline` 의 **반대 방향 위험**이다. 그 교훈은 "미커밋
상태에서 원복하면 내 작업이 날아간다"였고, 이것은 "**남의 미커밋 작업이 날아간다**"다.
병렬 wave 에서는 `git checkout --` 를 뮤테이션 원복 수단으로 쓰지 않는다.

### ★★ 가장 값진 발견 — 기존 멘션 가드가 공허했다

`mention.onKeyDown(e)` 호출을 **지웠을 때 기존 멘션 유닛 4종이 전부 통과**했다.
기존 Escape 테스트조차 잡지 못했다 — 후보 선택을 `mouseDown` 으로만 검증하기 때문이다.

즉 **「멘션 키보드 경로가 죽는 회귀」를 잡는 유닛 가드가 레포에 0개였고**, 이번에 추가한
E10 테스트가 유일한 증인이다. 계획도 plan 리뷰도 이것을 예상하지 못했다.
계열 교훈 `mock-swallowed-prop-is-invisible-to-unit-tests` 의 변종 — **이벤트 위임 체인은
호출을 지워도 단위 테스트가 조용하다.**

### wave 4 (2026-08-03) — Task 6

**Task 6 (E2E + 계약 무손상) — PASS, 그리고 결함 2건을 적발했다.**
`test: cc3f36c5b` — 신규 `inline-edit.spec.ts` 4 tests 전부 통과(e2e 스펙 141 → 142).

**계약 무손상 3종 전부 EXIT 0.** ① `keyboard-shortcuts/` diff 0 · `shortcuts` 107 passed
② 기존 E2E 4종 diff 0 ③ `backend/`·`package.json` diff 0.
전체 스위트 — `tsc` 0 · **vitest 8535 passed(542 files)** · playwright **629 passed / 5 failed / 3 skipped**.

**PRE_EXISTING 판정 방법이 모범적이었다.** main(`7ef1ca9d0`)을 **별도 임시 worktree 에 `--detach`
로 띄워** 같은 스펙을 실행했다 — 작업 트리를 오염시키지 않고 근거를 만들었고, 5건을 뭉뚱그리지
않고 4/1 로 갈라 각각 근거를 댔다(`board-swimlane-field-change` 3건 = main 에서 같은 줄·같은
메시지로 실패 · 1건 = 풀 스위트에서만 실패하는 flaky).

### ★★★ 이 PR 이 유발한 회귀 1건 — plan 리뷰 F-5 의 판정이 틀렸다

`issue-ui-regression.spec.ts:36` 이 strict mode violation 으로 깨졌다.

```
getByRole('button', { name: '취소' }) → resolved to 2 elements
  1) <button class="text-left w-full …">E2E-4-2 삭제 취소 검증용</button>   ← Task 1 이 만든 제목 버튼
  2) <button>취소</button>
```

Task 1 의 제목 인라인 편집 버튼은 **접근성 이름이 이슈 제목 전문**이고, `getByRole` 의 `name` 은
**기본이 부분일치**라 제목에 든 `취소` 가 걸린다.

**F-5 를 "통과"로 판정한 근거가 부족했다.** 나는 *"이름 없는 `getByRole('button')` 사용처 전수
grep 0건"* 을 봤는데, 실제 파손 경로는 **이름 있는 셀렉터 + 제목 문자열 부분일치**였다.
grep 범위가 한 축 모자랐던 controller 의 오판이다.

더 뼈아픈 것 — **깨진 테스트의 주석이 자기 전제를 문서화해 뒀다.**
`:35` *"다이얼로그 안 외에 다른 cancel 버튼 없으므로 단일 매칭."* 그 전제가 이번 PR 로 거짓이
됐다. **주석에 적힌 가정도 grep 대상이었어야 한다.**
`learnings.md:631`(PR #46→#47, UI 추가가 기존 E2E 전역 셀렉터를 strict mode 로 깸) **재발면**이다.

**처방.** `exact: true` 1줄 + **주석 갱신**(거짓 주석을 남기면 다음 사람이 같은 함정에 빠진다).
구현 쪽 처방(제목 버튼에 별도 `aria-label`)은 `h1` 접근성 이름을 바꿔 계약 §2 즉사 항목을
깨므로 기각. 폭발 반경은 풀 스위트 637건 중 **이 1건뿐**임이 전수 실행으로 확인됐다.

**→ 봉합 완료. `test: b4e724933`** — `issue-ui-regression` 3/3 통과. 주석이 *"이전 주석은 …
이었으나 **거짓이 됐다**"* 로 갱신돼 파손 구조(접근성 이름 = 제목 전문 · `getByRole` name 기본
부분일치 · 짧은 라벨이 제목과 충돌)를 다음 사람에게 남겼다. NFR2 보호 대상 4파일에
`issue-ui-regression` 은 포함되지 않아 계약 위반이 아니다(3종 재확인 EXIT 0).

**같은 파일 동종 위험 전수 점검 (수정 안 함, 기록만).**
`:18` 이 같은 `취소` 짧은 라벨을 `exact` 없이 쓰지만, 실행 시점에 화면이 **제목 편집 모드**라
(h1 → input 교체) 제목 진입 버튼이 DOM 에 없어 구조적으로 비충돌이다. **"제목이 없어서 안전"한
구조 의존**이라 취약하나 이번 회귀와 무관해 범위 밖으로 뒀다.
`:22`·`:49` 의 heading 셀렉터는 **반대 방향 느슨함** — strict 충돌은 없지만 부분일치라
*제목이 더 길어져도 통과*한다(가짜 그린 방향). 신규 스펙에는 `exact: true` 를 썼다.

### ★★ FR1 · S4 미충족 — 편집 진입 포커스가 0건이다

Task 6 이 소견으로 올렸고 controller 가 실측 재확인했다.
`issues.$key.tsx` · `IssueDescription.tsx` 양쪽에 **`focus()` · `autoFocus` · `setSelectionRange`
가 전부 0건**이다.

- **FR1** *"진입 시 커서는 텍스트 끝"* · **S1** *"커서가 텍스트 끝에 놓인다"* — 미충족
- **S4** *"포커스가 입력 영역에 놓인다"* — 미충족

즉 클릭해서 편집을 열어도 **바로 타이핑할 수 없고 한 번 더 클릭해야 한다.** 클릭 진입을 만든
이유가 절반 사라진 상태다.

**E2E 가 이 공백을 구조적으로 못 잡는다** — `locator.press()` 가 자동으로 포커스를 주기 때문에
테스트는 초록인데 실사용은 불편하다. **전형적인 가짜 그린이고, 유닛/E2E 어느 쪽도 증인이 아니다.**
브라우저 눈확인도 놓쳤다 — "클릭하면 편집창이 뜨는가"만 봤지 "**바로 타이핑되는가**"를 안 봤다.

→ 제목·본문 양쪽에 봉합 발주. 눈확인 항목에 「추가 클릭 없이 타이핑 가능」을 명시했다.

**→ 제목 봉합 완료. `feat: 18841a588` · `test: ac59e4d23` · `test: 1caaa2162`.**
모듈 레벨 훅 `useTitleEditFocus` 신설(`usePaneEscapeClose`·`usePaneFocusOnLoad` 관례 동일).
두 진입로가 모두 `isEditingTitle` 을 켜므로 한 곳만 두면 갈라지지 않는다.
`usePaneFocusOnLoad` 와 충돌 없음을 실측 확인 — 그쪽은 `hasFocusedRef` 로 로드 시 1회만
발화하고 끝나 시점이 겹치지 않는다.

**★ 눈확인 방법이 이 문제의 정답이었다.** 진입 직후 `page.keyboard.type('!!')` 로 **키보드만**
입력했다 — 포커스가 없으면 글자가 입력창에 안 들어간다. 라이트/다크 × 두 진입로 **4개 조합
전부** `activeElement === INPUT` · 커서 20/20 · 친 글자가 맨 뒤에 붙음 · 원문 보존(전체 선택 아님).
「편집창이 뜨는가」가 아니라 **「바로 타이핑되는가」를 직접 물은 것**이 핵심이다.

### ★ 뮤테이션이 자기 테스트 하나를 공허로 드러냈다 — 그리고 정직하게 좁혔다

| 뮤테이션 | 결과 | 판정 |
|---|---|---|
| `input.focus()` 제거 | `F8-FR1-1`·`F8-FR1-3` 빨강 | 포커스 단언 **비-공허** |
| `setSelectionRange` 제거 | **전부 초록** | **커서 단언 공허** |
| 의존성에 `editSummary` 추가 | `F8-FR1-4` 빨강 | 타이핑 중 커서 고정 계약 비-공허 |

jsdom 이 `value` 설정 시 커서를 자동으로 끝에 두어 **기대값과 우연히 일치**했다.
담당 에이전트가 숨기지 않고 테스트 이름을 `커서가 텍스트 끝에 놓인다` →
`커서가 전체 선택도 맨 앞도 아니다` 로 **좁혀 실제로 잡는 회귀만 주장**하게 하고 한계를 주석에
남겼다. **가짜 초록을 이름으로 정직하게 만든 처리다.**

**controller 판정 — `setSelectionRange` 2줄은 유지한다.**
반대 실험에서 Chromium 도 프로그램 `focus()` 시 커서를 끝에 두므로 관측상 무효과인 것은 맞다.
그럼에도 남기는 이유 — ① 스펙 FR1·S1 이 *"커서는 텍스트 끝"* 을 **명시 요구**하는데 그것을
**문서화되지 않은 브라우저 기본값에 맡기면 계약을 우연에 거는 것**이다 ② Firefox/Safari 는
프로그램 포커스 시 전체 선택 등 다른 동작을 할 수 있는데 Playwright 는 chromium 단일이라
**회귀를 잡지 못한다** ③ `CLAUDE.md §2 Simplicity First` 는 **speculative 코드**를 금지하는
것이지 명시 스펙을 충족하는 2줄을 금지하는 것이 아니다.

### wave 5 (2026-08-03) — 본문 커서 끝 통일 + Task 7

**Maxi 확정 (2026-08-03).** 본문 커서를 끝에 두면 멘션 자동완성이 잘못 깨어나는 문제에 대해
선택지 3개(멘션 훅 수정 / 현상 유지 / 제목도 맨 앞)를 올렸고 **훅 수정**으로 확정됐다.

**본문 커서 끝 — PASS.** `feat: 5fa65a3c2` · `test: 1cc605a46` · `fix: 00789f961` · `test: 26c21999a`.
훅에 `suppressNextSelect()` 를 신설해 **프로그램적 caret 이동 직전 1회만** 감지에서 제외한다.
시간 기반 억제(`setTimeout` N ms)는 타이밍 의존이라 쓰지 않았고, 사용자의 클릭·방향키
`select` 는 종전 그대로 감지된다. 플래그 잔류를 막으려 `handleChange` 에서도 버려 억제 범위를
**"다음 타건 전까지"** 로 못 박았다.

`restoreCaretAfterFrame`(`:42`)에는 억제가 **불필요**함을 실측했다 — `spliceMention` 결과가
`@alice `(후행 공백)이라 `detectActiveMention` 이 "활성 멘션 없음"으로 판정해 닫기로 수렴한다.

**뮤테이션 3종.** 억제 제거 → 2건 빨강 · `setSelectionRange` 제거 → 2건 빨강(**제목과 달리
textarea 는 jsdom 이 커서를 0 에 둬 discriminate 한다**) · blur 타이머 취소 제거 → 1건 빨강.
가드에 `selectionStart === 본문 길이` **전제를 넣은 이유**가 두 번째에서 드러난다 — 전제가 없으면
"커서가 0 이라 안 뜬 것"과 구별되지 않아 공허해진다.

### ★★★ E2E 가 선재 결함 1건을 추가로 적발했다 — 진입 포커스가 드러낸 것

첫 E2E 실행에서 `issue-mention-autocomplete S1` 이 빨강이었다. 추측하지 않고 실브라우저에서
**시간축으로 관찰**했다.

```
@al 타이핑 직후   listbox:true   options:[alice,bob,carol,dave,eve]
+150ms           listbox:false  ← 포커스·값·caret 은 그대로
```

150ms 는 `handleBlur` 의 지연 닫기 타이머와 일치한다. **진입 포커스(S4)가 생기면서 그 다음
`편집` 탭 클릭이 textarea blur 를 만들고, 거기서 예약된 닫기가 나중에 열린 드롭다운을 죽이고
있었다.** 포커스가 돌아와도 예약을 취소하는 경로가 없었다 — **이 PR 이전부터 있던 결함**이고
진입 포커스가 조건을 만들어 드러났다.

처방은 `handleChange` 에서 pending 타이머 취소 — 타이핑은 사용자가 그 입력칸으로 돌아왔다는
뜻이므로 이전 blur 의 닫기 예약은 무효다. 봉합 후 `+2000ms` 까지 유지 확인.

**"측정하니 드러났다"의 사례다.** 없던 버그를 만든 게 아니라 **잠복 조건을 실현시킨 것**이고,
E2E 가 그것을 잡았다.

### ★ 훅 단위 테스트를 만들지 않은 것이 옳았다

담당 에이전트가 훅 단위 `select` 테스트를 쓰려다 **jsdom 에서 `fireEvent.select` 가 React 의
`onSelect` 에 도달하지 않음**을 스파이로 실측했다(0회 — focus 선행·`keyUp` 병발 모두 0회).
모르고 썼다면 **"억제가 동작해서 안 뜬 것"과 "이벤트가 안 와서 안 뜬 것"을 구별 못 하는 가짜
초록**이 됐을 것이다. 작성했던 하네스를 되돌려 `use-mention-autocomplete.test.tsx` 는 **무수정**이고,
증인은 컴포넌트 테스트(진입 실경로) · E2E · 눈확인이 맡는다.

(부수 관찰) 같은 이유로 **기존 훅 테스트의 `onSelect` 경로 커버리지도 실효가 없을 가능성**이
있다. 감지는 `onChange` 로 성립하므로 기능은 무사하다. 별도 확인 대상으로만 남긴다.

### wave 5 — Task 7 정본 동기화 PASS

`docs: bce44e6e7` — **pre-commit 정상 통과**(controller 가 미커밋 문서를 먼저 커밋해 drift 원인
소멸). `verify-master-plan.sh` **종료 0** · `build-doc-index.mjs --check` **종료 0** ·
대시보드 재생성 134/139(96%).

**★ controller 가 준 측정 명령이 3건 과다 계상이었다.** 지시한 `grep -rc '^- \[x\] D'` 는
**938/32** 를 냈으나, README·verify 룰 H 가 쓰는 **정본 패턴** `'^- \[x\] D[0-9]+\.'` 는 **935/30**
이다. 느슨한 패턴이 `- [x] D단계` 같은 줄까지 센다. 담당 에이전트가 **정본 패턴을 채택**했다 —
느슨한 값을 썼으면 룰 H 대조에서 어긋났을 것이다.

| D 마커 | 전 | 후 |
|---|---|---|
| `[x]` | 935 | **940** (+5 = D1~D5) |
| `[ ]` | 30 | **25** |

**FR 수 불변 139.**

### ★★ verify 가 못 잡는 구멍 1건 — 두 목록이 서로를 검사하지 않는다

`CLAUDE.md`(935/30) · `CHANGELOG.md`(930/35)의 D 마커 수치가 실제(940/25)와 어긋나 있었는데
**`verify-master-plan.sh` 룰 E 는 두 파일의 `N FR` 표기(139)만 검사하고 D 마커 수치는 검사하지
않아 종료 0 으로 통과**했다. 계열 교훈 `two-lists-never-check-each-other` 의 새 사례다.

→ controller 가 `docs: c6e297229` 로 두 파일을 **940/25 로 동기화**했다(`CLAUDE.md` 는 이 PR 이
D 마커 5개를 바꿔 생긴 drift, `CHANGELOG.md` 는 `[Unreleased]` 라 이 PR 이 갱신 주체).
**판별식에 D 마커 수치 대조를 추가하는 것은 별도 작업**으로 남긴다 — 게이트 2 보고 대상.

### ★ 로드맵 FR-UX-10 행 선재 drift — 보고만, 고치지 않음

`jira-parity-roadmap.md:38` 의 FR-UX-10 행이 아직 `⬜ 미착수` 인데 F10 은 **#336 으로 머지됐고**
(`7100340d3`) `personalization.md §4.8` 은 `F10 완료 (PR #336)` 로 적고 있다. Tier 2 표의 F10 행에도
완료 표기가 없다. controller 가 *"표기 관례는 FR-UX-10 행을 따르라"* 고 지시했으나 **그 행 자체가
정본과 어긋나 참고 대상이 되지 못했다.**

**고치지 않았다** — 다른 FR 소관이고 `CLAUDE.md §3 Surgical Changes`("모든 변경 줄은 사용자
요청에 직접 추적돼야 한다")에 걸린다. 게이트 2 보고 대상.

### ★ 구현 중 발견 3건 — 계획이 놓친 것

| # | 발견 | 조치 |
|---|---|---|
| **I-1** | **PR22 원시 `<button>` 금지 이중 락**에 막혀 Task 1 이 커밋 불가. 계약 §5 사전 grep 이 E2E·유닛 어서션만 훑고 **lint 규칙은 안 봤다** | Task 1 files 를 4개로 확장하고 PR22 OUT 2줄 등재. 판정식(`text-left` 보유 → OUT)과 동형 선례(`DashboardTile.tsx:147` 타일 제목 인라인 편집 트리거) 둘 다 controller 가 실측 검증 |
| **I-2** | 스펙 E3 이 "링크나 **멘션**"을 배제 대상으로 적었으나 **멘션은 `<a>` 가 아니다** — 백엔드 `MentionExtension.kt:121-123` 이 `span.mention` 으로 렌더하고 클릭 동작이 없다 | 스펙 E3 문구 정정. 가드는 `closest('a')` 하나로 유지 — 멘션 배제는 **공허**하고 막으면 죽은 영역이 생긴다 |
| **I-3** | 테스트 파일 2개가 중복이 아니라 **역할이 다른 활성 파일 2개**였다(일반 2119줄 / FR-PM-02 권한 전용 218줄) | 신규 테스트는 주 파일에만. `__tests__/` 는 미수정 |

### 미해결로 게이트 2 에 올리는 것 2건

- **M-1. `--no-verify` 우회 1회 (Task 3).** pre-commit 의 `doc-index --check` 가 **controller 가
  동시에 수정 중이던 plan/spec** 때문에 drift 를 보고해 커밋이 막혔다. 우회는 했으나 **커밋에
  docs 0건**을 `git show --stat` 으로 확인했고, 빠진 lint 는 동일한
  `eslint --max-warnings 0` + `tsc --noEmit` 로 수동 대체됐다. 인덱스 재생성은 Task 7 소관.
- **M-2. 모바일 롱프레스 선택(리뷰 F-4) 판정 불가.** 터치 에뮬레이션(390×844, CDP 900ms)에서
  **네이티브 선택 제스처가 재현되지 않아**(`selectedText: ""`) "가드가 삼켰다"와 "선택이 애초에
  안 일어났다"를 구분할 수 없었다. plan 이 애초에 **실기기 확인**으로 적어 둔 항목이라 미해결로 남긴다.
- **M-3. 저장해도 본문 편집창이 닫히지 않는다 — 선재 동작, 이번 PR 범위 밖.**
  `IssueDescription` 의 `handleSave` 가 `setIsEditing(false)` 를 하지 않고 부모도 닫지 않는다.
  기존 E2E 가 이미 못박아 뒀다 — `issue-body-meta.spec.ts:57` *"구현상 저장 성공 후 편집 모드가
  자동으로 닫히지 않으므로 취소로 ReadMode 전환."* 즉 `Ctrl+Enter` 는 `저장` 버튼과 **완전히 동일**
  하게 동작하고 FR5 는 충족된다. 다만 Task 5(`Esc` 취소)가 들어가면 **「취소하면 닫히고 저장하면
  안 닫히는」 비대칭**이 사용자에게 보인다. 스펙에 규정이 없고 인접 코드 개선 금지 규율에 걸려
  **손대지 않았다** — Maxi 판단 대상.
- **M-4. `isSaving` 중복 제출 가드에 유닛 테스트 없음.** `isSaving=true` 면 textarea 가 `disabled`
  라 실브라우저에서 keydown 이 안 가고, `fireEvent` 로 억지로 쏘면 **실제와 다른 경로를 검증하는
  가짜 테스트**가 된다. 가짜 초록을 만드느니 안 만드는 쪽을 택했고, 브라우저에서 PATCH **정확히
  1건**임을 확인해 대체했다. `disabled` 뒤의 이중 방어라 위험은 낮다.
- **M-5. `취소` 버튼은 여전히 묻지 않고 즉시 버린다 — 확인은 `Esc` 에만 붙었다.**
  "키보드로 나가면 묻고 마우스로 나가면 안 묻는" 비대칭이다. 의도적으로 손대지 않았다 —
  ① 스펙 FR6 이 확인 범위를 **`Esc` 로 한정**했고 ② `issue-body-meta.spec.ts` 가 저장 후
  `취소` 를 눌러 읽기 모드로 나가는데 여기에 확인을 붙이면 재조회 타이밍에 따라 패널이 떠
  **그 E2E 가 깨진다**(NFR2 위반). 일관성을 맞추려면 별도 판단이 필요하다.
- (부수 관찰) 저장 직후 `Esc` 는 확인 없이 바로 닫힌다 — 재조회로 원본이 초안과 같아져
  `draftMarkdown === initialMarkdown` 이 되기 때문이다. 지킬 것이 없으니 묻지 않는 셈이라
  논리적으로 일관되나, M-3 과 합쳐지면 **"저장했는데 창이 안 닫히고 한 번 더 `Esc` 를 눌러야
  끝나는" 2단 조작**이 남는다.
- (부수 관찰, 범위 밖) 모바일 폭에서 사이드바가 본문을 크게 밀어내는 반응형 문제가 보였으나
  **이번 변경과 무관한 선재 상태**라 손대지 않았다.

### 최종 검증 (2026-08-03, 구현 완료 후 controller 직접 실행)

| 항목 | 결과 |
|---|---|
| `tsc --noEmit` | **OK** |
| `vitest run` | **542 files / 8546 tests passed** (착수 전 8535 → +11) |
| `playwright test` (전체) | **629 passed** / 5 failed / 3 skipped (13.1m) |
| `pnpm test:workflow` (CI 동일 목록) | **92/92 pass** · fail 0 |
| `eslint . --max-warnings 0` | **0 errors** / 9 warnings — **전부 PRE_EXISTING** |
| `verify-master-plan.sh` | **종료 0** |
| 계약 무손상 3종 | **EXIT 0 · 0 · 0** |
| 커밋 수 | **28** |

**E2E 실패 5건 판정 — 전부 이 PR 무관.**
- `board-swimlane-field-change` S1·S2·S7 — **PRE_EXISTING**. Task 6 이 main(`7ef1ca9d0`)을 별도
  임시 worktree 에 `--detach` 로 띄워 **같은 줄·같은 메시지**로 실패함을 실측했다
- 〃 S3 · `workflow-scheme-assignment` S9 — **flaky**. 풀 스위트에서만 실패하고 **단독 재실행은
  통과**(`workflow-scheme-assignment` 2 passed 실측). 결정적 증거는 **실행마다 실패 대상이 바뀐다는
  것** — Task 6 실행 때는 `issue-ui-regression` 이 실패하고 `workflow-scheme` 은 통과했는데, 이번엔
  정반대다. 계열 교훈 `flaky-determination-needs-repeat-not-single-contrast` 의 *"실패 대상이
  바뀌면 그게 flaky 서명"* 그대로다

**lint 경고 9건 전수 확인.** 발생 파일은 `public/mockServiceWorker.js` ·
`components/settings/SlackResultBanner.tsx` · `features/calendar/WeekGrid.tsx` **3개뿐이고
이 PR 이 건드린 파일은 0개**다. 전부 `react-refresh/only-export-components` 로, 메모리 #286 의
*"전부 PRE_EXISTING SlackResultBanner/WeekGrid"* 기록과 일치한다. **신규 경고 0.**

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

### 코드리뷰 (2026-08-03) — 독립 2건, 둘 다 **CONCERNS · BLOCKER 0**

**두 리뷰가 독립적으로 같은 결함 3건을 지목했다.** 겹친 것이 진짜 문제라는 신호다.

| 지적 | 리뷰 A(절대 규칙) | 리뷰 B(구조·안전성) | 조치 |
|---|---|---|---|
| 확인 패널 잔류 (저장·타이핑·탭 전환) | I-2 | **C-1** (임시 테스트로 2/2 재현) | ✅ 파생값으로 근본 수정 |
| `Esc` 우회 + 두 번째 `Esc` 죽은 키 | I-2(c) | **C-3** | ✅ 패널에 `Escape`→`계속 편집` 매핑 + 포커스 이동 |
| 편집 종료 후 포커스 유실 (WCAG 2.4.3) | S-4 | **C-5** | ✅ 진입면으로 복귀 |
| **제목에 D-2 가드 없음 — 본문과 비대칭** | — | **C-2** | ✅ 본문과 동일 가드 |
| 확인 패널 스크린리더 무음 | — | **C-4** | ✅ `role="alert"` |
| **도달 불가 공허 가드** (`if (isSaving) return`) | **I-1** | — | ✅ 남기고 주석 정정(근거 3가지) |
| i18n 충돌 가드가 완전일치만 검사 | **S-3** | — | ✅ 양방향 `not.toContain` |
| 낡은 개수 리터럴 주석 | **S-1** | — | ✅ 제거 |
| 주석 3건이 사실과 다름 | S-6·I-1 | — | ✅ 정정 |

**절대 규칙 19개 — 위반 0건.** 추가 라인 전수 grep 에서 `any`·`!!`·빈 catch·`console.log`·PoC
어휘 0건. `package.json` diff 0 실측. Enter 저장이 기존 `handleEditSave` 를 그대로 타
**OCC 를 승계**한 것을 *"새 저장 경로를 만들지 않은 것이 정답"* 으로 평가받았다.

### ★★★ 리뷰 봉합 중에 눈확인이 잡은 회귀 — 제목 복사가 죽어 있었다

Task 1 이 제목을 `<button>` 으로 감싼 **시점부터 사용자가 제목을 드래그해 복사할 수 없었다.**

| 실측 (Chromium) | 결과 |
|---|---|
| `user-select` **계산값** | `"auto"` ← **코드·DevTools 로는 정상으로 보인다** |
| 실제 드래그 선택 | `""` **길이 0** |
| `select-text` 주입 후 | `"첫 번째 이슈 — 로"` 길이 11 |
| 그 상태에서 드래그 직후 클릭 | 편집 **열림 false** ← 가드가 그제서야 작동 |

원인은 CSS UI 규격 — **`<button>` 안에서 `user-select: auto` 는 `none` 으로 해석된다.**
계산값이 `"auto"` 로 보고돼 **코드만 읽거나 DevTools 만 봐서는 정상으로 오인**한다.

**2차 영향이 더 중요하다.** 선택이 애초에 생기지 않으니 리뷰 지적으로 방금 넣은 C-2 가드
(`Selection.isCollapsed`)는 **실사용에서 영원히 발동 못 하는 죽은 코드**였다. C-2 만 넣고
끝냈으면 **가짜 봉합**이 될 뻔했다 — 리뷰가 시킨 일을 했는데 결과가 0 인 상태.

봉합 `fix: 70ad11a13`(`select-text`). 동반 가드는 **클래스 문자열 잔존만** 재고(jsdom 은
Tailwind 를 적용하지 않아 `user-select` 계산값을 검증 못 한다) 테스트 이름에
*"복사 가능성 대리 지표"* 라고 한계를 명시했다. **실제 동작의 증인은 브라우저 눈확인이다.**

### ★ 담당 에이전트들의 판단 3건 — 전부 채택

1. **두 번째 `Esc` 를 「폐기」가 아니라 「패널 닫기」로 매핑.** 폐기로 두면 `Esc` 두 번에 초안이
   날아가 **우리가 피하려던 Jira 결함(JRACLOUD-41814) 그 자체**가 된다.
2. **`role="alertdialog"` 를 쓰지 않고 `role="alert"` 만.** 포커스 트랩 등 모달 계약을 구현하지
   않은 채 그 role 을 붙이면 스크린리더 사용자에게 **틀린 약속**이라 없느니만 못하다.
3. **공허 가드를 지우지 않고 남김.** 막는 대상이 **PATCH 중복 발행**(되돌리기 어려운 쓰기 사고)
   이고, 1차 방어인 `disabled` 는 UI 요구로 언제든 제거될 수 있다. 지적의 본질은 "죽은 코드"가
   아니라 **주석이 보호 주체를 잘못 지목한 것**이었고 그것을 정정했다.

### E2E 보강 — `편집 그만두기` 경로 (`test: f092a8116`)

리뷰가 *"`계속 편집` 만 검증하고 `편집 그만두기` 는 없다"* 를 지적했다. 보강 설계가 좋다.

- **원본을 비워 두지 않았다.** 빈 본문에서 시작하면 「초안 버림」과 「본문 통째 소실」이 **둘 다
  placeholder** 라 화면상 같다. Given 에서 원본을 먼저 저장해 **버림=원본 / 저장=초안 /
  소실=placeholder** 세 갈래가 서로 다른 화면이 되게 했다.
- **PATCH 카운터가 화면 단언의 사각을 메운다.** 화면 값만 보면 *"실제로는 저장됐는데 재조회를
  안 해서 옛 값이 보이는"* 경우를 못 거른다. 요청 0건이면 **아무것도 영속되지 않았다**가 확정된다.
- 보너스로 **pane 확인 패널 위 `Esc`** 실브라우저 증인까지 확보(6 passed).

**정직 보고 1건.** 신규 E2E 2건은 뮤테이션 미실시다 — `src/` 수정 금지 + 동시 작업 위험 때문.
비-공허 근거는 코드 경로 독해와 유닛 증인 대조로 대신했다.
