# FR-UX-10 F11 — 이슈 상세 액션 단축키 8종

> slug: fr-ux-10-f11-detail-action-shortcuts
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-04

## Brief

**사용자 원문.** "fr-ux-10 남은 작업 진행해줘"

**FR ID.** FR-UX-10 (컨텍스트 의존 단축키) — 잔여 **F11 1건**.
정본 `docs/plan/product/personalization.md §4.8`. 선행이던 §4.9 FR-UX-11 은
F8(#337) · F9(#338) 로 2026-08-04 완주해 **차단 요인이 없다**.

**범위.** 이슈 상세 화면의 액션 단축키 **8종** — `a`/`i`/`m`/`e`/`l`/`s`/`w`/`.`
(`s` 는 이연↔승계 대사표에서 복원된 즐겨찾기 토글).
소비처 정본 명시 — `issues.$key.tsx` · `IssueMetaPanel.tsx` · `WatchersSection.tsx` ·
`CommentSection.tsx` · `api/favorites.ts`.

**🛑 불변 계약.** `shortcuts.ts` 의 `SHORTCUTS` 를 건드리지 않는다.
성공 판정식 = `shortcuts.test.ts:121` `toHaveLength(5)` **무수정 green** 유지
(F10 #336 은 `shortcuts.ts`·`shortcuts.test.ts` **git diff 0** 으로 판정했다 — 같은 기준 승계).

**완료 조건.** `personalization.md §4.8` D6/D7 을 `[x]` 로 닫는다 → FR-UX-10 완주.

### classify 정정 (재발 1건)

`scripts/workflow/classify-task.ts` 가 `type=backend` · `agent=backend-engineer` ·
`primary_bc=issue-tracking` · `slug=fr-ux-10-f11-8` 로 오분류했다. **F9(#338)에서 이미
관측된 재발**이다. 정본 근거로 정정 —

| 항목 | 분류기 출력 | 정정값 | 근거 |
|---|---|---|---|
| type | `backend` | **`ui`** | 선행 F10 #336 · F8 #337 · F9 #338 전량 백엔드 0줄 |
| agent | `backend-engineer` | **`frontend-engineer`** | 소비처 5파일 전부 `apps/web` |
| primary_bc | `issue-tracking` | **`personalization`**(논리) / `apps/web`(물리) | ADR §D2 논리 ≠ 물리 |
| slug | `fr-ux-10-f11-8` | **`fr-ux-10-f11-detail-action-shortcuts`** | F10·F8·F9 slug 관례 |

후속 항목 후보 — 분류기가 FR 정본을 참조하지 않아 **3회 연속 오분류**했다.

## 도메인 정리

**판정 — 스킵** (`type == ui` 기존 화면 수정, Maxi 확정 2026-08-03). 신규 도메인 개념 0.

- **BC.** `personalization`(논리) / `apps/web`(물리) — ADR §D2 논리 ≠ 물리
- **영향 엔티티.** 전량 기존 — `Issue` · `IssueComment` · `Favorite`(notification BC) ·
  `Watcher`. 신규 엔티티 0
- **새 용어.** 0. `glossary.md` 에 단축키/키맵/즐겨찾기/관심 항목이 **없고**(grep 0건)
  F10 도 추가하지 않았다. F11 이 새로 만드는 개념도 없다
- **새 라우트.** 0 (기존 `issues.$key.tsx` 에 얹는다)
- **기존 결정 충돌.** 없음. F11 은 F10 ADR 의 **소비자**다

### 선행 ADR 이 이미 규정한 것 (F11 이 지켜야 할 제약)

[decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md](../decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md)

| 조항 | F11 에 걸리는 내용 |
|---|---|
| **D-1** | 컨텍스트 키 = 영속 0 · 프론트 전용. `user_keymap` 에 넣지 않는다 |
| **D-2** | 새 `keydown` 리스너를 만들지 않는다 — 기존 파이프라인에 레이어만 추가 |
| **D-4** | 🛑 **예약 목록은 `CONTEXT_SHORTCUTS` 에서 파생한다.** ADR 이 *"하드코딩하면 F11 이 상세 액션 8종을 추가할 때 이 가드만 뒤처져 조용히 뚫린다"* 고 **F11 을 지목해** 경고했다. **착수 시 파생 여부를 실측**한다 (계열 `two-lists-never-check-each-other`) |
| **D-5-a** | 콜백이 아니라 **등록**을 끊는다 — `useContextShortcuts(context, handlers, enabled)` |
| **D-5-b** | 모달 열림 중 차단. 상세 화면의 모달 목록을 전수로 셀 것 |
| **D-5-c** | 판별이 `{ layer, action }` 을 함께 반환. 액션 종류로 레이어를 **재추론하지 않는다** |
| **D-5-d** | 도움말은 실효 키맵을 표기. 컨텍스트 키는 v1 고정이라 영향 없음 |

### ADR 이 스펙에 남긴 숙제 (F11 이 상속)

*"`## Jira 대조` 기록 부재"* — 계약 §1 이 4단계 대조와 그 기록을 요구하는데 F10 의 키 선정은
**로드맵에 키만 있고 근거 기록이 없었다**. F11 의 8종(`a`/`i`/`m`/`e`/`l`/`s`/`w`/`.`)도 같은
상태이므로 **스펙 단계에서 키별 근거를 실측·기록**한다. 특히 `s` 는 이연↔승계 대사표에서
복원된 항목이라 지라 대응이 아예 없다(BTS 고유 배치).

**신규 ADR.** 스펙 단계 결과에 따라 판단 (현재는 불필요 추정 — F10 ADR 의 연장이다).

## 스펙

전체 스펙. [docs/specs/2026-08-04-fr-ux-10-f11-detail-action-shortcuts.md](../specs/2026-08-04-fr-ux-10-f11-detail-action-shortcuts.md)

**핵심 3줄.**
- 이슈 상세에서 키 하나로 담당자(`a`) · 나에게 할당(`i`) · 댓글(`m`) · 제목 편집(`e`) ·
  라벨(`l`) · 즐겨찾기(`s`) · 관심(`w`) 을 조작하고, `.` 은 명령 팔레트를 연다
- **8종 전부 기존 UI 를 소비**한다 — 신규 컴포넌트 0 · 백엔드 0 · 마이그레이션 0
- 새 레이어 `issue-detail` 을 얹되 **와이드 split view 에서 F10 의 `j`/`k` 가 계속 살아야** 한다

### Maxi 확정 1건 (2026-08-04)

**`.` = 팔레트 여는 별칭.** Jira `.` 은 빠른 작업 · 사이트 탐색 · 설정 3구역 드롭다운인데
(Maxi 실사용 증언 — 두 공식 문서 모두 미기재였다) BTS 팔레트가 갖춘 것은 사이트 탐색뿐이다.
「빠른 작업」 구역 신설은 제외하고 여는 경로만 늘린다. 팔레트 내용을 채우는 일은 §4.10
FR-UX-12(검색 진입)가 이미 예약하고 있다.

### 착수 전 실측이 뒤집은 정본 2건

1. **`s` 「지라 대응 없음」이 부정확** — Jira 에 `s` 는 있고 **검색 조건 공유**다. 결론(이슈
   상세에 대응 없음)은 같지만 근거가 「없음」이 아니라 **「글자만 같은 세 번째 항목」** 이다
2. **즐겨찾기 UI 가 이미 있다** — 정본은 REST 3매핑만 실측하고 *"기능이 완비"* 라고 썼는데
   `IssueMetaPanel.tsx:322` 에 `FavoriteButton` 이 이미 배치돼 있다(FR-UX-02). `s` 는 새
   버튼이 아니라 **기존 버튼의 동작을 재사용**한다

### 착수 전 실측이 찾은 설계 함정 1건

**와이드 split view 는 목록 + 상세가 동시 마운트**다(`issues.index.tsx:28`). 현재
`resolveActiveContext()` 는 **가장 좁은 레이어 하나만** 활성으로 고르므로, `issue-detail` 을
`issue-list` 보다 좁게 두면 **F10 이 만든 `j`/`k` 가 죽는다.** 폴백 순서를
`['issue-detail', 'issue-list', 'app-shell']` 로 열되, 전체화면 상세처럼 **등록되지 않은
레이어는 폴백에서 빠져야** 한다(E5 — 판별만 성공하고 `preventDefault` 로 끝나는 상태 금지).

## Brainstorming Check

✅ 통과 (ui 경량 경로 — `## Jira 대조` + 즉사 계약 §2 교차가 대체). Maxi 결정 gap 1건은
착수 전 해소. 확신도 낮은 항목 `e` 1건은 스펙에 그대로 기록했다.

## Plan

**아키텍처 한 줄.** F10 이 만든 레지스트리·파이프라인에 **레이어 하나(`issue-detail`)와 액션
8종을 얹는다**. 새 리스너·새 컴포넌트·백엔드 변경은 0 이고, 기존 컨트롤에 포커스를 옮기거나
기존 뮤테이션을 호출하는 것이 전부다.

**선행 실측 3건** (이 plan 이 의존하는 사실).
- 파이프라인 폴백은 `useKeyboardShortcuts.ts:177-183` — `resolveContextKeydown(e.key,
  resolveActiveContext())` → `null` 이면 return → 아니면 `preventDefault` → `dispatchContextAction`
- 팔레트 상태는 `__root.tsx:16` 이 `useState` 로 소유해 **`ShellLayout` 에서 손댈 수 없다**
- 필드 권한 헬퍼 `isFieldHidden(key, restrictedFields)` · `isFieldDisabled(key, canEdit,
  noneditableFields)` 가 `IssueMetaPanel.tsx:126,142` 에 이미 export 돼 있다

### Task 1. 레이어 확장 + 등록 인지 판별 (E5 봉합)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/keyboard-shortcuts/context-shortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/context-shortcuts.test.ts`, `apps/web/src/components/keyboard-shortcuts/useContextShortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/useContextShortcuts.test.tsx`, `apps/web/src/components/keyboard-shortcuts/useKeyboardShortcuts.ts`]
- depends-on: []

**왜 이 task 가 먼저인가.** 8종을 먼저 등재하면 **F10 의 `j`/`k` 가 죽은 상태로 커밋된다**.
판별 규칙을 먼저 고쳐 놓아야 그 위에 안전하게 키를 얹는다.

**RED** — `context-shortcuts.test.ts` 에 추가.

```ts
describe('resolveContextKeydown — 등록 인지 폴백 (E5)', () => {
  it('상세가 활성이어도 목록이 등록돼 있으면 j 가 목록 레이어로 판별된다 (S9 와이드 split)', () => {
    const registered = new Set<ShortcutContext>(['issue-detail', 'issue-list', 'app-shell'])
    expect(resolveContextKeydown('j', 'issue-detail', registered)).toEqual({
      layer: 'issue-list',
      action: { kind: 'cursor-move', delta: 1 },
    })
  })

  it('목록이 등록돼 있지 않으면 j 는 판별되지 않는다 (E5 — 전체화면 상세)', () => {
    const registered = new Set<ShortcutContext>(['issue-detail', 'app-shell'])
    expect(resolveContextKeydown('j', 'issue-detail', registered)).toBeNull()
  })

  it('등록되지 않은 app-shell 로는 폴백하지 않는다', () => {
    const registered = new Set<ShortcutContext>(['issue-list'])
    expect(resolveContextKeydown('[', 'issue-list', registered)).toBeNull()
  })
})
```

`useContextShortcuts.test.tsx` 에 추가.

```ts
it('등록된 컨텍스트 집합을 그대로 돌려준다', () => {
  useContextShortcutsStore.getState().register('issue-detail', {})
  useContextShortcutsStore.getState().register('app-shell', {})
  expect(getRegisteredContexts()).toEqual(new Set(['issue-detail', 'app-shell']))
})
```

**실패 메시지 (예상).** `resolveContextKeydown` 이 인자 2개만 받음 (TS2554) ·
`getRegisteredContexts` 없음 (TS2305) · `'issue-detail'` 이 `ShortcutContext` 에 없음 (TS2345).

**GREEN**.

`context-shortcuts.ts` — 유니온·폴백표·판별 함수 3곳.

```ts
export type ShortcutContext = 'issue-detail' | 'issue-list' | 'app-shell'

const CONTEXT_LAYERS: Record<ShortcutContext, readonly ShortcutContext[]> = {
  // ★상세가 활성이어도 목록 항법이 살아 있어야 한다. 와이드 split view 는 목록과
  // 상세가 **동시 마운트**라(`issues.index.tsx:28`), 상세를 좁다는 이유로 단독
  // 활성으로 두면 F10 이 만든 `j`/`k` 가 그 화면에서만 죽는다(S9).
  'issue-detail': ['issue-detail', 'issue-list', 'app-shell'],
  'issue-list': ['issue-list', 'app-shell'],
  'app-shell': ['app-shell'],
}

export function resolveContextKeydown(
  key: string,
  active: ShortcutContext,
  registered: ReadonlySet<ShortcutContext>,
): ContextShortcutHit {
  for (const layer of CONTEXT_LAYERS[active]) {
    // ★등록되지 않은 레이어는 건너뛴다. 정적 폴백표만 보면 전체화면 상세에서 `j` 가
    // 판별에 성공해 `preventDefault` 까지 한 뒤 dispatch 에서 핸들러가 없어 아무 일도
    // 일어나지 않는다 — 브라우저 기본 동작만 사라지는 ADR D-5-a 의 그 형태다(E5).
    if (!registered.has(layer)) continue
    const found = CONTEXT_SHORTCUTS.find(
      (shortcut) => shortcut.context === layer && shortcut.key === key,
    )
    if (found) return { layer: found.context, action: found.action }
  }
  return null
}
```

`useContextShortcuts.ts` — 우선순위 항목 + 등록 집합 노출.

```ts
const CONTEXT_PRIORITY: Record<ShortcutContext, number> = {
  'issue-detail': 0,
  'issue-list': 1,
  'app-shell': 2,
}

/**
 * 현재 등록된 컨텍스트 집합. 판별이 정적 폴백표를 그대로 믿지 않게 하는 입력이다(E5).
 */
export function getRegisteredContexts(): ReadonlySet<ShortcutContext> {
  return new Set(
    Object.keys(useContextShortcutsStore.getState().handlers) as ShortcutContext[],
  )
}
```

`useKeyboardShortcuts.ts:177` — 호출부 한 줄.

```ts
const contextHit = resolveContextKeydown(e.key, resolveActiveContext(), getRegisteredContexts())
```

**REFACTOR**. `resolveActiveContext` 와 `getRegisteredContexts` 가 같은 스토어를 두 번 읽는다.
한 번만 읽어 둘 다 계산하는 형태로 정리하되 **호출부 시그니처는 유지**한다(테스트 무손상).

**검증**.
- `pnpm --filter @bts/web test -- context-shortcuts useContextShortcuts useKeyboardShortcuts`
- 동반 E2E. `context-shortcuts.spec.ts`(F10 8 시나리오) · `keyboard-shortcuts.spec.ts`(전역 5종)
- 눈확인. 아직 없음 (순수 로직 task)
- **판정식.** `git diff --stat apps/web/src/components/keyboard-shortcuts/shortcuts.ts
  apps/web/src/components/keyboard-shortcuts/shortcuts.test.ts` → **출력 0줄**

### Task 2. 액션 8종 + 레지스트리 등재 + dispatch

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/keyboard-shortcuts/context-shortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/context-shortcuts.test.ts`, `apps/web/src/components/keyboard-shortcuts/useContextShortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/useContextShortcuts.test.tsx`]
- depends-on: [1]

**RED** — `context-shortcuts.test.ts` 의 기존 2단언을 **교체**(F10 의 5종 단언이 13종으로).

```ts
it('13종(F10 5 + F11 8)이 순서대로 등록돼 있다', () => {
  expect(CONTEXT_SHORTCUTS).toHaveLength(13)
  expect(CONTEXT_SHORTCUTS.map((s) => s.key)).toEqual([
    'j', 'k', 'o', 't', '[', 'a', 'i', 'm', 'e', 'l', 's', 'w', '.',
  ])
})

it('상세 액션 7종은 issue-detail 레이어이고 팔레트만 app-shell 이다', () => {
  const layerOf = (key: string) => CONTEXT_SHORTCUTS.find((s) => s.key === key)?.context
  for (const key of ['a', 'i', 'm', 'e', 'l', 's', 'w']) {
    expect(layerOf(key)).toBe('issue-detail')
  }
  // `.` 은 팔레트를 여는데 팔레트는 전역 기능이다 — 목록에서도 눌러야 열린다.
  expect(layerOf('.')).toBe('app-shell')
})
```

`useContextShortcuts.test.tsx` 에 dispatch 8종 (한 예시, 나머지 7종 동형).

```ts
it('assign-to-me 를 상세 레이어 핸들러로 흘린다', () => {
  const onAssignToMe = vi.fn()
  useContextShortcutsStore.getState().register('issue-detail', { onAssignToMe })
  dispatchContextAction({ layer: 'issue-detail', action: { kind: 'assign-to-me' } })
  expect(onAssignToMe).toHaveBeenCalledTimes(1)
})
```

**실패 메시지 (예상).** `toHaveLength(13)` → received 5 · `kind: 'assign-to-me'` 가
`ContextShortcutAction` 에 없음 (TS2322).

**GREEN**.

`context-shortcuts.ts` — 액션 유니온 8종 추가.

```ts
export type ContextShortcutAction =
  | { kind: 'cursor-move'; delta: 1 | -1 }
  | { kind: 'open-current' }
  | { kind: 'toggle-detail-pane' }
  | { kind: 'toggle-sidebar' }
  | { kind: 'focus-assignee' }        // a
  | { kind: 'assign-to-me' }          // i
  | { kind: 'focus-comment' }         // m
  | { kind: 'edit-title' }            // e
  | { kind: 'focus-labels' }          // l
  | { kind: 'toggle-favorite' }       // s
  | { kind: 'toggle-watch' }          // w
  | { kind: 'open-command-palette' }  // .
  | { kind: 'none' }
```

`CONTEXT_SHORTCUTS` 배열 끝에 8항목 추가.

```ts
  { key: 'a', context: 'issue-detail', description: '담당자 지정', action: { kind: 'focus-assignee' } },
  { key: 'i', context: 'issue-detail', description: '나에게 할당 / 해제', action: { kind: 'assign-to-me' } },
  { key: 'm', context: 'issue-detail', description: '댓글 쓰기', action: { kind: 'focus-comment' } },
  { key: 'e', context: 'issue-detail', description: '제목 편집', action: { kind: 'edit-title' } },
  { key: 'l', context: 'issue-detail', description: '라벨 편집', action: { kind: 'focus-labels' } },
  { key: 's', context: 'issue-detail', description: '즐겨찾기 켜기/끄기', action: { kind: 'toggle-favorite' } },
  { key: 'w', context: 'issue-detail', description: '관심 켜기/끄기', action: { kind: 'toggle-watch' } },
  { key: '.', context: 'app-shell', description: '명령 팔레트 열기', action: { kind: 'open-command-palette' } },
```

`useContextShortcuts.ts` — 핸들러 인터페이스 8종 + switch 8 case.

```ts
export interface ContextShortcutHandlers {
  readonly onCursorMove?: (delta: 1 | -1) => void
  readonly onOpenCurrent?: () => void
  readonly onToggleDetailPane?: () => void
  readonly onToggleSidebar?: () => void
  readonly onFocusAssignee?: () => void
  readonly onAssignToMe?: () => void
  readonly onFocusComment?: () => void
  readonly onEditTitle?: () => void
  readonly onFocusLabels?: () => void
  readonly onToggleFavorite?: () => void
  readonly onToggleWatch?: () => void
  readonly onOpenCommandPalette?: () => void
}
```

`dispatchContextAction` 에 8 case 추가(`exhaustive: never` 가드가 누락을 컴파일 에러로 잡는다).
`useContextShortcuts` 의 register 래퍼에도 8종을 미러링한다 — **여기를 빠뜨리면 ref 미러링을
통과하지 못해 핸들러가 영영 호출되지 않는다**(F10 이 4종을 명시 나열한 것과 같은 이유).

**REFACTOR**. 래퍼 미러링이 12줄 반복이 된다. 키 목록에서 파생하는 형태로 줄이되
**타입 안전성을 잃지 않는 선까지만** 한다.

**검증**.
- `pnpm --filter @bts/web test -- context-shortcuts useContextShortcuts ShortcutsHelpDialog KeymapForm`
- 동반 E2E. `context-shortcuts.spec.ts` · `keyboard-shortcuts.spec.ts`
- 눈확인. `?` 도움말 모달에 「어디서나」 그룹에 `.` 이 나타나는지 (라이트/다크)
- **판정식.** `shortcuts.ts` · `shortcuts.test.ts` git diff 0

### Task 3. 팔레트 스토어 전환 + `.` 배선

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/useCommandPalette.ts`, `apps/web/src/components/command-palette/useCommandPalette.test.tsx`, `apps/web/src/components/layout/ShellLayout.tsx`, `apps/web/src/components/layout/ShellLayout.test.tsx`]
- depends-on: [2]

**왜 스토어 전환이 필요한가.** `.` 은 `app-shell` 레이어이고 그 등록 지점은 `ShellLayout:74`
인데, 팔레트 열림 상태는 `__root.tsx:16` 의 `useState` 가 소유해 **ShellLayout 에서 닿지
않는다**. `[` 가 쓰는 `use-sidebar-collapsed.ts`(zustand + 셀렉터) 패턴으로 맞춘다.
**훅의 반환 시그니처 `{ open, setOpen }` 은 그대로 두어** `__root.tsx` 와 기존 테스트를
건드리지 않는다.

**RED** — `useCommandPalette.test.tsx` 에 추가.

```ts
it('스토어로 연 팔레트가 훅 반환값에도 반영된다', () => {
  const { result } = renderHook(() => useCommandPalette(true))
  expect(result.current.open).toBe(false)
  act(() => { useCommandPaletteStore.getState().setOpen(true) })
  expect(result.current.open).toBe(true)
})
```

`ShellLayout.test.tsx` 에 추가.

```ts
it('. 키 액션이 팔레트를 연다', () => {
  renderShell({ isAuthenticated: true })
  dispatchContextAction({ layer: 'app-shell', action: { kind: 'open-command-palette' } })
  expect(useCommandPaletteStore.getState().open).toBe(true)
})
```

**실패 메시지 (예상).** `useCommandPaletteStore` 없음 (TS2305) · 팔레트가 안 열림 (received false).

**GREEN**.

```ts
/** 팔레트 열림 상태 — 컴포넌트 트리 밖(`ShellLayout` 의 컨텍스트 핸들러)에서 열어야 하므로
 *  `use-sidebar-collapsed.ts` 와 같은 이유로 zustand 를 쓴다. */
export const useCommandPaletteStore = create<{
  open: boolean
  setOpen: (open: boolean) => void
}>((set) => ({
  open: false,
  setOpen: (open) => set({ open }),
}))

export function useCommandPalette(enabled: boolean): UseCommandPaletteResult {
  const open = useCommandPaletteStore((s) => s.open)
  const setOpen = useCommandPaletteStore((s) => s.setOpen)
  useEffect(() => {
    if (!enabled) { setOpen(false); return }
    function handleKeyDown(e: KeyboardEvent): void {
      if (!isToggleShortcut(e)) return
      e.preventDefault()
      setOpen(!useCommandPaletteStore.getState().open)
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => { document.removeEventListener('keydown', handleKeyDown) }
  }, [enabled, setOpen])
  return { open, setOpen }
}
```

`ShellLayout.tsx:74` 등록에 핸들러 한 줄 추가.

```ts
useContextShortcuts(
  'app-shell',
  {
    onToggleSidebar: toggleSidebar,
    // `.` 은 **여는 것만** 한다. 팔레트가 열리면 입력창이 포커스를 가져가고
    // `shouldIgnoreEvent` 가 input 을 막으므로 `.` 로는 닫히지 않는다(E3). 닫기는 `Esc`.
    onOpenCommandPalette: () => openCommandPalette(true),
  },
  isAuthenticated,
)
```

**REFACTOR**. 훅 안의 `setOpen(!getState().open)` 토글이 읽기·쓰기 두 단계다. 스토어에
`toggle` 을 두고 훅이 그걸 부르게 정리한다(`use-sidebar-collapsed` 와 동일한 모양).

**검증**.
- `pnpm --filter @bts/web test -- useCommandPalette CommandPalette ShellLayout`
- 동반 E2E. **`command-palette.spec.ts`**(빈 입력 시 바로가기 4개와 **순서 보존** 계약 §2) ·
  `context-shortcuts.spec.ts`
- 눈확인. ① 목록 화면에서 `.` → 팔레트가 뜬다 ② 팔레트 안에서 마침표를 쳐도 닫히지 않는다
  ③ `Esc` 로 닫힌다 ④ `Cmd+K` 가 여전히 토글로 동작한다 (라이트/다크)

### Task 4. 포커스 대상 ref 노출 (하위 컨트롤 3종)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/meta/IssueAssigneeSelect.tsx`, `apps/web/src/components/issue/meta/IssueLabelsEdit.tsx`, `apps/web/src/components/issue/CommentSection.tsx`, `apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/favorite/FavoriteButton.tsx`, `apps/web/src/components/issue/WatchersSection.tsx`, `apps/web/src/components/issue/meta/LabelChipsEditor.tsx`, `apps/web/src/components/labels/LabelAutocompleteInput.tsx`, `apps/web/src/components/issue/meta/__tests__/IssueAssigneeSelect.test.tsx`, `apps/web/src/components/issue/meta/__tests__/IssueLabelsEdit.test.tsx`, `apps/web/src/components/issue/CommentSection.test.tsx`, `apps/web/src/components/favorite/FavoriteButton.test.tsx`, `apps/web/src/components/issue/WatchersSection.test.tsx`, `apps/web/src/components/issue/meta/__tests__/LabelChipsEditor.test.tsx`, `apps/web/src/components/labels/LabelAutocompleteInput.test.tsx`]
- depends-on: []

**왜 분리하나.** `a`/`m`/`l` 은 상세 라우트가 **하위 컴포넌트 내부의 입력 요소**에 포커스를
줘야 하는데 지금은 그 요소로 가는 손잡이가 없다. 이 task 는 손잡이만 만들고 소비는 Task 5 다
— 파일이 겹치지 않아 Task 1~2 와 **같은 wave 로 병렬 실행**할 수 있다.

**대상 5종** (Task 5 가 소비할 손잡이 전량).

| 키 | 컴포넌트 | ref 가 가리킬 것 |
|---|---|---|
| `a` | `IssueAssigneeSelect` | 담당자 검색 `<input>` |
| `m` | `CommentSection` | 댓글 작성 `<textarea>` |
| `l` | `IssueLabelsEdit` | 라벨 입력 컨트롤 |
| `s` | `FavoriteButton` | 즐겨찾기 `<button>` |
| `w` | `WatchersSection` | `watch-toggle-button` |

`s`/`w` 도 여기서 ref 를 받는다 — Task 5 가 `.click()` 으로 밀어야 그 버튼이 이미 가진
**진행 중 `disabled` 판정과 토스트 처리를 재사용**할 수 있기 때문이다. ref 없이
`document.querySelector` 로 찾는 것은 렌더 트리를 우회하는 안티패턴이라 쓰지 않는다.

> **★ files 정정 (착수 중 발견, 2026-08-04).** 위 5종 중 **라벨만 `IssueLabelsEdit` 선에서
> 닿지 않는다.** 실제 `<input>` 은 두 단계 아래에 있고 중간 컴포넌트가 **`...rest` 스프레드도
> 받지 않아** 통로가 물리적으로 없다 —
> `IssueLabelsEdit:56` → `LabelChipsEditor:82` → `LabelAutocompleteInput:136` (`CommandPrimitive.Input`).
> plan 작성 시 컴포넌트 체인을 끝까지 따라가지 않은 누락이다. **files 에 2파일 + 테스트를
> 추가**한다. `<div ref>` 로 감싸 `querySelector` 하는 우회는 쓰지 않는다 — 렌더 트리 우회이고,
> 포커스 불가능한 div 에 `aria-keyshortcuts` 를 붙이면 스크린리더가 읽지 않아 **F-3 이 라벨에서만
> 공허해진다**.

> **★ 설계 규칙 추가 — `aria-keyshortcuts` 는 조건부로 붙인다 (구현 중 실측 반영).**
> 5종 중 **2종이 단축키가 없는 화면과 컴포넌트를 공유**한다 — `IssueAssigneeSelect` 는 이슈
> 생성 폼이, `FavoriteButton` 은 저장 필터·대시보드·보드 3화면이 함께 쓴다. 리터럴로 무조건
> 붙이면 **그 화면들에서 없는 단축키를 스크린리더가 안내**한다. F-3 이 닫으려던 것보다 나쁜
> 오안내다. 규칙 — **`focusRef` 가 연결된 화면에만 `aria-keyshortcuts` 를 부착한다.**
> "손잡이가 연결된 화면에만 단축키가 있다"가 5종 공통 계약이고, Task 5 의 호출 방식은
> 그대로다(ref 하나만 넘긴다).

**RED** — 각 컴포넌트 테스트에 (담당자 예시, 라벨·댓글 동형).

```ts
it('focusRef 로 검색 입력에 포커스를 줄 수 있다', () => {
  const ref = createRef<HTMLInputElement>()
  render(<IssueAssigneeSelect {...baseProps} focusRef={ref} />)
  act(() => { ref.current?.focus() })
  expect(screen.getByLabelText('담당자 검색')).toHaveFocus()
})
```

**실패 메시지 (예상).** `focusRef` 가 props 에 없음 (TS2322).

**GREEN**. 각 컴포넌트에 옵셔널 `focusRef` prop 을 받아 해당 요소의 `ref` 에 전달한다.
`IssueMetaPanel` 은 담당자·라벨 두 ref 를 그대로 아래로 통과시킨다(자체 소비 없음).

**★ 리뷰 반영 F-1 — 댓글 입력에 포커스 표시를 넣는다.** 세 대상의 포커스 스타일을 실측하니
**댓글만 비어 있었다**.

| 대상 | 현재 | 판정 |
|---|---|---|
| 담당자 검색 | `focus:ring-2 focus:ring-ring` | 있음 |
| 라벨 입력 | `focus-visible:ring-3 focus-visible:ring-ring/50` | 있음 |
| 댓글 textarea | (없음 — 브라우저 기본 outline) | **없음** |

`m` 은 화면 다른 곳에서 **갑자기 점프**하는 이동이라 "내가 지금 어디 있나"를 표시가 대신
말해줘야 한다. 브라우저 기본 outline 은 다크 모드 대비가 보장되지 않고 옆 두 필드와도
모양이 다르다. 라벨 입력과 같은 표기로 맞춘다.

```tsx
className="w-full rounded border border-border bg-background p-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
```

**선재 결함이지만 이 PR 에서 닫는다** — Tab 으로 도달할 때는 옆 필드와 비교되지 않아 드러나지
않던 것을, `m` 이 그 경로를 처음 밟게 만들어 실현시킨다(F8 #337 의 `handleBlur` 사례와 동형).

**★ 리뷰 반영 F-3 — `aria-keyshortcuts` 로 발견 경로를 연다.** 지금 단축키의 존재를 알
방법은 `?` 도움말 모달뿐이고, 상세 화면에는 신호가 0 이다. 8종이 늘면 **모르는 기능의 양이
2.6배**가 된다. 표준 속성 하나로 시각 변경 없이 닫는다 — 스크린리더가 컨트롤을 읽을 때
단축키를 함께 알린다.

```tsx
aria-keyshortcuts="a"   // 담당자 검색 입력
aria-keyshortcuts="m"   // 댓글 textarea
aria-keyshortcuts="l"   // 라벨 입력
aria-keyshortcuts="s"   // 즐겨찾기 버튼
aria-keyshortcuts="w"   // 관심 버튼
```

시각 툴팁은 **넣지 않는다** — 신규 UI 0 제약을 지키고, 툴팁은 마우스 사용자에게만 닿아
키보드 사용자를 돕지 못한다.

```ts
export interface IssueAssigneeSelectProps {
  // …기존 props
  /** 단축키 `a` 가 포커스를 주는 검색 입력 — FR-UX-10 F11 */
  readonly focusRef?: RefObject<HTMLInputElement | null>
}
```

**REFACTOR**. 세 컴포넌트가 같은 모양의 옵셔널 ref 를 받는다. 주석에 "누가 이 ref 를
쓰는가"(F11 어느 키인지)를 한 줄로 남겨 다음 사람이 소비처를 역추적할 수 있게 한다.

**검증**.
- `pnpm --filter @bts/web test -- IssueAssigneeSelect IssueLabelsEdit CommentSection IssueMetaPanel`
- 동반 E2E. `inline-edit.spec.ts` · `field-permissions.spec.ts`
- 눈확인. 없음 (이 task 만으로는 시각 변화 0 — prop 추가뿐)

### Task 5. 상세 화면 7종 핸들러 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/__tests__/issues.$key.test.tsx`, `apps/web/src/components/issue/IssueActivityTabs.tsx`]
- depends-on: [2, 4]

> **★ files 정정 2 (Task 4 가 인계).** `CommentSection` 은 라우트가 직접 렌더하지 않는다 —
> `IssueActivityTabs.tsx:113` 이 렌더한다. 라우트의 `commentInputRef` 가 `CommentSection.focusRef`
> 까지 닿으려면 이 파일에 통과 prop 이 필요하다. Task 4 가 라벨에서 부딪힌 것과 **같은 종류의
> 벽**이라 미리 연다.

**RED** — 라우트 테스트에 (7종 중 3개 예시, 나머지 동형).

```ts
it('a 가 담당자 검색 입력에 포커스를 준다', async () => {
  renderDetail({ issue: baseIssue })
  await act(async () => { fireEvent.keyDown(document, { key: 'a' }) })
  expect(screen.getByLabelText('담당자 검색')).toHaveFocus()
})

it('담당자가 열람 숨김이면 a 는 무동작이다 (FR8 · E6)', async () => {
  renderDetail({ issue: { ...baseIssue, restrictedFields: ['assigneeId'] } })
  await act(async () => { fireEvent.keyDown(document, { key: 'a' }) })
  expect(document.body).toHaveFocus()   // 포커스가 옮겨가지 않았다
})

it('모달이 열려 있으면 issue-detail 을 등록하지 않는다 (E2)', () => {
  renderDetail({ issue: baseIssue, cloneDialogOpen: true })
  expect(getRegisteredContexts().has('issue-detail')).toBe(false)
})
```

**실패 메시지 (예상).** 포커스가 `body` 에 남음 · `issue-detail` 이 등록돼 있음 (received true).

**GREEN**. 라우트에 등록 한 블록.

```ts
// ── FR-UX-10 F11 — 상세 액션 단축키 7종 (`.` 은 app-shell 소관) ──────────────
//
// 활성 조건은 F10 D-5-a 를 그대로 승계한다 — 화면이 실제로 그 조작을 받을 수 있을
// 때만 **등록**한다. 콜백만 끊으면 키를 삼키고도 아무 일이 안 일어난다.
const detailShortcutsEnabled =
  issue !== undefined && !isLoading && !isError &&
  // 모달 3종 + 삭제 확인은 입력 요소가 없어 `shouldIgnoreEvent` 를 통과한다(E2).
  pendingDoneTransition === null && !cloneDialogOpen && !moveDialogOpen && !confirmDelete

useContextShortcuts('issue-detail', {
  // ★필드 권한 두 목록을 **모두** 본다. 백엔드가 두 목록을 배타적으로 만들어
  // 열람 숨김 필드는 수정 금지 목록에 절대 오지 않는다 — `noneditableFields` 만
  // 보면 숨긴 필드를 단축키로 열어주게 된다(F9 #338 이 실제로 그랬다).
  onFocusAssignee: () => {
    if (isFieldHidden('assigneeId', restrictedFields)) return
    if (isFieldDisabled('assigneeId', canEdit, noneditableFields)) return
    assigneeSearchRef.current?.focus()
  },
  onAssignToMe: () => {
    if (isFieldHidden('assigneeId', restrictedFields)) return
    if (isFieldDisabled('assigneeId', canEdit, noneditableFields)) return
    const me = currentUserId
    if (me === null) return                                   // E7
    // Jira 문구가 `Toggle` 이다 — 이미 나면 해제한다.
    changeAssignee.mutate(issue.assigneeId === me ? null : me)
  },
  onFocusComment: () => commentInputRef.current?.focus(),
  onEditTitle: () => {
    if (isFieldDisabled('summary', canEdit, noneditableFields)) return
    setIsEditingTitle(true)
  },
  onFocusLabels: () => {
    if (isFieldHidden('labels', restrictedFields)) return
    if (isFieldDisabled('labels', canEdit, noneditableFields)) return
    labelsInputRef.current?.focus()
  },
  // ★F-2 — 포커스를 먼저 옮기고 누른다. 순서가 접근성의 전부다(아래 참조).
  onToggleFavorite: () => { favoriteToggleRef.current?.focus(); favoriteToggleRef.current?.click() },
  onToggleWatch: () => { watchToggleRef.current?.focus(); watchToggleRef.current?.click() },
}, detailShortcutsEnabled)
```

`s`/`w` 를 `.click()` 으로 미는 이유 — 두 버튼은 **자신의 진행 중 상태(`disabled`)와
토스트 처리를 이미 갖고 있다**. 로직을 복제하면 그 규칙이 두 벌이 되어 어긋난다.
`disabled` 버튼의 `click()` 은 브라우저가 무시하므로 E8(중복 발행 금지)이 공짜로 성립한다.

**★ 리뷰 반영 F-2 — 누르기 전에 포커스를 옮긴다. 안 그러면 스크린리더에 무음이다.**

두 버튼은 `aria-pressed` 를 올바르게 갖고 있다(`FavoriteButton:90` · `WatchersSection:77`).
그런데 **포커스가 그 버튼에 없으면 스크린리더는 `aria-pressed` 변화를 읽지 않는다.**
성공 토스트도 없고(실패 시에만 `toast.error`), `aria-live` 영역은 앱 전역에 `CalendarView`
1건뿐이다. 그대로 두면 —

| 사용자 | `s` 를 눌렀을 때 |
|---|---|
| 눈으로 보는 사람 | 별이 채워지는 것을 본다 |
| 화면을 못 보는 사람 | **아무 일도 안 일어난 것과 구분 불가** |

`focus()` → `click()` 순서면 스크린리더가 버튼 이름과 눌림 상태를 읽는다. 부작용도 자연스럽다
— 방금 조작한 컨트롤에 포커스가 있는 것이 표준 동작이다. **`aria-live` 영역을 새로 만들지
않는다** — 그건 앱 전역 패턴 도입이라 이 PR 범위를 넘고, 표준 위젯 시맨틱으로 닫히는 문제다.

**짝 테스트** (이 순서가 뒤집히면 red 여야 한다).

```ts
it('s 는 즐겨찾기 버튼에 포커스를 준 뒤 누른다 (스크린리더 알림 조건)', async () => {
  renderDetail({ issue: baseIssue })
  await act(async () => { fireEvent.keyDown(document, { key: 's' }) })
  expect(screen.getByTestId('favorite-button')).toHaveFocus()
  expect(screen.getByTestId('favorite-button')).toHaveAttribute('aria-pressed', 'true')
})
```

**REFACTOR**. 필드 권한 2연속 검사가 3곳에서 반복된다. `canUseField(key)` 지역 헬퍼로 묶되
`lib/` 로 추출하지는 않는다 — 그건 F9 가 남긴 별도 후속 항목이다.

**검증**.
- `pnpm --filter @bts/web test -- issues.\$key IssueMetaPanel`
- 동반 E2E. `inline-edit.spec.ts` · `field-permissions.spec.ts` · `favorites.spec.ts` ·
  `issue-clone.spec.ts` · `issue-attachments.spec.ts` · `issue-changelog.spec.ts`
- 눈확인. `a`/`m`/`e`/`l` 각각에서 **포커스 링이 실제로 보이는가**(라이트/다크) ·
  `s`/`w` 토글 후 상태가 즉시 바뀌는가 · 댓글 본문에 마침표를 쳐도 팔레트가 안 뜨는가(E1)

### Task 6. 도움말 모달 「이슈 상세에서」 그룹

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/keyboard-shortcuts/ShortcutsHelpDialog.tsx`, `apps/web/src/components/keyboard-shortcuts/ShortcutsHelpDialog.test.tsx`]
- depends-on: [2]

**RED**.

```ts
it('「이슈 상세에서」 그룹에 7종이 렌더된다', () => {
  render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} keymap={DEFAULT_KEYMAP} />)
  const group = screen.getByRole('region', { name: '이슈 상세에서' })
  expect(within(group).getAllByRole('listitem')).toHaveLength(7)
})
```

**실패 메시지 (예상).** `region` 이름 `이슈 상세에서` 없음.

**GREEN**. `buildHelpGroups` 반환 배열에 항목 하나.

```ts
{
  // ★한국어 라벨을 ID 로 쓰면 `aria-labelledby` 가 공백으로 쪼개 연결이 끊긴다.
  id: 'issue-detail',
  label: '이슈 상세에서',
  items: contextItems('issue-detail'),
},
```

`.` 은 `app-shell` 이라 기존 「어디서나」 그룹에 **자동으로** 들어간다 — 별도 배선 없음.

> **★ Task 2 인계 — 「자동으로 들어간다」가 절반만 맞았다. 3건이 red 다.**
>
> | 증상 | 원인 |
> |---|---|
> | `렌더 수 = 두 레지스트리 합계` 가 `19 ≠ 12` | 「이슈 상세에서」 그룹 부재 → 위 GREEN 이 닫는다 |
> | `Found multiple elements: 명령 팔레트 열기` 2건 | **`PALETTE_HELP_ITEM`(Cmd/Ctrl K)과 새 `.` 항목이 같은 description 으로 「어디서나」에 두 행** |
>
> React 도 `Encountered two children with the same key, 명령 팔레트 열기` 를 경고한다
> (`ShortcutsHelpDialog.tsx:174` 가 `key={item.description}`).
>
> **처방 — 두 행이 아니라 한 행 · 키 칩 2개로 병합한다.** `.` 은 Cmd/Ctrl+K 와 **같은 동작의
> 두 번째 열쇠**이므로(Maxi 확정 §Jira 대조 3-b) 도움말도 그렇게 보여야 한다. 같은 문구가
> 두 줄 뜨는 것은 Jira 패리티상 부자연스럽다. 병합은 `buildHelpGroups` 안에서 한다 —
> `shortcuts.ts` 는 동결이다.
>
> ```tsx
> // `.` 과 Cmd/Ctrl+K 는 같은 팔레트를 연다. description 이 같으므로 행을 합치고
> // 키 칩만 둘로 둔다 — 합치지 않으면 접근성 이름이 중복돼 e2e 가 strict mode 로 죽고
> // React key 도 충돌한다(둘 다 실측).
> const paletteItem: HelpItem = {
>   keys: [...PALETTE_HELP_ITEM.keys, '.'],
>   description: PALETTE_HELP_ITEM.description,
> }
> ```
> 그리고 `contextItems('app-shell')` 에서 `.` 을 **제외**해 이중 렌더를 막는다. 제외를
> 하드코딩하지 말고 "이미 병합된 키"를 기준으로 걸러 다음 사람이 같은 함정을 안 밟게 한다.
>
> **★ 가드 1건 은퇴 (같은 PR 필수).** `★C5 F11 미구현 키(담당자·라벨…)는 표시되지 않는다` 는
> **F11 이 구현되는 순간 거짓이 된다** — 금지 문구 목록에 `담당자 지정`·`라벨 편집` 이 들어
> 있는데 그게 이제 정식 description 이다. 이 가드는 "아직 없는 키를 광고하지 마라"는
> FR-UX-05 FR8 계약의 F10 시점 스냅샷이었다. **삭제가 아니라 교체**한다 — 계약 자체는
> 살아 있어야 하므로 "레지스트리에 없는 키는 표시되지 않는다"는 **파생형 단언**으로 바꾼다.
> 그래야 F12 이후에도 같은 보호가 유지된다.

**REFACTOR**. 그룹 3개가 같은 모양이다. 순서(어디서나 → 목록 → 상세)가 **화면 계층 순**임을
주석 한 줄로 남긴다.

**검증**.
- `pnpm --filter @bts/web test -- ShortcutsHelpDialog`
- 동반 E2E. `keyboard-shortcuts.spec.ts`
- 눈확인. `?` 로 모달을 열어 3그룹 헤딩 대비를 **라이트/다크 양쪽**에서 확인
  (F10 에서 그룹 헤딩 대비 결함이 눈확인으로 적발된 전례)

### Task 7. 예약어 가드 파생 증명 (FR7)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/KeymapForm.test.tsx`]
- depends-on: [2]

