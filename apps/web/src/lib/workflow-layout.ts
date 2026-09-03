// 워크플로우 다이어그램 노드 좌표를 계산하는 순수 함수 (FR-WF-07 D8)
import type { StateCategory, TransitionKind } from '@/api/workflows-admin.types'

/**
 * 배치에 필요한 상태의 최소 모양. **초안 타입에 결합하지 않는다** —
 * `DraftState` 에 필드가 늘 때마다 이 모듈의 픽스처가 깨지지 않게 구조적으로 받는다.
 * 초안의 상태는 이 모양을 구조적으로 만족하므로 호출부는 그대로 넘기면 된다.
 */
export interface LayoutInputState {
  readonly key: string
  /**
   * 화면에 보이는 상태 이름.
   *
   * ★ **배치에 이름이 필요한 이유.** 노드 폭이 `min-w-36 max-w-52` 로 **가변**이라 이름 길이가
   * 폭을 정하고, 폭이 간선 끝점(핸들) 좌표를 정하고, 그것이 라벨이 놓일 자리를 정한다.
   * 이름을 빼면 라벨 겹침을 이 모듈이 **그릴 좌표와 다른 좌표계에서** 판정하게 된다.
   */
  readonly name: string
  readonly category: StateCategory
  readonly displayOrder: number
  readonly layoutX: number | null
  readonly layoutY: number | null
}

/** 캔버스에 놓일 노드 한 개의 좌표와 크기. 캔버스 좌표계(px) 기준이다. */
export interface PlacedNode {
  key: string
  x: number
  y: number
  /** 이름 길이로 정해지는 폭. 간선 끝점이 여기 달려 있다 */
  width: number
  height: number
}

/**
 * 상태 노드의 기하 — `StatusNode.tsx` 의 Tailwind 클래스와 **같은 값이라야 한다.**
 *
 * ★ 두 목록이 서로를 검사하지 않으면 조용히 갈라진다(이 저장소가 이름 붙인 지배 결함 양식).
 * `scripts/workflow/node-geometry-alignment.test.ts` 가 `StatusNode.tsx` 의
 * `min-h-11`·`min-w-36`·`max-w-52` 와 아래 세 값을 대조해 red 를 낸다.
 */
export const NODE_HEIGHT_PX = 44
export const NODE_MIN_WIDTH_PX = 144
export const NODE_MAX_WIDTH_PX = 208

/** 노드의 좌우 안쪽 여백 합(`px-3` 양쪽). 이름이 들어갈 실폭은 폭에서 이만큼 뺀 값이다. */
const NODE_PADDING_X_PX = 24

/**
 * 가상 시작 노드의 지름(`h-4 w-4`). 상태 노드와 달리 이름이 없어 고정이다.
 *
 * ★ 이름을 붙이지 않는 것이 mermaid `[*]` 와의 대응이다 — 붙이면 사용자가 상태 하나로 읽는다.
 */
export const START_NODE_SIZE_PX = 16

/** 시작 노드를 첫 열보다 얼마나 왼쪽에 둘지(px). 캔버스와 이 모듈이 **같은 값을 봐야 한다.** */
export const START_NODE_MARGIN_PX = 120

/**
 * `text-xs`(12px) 기준 반각 한 글자의 대략 폭(px).
 *
 * ★ **정확한 값이 아니라 추정이다.** 폰트 메트릭을 순수 함수가 알 방법이 없다 — 실측은 DOM 이
 * 그려진 뒤에만 가능하다. 그래서 이 추정으로 **겹칠 만한 것들을 넉넉히 잡고**, 실제로 안 겹치는지는
 * `e2e/workflow-diagram.spec.ts` 의 D8-4 가 브라우저 좌표로 잰다. 두 판정은 서로를 대신하지 않는다.
 */
const NARROW_CHAR_WIDTH_PX = 7

/** 한글·전각 한 글자의 대략 폭(px). 반각의 두 배로 잡는다. */
const WIDE_CHAR_WIDTH_PX = 13

/**
 * 글자열의 대략 폭(px). 전각(한글·한자·가나·전각기호)만 골라 두 배로 센다.
 *
 * @param text 잴 문자열
 */
