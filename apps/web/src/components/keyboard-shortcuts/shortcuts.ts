// 전역 키보드 단축키 레지스트리 + keydown 판별 순수 로직 — FR-UX-05 Task-1, FR-PF-03 Task-6(action id·병합 키맵)
/** leader-key 시퀀스(`g` + 다음 키)를 여는 키 — FR-PF-03도 이 키는 고정으로 유지한다(YAGNI, 재배치는 후속) */
export const LEADER_KEY = 'g'

/** leader-key 입력 후 다음 키를 기다리는 최대 시간(ms) — 이 시간이 지나면 시퀀스가 리셋된다 */
export const LEADER_TIMEOUT_MS = 1000

/**
 * keydown 해석 결과 — 판별 유니온.
 *
 * - `navigate`: 라우터로 `to` 경로 이동
 * - `toggle-help`: 단축키 도움말 모달 열림/닫힘 토글
 * - `set-leader`: leader-key 대기 상태 진입(또는 재시작)
 * - `reset`: leader-key 대기 상태 해제(무효 키 입력)
 * - `none`: 처리할 단축키 없음(무동작)
 */
export type ShortcutAction =
  | { kind: 'navigate'; to: string }
  | { kind: 'toggle-help' }
  | { kind: 'set-leader'; leader: string }
  | { kind: 'reset' }
  | { kind: 'none' }

/**
 * 단축키 안정 action id 5종 — FR-PF-03 백엔드 `KeymapAction` 화이트리스트와 값이
 * 일치하는 계약(SSOT는 백엔드). 사용자는 이 id에 배정된 key_combo만 재배치할 뿐,
 * id가 가리키는 동작(effect)은 고정이다.
 */
export type KeymapActionId = 'help' | 'create-issue' | 'search' | 'goto-my-issues' | 'goto-dashboard'

/**
 * action id → key_combo 정규화 문자열 전체 매핑(5종 모두 채워짐).
 *
 * key_combo 형식 — single(1글자, 예: `c`)이거나 leader(`g <key>`, 공백 1칸, 예: `g i`).
 * 기본 키맵(`DEFAULT_KEYMAP`) 또는 사용자 override와 병합된 effective 키맵을 담는다.
 */
export type Keymap = Readonly<Record<KeymapActionId, string>>

/**
 * 단축키가 발화하는 방식(effective key_combo에서 파생, `parseKeyCombo` 참조).
 *
 * - `single`: 수정자 없이 `e.key === key`면 즉시 발화
 * - `leader`: `LEADER_KEY` 직후(타임아웃 이내) `e.key === key`면 발화
 */
type ShortcutTrigger = { type: 'single'; key: string } | { type: 'leader'; key: string }

/** 단축키 정의 — 도움말 모달과 `resolveKeydown`이 함께 구동하는 단일 진실 출처 */
export interface ShortcutDef {
  /** 안정 action id — FR-PF-03 키맵 커스터마이즈가 이 id로 key_combo를 재배치한다 */
  readonly action: KeymapActionId
  /** 화면 표기용 기본 키 조합 (예: ['g', 'i']) — 사용자 override는 반영하지 않는 정적 표기 */
  readonly keys: readonly string[]
  /** 사용자에게 보여줄 한국어 설명 */
  readonly description: string
  /** 발화 시 실행할 부수효과 */
  readonly effect: ShortcutAction
}

/**
 * action id → 기본 key_combo 매핑 — 백엔드 `KeymapAction` 기본값과 값이 일치해야
 * 하는 계약(FR-PF-03 §배경). `resolveKeydown`의 `keymap` 인자 기본값으로 쓰인다.
 */
export const DEFAULT_KEYMAP: Keymap = {
  help: '?',
  'create-issue': 'c',
  search: '/',
  'goto-my-issues': 'g i',
  'goto-dashboard': 'g d',
}