**왜 테스트만 있는 task 인가.** `KeymapForm.tsx:119` 가 이미 `CONTEXT_SHORTCUTS` 에서
예약어를 파생하므로 **프로덕션 코드는 한 줄도 바뀌지 않아야 한다**. ADR D-4 가 *"하드코딩하면
F11 이 8종을 추가할 때 이 가드만 뒤처져 조용히 뚫린다"* 고 F11 을 지목해 경고했으니,
**파생이 실제로 도는지를 증명**하는 것이 이 task 의 산출물이다.

**RED** — 기존 순회 테스트는 이미 `CONTEXT_SHORTCUTS` 전량을 돌지만 **8종이 늘어난 것을
검사하는 비-공허 짝이 없다**. 개수 리터럴 없이 못 박는다.

```ts
it('F11 상세 액션 8종이 예약 키로 막힌다 (코드 변경 없이 파생)', async () => {
  for (const key of ['a', 'i', 'm', 'e', 'l', 's', 'w', '.']) {
    const { unmount } = renderKeymapForm()
    await rebind('새 이슈 생성', key)
    expect(screen.getByRole('alert')).toHaveTextContent('예약')
    unmount()
  }
})

it('예약 목록이 레지스트리와 정확히 같다 (하드코딩 회귀 차단)', () => {
  expect(reservedKeysForTest()).toEqual(new Set(CONTEXT_SHORTCUTS.map((s) => s.key)))
})
```