function textWidthPx(text: string): number {
  let width = 0
  for (const char of text) {
    const code = char.codePointAt(0) ?? 0
    // CJK·한글·전각 구간. 정밀한 East Asian Width 표가 아니라 **폭 추정용 근사**다.
    const wide =
      (code >= 0x1100 && code <= 0x115f) ||
      (code >= 0x2e80 && code <= 0xa4cf) ||
      (code >= 0xac00 && code <= 0xd7a3) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xfe30 && code <= 0xfe6f) ||
      (code >= 0xff00 && code <= 0xff60) ||
      (code >= 0xffe0 && code <= 0xffe6)
    width += wide ? WIDE_CHAR_WIDTH_PX : NARROW_CHAR_WIDTH_PX
  }
  return width
}

/**
 * 상태 이름으로 노드 폭을 정한다 — `min-w-36 max-w-52` 사이로 접는다.
 *
 * @param name 상태 이름
 */
function nodeWidth(name: string): number {
  const wanted = textWidthPx(name) + NODE_PADDING_X_PX
  return Math.min(Math.max(wanted, NODE_MIN_WIDTH_PX), NODE_MAX_WIDTH_PX)
}

/** 카테고리 → 열 번호. 왼쪽에서 오른쪽으로 진행 방향을 그린다. */
const CATEGORY_COLUMN: Record<StateCategory, number> = {
  TODO: 0,
  IN_PROGRESS: 1,
  DONE: 2,
}

/** 카테고리 열 사이 가로 간격(px). 노드 폭보다 넉넉해야 열 사이 간선 라벨이 노드를 덮지 않는다. */
export const COLUMN_GAP_PX = 260

/** 같은 열 안 행 사이 세로 간격(px). */
const ROW_GAP_PX = 120

/**
 * 좌표가 이미 정해진 상태인지 판정한다.
 *
 * **둘 다** 채워졌을 때만 고정으로 본다. 한쪽만 있으면 노드를 놓을 수 없으므로 자동 배치 대상이다.
 */
function hasFixedPosition(
  state: LayoutInputState,
): state is LayoutInputState & { layoutX: number; layoutY: number } {
  return state.layoutX !== null && state.layoutY !== null
}

/**
 * 상태별 좌표를 계산해 상태 객체를 키로 하는 사상으로 돌려준다.
 *
 * 키를 상태 객체 자체로 잡는 이유. 상태 키 문자열이 중복돼도 결과가 서로를 덮지 않는다.
 */
function placeStates(states: readonly LayoutInputState[]): Map<LayoutInputState, PlacedNode> {
  // ★ 정렬 전에 복사한다. Array.prototype.sort 는 제자리 정렬이라 원본(초안)의 상태 순서를 뒤집는다.
  const byDisplayOrder = [...states].sort((a, b) => a.displayOrder - b.displayOrder)
  const placed = new Map<LayoutInputState, PlacedNode>()
  const filledRows = new Map<StateCategory, number>()

  for (const state of byDisplayOrder) {
    const size = { width: nodeWidth(state.name), height: NODE_HEIGHT_PX }

    if (hasFixedPosition(state)) {
      placed.set(state, { key: state.key, x: state.layoutX, y: state.layoutY, ...size })
      continue
    }
    // 고정 좌표 노드는 행을 차지하지 않는다 — 자동 배치 노드끼리만 열 안에서 쌓인다
    const row = filledRows.get(state.category) ?? 0
    placed.set(state, {
      key: state.key,
      x: CATEGORY_COLUMN[state.category] * COLUMN_GAP_PX,
      y: row * ROW_GAP_PX,
      ...size,
    })
    filledRows.set(state.category, row + 1)
  }

  return placed
}

/**
 * 좌표가 없는 상태에 카테고리 열 기준 초기 좌표를 만들어 준다 (F4).
 *
 * - `layoutX`·`layoutY` 가 둘 다 있는 상태는 그 값을 그대로 통과시킨다.
 * - 나머지는 카테고리별 열(TODO → IN_PROGRESS → DONE)에 놓고, 같은 열 안에서는
 *   `displayOrder` 순으로 아래로 쌓는다.
 * - 결과 배열은 입력 순서를 유지한다.
 *
 * ★ **자동 배치는 표시일 뿐 편집이 아니다 — 이 결과를 초안에 써 넣지 마라.** 좌표를 초안 상태에
 * 되돌려 쓰면 초안이 열리자마자 dirty 가 되어 **화면을 열기만 해도 「저장 안 됨」이 뜬다.**
 * 초안의 `layoutX`/`layoutY` 를 바꾸는 것은 사용자가 노드를 실제로 끌어 옮겼을 때뿐이다.
 * 그래서 이 함수는 입력 배열도, 입력 상태 객체도 변형하지 않는다(`autoLayout` 판정 3번).
 *
 * @param states 배치할 상태 목록
 * @returns 입력과 같은 순서의 노드 좌표 배열
 */