/**
 * 전역 단축키 5종 — 도움말 모달·`resolveKeydown` 공용 레지스트리(FR8).
 *
 * key_combo의 SSOT는 `DEFAULT_KEYMAP`(+ 사용자 override 병합)이다. 여기 `keys`는
 * 도움말 모달의 정적 기본 표기용일 뿐, `resolveKeydown`의 실제 매칭에는 쓰이지 않는다.
 */
export const SHORTCUTS: readonly ShortcutDef[] = [
  {
    action: 'help',
    keys: ['?'],
    description: '단축키 도움말 열기/닫기',
    effect: { kind: 'toggle-help' },
  },
  {
    action: 'create-issue',
    keys: ['c'],
    description: '새 이슈 생성',
    effect: { kind: 'navigate', to: '/issues/new' },
  },
  {
    action: 'search',
    keys: ['/'],
    description: '검색으로 이동',
    effect: { kind: 'navigate', to: '/search' },
  },
  {
    action: 'goto-my-issues',
    keys: ['g', 'i'],
    description: '내 이슈로 이동',
    effect: { kind: 'navigate', to: '/issues' },
  },
  {
    action: 'goto-dashboard',
    keys: ['g', 'd'],
    description: '대시보드로 이동',
    effect: { kind: 'navigate', to: '/dashboards' },
  },
]

/**
 * 명령 팔레트(FR-UX-04, Cmd+K)는 별도 훅이 처리 — 도움말 모달 표기 전용 항목.
 *
 * 팔레트 토글은 `metaKey || ctrlKey`로 mac(Cmd)·win/linux(Ctrl)를 모두 지원하므로
 * (useCommandPalette) 혼합 OS 환경에서 오표기하지 않도록 `Cmd/Ctrl`을 병기한다.
 */
export const PALETTE_HELP_ITEM: { readonly keys: readonly string[]; readonly description: string } = {
  keys: ['Cmd/Ctrl', 'K'],
  description: '명령 팔레트 열기',
}

/**
 * key_combo 정규화 문자열을 발화 트리거로 파싱한다.
 *
 * `g <key>`(공백 1칸, 정확히 2토큰, 첫 토큰이 `LEADER_KEY`)면 leader 트리거로,
 * 그 외는 combo 전체를 키로 하는 single 트리거로 해석한다.
 *
 * @param keyCombo 정규화된 key_combo 문자열(예: `c`, `g i`)
 * @returns 파싱된 발화 트리거
 */
function parseKeyCombo(keyCombo: string): ShortcutTrigger {
  const tokens = keyCombo.split(' ')
  const leaderToken = tokens[0]
  const continuationKey = tokens[1]
  if (tokens.length === 2 && leaderToken === LEADER_KEY && continuationKey !== undefined) {
    return { type: 'leader', key: continuationKey }
  }
  return { type: 'single', key: keyCombo }
}

/**
 * 단축키의 effective 발화 트리거를 병합 키맵 기준으로 유도한다.
 *
 * @param shortcut 트리거를 조회할 단축키 정의
 * @param keymap effective(기본값+사용자 override 병합) 키맵
 * @returns 그 단축키의 현재 발화 트리거
 */
function effectiveTrigger(shortcut: ShortcutDef, keymap: Keymap): ShortcutTrigger {
  return parseKeyCombo(keymap[shortcut.action])
}