**★ 판별식 1건 추가 (Task 4 가 인계한 잔여 리스크).** Task 4 가 붙인 `aria-keyshortcuts` 의 키
문자는 **JSX 리터럴**이라 `CONTEXT_SHORTCUTS` 와 조용히 어긋날 수 있다. 키를 재배치하면
레지스트리만 바뀌고 화면이 스크린리더에 **옛 키를 계속 안내**한다 — 두 목록이 서로를 검사하지
않는 지배 결함 양식(`two-lists-never-check-each-other`). 차집합으로 못 박는다.

```ts
it('aria-keyshortcuts 리터럴이 레지스트리 키와 일치한다', () => {
  // 소스 전수 grep — 5곳의 리터럴을 뽑아 레지스트리와 대조한다. 런타임 렌더가 아니라
  // 소스를 재는 이유는, 렌더 테스트는 그 컴포넌트가 쓰이는 화면에서만 돌아 누락을 놓치기 때문이다.
  const declared = collectAriaKeyshortcutLiterals('apps/web/src')
  const registry = new Set(CONTEXT_SHORTCUTS.filter((s) => s.context === 'issue-detail').map((s) => s.key))
  expect(new Set(declared)).toEqual(registry)
})
```

이 판별식이 **비-공허한지** 확인한다 — `aria-keyshortcuts="a"` 를 `"x"` 로 바꿔 red 가 나오는지
보고 되돌린다. **되돌릴 때 `git checkout` 을 쓰지 마라**(미커밋 산출물이 통째로 날아간다 —
F10 실사고). 역방향 Edit 으로 되돌린다.

