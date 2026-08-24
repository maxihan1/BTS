// 화면 컨텍스트별 단축키 레지스트리 + keydown 판별·커서 경계 계산 순수 로직 — FR-UX-10 F10 Task-1
//
// 🛑 이 모듈은 `shortcuts.ts`(전역 5종)를 import 하지 않는다 — 단방향 유지.
// 전역 `SHORTCUTS`·`DEFAULT_KEYMAP` 은 identity-access 의 `KeymapAction` enum ·
// `user_keymap.action` CHECK 제약 · `shortcuts.test.ts` 2단언과 묶인 4중 계약이고
// 그 소유자는 §3.3 FR-PF-03 이다. 여기 키를 늘려도 저쪽은 건드리지 않는다.
// (ADR docs/decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md D-1)

/**
 * 단축키가 살아나는 화면 레이어.
 *
 * 레이어는 **중첩**된다 — `issue-list` 화면에서는 `app-shell` 항목도 함께 발화한다.
 * 판별은 좁은 레이어부터 훑는다([CONTEXT_LAYERS]).
 *
 * - `issue-detail`: 이슈 상세 — 상세 액션(담당자·댓글·라벨 등)
 * - `issue-list`: 이슈 목록(`/issues`) — 목록 항법 `j`/`k`/`o`/`t`
 * - `app-shell`: 인증된 셸이 렌더된 모든 화면 — 사이드바 토글 `[`
 */
export type ShortcutContext = 'issue-detail' | 'issue-list' | 'app-shell'

/**
 * 컨텍스트 단축키가 발화했을 때 호출부가 실행할 동작 — 판별 유니온.
 *
 * 이 모듈은 부수효과를 실행하지 않는다(순수). 실제 라우팅·상태 변경은
 * `useContextShortcuts` 에 등록된 핸들러가 맡는다.
 *
 * - `cursor-move`: 목록 커서를 `delta` 만큼 이동(+1 아래 / -1 위)
 * - `open-current`: 커서 이슈를 전체화면 상세로 열기
 * - `toggle-detail-pane`: 우측 상세 페인(split view) 열기/닫기
 * - `toggle-sidebar`: 사이드바 접기/펼치기
 * - `focus-assignee`: 담당자 선택 컨트롤로 포커스 이동(F11 `a`)
 * - `assign-to-me`: 담당자를 나로 지정, 이미 나면 해제(F11 `i` — Jira `Toggle` 문구)
 * - `focus-comment`: 댓글 입력창으로 포커스 이동(F11 `m`)
 * - `edit-title`: 제목 인라인 편집 진입(F11 `e`)
 * - `focus-labels`: 라벨 편집 컨트롤로 포커스 이동(F11 `l`)
 * - `toggle-favorite`: 이 이슈 즐겨찾기 켜기/끄기(F11 `s`)
 * - `toggle-watch`: 이 이슈 관심(watch) 켜기/끄기(F11 `w`)
 * - `open-command-palette`: 명령 팔레트 열기(F11 `.`)
 * - `close-sidebar-drawer`: 모바일 사이드바 드로어 닫기(F24 `Escape`)
 * - `none`: 처리할 단축키 없음(무동작)
 */
export type ContextShortcutAction =
  | { kind: 'cursor-move'; delta: 1 | -1 }
  | { kind: 'open-current' }
  | { kind: 'toggle-detail-pane' }
  | { kind: 'toggle-sidebar' }
  | { kind: 'focus-assignee' }
  | { kind: 'assign-to-me' }
  | { kind: 'focus-comment' }
  | { kind: 'edit-title' }
  | { kind: 'focus-labels' }
  | { kind: 'toggle-favorite' }
  | { kind: 'toggle-watch' }
  | { kind: 'open-command-palette' }
  | { kind: 'close-sidebar-drawer' }
  | { kind: 'none' }

/** 컨텍스트 단축키 정의 — 훅 dispatch 와 도움말 모달이 함께 구동하는 단일 진실 출처 */
export interface ContextShortcutDef {
  /** 발화 키 — `e.key` 와 정확히 비교한다(대소문자 구분, 수정자 없음) */
  readonly key: string
  /** 이 단축키가 살아나는 레이어 */
  readonly context: ShortcutContext
  /** 도움말 모달에 노출할 한국어 설명 */
  readonly description: string
  /** 발화 시 호출부가 실행할 동작 */
  readonly action: ContextShortcutAction
}