export function autoLayout(states: readonly LayoutInputState[]): PlacedNode[] {
  const placed = placeStates(states)
  const result: PlacedNode[] = []

  for (const state of states) {
    const node = placed.get(state)
    if (node !== undefined) result.push(node)
  }

  return result
}

// ─────────────────────────────────────────────────────────────────────────────
// 간선(엣지) 경로
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 경로 계산에 필요한 전환의 최소 모양. `LayoutInputState` 와 같은 이유로 초안 타입에 결합하지 않는다.
 * 초안의 전환(`DraftTransition`)은 이 모양을 구조적으로 만족하므로 호출부는 그대로 넘기면 된다.
 */
export interface LayoutInputTransition {
  readonly from: string | null
  readonly to: string
  readonly name: string
  readonly kind: TransitionKind
}

/** 캔버스에 그릴 간선 한 개. */
export interface RoutedEdge {
  id: string
  /** 출발 노드 id — 상태 키이거나 `START_NODE_ID` 다. **절대 null 이 아니다.** */
  source: string
  target: string
  label: string
  /**
   * 입력 배열에서의 위치. 초안 전환에는 `id` 가 없어서(아직 발행되지 않아 DB 행이 아니다) 이것이
   * 화면과 초안을 잇는 유일한 identity 다 — 간선을 고르면 이 번호로 전환을 되찾는다.
   */
  transitionIndex: number
  selfLoop: boolean
  /** 곡선을 중심선에서 얼마나 벌릴지(px). 0 이면 직선이다. */
  offset: number
  /**
   * 겹침 해소까지 끝난 **최종 라벨 자리**. 이 모듈이 판정에 쓴 모델 좌표다.
   *
   * ★ 화면 좌표와 같지 않다 — 컴포넌트는 xyflow 가 주는 핸들 좌표에 그리고 이쪽은 배치
   * 좌표로 셈한다. 그래서 **두 판정이 필요하다**. 단위 테스트는 이 사각형으로 「이 모듈이
   * 자기 모델 안에서 겹침을 실제로 없앴는가」를 재고, `e2e/workflow-diagram.spec.ts` 의
   * D8-4 가 브라우저 좌표로 「화면에서 실제로 안 겹치는가」를 잰다. 어느 쪽도 다른 쪽을
   * 대신하지 못한다 — 모델이 맞아도 화면이 틀릴 수 있고, 그 반대도 그렇다.
   */
  labelBox: LabelRect

  /**
   * 라벨을 간선 중점에서 추가로 밀어낼 거리(px).
   *
   * **`offset` 과 다른 문제를 푼다.** `offset` 은 *같은 상태쌍* 간선이 서로 겹치는 것을 막고,
   * 이것은 *서로 다른 상태쌍* 간선의 중점이 한 자리에 모여 **라벨끼리** 겹치는 것을 막는다.
   * 겹칠 이웃이 없으면 `{ x: 0, y: 0 }` 이다 — 굽힐 이유가 없는 라벨은 중점에 그대로 둔다.
   */
  labelOffset: LabelOffset
}

/** 캔버스 좌표계의 사각형. 라벨이 차지하는 자리다. */
export interface LabelRect {
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
}

/**
 * 라벨을 중점에서 밀어낼 벡터(px). 캔버스 좌표계 기준이다.
 *
 * ★ **읽기 전용이다.** 밀 이유가 없는 간선은 전부 `NO_LABEL_OFFSET` **한 객체를 공유**하고
 * 그것이 xyflow 의 `data` 로 그대로 나간다 — 가변이면 소비처 한 곳이 `y += n` 하는 순간
 * 캔버스의 모든 라벨이 함께 움직인다.
 */
export interface LabelOffset {
  readonly x: number
  readonly y: number
}

/** 간선을 만들지 않고 「모든 상태에서」 패널에 나열할 전역 전환 (F11). */
export interface GlobalTransitionRoute {
  transitionIndex: number
  to: string
  name: string
}

/** `edgeRoutes` 의 결과. */
export interface EdgeRouteResult {
  edges: RoutedEdge[]
  globals: GlobalTransitionRoute[]
  /** 가상 시작 노드를 그릴지. 그것을 출발지로 쓰는 간선이 있을 때만 참이다. */
  hasStartNode: boolean
}

