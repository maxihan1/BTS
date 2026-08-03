// 전역 keydown 리스너로 단축키를 처리하고 도움말 모달 열림 상태를 소유하는 훅 — FR-UX-05 Task-2, FR-PF-03 Task-8(useKeymap 구독·부트 로드)
import { useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useKeymap, type KeymapBinding } from '@/api/keymap'
import {
  DEFAULT_KEYMAP,
  LEADER_TIMEOUT_MS,
  resolveKeydown,
  shouldIgnoreEvent,
  type Keymap,
  type KeymapActionId,
  type ShortcutAction,
} from './shortcuts'
import { resolveContextKeydown } from './context-shortcuts'
import { dispatchContextAction, resolveActiveContext } from './useContextShortcuts'

/** useKeyboardShortcuts 반환값 */
interface UseKeyboardShortcutsResult {
  /** 단축키 도움말 모달 열림 여부 */
  readonly helpOpen: boolean
  /** 열림 상태를 직접 제어하는 setter — ShortcutsHelpDialog의 onOpenChange에 연결 */
  readonly setHelpOpen: (open: boolean) => void
}

/** leader-key 시퀀스 대기 상태 + 타임아웃 타이머를 담는 참조 묶음 */
interface LeaderState {
  readonly pendingLeaderRef: RefObject<string | null>
  readonly timerRef: RefObject<ReturnType<typeof setTimeout> | null>
}

/** dispatchAction이 부수효과를 실행하는 데 필요한 의존성 묶음 */
interface DispatchContext {
  readonly navigate: ReturnType<typeof useNavigate>
  readonly setHelpOpen: (open: boolean) => void
  readonly helpOpenRef: RefObject<boolean>
  readonly leader: LeaderState
}

/**
 * leader-key 대기 상태와 타임아웃 타이머를 해제한다.
 *
 * @param leader leader 상태 참조 묶음
 */
function clearLeader(leader: LeaderState): void {
  if (leader.timerRef.current !== null) {
    clearTimeout(leader.timerRef.current)
    leader.timerRef.current = null
  }
  leader.pendingLeaderRef.current = null
}

/**
 * leader-key 대기 상태를 (재)시작하고 `LEADER_TIMEOUT_MS` 후 자동으로 리셋되는
 * 타이머를 새로 건다(E3 — 재입력 시 이전 타이머를 취소하고 다시 시작한다).
 *
 * @param leader leader 상태 참조 묶음
 * @param key 대기할 leader 키
 */
function armLeader(leader: LeaderState, key: string): void {
  clearLeader(leader)
  leader.pendingLeaderRef.current = key
  leader.timerRef.current = setTimeout(() => {
    leader.pendingLeaderRef.current = null
  }, LEADER_TIMEOUT_MS)
}

/**
 * `resolveKeydown`이 판별한 단축키 액션을 실제 부수효과로 옮긴다.
 *
 * 실제로 처리하는 액션(navigate/toggle-help/set-leader)에서만
 * `preventDefault`를 호출한다 — `reset`/`none`은 무효 입력이므로 브라우저
 * 기본 동작을 막지 않고 그대로 흘려보낸다.
 *
 * @param action resolveKeydown이 반환한 판별 유니온
 * @param e 원본 keydown 이벤트
 * @param ctx navigate/setHelpOpen/helpOpenRef/leader 상태 묶음
 */
function dispatchAction(action: ShortcutAction, e: KeyboardEvent, ctx: DispatchContext): void {
  switch (action.kind) {
    case 'navigate':
      e.preventDefault()
      void ctx.navigate({ to: action.to })
      clearLeader(ctx.leader)
      return
    case 'toggle-help':
      e.preventDefault()
      ctx.setHelpOpen(!ctx.helpOpenRef.current)
      clearLeader(ctx.leader)
      return
    case 'set-leader':
      e.preventDefault()
      armLeader(ctx.leader, action.leader)
      return
    case 'reset':
      clearLeader(ctx.leader)
      return
    case 'none':
      return
  }
}

/**
 * {@link useKeymap} 응답의 `bindings` 배열을 action → key_combo record(`Keymap`)로 병합한다.
 *
 * 데이터가 아직 없으면(비로그인 idle, 로딩 중, 에러) `DEFAULT_KEYMAP`을 그대로 반환한다.
 * 응답에 포함된 action만 덮어쓰는 방어적 병합이라 서버가 5종을 완비해 내려주는 계약이
 * 깨지더라도 나머지 action은 기본 키맵으로 계속 동작한다.
 *
 * @param bindings useKeymap이 반환한 KeymapResponse.bindings, 없으면 undefined
 * @returns effective 키맵(기본값 + 서버 override 병합)
 */
function toEffectiveKeymap(bindings: readonly KeymapBinding[] | undefined): Keymap {
  if (bindings === undefined) return DEFAULT_KEYMAP
  return bindings.reduce<Record<KeymapActionId, string>>(
    (acc, binding) => {
      acc[binding.action] = binding.keyCombo
      return acc
    },
    { ...DEFAULT_KEYMAP },
  )
}

