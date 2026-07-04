// 전역 키보드 단축키 레지스트리 + keydown 판별 순수 로직 — FR-UX-05 Task-1
/** leader-key 시퀀스(`g` + 다음 키)를 여는 키 */
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
 * 단축키가 발화하는 방식.
 *
 * - `single`: 수정자 없이 `e.key === key`면 즉시 발화
 * - `leader`: `LEADER_KEY` 직후(타임아웃 이내) `e.key === key`면 발화
 */
type ShortcutTrigger = { type: 'single'; key: string } | { type: 'leader'; key: string }

/** 단축키 정의 — 도움말 모달과 `resolveKeydown`이 함께 구동하는 단일 진실 출처 */
export interface ShortcutDef {
  /** 화면 표기용 키 조합 (예: ['g', 'i']) */
  readonly keys: readonly string[]
  /** 사용자에게 보여줄 한국어 설명 */
  readonly description: string
  /** 발화 조건 */
  readonly trigger: ShortcutTrigger
  /** 발화 시 실행할 액션 */
  readonly action: ShortcutAction
}

/** 전역 단축키 5종 — 도움말 모달·`resolveKeydown` 공용 레지스트리(FR8) */
export const SHORTCUTS: readonly ShortcutDef[] = [
  {
    keys: ['?'],
    description: '단축키 도움말 열기/닫기',
    trigger: { type: 'single', key: '?' },
    action: { kind: 'toggle-help' },
  },
  {
    keys: ['c'],
    description: '새 이슈 생성',
    trigger: { type: 'single', key: 'c' },
    action: { kind: 'navigate', to: '/issues/new' },
  },
  {
    keys: ['/'],
    description: '검색으로 이동',
    trigger: { type: 'single', key: '/' },
    action: { kind: 'navigate', to: '/search' },
  },
  {
    keys: ['g', 'i'],
    description: '내 이슈로 이동',
    trigger: { type: 'leader', key: 'i' },
    action: { kind: 'navigate', to: '/issues' },
  },
  {
    keys: ['g', 'd'],
    description: '대시보드로 이동',
    trigger: { type: 'leader', key: 'd' },
    action: { kind: 'navigate', to: '/dashboards' },
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
 * keydown을 단축키 액션으로 해석하는 순수 함수. 부수효과 없음(호출부=훅 책임).
 *
 * 검사 순서(중요) — (1) 도움말 열림 중이면 `?`만 반응, 나머지는 무동작(E7,
 * 모달 이탈 방지) (2) `e.key === LEADER_KEY`이면 대기 상태 진입/재시작(E3,
 * `g` 연타는 리셋이 아니라 재대기) (3) leader 대기 중이면 등록된 다음 키만
 * 발화, 그 외는 리셋(E2) (4) 단일 키 매칭.
 *
 * @param e keydown 이벤트에서 `key`만 뽑은 부분 타입
 * @param pendingLeader 대기 중인 leader 키(없으면 null)
 * @param helpOpen 도움말 모달 열림 여부
 * @returns 해석된 단축키 액션
 */
export function resolveKeydown(
  e: Pick<KeyboardEvent, 'key'>,
  pendingLeader: string | null,
  helpOpen: boolean,
): ShortcutAction {
  if (helpOpen) {
    return e.key === '?' ? { kind: 'toggle-help' } : { kind: 'none' }
  }

  if (e.key === LEADER_KEY) {
    return { kind: 'set-leader', leader: LEADER_KEY }
  }

  if (pendingLeader === LEADER_KEY) {
    const continuation = SHORTCUTS.find(
      (shortcut) => shortcut.trigger.type === 'leader' && shortcut.trigger.key === e.key,
    )
    return continuation ? continuation.action : { kind: 'reset' }
  }

  const single = SHORTCUTS.find(
    (shortcut) => shortcut.trigger.type === 'single' && shortcut.trigger.key === e.key,
  )
  return single ? single.action : { kind: 'none' }
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