/**
 * 출발지 없는 전환이 출발하는 가상 시작 노드의 id. mermaid 화면의 `[*]` 와 같은 자리다
 * (`WorkflowDiagram.tsx` 의 `transitionSourceNode` — 두 화면의 시작 표기를 같게 맞춘다).
 *
 * 상태 키와 겹치지 않도록 실무에서 쓰지 않는 밑줄 이중 접두 모양으로 잡았다.
 */
export const START_NODE_ID = '__start__'

/** 같은 상태쌍에 몰린 간선을 벌리는 간격(px). 전환 이름 라벨이 서로 겹치지 않을 만큼은 돼야 한다. */
const EDGE_BUNDLE_GAP_PX = 40

/** self-loop 고리의 기본 반지름(px). 노드 밖으로 나올 만큼은 커야 한다. */
const SELF_LOOP_BASE_RADIUS_PX = 14

/**
 * self-loop 이 여럿일 때 겹겹이 키우는 폭(px).
 *
 * ★ 작게 잡는다. 상한(`SELF_LOOP_MAX_RADIUS_PX`)에 닿는 순간부터 고리가 **완전히 포개져**
 * 여러 전환이 하나로 보이므로, 상한 안에 최대한 많이 담아야 한다. 14 에서 6 씩 키우면
 * 여섯 번째까지 서로 다른 반지름을 받는다.
 */
const SELF_LOOP_STEP_PX = 6

/**
 * self-loop 고리 반지름의 세로 상한(px). 노드 높이와 같다.
 *
 * **상한이 없으면 고리가 이웃 행을 삼킨다** (부채 170). 옛 기본값 60 은 지름 120 으로
 * `ROW_GAP_PX` 와 정확히 같아 아래 행 노드에 걸쳤다.
 */
const SELF_LOOP_MAX_RADIUS_PX = 44

/** 라벨을 self-loop 고리 바깥으로 더 밀어낼 여백(px). */
const SELF_LOOP_LABEL_GAP_PX = 10

/** 라벨 상자의 좌우 안쪽 여백 합(`px-1.5` 양쪽)과 테두리. */
const LABEL_PADDING_X_PX = 14

/** 라벨 상자 한 줄의 높이(px). `text-xs` + `py-0.5` + ring. */
const LABEL_HEIGHT_PX = 22

/**
 * 겹친 라벨을 세로로 한 칸 밀 때의 간격(px).
 *
 * ★ **세로다.** 처음에는 간선의 법선 방향으로 40px 밀었는데 E2E 가 red 였다 —
 * `Submit for Review` 는 폭이 100px 를 넘어 **가로 40px 분리로는 여전히 포개진다.**
 * 라벨은 가로로 길고 세로로 짧으므로 세로가 같은 간격으로 훨씬 확실하게 떨어진다.
 */
const LABEL_SPREAD_STEP_PX = 26

/**
 * 라벨 하나를 옮겨 볼 최대 횟수.
 *
 * ★ **무한 루프를 막는 상한이 아니라 포기 지점이다.** 라벨이 아주 많으면 어느 배치도
 * 전부를 떼어 놓을 수 없다. 그때는 **밀다 만 자리**에 두는 편이 낫다 — 원점에 되돌리면
 * 이미 자리를 잡은 이웃과 다시 겹친다.
 */
const LABEL_SPREAD_MAX_TRIES = 12

/** 쌍 키 구분자. 상태 키에 섞일 수 없는 문자라야 `a\0b`·`ab\0` 같은 충돌이 안 난다. */
const PAIR_KEY_SEPARATOR = '\u0000'

/**
 * 전환의 출발 노드 id 를 고른다.
 *
 * ★ **여기서 `from` 을 그대로 문자열 보간하면 안 된다.** mermaid 화면이 `${from} --> ${to}` 로
 * 보간했다가 `from` 이 null 인 전환에서 **`null` 이라는 이름의 상태 노드**를 만들어 낸 실사고가
 * 있다(learnings.md:223). 그래서 null 을 여기서 한 번에 걸러 낸다.
 *
 * @param transition 전환
 * @returns 출발 노드 id. `GLOBAL` 은 간선을 만들지 않으므로 null 이다
 */
function sourceNodeId(transition: LayoutInputTransition): string | null {
  // GLOBAL 은 「모든 상태에서」다. 모든 노드에서 선을 뽑으면 화면을 읽을 수 없어 패널로 뺀다
  if (transition.kind === 'GLOBAL') return null
  // INITIAL 은 출발지가 없는 것이 정의다. NORMAL 인데 from 이 비어 있는 계약 위반 데이터도
  // 같은 자리로 떨어뜨린다 — 전환을 통째로 버리는 것보다 시작 노드에 매다는 쪽이 눈에 띈다
  return transition.from ?? START_NODE_ID
}