**실패 메시지 (예상).** 없음 — **이 테스트는 처음부터 green 이어야 정상**이다.
red 가 나오면 파생이 깨진 것이므로 `KeymapForm.tsx` 를 고친다.

> **★green-first 인 이유를 남긴다.** TDD 규율의 예외가 아니라 **회귀 가드**다. 파생 구조가
> 이미 옳다는 것을 증명하는 테스트이고, 뮤테이션(파생을 하드코딩 배열로 바꿔보기)으로
> **비-공허**를 확인한다. 그 뮤테이션에서 red 가 안 나면 테스트가 가짜다.

**REFACTOR**. 없음 (테스트만).

**검증**.
- `pnpm --filter @bts/web test -- KeymapForm`
- **뮤테이션.** `RESERVED_CONTEXT_KEYS` 를 `new Map([['j','x']])` 로 바꿔 두 테스트가
  **모두 red** 인지 확인 후 원복. **원복은 `git checkout` 이 아니라 역방향 Edit 으로 한다**
  (미커밋 상태에서 checkout 하면 수정분이 통째로 날아간다 — F10 실사고)
- 동반 E2E. `keyboard-shortcuts.spec.ts`
- 눈확인. `설정 → 키맵` 에서 `새 이슈 생성` 을 `a` 로 바꿔 저장이 막히고 안내가 뜨는지

