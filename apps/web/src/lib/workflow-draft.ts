// 워크플로우 초안 편집 리듀서와 파생 셀렉터 — React 없는 순수 로직 (FR-WF-07 D6)
import type {
  DraftDefinition,
  DraftState,
  DraftTransition,
  StatusMappingInput,
} from '@/api/workflows-draft.types'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 화면이 다루는 전환 — 서버 형태에 **로컬 식별자**만 얹었다.
 *
 * 초안 전환에는 서버 id 가 없다. 초안은 아직 DB 행이 아니라 전환 identity 가 없고, 발행
 * 시점에 DB 가 실제 id 를 정한다(`DraftTransitionDto` KDoc). 배열 인덱스를 React key 로 쓰면
 * 중간 행을 지웠을 때 뒤 행들이 통째로 리마운트되어 열려 있던 폼이 엉뚱한 값을 든다.
 *
 * [toWireDefinition] 이 저장 직전에 이 필드를 뗀다 — 서버 스키마는 `.strict()` 다.
 */
export interface EditableTransition extends DraftTransition {
  /** 이 세션 안에서만 유효한 식별자. 서버로 나가지 않는다. */
  localId: string
}

/** 화면이 다루는 초안 — 전환만 [EditableTransition] 으로 바뀐 형태. */
export interface EditableDraft extends Omit<DraftDefinition, 'transitions'> {
  transitions: EditableTransition[]
}

/** 상태를 편성할 때 카탈로그에서 가져오는 값. 이름·카테고리의 정본은 카탈로그다. */
export interface StatusCatalogChoice {
  key: string
  name: string
  category: DraftState['category']
}

/** 전환 폼이 돌려주는 입력. 규칙은 담지 않는다 — 목록 편집기는 규칙을 편집하지 않는다. */
export interface TransitionInput {
  from: string | null
  to: string
  name: string
  kind: DraftTransition['kind']
}

/**
 * 초안 편집 상태.
 *
 * @property baseVersion 낙관적 락 앵커. `loadFromServer`·`resetToDefault` 만 이 값을 바꾼다 —
 *   자동저장이나 미리보기가 갱신하면 락이 풀린다.
 * @property revision 편집 횟수. 자동저장이 「저장할 것이 있나」를 판정하는 근거이며,
 *   거절된 편집은 올리지 않는다.
 * @property lastRejection 마지막으로 거절된 편집의 사유. 화면이 그대로 띄운다.
 */
export interface DraftEditorState {
  draft: EditableDraft
  baseVersion: number
  exists: boolean
  canResetToDefault: boolean
  revision: number
  lastRejection: string | null
}

/** 리듀서가 받는 편집 액션. */
export type DraftAction =
  | {
      type: 'loadFromServer'
      definition: DraftDefinition
      baseVersion: number
      exists: boolean
      canResetToDefault: boolean
    }
  | { type: 'resetToDefault'; definition: DraftDefinition; baseVersion: number; canResetToDefault: boolean }
  | { type: 'setName'; value: string }
  | { type: 'setDescription'; value: string }
  | { type: 'addState'; entry: StatusCatalogChoice }
  | { type: 'removeState'; key: string }
  | { type: 'reorderStates'; orderedKeys: string[] }
  | { type: 'createTransition'; input: TransitionInput }
  | { type: 'updateTransition'; localId: string; input: TransitionInput }
  | { type: 'deleteTransition'; localId: string }

// ─────────────────────────────────────────────────────────────────────────────
// 초기값·변환
// ─────────────────────────────────────────────────────────────────────────────

/** 서버 응답이 오기 전의 빈 상태. */
export const initialDraftState: DraftEditorState = {
  draft: { key: '', name: '', description: null, states: [], transitions: [] },
  baseVersion: 0,
  exists: false,
  canResetToDefault: false,
  revision: 0,
  lastRejection: null,
}

/** 로컬 식별자를 만든다. `crypto.randomUUID` 가 없는 환경(구형 jsdom)을 위해 대체를 둔다. */
let localIdCounter = 0
function nextLocalId(): string {
  localIdCounter += 1
  const rand = globalThis.crypto?.randomUUID?.()
  return rand ?? `local-${String(localIdCounter)}`
}

/** 서버 정의를 편집 가능한 형태로 옮긴다. */
function toEditable(definition: DraftDefinition): EditableDraft {
  return {
    ...definition,
    transitions: definition.transitions.map((t) => ({ ...t, localId: nextLocalId() })),
  }
}

/**
 * 저장 요청에 실을 형태로 되돌린다 — 로컬 필드를 뗀다.
 *
 * ★ `validators`·`postActions` 를 **그대로 통과시킨다.** 목록 편집기는 규칙을 편집하지 않지만
 * `GET /draft` 는 규칙을 실어 보내고, 여기서 버리면 그 초안을 발행하는 순간
 * `DraftRuleWriter.writeAll` 이 빈 목록으로 덮어써 **전 워크플로우의 전환 규칙이 화면에 아무
 * 표시 없이 사라진다.** 화면의 임무는 해석이 아니라 왕복이다.
 */