/** 출발·도착 쌍을 Map 키 하나로 접는다. */
function pairKey(source: string, target: string): string {
  return `${source}${PAIR_KEY_SEPARATOR}${target}`
}

/**
 * 상태쌍마다 간선이 몇 개인지 미리 센다. 벌림 폭이 총수에 달려 있어 한 번 훑고 시작해야 한다.
 *
 * @param transitions 전환 목록
 * @returns 쌍 키 → 간선 총수. 간선을 만들지 않는 `GLOBAL` 은 세지 않는다
 */
function countByPair(transitions: readonly LayoutInputTransition[]): Map<string, number> {
  const totals = new Map<string, number>()

  for (const transition of transitions) {
    const source = sourceNodeId(transition)
    if (source === null) continue
    const key = pairKey(source, transition.to)
    totals.set(key, (totals.get(key) ?? 0) + 1)
  }

  return totals
}

/**
 * 같은 상태쌍에 몰린 간선을 중심선 좌우로 대칭 분산한다 (F12 — FR-WF-05 의 다중 전환).
 *
 * 홑간선(`total === 1`)은 0 이다 — 굽힐 이유가 없다. 총수가 홀수면 가운데 하나가 0 을 받는다.
 *
 * @param index 그 쌍 안에서 몇 번째 간선인지 (0-based)
 * @param total 그 쌍의 간선 총수
 * @returns 중심선에서 벌릴 거리(px). 음수는 반대쪽이다
 */
function bundleOffset(index: number, total: number): number {
  return (index - (total - 1) / 2) * EDGE_BUNDLE_GAP_PX
}

/**
 * self-loop 고리의 반지름을 정한다 (F10 — J5).
 *
 * `bundleOffset` 과 달리 **0 이 나오면 안 된다.** self-loop 은 출발점과 도착점이 같은 자리라
 * 0 이면 노드 뒤에 완전히 숨어 전환이 있는지조차 보이지 않는다. 그래서 좌우 대칭이 아니라
 * 기본 반지름에서 바깥으로 겹겹이 키우되, **두 방향의 상한**에 함께 걸린다.
 *
 * - **세로** — `SELF_LOOP_MAX_RADIUS_PX`. 넘으면 아래 행 노드를 삼킨다.
 * - **가로** — 고리는 노드 오른쪽에 지름(`2r`)만큼 뻗으므로 다음 열까지의 여유를 넘으면
 *   **옆 열 노드를 관통한다.** 부채 170 과 같은 증상이 90도 돌아간 것이다. 노드가
 *   `max-w-52`(208px)면 `COLUMN_GAP_PX`(260) 에서 남는 가로 여유는 52px 뿐이다.
 *
 * @param index 그 노드의 몇 번째 self-loop 인지 (0-based)
 * @param sourceWidth 고리가 달린 노드의 폭(px)
 * @returns 반지름(px). 항상 0 보다 크다
 */
function selfLoopRadius(index: number, sourceWidth: number): number {
  const wanted = SELF_LOOP_BASE_RADIUS_PX + index * SELF_LOOP_STEP_PX
  const horizontalRoom = (COLUMN_GAP_PX - sourceWidth - SELF_LOOP_LABEL_GAP_PX) / 2
  // 여유가 음수로 나오는 배치(손으로 노드를 붙여 놓은 경우)에서도 고리는 보여야 한다
  return Math.max(Math.min(wanted, SELF_LOOP_MAX_RADIUS_PX, horizontalRoom), SELF_LOOP_BASE_RADIUS_PX)
}

/**
 * 밀어낼 이웃이 없는 라벨의 오프셋. 매번 새 객체를 만들지 않는다.
 *
 * 공유 객체라 얼려 둔다 — 타입의 `readonly` 는 컴파일 시각뿐이고, 이 값은 `data` 를 통해
 * 타입 밖(`Record<string, unknown>`)으로 나간다.
 */
const NO_LABEL_OFFSET: LabelOffset = Object.freeze({ x: 0, y: 0 })

/**
 * 좌표를 몰라 자리를 못 정한 라벨의 사각형.
 *
 * 폭·높이가 0 이라 **어떤 사각형과도 겹치지 않는다** — 좌표 없이 부른 호출(빈 배치)에서
 * 겹침 판정이 거짓 양성을 내지 않는다.
 */