### Task 8. E2E 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/detail-action-shortcuts.spec.ts`]
- depends-on: [3, 5, 6]

**RED/시나리오** (신규 스펙 파일, 8 시나리오).

| # | 시나리오 | 단언 |
|---|---|---|
| 1 | `a` → 담당자 검색 포커스 | `expect(input).toBeFocused()` |
| 2 | `i` → 담당자가 나로 바뀐다 | 메타 패널에 내 이름 |
| 3 | `i` 재입력 → 해제된다 | 「미배정」 |
| 4 | `m` → 댓글 입력 포커스 | `toBeFocused()` |
| 5 | `e` → 제목 입력 상태 | 제목 입력창 노출 |
| 6 | `s` → 별 상태 반전 | `aria-pressed` 반전 |
| 7 | `w` → 관심 반전 | `aria-pressed` 반전 |
| 8 | `.` → 팔레트 열림 | `role="dialog"` 노출 |
| 9 | **★S9 와이드 동시 생존** | split view 에서 `j` 로 커서 이동 후 `m` 으로 우측 댓글 진입 |
| 10 | **★E1 입력 중 무발화** | 댓글에 `a.m` 을 쳐도 팔레트·포커스 이동 없음 |
| 11 | **E9 좁은 폭** | 375px 전체화면 상세에서 `a`/`s`/`w` 가 그대로 동작 (F10 커서 4종과 달리 폭 무관) |
| 12 | **E5 전체화면에서 `j`** | 전체화면 상세에서 `j` 를 눌러도 URL 이 바뀌지 않고, **브라우저 기본 동작이 살아 있다**(`defaultPrevented` 가 false) |