/**
 * 컨텍스트 단축키 13종 — 목록 항법 + 사이드바(F10 5종) · 이슈 상세 액션(F11 8종).
 *
 * 도움말 모달·키맵 폼의 예약 키 가드가 **이 배열에서 파생**한다. 여기 없는 키는 표기될
 * 수도 예약될 수도 없으므로, 미구현 키가 "동작하는 것처럼" 보이지 않는다
 * (FR-UX-05 FR8 계약 승계).
 *
 * 키 선정 근거는 Jira Cloud 공식 문서 대조 — F10 5종은
 * `docs/specs/2026-08-03-fr-ux-10-f10-context-shortcuts.md` §Jira 대조,
 * F11 8종은 `docs/specs/2026-08-04-fr-ux-10-f11-detail-action-shortcuts.md` §Jira 대조.
 * (`e`·`s` 는 Jira 미기재라 BTS 고유 배치다 — 근거는 그 §4 대응 없는 항목)
 */
export const CONTEXT_SHORTCUTS: readonly ContextShortcutDef[] = [
  {
    key: 'j',
    context: 'issue-list',
    description: '다음 이슈로 이동',
    action: { kind: 'cursor-move', delta: 1 },
  },
  {
    key: 'k',
    context: 'issue-list',
    description: '이전 이슈로 이동',
    action: { kind: 'cursor-move', delta: -1 },
  },
  {
    key: 'o',
    context: 'issue-list',
    description: '선택한 이슈 열기',
    action: { kind: 'open-current' },
  },
  {
    key: 't',
    context: 'issue-list',
    description: '상세 패널 열기/닫기',
    action: { kind: 'toggle-detail-pane' },
  },
  {
    key: '[',
    context: 'app-shell',
    description: '사이드바 접기/펼치기',
    action: { kind: 'toggle-sidebar' },
  },
  {
    key: 'a',
    context: 'issue-detail',
    description: '담당자 지정',
    action: { kind: 'focus-assignee' },
  },
  {
    key: 'i',
    context: 'issue-detail',
    description: '나에게 할당 / 해제',
    action: { kind: 'assign-to-me' },
  },
  {
    key: 'm',
    context: 'issue-detail',
    description: '댓글 쓰기',
    action: { kind: 'focus-comment' },
  },
  {
    key: 'e',
    context: 'issue-detail',
    description: '제목 편집',
    action: { kind: 'edit-title' },
  },
  {
    key: 'l',
    context: 'issue-detail',
    description: '라벨 편집',
    action: { kind: 'focus-labels' },
  },
  {
    key: 's',
    context: 'issue-detail',
    description: '즐겨찾기 켜기/끄기',
    action: { kind: 'toggle-favorite' },
  },
  {
    key: 'w',
    context: 'issue-detail',
    description: '관심 켜기/끄기',
    action: { kind: 'toggle-watch' },
  },
  // ★`.` 만 `app-shell` 이다. 팔레트는 전역 기능이라 목록·대시보드에서도 열려야 하고,
  // Jira 도 `.` 을 전역에서 발화시킨다(스펙 §Jira 대조 3-a).
  {
    key: '.',
    context: 'app-shell',
    description: '명령 팔레트 열기',
    action: { kind: 'open-command-palette' },
  },
  // ★F24 — 모바일 드로어는 백드롭으로 본문을 덮으므로 포인터 사용자에게는 모달로 읽힌다.
  //   키보드로도 같은 방식으로 빠져나갈 수 있어야 한다. 드로어가 닫혀 있으면 무동작이라
  //   다른 화면에서 Escape 를 눌러도 관측되는 변화가 없다.
  //   🛑 이걸 위해 셸 컴포넌트에 전역 키 리스너를 새로 달지 마라 — ADR D-2 가 리스너 소유자를
  //      허용목록으로 봉인했고, 여기 **등록**하는 것이 그 ADR 이 지정한 유일한 정식 경로다.
  {
    key: 'Escape',
    context: 'app-shell',
    description: '모바일 사이드바 드로어 닫기',
    action: { kind: 'close-sidebar-drawer' },
  },
]