const UNPLACED_LABEL_BOX: LabelRect = Object.freeze({ x: 0, y: 0, width: 0, height: 0 })

/** 간선 하나를 만드는 데 필요한 자리 정보. */
interface EdgeSlot {
  readonly transition: LayoutInputTransition
  readonly transitionIndex: number
  readonly source: string
  /** 출발 노드의 폭(px). self-loop 고리가 옆 열을 안 뚫을 만큼만 커지도록 재는 데 쓴다 */
  readonly sourceWidth: number
  /** 같은 상태쌍 안에서 몇 번째인지 (0-based) */
  readonly index: number
  /** 같은 상태쌍의 간선 총수 */
  readonly total: number
}

/**
 * 자리 정보를 캔버스 간선 하나로 옮긴다.
 *
 * @param slot 전환과 그 전환이 놓일 자리
 * @returns 간선. `id` 는 입력 위치를 달고 있어 같은 쌍의 전환이 여럿이어도 겹치지 않는다
 */
function routedEdge(slot: EdgeSlot): RoutedEdge {
  const { transition, transitionIndex, source, sourceWidth, index, total } = slot
  const selfLoop = source === transition.to

  return {
    id: `${source}->${transition.to}#${transitionIndex}`,
    source,
    target: transition.to,
    label: transition.name,
    transitionIndex,
    selfLoop,
    offset: selfLoop ? selfLoopRadius(index, sourceWidth) : bundleOffset(index, total),
    labelOffset: NO_LABEL_OFFSET,
    // 자리는 `spreadLabels` 가 채운다 — 노드 좌표를 알아야 셀 수 있어 여기서는 못 정한다
    labelBox: UNPLACED_LABEL_BOX,
  }
}

/** 두 사각형이 포개지는가. 맞닿기만 한 것은 겹침이 아니다. */
export function labelsOverlap(a: LabelRect, b: LabelRect): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
}

/**
 * 간선의 출발·도착 핸들 좌표.
 *
 * ★ **노드 좌표가 아니라 핸들 좌표다.** `StatusNode` 는 출발 핸들을 오른쪽 중앙,
 * 도착 핸들을 왼쪽 중앙에 둔다(`Position.Right` · `Position.Left`). 라벨은 그 두 점 사이에
 * 그려지므로, 노드 top-left 로 셈하면 **그리는 좌표계와 다른 곳에서** 겹침을 판정하게 된다.
 */
function handlePoints(source: PlacedNode, target: PlacedNode): {
  sourceX: number
  sourceY: number
  targetX: number
  targetY: number
} {
  return {
    sourceX: source.x + source.width,
    sourceY: source.y + source.height / 2,
    targetX: target.x,
    targetY: target.y + target.height / 2,
  }
}

/**
 * 라벨이 실제로 놓일 사각형을 계산한다 — **`TransitionEdge` 가 그리는 자리와 같은 셈이다.**
 *
 * 세 갈래가 컴포넌트의 세 갈래와 짝을 이룬다.
 * - `selfLoop` — 고리 오른쪽 바깥. 고리 지름과 여백만큼 밀린다
 * - `offset !== 0` — 2차 베지에라 곡선 중점은 제어점의 **절반**만큼 밀린다
 * - 그 밖 — 두 핸들의 중점
 *
 * @returns 라벨 사각형. 두 끝 중 하나라도 배치에 없으면 null 이다
 */
function labelRect(edge: RoutedEdge, byKey: Map<string, PlacedNode>): LabelRect | null {
  const source = byKey.get(edge.source)
  const target = byKey.get(edge.target)
  if (source === undefined || target === undefined) return null

  const width = textWidthPx(edge.label) + LABEL_PADDING_X_PX
  const { sourceX, sourceY, targetX, targetY } = handlePoints(source, target)

  if (edge.selfLoop) {
    // ★ 고리 라벨은 **왼쪽 정렬**이다(`TransitionEdge` 가 `translate(0,-50%)` 로 그린다).
    //   가운데 정렬이면 폭의 절반이 고리 안으로 들어가는데, 컴포넌트는 폭을 모르므로
    //   그것을 보정할 수 없다. 왼쪽 정렬은 폭을 몰라도 상자가 고리 바깥에서 시작한다.
    return {
      x: sourceX + edge.offset * 2 + SELF_LOOP_LABEL_GAP_PX,
      y: sourceY - LABEL_HEIGHT_PX / 2,
      width,
      height: LABEL_HEIGHT_PX,
    }
  }

  let centerX: number
  let centerY: number

  if (edge.offset !== 0) {
    const dx = targetX - sourceX
    const dy = targetY - sourceY
    const length = Math.hypot(dx, dy) || 1
    centerX = (sourceX + targetX) / 2 + (-(dy / length) * edge.offset) / 2
    centerY = (sourceY + targetY) / 2 + ((dx / length) * edge.offset) / 2
  } else {
    centerX = (sourceX + targetX) / 2
    centerY = (sourceY + targetY) / 2
  }

  return {
    x: centerX - width / 2,
    y: centerY - LABEL_HEIGHT_PX / 2,
    width,
    height: LABEL_HEIGHT_PX,
  }
}