> **★negative 단언에는 settle barrier 를 세운다.** `page.keyboard.press` 는 이벤트 디스패치
> 까지만 기다리므로, 회귀가 생겨도 **갱신 전 상태를 보고 통과**한다(F10 이 실제로 이 함정에
> 빠졌다). 시나리오 10 은 마침표를 친 뒤 **효과가 관측되는 키를 한 번 눌러 왕복을 확인**하고
> 그 시점에 negative 를 단언한다.

**검증**.
- `pnpm --filter @bts/web e2e -- detail-action-shortcuts.spec.ts`
- **회귀 동반 실행 필수.** `context-shortcuts.spec.ts` · `keyboard-shortcuts.spec.ts` ·
  `command-palette.spec.ts` · `inline-edit.spec.ts` · `favorites.spec.ts` ·
  `field-permissions.spec.ts`
- **2회 연속 통과**로 flaky 아님을 확인 (실패 대상이 바뀌면 flaky 서명)

### Task 9. 정본 전수 동기화 + 완주 선언

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/plan/README.md`, `docs/design/jira-parity-roadmap.md`, `CHANGELOG.md`, `CLAUDE.md`]
- depends-on: [8]

**내용** (`docs/rules/fr-sync-checklist.md` 9종 체크리스트 적용).

1. `personalization.md §4.8` D6/D7 을 `[x]` — **F11 완료 문구 + PR 번호**
2. 같은 절의 *"`s` 지라 대응 없음"* 을 **원문 교체**로 정정(§Jira 대조 §2 참조) —
   정정 노트가 아니라 원문 교체가 F3 #333 · F8 #337 선례다
3. *"기능이 이미 완비"* 근거에 **UI 실재**(`IssueMetaPanel.tsx:322`) 추가
4. `docs/plan/README.md` — personalization 행의 잔여 표기에서 FR-UX-10 제거,
   진척 카운트 갱신(실측 `grep -c '^- \[x\] D'`)
5. `jira-parity-roadmap.md` F11 행 상태 갱신
6. `CHANGELOG.md` 항목 추가
7. `CLAUDE.md` 현재 단계 문장에서 FR-UX-10 을 완주 목록으로 이동 (잔여 3건으로)

**검증**.
- `bash scripts/verify-master-plan.sh` → **EXIT 0** (종료 4 면 머지 차단)
- `node scripts/build-doc-index.mjs` 재실행 후 `git diff` 확인
- **개수 리터럴을 손으로 쓰지 않는다** — 전부 실측 명령 결과로 채운다
  (오케스트레이터가 지시에 쓴 개수가 틀린 사고 전력)

## Plan 메타

- task 수: **9**
- 예상 wave: **5** (w1. Task 1·4 → w2. Task 2 → w3. Task 3·5·6·7 → w4. Task 8 → w5. Task 9)
- 구현 규율: **ui 시각 검증 트랙** (red-first 면제, 기존 E2E 동반 실행 + 브라우저 눈확인 필수)
- 병렬 dispatch: bts-impl 이 `depends-on` + `files` 교집합으로 wave 계산
- 추가 검증: typecheck · eslint · vitest 전량 · playwright(qa-engineer) · `verify-master-plan.sh`
- **전 task 공통 판정식**. `git diff --stat` 에 `shortcuts.ts` · `shortcuts.test.ts` 가
  **한 번도 등장하지 않아야** 한다

## 리뷰 결과

### plan-design-review (2026-08-04)

**범위 — Maxi 확정.** 시각 변경이 0 인 작업이라(신규 컴포넌트 0, 기존 컨트롤 재사용)
목업 생성과 정보 위계·빈 상태·카드 밀도·AI 슬롭 패스는 **비대상**으로 판정하고,
**접근성 + 설계 함정**으로 좁혔다. 표준 7패스를 그대로 돌렸다면 대부분 "해당 없음"이
나왔을 것이고 그건 검증이 아니라 빈칸이다.

**등급.** 접근성 **6/10 → 9/10**(반영 후) · 설계 함정 **9/10**(반영 전부터).

| # | 심각도 | 발견 | 반영 |
|---|---|---|---|
| **F-2** | **High** | `s`/`w` 토글이 **스크린리더에 무음**. 두 버튼 다 `aria-pressed` 는 옳지만 **포커스가 없으면 읽히지 않는다**. 성공 토스트 없음, `aria-live` 는 앱 전역 1건뿐 → 화면을 못 보는 사용자에게 "아무 일도 안 일어난 것"과 구분 불가 | Task 5 — `focus()` → `click()` 순서 + 순서가 뒤집히면 red 인 짝 테스트 |
| **F-1** | Medium | `m` 이 데려가는 **댓글 입력에만 포커스 표시가 없다**(담당자 `ring-2` · 라벨 `ring-3` · 댓글 없음). 점프 이동은 "지금 어디 있나"를 표시가 대신 말해줘야 한다 | Task 4 — 라벨 입력과 같은 표기로 통일 |
| **F-3** | Medium | 단축키의 존재를 알 경로가 `?` 모달뿐. 상세 화면에 신호 0 인데 **모르는 기능이 2.6배**로 는다 | Task 4 — `aria-keyshortcuts` 5곳. 시각 툴팁은 기각(신규 UI 0 · 마우스 사용자에게만 닿음) |
| F-4 | — | **S9 와이드 동시 생존** · **E5 헛도는 `preventDefault`** — plan 이 이미 등록 인지 폴백으로 닫고 테스트도 있다 | 통과 |
| F-5 | Low | 도움말 3그룹 대비 — F10 이 같은 결함을 잡아 수정했고 3번째 그룹은 같은 스타일 재사용 | 눈확인 항목으로 유지 |

**F-1 의 성격.** 선재 결함이다. 그런데 Tab 으로 도달할 때는 옆 필드와 비교되지 않아 드러나지
않던 것을 `m` 이 실현시킨다 — F8 #337 의 `handleBlur` 150ms 사례와 **같은 구조**(없던 버그를
만든 게 아니라 잠복 조건을 실현시킨 것). 그래서 같은 PR 에서 닫는다.

**BLOCKER.** 없음.

**반영 후 재판정.** 접근성 3건이 전부 **plan 본문에 코드와 짝 테스트까지 들어갔다**.
남은 위험은 "테스트가 통과해도 실제로 안 보일 수 있다"인데, 그건 계약 §6 브라우저 눈확인이
받는다 — F8 에서 눈확인이 가짜 봉합을 막은 전례가 있어 이 PR 도 생략하지 않는다.
