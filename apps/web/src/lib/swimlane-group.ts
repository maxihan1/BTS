// 보드 카드를 스윔레인 기준으로 그룹화하는 순수 헬퍼
import type { BoardCard, SwimlaneField } from '@/api/boards'
import type { CardAssigneeDisplay } from '@/components/board/BoardCard'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 스윔레인 단일 그룹 */
export interface SwimlaneGroup {
  /** React key 용 고유 식별자 */
  key: string
  /** 서브헤더 표시 텍스트. NONE이면 빈 문자열 */
  label: string
  /** 그룹에 속한 카드 목록 */
  cards: BoardCard[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 미배정 그룹 라벨 */
const LABEL_UNASSIGNED = '미배정'

/** 이름 미확인 담당자 그룹 라벨 */
const LABEL_UNKNOWN = '이름 미확인'

/** 에픽 없음 그룹 라벨 */
const LABEL_NO_EPIC = '에픽 없음'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 담당자 표시 상태에서 그룹 라벨과 key suffix를 반환한다.
 * unassigned → LABEL_UNASSIGNED, unknown → LABEL_UNKNOWN, named → 이름
 */
function resolveAssigneeLabel(display: CardAssigneeDisplay): { label: string; keySuffix: string } {
  if (display.state === 'named') {
    return { label: display.name, keySuffix: `named-${display.name}` }
  }
  if (display.state === 'unknown') {
    return { label: LABEL_UNKNOWN, keySuffix: 'unknown' }
  }
  // unassigned
  return { label: LABEL_UNASSIGNED, keySuffix: 'unassigned' }
}

// ─────────────────────────────────────────────────────────────────────────────
// NONE 그룹화
// ─────────────────────────────────────────────────────────────────────────────

function groupByNone(cards: BoardCard[]): SwimlaneGroup[] {
  if (cards.length === 0) return []
  return [{ key: 'none', label: '', cards }]
}

// ─────────────────────────────────────────────────────────────────────────────
// ASSIGNEE 그룹화
// ─────────────────────────────────────────────────────────────────────────────

/**
 * groupMap에서 named/unknown/unassigned를 분리해 정렬된 배열을 반환한다.
 * named는 가나다 정렬, unknown → unassigned 순으로 마지막에 위치.
 */
function sortAssigneeGroups(groupMap: Map<string, SwimlaneGroup>): SwimlaneGroup[] {
  const named: SwimlaneGroup[] = []
  let unassigned: SwimlaneGroup | undefined
  let unknown: SwimlaneGroup | undefined

  for (const group of groupMap.values()) {
    if (group.label === LABEL_UNASSIGNED) {
      unassigned = group
    } else if (group.label === LABEL_UNKNOWN) {
      unknown = group
    } else {
      named.push(group)
    }
  }

  named.sort((a, b) => a.label.localeCompare(b.label, 'ko'))

  const result = [...named]
  if (unknown !== undefined) result.push(unknown)
  if (unassigned !== undefined) result.push(unassigned)
  return result
}

function groupByAssignee(
  cards: BoardCard[],
  assigneeNames: Map<string, CardAssigneeDisplay>,
): SwimlaneGroup[] {
  const groupMap = new Map<string, SwimlaneGroup>()

  for (const card of cards) {
    const display = assigneeNames.get(card.issueKey) ?? ({ state: 'unassigned' } satisfies CardAssigneeDisplay)
    const { label, keySuffix } = resolveAssigneeLabel(display)
    const key = `assignee-${keySuffix}`

    const existing = groupMap.get(key)
    if (existing !== undefined) {
      existing.cards.push(card)
    } else {
      groupMap.set(key, { key, label, cards: [card] })
    }
  }

  return sortAssigneeGroups(groupMap)
}

// ─────────────────────────────────────────────────────────────────────────────
// PRIORITY 그룹화
// ─────────────────────────────────────────────────────────────────────────────

function groupByPriority(cards: BoardCard[]): SwimlaneGroup[] {
  const groupMap = new Map<number, SwimlaneGroup>()

  for (const card of cards) {
    const p = card.priority
    const existing = groupMap.get(p)
    if (existing !== undefined) {
      existing.cards.push(card)
    } else {
      groupMap.set(p, {
        key: `priority-${p}`,
        label: `우선순위 ${p}`,
        cards: [card],
      })
    }
  }

  // priority 오름차순 정렬
  return [...groupMap.entries()]
    .sort(([a], [b]) => a - b)
    .map(([, group]) => group)
}

// ─────────────────────────────────────────────────────────────────────────────
// EPIC 그룹화
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 키별로 카드를 그룹화한다.
 * epicKey가 있는 그룹은 가나다(문자열) 오름차순 정렬.
 * epicKey=null(에픽 없음) 그룹은 마지막.
 */
function groupByEpic(cards: BoardCard[]): SwimlaneGroup[] {
  const groupMap = new Map<string, SwimlaneGroup>()
  let noEpicGroup: SwimlaneGroup | undefined

  for (const card of cards) {
    const epicKey = card.epicKey
    if (epicKey === null) {
      if (noEpicGroup === undefined) {
        noEpicGroup = { key: 'epic-no-epic', label: LABEL_NO_EPIC, cards: [] }
      }
      noEpicGroup.cards.push(card)
    } else {
      const key = `epic-${epicKey}`
      const existing = groupMap.get(key)
      if (existing !== undefined) {
        existing.cards.push(card)
      } else {
        groupMap.set(key, { key, label: epicKey, cards: [card] })
      }
    }
  }

  const sorted = [...groupMap.values()].sort((a, b) =>
    a.label.localeCompare(b.label, 'ko'),
  )
  if (noEpicGroup !== undefined) {
    sorted.push(noEpicGroup)
  }
  return sorted
}

// ─────────────────────────────────────────────────────────────────────────────
// 공개 API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 카드를 스윔레인 기준으로 그룹화한다.
 *
 * 부수효과 없는 순수 함수.
 *
 * - NONE: 단일 그룹, 빈 라벨. 카드가 없으면 빈 배열.
 * - ASSIGNEE: 담당자별 그룹. 이름 있는 담당자는 가나다 정렬,
 *             이름 미확인(unknown)은 그 뒤, 미배정(unassigned)은 마지막.
 *             빈 그룹 생략.
 * - PRIORITY: 우선순위 숫자 오름차순 그룹. 라벨 "우선순위 N".
 *             빈 그룹 생략.
 * - EPIC: 에픽 키별 그룹. 가나다 정렬, "에픽 없음"(epicKey=null)은 마지막.
 *         빈 그룹 생략.
 *
 * @param cards 컬럼에 속한 카드 목록
 * @param swimlaneField 그룹화 기준 필드
 * @param assigneeNames 이슈 키 → 담당자 표시 상태 맵 (ASSIGNEE에서만 사용)
 * @returns SwimlaneGroup[] — 정렬된 그룹 목록
 */
export function groupCardsBySwimlane(
  cards: BoardCard[],
  swimlaneField: SwimlaneField,
  assigneeNames: Map<string, CardAssigneeDisplay>,
): SwimlaneGroup[] {
  if (swimlaneField === 'NONE') return groupByNone(cards)
  if (swimlaneField === 'ASSIGNEE') return groupByAssignee(cards, assigneeNames)
  if (swimlaneField === 'EPIC') return groupByEpic(cards)
  // PRIORITY
  return groupByPriority(cards)
}