/**
 * n 번째 시도에서 라벨을 세로로 얼마나 옮길지. `0, +26, -26, +52, -52 …` 로 번갈아 벌린다.
 *
 * 한쪽으로만 밀면 그룹이 통째로 아래로 흘러 원래 간선에서 멀어진다. 번갈아 밀면 중앙값이
 * 제자리에 남아 **어느 간선의 이름인지**가 덜 흐려진다.
 */
function spreadStep(tries: number): number {
  const rank = Math.ceil(tries / 2)
  return (tries % 2 === 1 ? rank : -rank) * LABEL_SPREAD_STEP_PX
}

/**
 * 라벨이 서로 포개지지 않도록 세로로 밀어낸다 (부채 168).
 *
 * **`bundleOffset` 이 못 푸는 문제다.** 그쪽은 같은 상태쌍 안에서만 벌리는데, 카테고리 열 배치는
 * *서로 다른 상태쌍*의 라벨을 한 자리에 모은다 — `software-default` 에서 `open→closed` 와
 * `in_progress↔in_review` 의 라벨 자리가 `520 = 2 × 260` 때문에 대수적으로 같다.
 *
 * ### 격자가 아니라 실제 사각형으로 판정하는 이유
 * 앵커를 격자 칸으로 묶으면 **칸 경계를 사이에 둔 2px 차이가 다른 칸**이 되어 눈에 겹치는
 * 둘을 못 잡고, 반대로 안 겹치는 둘을 억지로 민다. 라벨 폭은 이름 길이로 크게 달라지므로
 * (`Submit for Review` 100px 대 `Cancel` 45px) 폭을 모르는 판정은 어느 쪽으로도 틀린다.
 *
 * ### self-loop 도 함께 넣는 이유
 * 고리 라벨은 노드 오른쪽에 붙고, 같은 노드에서 오른쪽으로 나가는 일반 간선의 라벨도 그 근처다.
 * 한쪽만 빼면 **그 둘은 영영 안 떨어진다.**
 *
 * @param edges 간선 목록. **제자리에서 바꾸지 않고** 새 배열을 돌려준다
 * @param byKey 배치된 노드. 시작 노드까지 들어 있어야 그 간선의 라벨도 참여한다
 */
function spreadLabels(edges: readonly RoutedEdge[], byKey: Map<string, PlacedNode>): RoutedEdge[] {
  const rects: { edge: RoutedEdge; rect: LabelRect }[] = []
  for (const edge of edges) {
    const rect = labelRect(edge, byKey)
    if (rect !== null) rects.push({ edge, rect })
  }

  // 결정론적 순서로 놓는다 — 입력 순서가 같으면 결과도 같아야 리렌더가 라벨을 흔들지 않는다.
  rects.sort((a, b) => a.rect.y - b.rect.y || a.rect.x - b.rect.x || a.edge.id.localeCompare(b.edge.id))

  const settled: LabelRect[] = []
  const boxes = new Map<string, LabelRect>()
  const shifts = new Map<string, LabelOffset>()

  for (const { edge, rect } of rects) {
    let tries = 0
    let shift = 0
    let candidate = rect

    while (tries < LABEL_SPREAD_MAX_TRIES && settled.some((other) => labelsOverlap(candidate, other))) {
      tries += 1
      shift = spreadStep(tries)
      candidate = { ...rect, y: rect.y + shift }
    }

    settled.push(candidate)
    boxes.set(edge.id, candidate)
    // 이름이 빈 전환은 라벨 상자를 그리지 않으므로 밀 것도 없다(컴포넌트가 null 을 낸다).
    if (shift !== 0) shifts.set(edge.id, { x: 0, y: shift })
  }

  return edges.map((edge) => {
    const shift = shifts.get(edge.id)
    const box = boxes.get(edge.id)
    if (shift === undefined && box === undefined) return edge
    return {
      ...edge,
      labelOffset: shift ?? edge.labelOffset,
      labelBox: box ?? edge.labelBox,
    }
  })
}