export function toWireDefinition(draft: EditableDraft): DraftDefinition {
  return {
    key: draft.key,
    name: draft.name,
    description: draft.description,
    states: draft.states,
    // 스프레드로 `localId` 만 빼지 않고 **필드를 적는다** — 서버 스키마가 `.strict()` 라
    // 로컬 필드가 하나라도 새면 저장 요청이 만들어지기 전에 터지고, 나열해 두면 그 대응이
    // 눈에 보인다. 필드가 늘면 여기와 스키마가 함께 red 가 된다.
    transitions: draft.transitions.map((t) => ({
      from: t.from,
      to: t.to,
      name: t.name,
      kind: t.kind,
      validators: t.validators,
      postActions: t.postActions,
    })),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 리듀서
// ─────────────────────────────────────────────────────────────────────────────

/** 편집 결과를 담아 revision 을 올린다. */
function edited(state: DraftEditorState, draft: EditableDraft): DraftEditorState {
  return { ...state, draft, revision: state.revision + 1, lastRejection: null }
}

/** 편집을 거절한다. revision 을 올리지 않는다 — 올리면 자동저장이 헛돈다. */
function rejected(state: DraftEditorState, reason: string): DraftEditorState {
  return { ...state, lastRejection: reason }
}

/** `displayOrder` 를 1..n 으로 다시 매긴다. 순서의 정본은 배열이고 이 필드는 그 사본이다. */
function renumber(states: DraftState[]): DraftState[] {
  return states.map((s, index) => ({ ...s, displayOrder: index + 1 }))
}

/**
 * 출발지가 없는 종류인가. `GLOBAL`·`INITIAL` 이 그렇다.
 *
 * 그 둘에 `from` 이 실리면 백엔드가 400 이다 — 출발지가 없다는 것이 두 종류의 정의다.
 */
function isOriginless(kind: DraftTransition['kind']): boolean {
  return kind === 'GLOBAL' || kind === 'INITIAL'
}

/** 폼 입력을 전환 필드로 옮긴다. 출발지 없는 종류의 `from` 을 여기서 한 번에 지운다. */
function normalizeInput(input: TransitionInput): Omit<DraftTransition, 'validators' | 'postActions'> {
  return {
    from: isOriginless(input.kind) ? null : input.from,
    to: input.to,
    name: input.name,
    kind: input.kind,
  }
}

/**
 * 초안 편집 리듀서.
 *
 * 순수 함수이고 React 를 모른다 — 캐스케이드 규칙([removeState])과 보존 규칙
 * ([updateTransition])을 컴포넌트가 아니라 여기 한 곳에 둔다.
 */
export function draftReducer(state: DraftEditorState, action: DraftAction): DraftEditorState {
  switch (action.type) {
    case 'loadFromServer':
      return {
        draft: toEditable(action.definition),
        baseVersion: action.baseVersion,
        exists: action.exists,
        canResetToDefault: action.canResetToDefault,
        revision: 0,
        lastRejection: null,
      }

    case 'resetToDefault':
      // 복원은 서버가 새 앵커를 준다. 편집 이력은 버려진 것이므로 revision 도 0 이다.
      return {
        draft: toEditable(action.definition),
        baseVersion: action.baseVersion,
        exists: true,
        canResetToDefault: action.canResetToDefault,
        revision: 0,
        lastRejection: null,
      }

    case 'setName':
      return edited(state, { ...state.draft, name: action.value })

    case 'setDescription':
      return edited(state, {
        ...state.draft,
        description: action.value.length > 0 ? action.value : null,
      })

    case 'addState': {
      if (state.draft.states.some((s) => s.key === action.entry.key)) {
        return state
      }
      // ★ 이름·카테고리를 카탈로그 값 그대로 싣는다. 화면이 임의로 채우면
      //   `DraftIdentityGuard.requireStatesMatchCatalog` 가 저장을 400 으로 막는다.
      const appended: DraftState[] = [
        ...state.draft.states,
        { key: action.entry.key, name: action.entry.name, category: action.entry.category, displayOrder: 0 },
      ]
      return edited(state, { ...state.draft, states: renumber(appended) })
    }

    case 'removeState': {
      const states = state.draft.states.filter((s) => s.key !== action.key)
      // ★ 그 상태를 가리키는 전환을 **함께** 뺀다. 상태만 빼면 `Workflow.of` 의
      //   `requireValidTransitions` 가 400 을 내고 그 순간부터 자동저장이 전부 실패한다 —
      //   사용자는 무엇이 잘못됐는지 모른 채 편집이 멈춘다.
      const transitions = state.draft.transitions.filter(
        (t) => t.from !== action.key && t.to !== action.key,
      )
      return edited(state, { ...state.draft, states: renumber(states), transitions })
    }

    case 'reorderStates': {
      const byKey = new Map(state.draft.states.map((s) => [s.key, s]))
      const ordered = action.orderedKeys.flatMap((key) => {
        const found = byKey.get(key)
        return found === undefined ? [] : [found]
      })
      // 목록에 없던 상태가 조용히 사라지지 않게 뒤에 붙인다.
      const missing = state.draft.states.filter((s) => !action.orderedKeys.includes(s.key))
      return edited(state, { ...state.draft, states: renumber([...ordered, ...missing]) })
    }

    case 'createTransition': {
      if (action.input.kind === 'INITIAL' && state.draft.transitions.some((t) => t.kind === 'INITIAL')) {
        // 발행 경계가 「정확히 1개」를 요구한다. 로컬에서 막으면 저장 왕복 없이 즉시 안다.
        return rejected(state, '시작 전환은 하나만 둘 수 있습니다')
      }
      const created: EditableTransition = {
        ...normalizeInput(action.input),
        validators: [],
        postActions: [],
        localId: nextLocalId(),
      }
      return edited(state, { ...state.draft, transitions: [...state.draft.transitions, created] })
    }

    case 'updateTransition': {
      const target = state.draft.transitions.find((t) => t.localId === action.localId)
      if (target === undefined) {
        return state
      }
      if (
        action.input.kind === 'INITIAL' &&
        state.draft.transitions.some((t) => t.kind === 'INITIAL' && t.localId !== action.localId)
      ) {
        return rejected(state, '시작 전환은 하나만 둘 수 있습니다')
      }
      const transitions = state.draft.transitions.map((t) =>
        // ★ 규칙은 손대지 않는다 — 목록 편집기는 규칙을 편집하지 않고, 버리면 발행이
        //   전 워크플로우의 규칙을 빈 목록으로 덮어쓴다.
        t.localId === action.localId ? { ...t, ...normalizeInput(action.input) } : t,
      )
      return edited(state, { ...state.draft, transitions })
    }

    case 'deleteTransition':
      return edited(state, {
        ...state.draft,
        transitions: state.draft.transitions.filter((t) => t.localId !== action.localId),
      })
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 파생 셀렉터
// ─────────────────────────────────────────────────────────────────────────────

/** 발행하면 이 워크플로우에서 빠지는 상태 키 — 발행본에는 있고 초안에 없는 것. */
export function removedStatusKeys(draft: EditableDraft, published: DraftDefinition): string[] {
  const kept = new Set(draft.states.map((s) => s.key))
  return published.states.filter((s) => !kept.has(s.key)).map((s) => s.key)
}

/**
 * 이관 도착지가 될 수 있는 상태 — **초안과 발행본 양쪽에 있는** 것뿐이다.
 *
 * ★ 백엔드 가드 둘을 화면이 그대로 지킨다.
 * - F8. 도착지가 초안 상태에 있어야 한다 — 발행 뒤 사라질 곳으로 옮기면 유령이 또 생긴다
 * - F16. 도착지가 지금 편성에도 있어야 한다 — 이관은 발행 **전에** 실행되므로 아직 발행되지
 *   않은 상태로는 옮길 수 없다
 *
 * 화면이 초안 상태만 제시하면 새로 추가한 상태가 후보로 뜨고, 고르면 400 이다.
 */
export function migrationTargets(draft: EditableDraft, published: DraftDefinition): DraftState[] {
  const live = new Set(published.states.map((s) => s.key))
  return draft.states.filter((s) => live.has(s.key))
}

/** 상태 키 → 사람이 읽는 이름. 화면이 키를 그대로 그리면 사용자가 `in_progress` 를 읽는다. */
export function stateNameMap(definition: DraftDefinition | EditableDraft): Record<string, string> {
  return Object.fromEntries(definition.states.map((s) => [s.key, s.name]))
}

/**
 * 발행을 막는 **로컬에서 알 수 있는** 사유. 없으면 null.
 *
 * 서버가 어차피 검사하지만, 눌러 보고 400 을 받게 두지 않는다. 특히 시작 전환은 상태를 빼는
 * 캐스케이드로 **조용히 사라질 수 있어** 사용자가 원인을 짐작하기 어렵다.
 *
 * 여기서 알 수 없는 것(형제 워크플로우·스킴 미연결·상한 초과)은 서버 문장을 그대로 보여준다.
 */
export function publishBlockReason(draft: EditableDraft): string | null {
  if (draft.states.length === 0) {
    return '상태가 하나도 없습니다. 발행하려면 최소 하나가 필요합니다'
  }
  const initialCount = draft.transitions.filter((t) => t.kind === 'INITIAL').length
  if (initialCount === 0) {
    return '이슈 생성 시 진입할 상태를 정하는 전환이 없습니다'
  }
  if (initialCount > 1) {
    return '시작 전환이 둘 이상입니다. 하나만 남겨 주세요'
  }
  return null
}

/** 빠지는 상태마다 도착지를 고른 결과를 이관 요청 형태로 옮긴다. */
export function toMappingInputs(chosen: Record<string, string>): StatusMappingInput[] {
  return Object.entries(chosen).map(([fromStatusKey, toStatusKey]) => ({ fromStatusKey, toStatusKey }))
}
