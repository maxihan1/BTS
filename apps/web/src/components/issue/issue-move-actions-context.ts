// 이슈 작업 메뉴의 「이동」 항목을 화면(보드·백로그)이 채워 주는 통로 (JB-B-2 · JCB-17)
import { createContext } from 'react'
import { computeNeighbors } from '@/lib/backlog-drag'
import type { NeighborResult } from '@/lib/backlog-drag'

/** 메뉴의 이동 항목 하나 — 맨 위로 · 다른 스프린트로 등 */
export interface IssueMoveAction {
  /** 메뉴에 보이는 문구. 예: "맨 위로" */
  label: string
  /** 지금 위치에서 의미 없는 이동(이미 맨 위 등)이면 true */
  disabled?: boolean
  onSelect: () => void
}

/**
 * 이슈 키 → 그 이슈가 지금 갈 수 있는 이동 목록.
 *
 * 화면이 하나만 제공하고 카드는 키만 넘긴다 — 카드가 `memo` 라 순서 목록을 prop 으로 받으면
 * 행마다 새 배열이 되어 재렌더 스킵이 죽는다. 메뉴가 **열릴 때만** 부른다.
 * 제공자가 없으면(드래그 미리보기 · 단독 렌더) 메뉴에 이동 항목이 없다.
 */
export type IssueMoveActionsResolver = (issueKey: string) => IssueMoveAction[]

export const IssueMoveActionsContext = createContext<IssueMoveActionsResolver | null>(null)

/** 순서 이동 4종 — 자기를 뺀 이슈들 사이 몇 번째 자리로 옮길지 (`index` = 지금 자리) */
const IN_LIST_MOVES: readonly { label: string; target: (index: number, last: number) => number }[] = [
  { label: '맨 위로', target: () => 0 },
  { label: '위로', target: (i) => i - 1 },
  { label: '아래로', target: (i) => i + 1 },
  { label: '맨 아래로', target: (_i, last) => last },
]

/**
 * 한 목록(보드 컬럼 · 백로그 섹션) 안에서의 맨 위로 · 위로 · 아래로 · 맨 아래로.
 *
 * 이웃 계산은 드래그와 같은 `computeNeighbors` 다 — rerank API 가 받는 `previous/nextIssueKey` 로 끝난다.
 * 이미 그 자리이거나 목록 밖이면 `disabled`.
 *
 * @param keys rank 순 이슈 키 (자기 포함)
 * @param issueKey 옮길 이슈
 * @param rerank 이웃을 받아 실제로 옮기는 함수
 */
export function buildInListMoves(
  keys: readonly string[],
  issueKey: string,
  rerank: (neighbors: NeighborResult) => void,
): IssueMoveAction[] {
  const others = keys.filter((k) => k !== issueKey)
  const index = keys.indexOf(issueKey)
  return IN_LIST_MOVES.map(({ label, target }) => {
    const to = target(index, others.length)
    return {
      label,
      disabled: others.length === 0 || to === index || to < 0 || to > others.length,
      onSelect: () => rerank(computeNeighbors(others, to)),
    }
  })
}
