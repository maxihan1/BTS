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
 * - `issue-list`: 이슈 목록(`/issues`) — 목록 항법 `j`/`k`/`o`/`t`
 * - `app-shell`: 인증된 셸이 렌더된 모든 화면 — 사이드바 토글 `[`
 */
export type ShortcutContext = 'issue-list' | 'app-shell'

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
 * - `none`: 처리할 단축키 없음(무동작)
 */
export type ContextShortcutAction =
  | { kind: 'cursor-move'; delta: 1 | -1 }
  | { kind: 'open-current' }
  | { kind: 'toggle-detail-pane' }
  | { kind: 'toggle-sidebar' }
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
 * 컨텍스트 단축키 5종 — F10 범위(목록 항법 + 사이드바).
 *
 * 상세 액션 7종(`a`/`i`/`m`/`e`/`l`/`w`/`.`)과 즐겨찾기(`s`)는 **F11 범위**라
 * 여기 없다. 도움말 모달은 이 배열만 렌더하므로 미구현 키가 "동작하는 것처럼"
 * 표기되지 않는다(FR-UX-05 FR8 계약 승계).
 *
 * 키 선정 근거는 Jira Cloud 공식 문서 대조 —
 * `docs/specs/2026-08-03-fr-ux-10-f10-context-shortcuts.md` §Jira 대조.
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
]

/**
 * 활성 컨텍스트에서 훑을 레이어 순서 — **좁은 것부터 넓은 것으로**.
 *
 * `issue-list` 화면에 있어도 `[`(사이드바)는 발화해야 하므로 `app-shell` 로 폴백한다.
 * 반대로 `app-shell` 만 활성인 화면에서 `j` 는 발화하지 않는다(E12).
 */
const CONTEXT_LAYERS: Record<ShortcutContext, readonly ShortcutContext[]> = {
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
 * @returns 소유 레이어 + 동작. 등록되지 않은 키이거나 컨텍스트가 맞지 않으면 `null`
 */
export function resolveContextKeydown(key: string, active: ShortcutContext): ContextShortcutHit {
  for (const layer of CONTEXT_LAYERS[active]) {
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