/**
 * 가상 시작 노드의 x 좌표. 첫 열보다 `START_NODE_MARGIN_PX` 만큼 왼쪽이다.
 *
 * ★ **캔버스와 이 모듈이 같은 셈을 봐야 한다.** 캔버스가 따로 계산하면 라벨 겹침 판정이
 * 그리는 자리와 다른 곳을 보게 된다 — 그래서 정본을 여기 두고 캔버스가 이것을 부른다.
 *
 * @param placed 배치된 상태 노드들. 비어 있으면 원점 기준이다
 */
export function startNodeX(placed: readonly PlacedNode[]): number {
  const leftmost = placed.reduce((min, node) => Math.min(min, node.x), 0)
  return leftmost - START_NODE_MARGIN_PX
}

/**
 * 전환 목록을 캔버스 간선 경로로 옮긴다 (F10 · F11 · F12).
 *
 * - `NORMAL`. 출발 상태 → 도착 상태. 같은 쌍이 여럿이면 서로 벌린다.
 * - `INITIAL`. 가상 시작 노드(`START_NODE_ID`) → 도착 상태. mermaid 의 `[*]` 와 같은 자리다.
 * - `GLOBAL`. **간선을 만들지 않는다.** 「모든 상태에서」 패널이 쓸 목록으로 따로 나간다.
 * - self-loop(출발 == 도착)은 노드에 가리지 않게 곡선 크기를 받는다.
 *
 * `autoLayout` 과 같이 **입력을 변형하지 않는다** — 전환 목록도, 전환 객체도 그대로다.
 *
 * @param transitions 초안의 전환 목록. 배열 순서가 곧 전환의 identity 다(`transitionIndex`)
 * @param placed 배치된 노드 좌표(`autoLayout` 의 결과). 라벨끼리 겹치는지 판정하는 데 쓴다 —
 *   좌표를 모르면 겹침을 알 수 없으므로 그때는 라벨을 밀지 않는다
 * @returns 간선 · 전역 전환 목록 · 시작 노드 표시 여부
 */
export function edgeRoutes(
  transitions: readonly LayoutInputTransition[],
  placed: readonly PlacedNode[],
): EdgeRouteResult {
  const totals = countByPair(transitions)
  const placedPerPair = new Map<string, number>()
  const edges: RoutedEdge[] = []
  const globals: GlobalTransitionRoute[] = []
  const byKey = new Map(placed.map((node) => [node.key, node]))

  transitions.forEach((transition, transitionIndex) => {
    const source = sourceNodeId(transition)
    if (source === null) {
      globals.push({ transitionIndex, to: transition.to, name: transition.name })
      return
    }

    const key = pairKey(source, transition.to)
    const index = placedPerPair.get(key) ?? 0
    placedPerPair.set(key, index + 1)
    const total = totals.get(key) ?? 1
    // 좌표를 모르는 노드(빈 배치로 부른 경우)는 최소폭으로 본다 — 고리가 안 보이는 것보다 낫다
    const sourceWidth = byKey.get(source)?.width ?? NODE_MIN_WIDTH_PX
    edges.push(routedEdge({ transition, transitionIndex, source, sourceWidth, index, total }))
  })

  // 시작 노드는 그것을 쓰는 간선이 있을 때만 그린다 — 늘 그리면 빈 원이 홀로 떠다닌다
  const hasStartNode = edges.some((edge) => edge.source === START_NODE_ID)
  if (hasStartNode) {
    // ★ 시작 노드도 좌표 사전에 넣는다. 빼면 그 간선의 라벨이 겹침 해소에서 **영영 제외**되어
    //   INITIAL 이 여럿일 때 라벨이 한 점에 쌓인다. 자리는 캔버스와 같은 규칙으로 정한다
    //   (`WorkflowEditorCanvas` 가 이 두 상수를 그대로 쓴다).
    byKey.set(START_NODE_ID, {
      key: START_NODE_ID,
      x: startNodeX(placed),
      y: 0,
      width: START_NODE_SIZE_PX,
      height: START_NODE_SIZE_PX,
    })
  }

  return { edges: spreadLabels(edges, byKey), globals, hasStartNode }
}