/**
 * keydown을 단축키 액션으로 해석하는 순수 함수. 부수효과 없음(호출부=훅 책임).
 *
 * 검사 순서(중요) — (1) 도움말 열림 중이면 `help` action의 effective key_combo가
 * single 트리거로 파싱될 때만 반응(FR5-a — `help` 재배치 시 도움말을 닫는 키도
 * 그 combo를 따라간다), 나머지는 무동작(E7, 모달 이탈 방지) (2) `e.key ===
 * LEADER_KEY`이면 대기 상태 진입/재시작(E3, `g` 연타는 리셋이 아니라 재대기)
 * (3) leader 대기 중이면 등록된 다음 키만 발화, 그 외는 리셋(E2) (4) 단일 키 매칭.
 *
 * 단축키별 발화 트리거는 `SHORTCUTS`에 하드코딩된 값이 아니라 `keymap[shortcut.action]`
 * (기본값+사용자 override 병합)을 `parseKeyCombo`로 파싱해 매 호출마다 유도한다.
 *
 * @param e keydown 이벤트에서 `key`만 뽑은 부분 타입
 * @param pendingLeader 대기 중인 leader 키(없으면 null)
 * @param helpOpen 도움말 모달 열림 여부
 * @param keymap effective(기본값+사용자 override 병합) 키맵 — 생략 시 `DEFAULT_KEYMAP`을
 *   사용한다(옵셔널 기본값 — FR-PF-03 Task-8 이전의 기존 호출부가 3-인자 그대로
 *   컴파일되도록 하는 인터페이스 확장 default 패턴)
 * @returns 해석된 단축키 액션
 */
export function resolveKeydown(
  e: Pick<KeyboardEvent, 'key'>,
  pendingLeader: string | null,
  helpOpen: boolean,
  keymap: Keymap = DEFAULT_KEYMAP,
): ShortcutAction {
  if (helpOpen) {
    const helpTrigger = parseKeyCombo(keymap.help)
    return helpTrigger.type === 'single' && e.key === helpTrigger.key
      ? { kind: 'toggle-help' }
      : { kind: 'none' }
  }

  if (e.key === LEADER_KEY) {
    return { kind: 'set-leader', leader: LEADER_KEY }
  }

  if (pendingLeader === LEADER_KEY) {
    const continuation = SHORTCUTS.find((shortcut) => {
      const trigger = effectiveTrigger(shortcut, keymap)
      return trigger.type === 'leader' && trigger.key === e.key
    })
    return continuation ? continuation.effect : { kind: 'reset' }
  }

  const single = SHORTCUTS.find((shortcut) => {
    const trigger = effectiveTrigger(shortcut, keymap)
    return trigger.type === 'single' && trigger.key === e.key
  })
  return single ? single.effect : { kind: 'none' }
}

/**
 * keydown 타깃이 텍스트 편집 가능 요소인지 판별한다.
 *
 * `isContentEditable` 프로퍼티는 jsdom에서 신뢰할 수 없어(use-timeline-zoom.ts
 * 선례) `contenteditable` 속성 값도 함께 확인한다.
 *
 * @param target keydown 이벤트의 `target`
 * @returns 편집 가능 요소이면 true
 */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return (
    target.tagName === 'INPUT' ||
    target.tagName === 'TEXTAREA' ||
    target.tagName === 'SELECT' ||
    target.isContentEditable ||
    target.getAttribute('contenteditable') === 'true'
  )
}

/**
 * 이 keydown을 단축키로 처리하지 말아야 하는지 판별하는 가드 술어.
 *
 * IME 조합 중(E5)이거나 meta/ctrl/alt 수정자가 눌렸거나(FR6, Shift는 허용)
 * 이벤트 타깃이 입력 가능 요소(input/textarea/select/contentEditable, E4)이면
 * true를 반환한다. 호출부는 `preventDefault` 이전에 이 가드를 통과시켜
 * 입력창에서의 정상 타이핑을 보존해야 한다.
 *
 * @param e keydown 이벤트에서 필요한 속성만 뽑은 부분 타입
 * @returns 단축키 처리를 건너뛰어야 하면 true
 */
export function shouldIgnoreEvent(
  e: Pick<KeyboardEvent, 'isComposing' | 'metaKey' | 'ctrlKey' | 'altKey' | 'target'>,
): boolean {
  if (e.isComposing) return true
  if (e.metaKey || e.ctrlKey || e.altKey) return true
  return isEditableTarget(e.target)
}