/**
 * enabled일 때만 document keydown 리스너를 등록하고 cleanup 함수를 반환한다.
 *
 * enabled=false이면(비로그인) 리스너를 등록하지 않고 leader 대기 상태를
 * 리셋한다(FR7, E8 — useCommandPalette 선례).
 *
 * @param enabled 훅 활성화 여부
 * @param leader leader 상태 참조 묶음
 * @param ctx navigate/setHelpOpen/helpOpenRef 의존성 묶음(leader 제외)
 * @param keymap effective(기본값+서버 override 병합) 키맵 — resolveKeydown에 그대로 전달(FR-PF-03 Task-8)
 * @returns 리스너 해제 cleanup 함수, 등록하지 않았다면 undefined
 */
function attachShortcutListener(
  enabled: boolean,
  leader: LeaderState,
  ctx: Omit<DispatchContext, 'leader'>,
  keymap: Keymap,
): (() => void) | undefined {
  if (!enabled) {
    clearLeader(leader)
    return undefined
  }

  function handleKeyDown(e: KeyboardEvent): void {
    if (shouldIgnoreEvent(e)) return
    const action = resolveKeydown(
      e,
      leader.pendingLeaderRef.current,
      ctx.helpOpenRef.current,
      keymap,
    )

    // 전역이 처리했으면 거기서 끝 — 컨텍스트로 넘기지 않는다.
    // `reset`(leader 대기 중 미등록 키)도 여기서 소비되므로 `g` 직후 `j` 는
    // 시퀀스만 리셋하고 커서를 움직이지 않는다(E6, FR-UX-10 ADR D-2).
    if (action.kind !== 'none') {
      dispatchAction(action, e, { ...ctx, leader })
      return
    }

    // 도움말 모달이 열려 있으면 `help` 키 외 전부 무동작이다. `resolveKeydown` 이
    // 그 경우에도 `none` 을 돌려주므로 여기서 한 번 더 막지 않으면 배후 목록이
    // 움직인다(E7).
    if (ctx.helpOpenRef.current) return

    const contextAction = resolveContextKeydown(e.key, resolveActiveContext())
    if (contextAction.kind === 'none') return

    // 후행 bubble 리스너(예: `usePaneEscapeClose`)가 `defaultPrevented` 로 걸러낼
    // 수 있도록 발화 시 반드시 막는다(ADR D-2 파생).
    e.preventDefault()
    dispatchContextAction(contextAction)
  }

  document.addEventListener('keydown', handleKeyDown)
  return () => {
    document.removeEventListener('keydown', handleKeyDown)
    clearLeader(leader)
  }
}

/**
 * 전역 keydown 이벤트로 단축키(SHORTCUTS)를 처리하고 도움말 모달 열림 상태를
 * 소유하는 훅.
 *
 * - `enabled`가 false이면(비로그인) `document` keydown 리스너를 등록하지 않고
 *   leader 대기 상태와 타이머를 리셋한다(FR7, E8).
 * - `enabled`가 true이면 리스너를 등록하고, `shouldIgnoreEvent` 가드를
 *   `preventDefault` 이전에 통과시켜(FR9) 입력창의 정상 타이핑을 보존한다.
 * - `helpOpen` 상태는 리스너 클로저의 stale 참조를 막기 위해 `helpOpenRef`로
 *   동시에 미러링한다.
 * - 언마운트 또는 `enabled` 변경 시 리스너와 타이머를 모두 해제한다(NFR4).
 * - `enabled`와 같은 조건으로 `useKeymap`을 구독해 사용자가 커스터마이즈한 단축키
 *   override를 로드한다(FR-PF-03 Task-8, FR5-b) — 이 훅이 앱 전역에서 한 번 마운트되는
 *   지점(RootLayout)이 그대로 "부트 로드"가 되므로 별도의 App 부트 배선이 필요 없다.
 *   데이터가 아직 없으면(비로그인/로딩 중/에러) `DEFAULT_KEYMAP`으로 폴백해 무회귀를 보장한다.
 *
 * @param enabled 훅 활성화 여부 — RootLayout에서 인증 상태를 전달
 * @returns 도움말 모달 열림 상태와 setter
 */
export function useKeyboardShortcuts(enabled: boolean): UseKeyboardShortcutsResult {
  const navigate = useNavigate()
  const [helpOpen, setHelpOpen] = useState(false)
  const helpOpenRef = useRef(helpOpen)
  const pendingLeaderRef = useRef<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { data: keymapResponse } = useKeymap({ enabled })
  const keymap = useMemo(() => toEffectiveKeymap(keymapResponse?.bindings), [keymapResponse])

  useEffect(() => {
    helpOpenRef.current = helpOpen
  }, [helpOpen])

  useEffect(() => {
    const leader: LeaderState = { pendingLeaderRef, timerRef }
    return attachShortcutListener(enabled, leader, { navigate, setHelpOpen, helpOpenRef }, keymap)
  }, [enabled, navigate, keymap])

  return { helpOpen, setHelpOpen }
}