/**
 * 활성 컨텍스트에서 훑을 레이어 순서 — **좁은 것부터 넓은 것으로**.
 *
 * `issue-list` 화면에 있어도 `[`(사이드바)는 발화해야 하므로 `app-shell` 로 폴백한다.
 * 반대로 `app-shell` 만 활성인 화면에서 `j` 는 발화하지 않는다(E12).
 */
const CONTEXT_LAYERS: Record<ShortcutContext, readonly ShortcutContext[]> = {
  // ★상세가 활성이어도 목록 항법이 살아 있어야 한다. 와이드 split view 는 목록과
  // 상세가 **동시 마운트**라(`issues.index.tsx` 우측 페인), 상세를 좁다는 이유로 단독
  // 활성으로 두면 F10 이 만든 `j`/`k` 가 그 화면에서만 죽는다(S9).
  'issue-detail': ['issue-detail', 'issue-list', 'app-shell'],
  'issue-list': ['issue-list', 'app-shell'],
  'app-shell': ['app-shell'],
}

/**
 * 판별 결과 — **동작과 그것이 속한 레이어를 함께** 돌려준다.
 *
 * ★레이어를 버리지 않는 것이 요점이다. 예전에는 동작만 반환해서 dispatch 쪽이
 * `handlers['issue-list']` / `handlers['app-shell']` 로 **소속을 재추론**했고, 그건
 * `CONTEXT_SHORTCUTS` 가 이미 `context` 필드로 선언한 지식의 복제였다. 그 복제가 있으면
 * 기존 단축키를 다른 레이어로 옮길 때(`[` 를 board 레이어로, 또는 board 가 `cursor-move`
 * 를 소유) 판별은 성공하고 `preventDefault` 까지 하는데 dispatch 만 엉뚱한 레이어를 봐
 * **아무 일도 안 일어난다**. `never` exhaustive 가드는 액션 *종류* 추가만 잡지 이 재배치는
 * 못 잡는다.
 */
export type ContextShortcutHit = {
  /** 이 동작을 소유한 레이어 — dispatch 가 그대로 핸들러 조회에 쓴다 */
  readonly layer: ShortcutContext
  /** 발화할 동작 */
  readonly action: ContextShortcutAction
} | null

/**
 * keydown 키를 컨텍스트 단축키 동작으로 해석한다. 부수효과 없음(호출부=훅 책임).
 *
 * 전역 단축키(`shortcuts.ts` `resolveKeydown`)가 **먼저** 판별하고, 그 결과가
 * `none` 일 때만 이 함수로 폴백한다 — 두 레지스트리가 한 파이프라인을 공유하는
 * 이유는 ADR D-2 참조(leader 대기·도움말 열림 가드를 한 곳에서 걸기 위함).
 *
 * @param key keydown 이벤트의 `e.key` (수정자·IME 가드는 호출부가 선행 처리)
 * @param active 현재 활성 컨텍스트
 * @param registered 지금 핸들러를 등록한 레이어 집합 — 폴백 대상을 여기로 좁힌다(E5)
 * @returns 소유 레이어 + 동작. 등록되지 않은 키이거나 컨텍스트가 맞지 않으면 `null`
 */
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

/**
 * 커서를 `delta` 만큼 옮긴 뒤의 이슈 키를 계산한다. **경계에서 감싸지 않는다**(FR6).
 *
 * 반환값 `null` 은 "커서를 바꾸지 않는다"는 뜻이며 네 경우에 나온다 —
 * 목록이 빔(E1) · 첫 행에서 위로(E3) · 마지막 행에서 아래로(E4) · 계산 불가.
 * 커서가 없거나(E2) 현재 목록에 없으면(E5, 필터 변경 직후) **첫 행**으로 진입한다.
 *
 * @param keys 현재 렌더된 목록의 이슈 키 배열(화면 순서 그대로)
 * @param current 현재 커서 이슈 키. 없으면 null
 * @param delta 이동 방향 — +1 아래, -1 위
 * @returns 이동 후 이슈 키, 또는 무동작이면 null
 */
export function nextCursorKey(
  keys: readonly string[],
  current: string | null,
  delta: 1 | -1,
): string | null {
  const first = keys[0]
  if (first === undefined) return null

  const currentIndex = current === null ? -1 : keys.indexOf(current)
  if (currentIndex === -1) return first

  const nextIndex = currentIndex + delta
  return nextIndex >= 0 && nextIndex < keys.length ? (keys[nextIndex] ?? null) : null
}
