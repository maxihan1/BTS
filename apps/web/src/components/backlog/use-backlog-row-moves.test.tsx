// 백로그 행 이동 항목 테스트 — 섹션 안 순서 · 다른 스프린트 · 백로그로 · 완료 스프린트 제외 · 권한 (JCB-17)
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { BacklogIssue, BacklogView, SprintMeta } from '@/api/backlog'
import { useBacklogRowMoves } from './use-backlog-row-moves'

const issue = (key: string): BacklogIssue => ({
  key,
  summary: key,
  currentStateKey: 'todo',
  assigneeId: null,
  priority: 3,
  rank: null,
  version: 1,
  epicKey: null,
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
})

const sprint = (n: number, status: string): SprintMeta => ({
  sprintId: `00000000-0000-0000-0000-00000000000${n}`,
  boardId: '00000000-0000-0000-0000-0000000000b0',
  name: `스프린트 ${n}`,
  goal: null,
  status,
  startDate: null,
  endDate: null,
  version: 1,
})

const view: BacklogView = {
  backlog: [issue('A-1'), issue('A-2')],
  sprints: [
    { sprint: sprint(1, 'ACTIVE'), issues: [issue('A-3')] },
    { sprint: sprint(2, 'PLANNED'), issues: [] },
    { sprint: sprint(3, 'COMPLETED'), issues: [] },
  ],
  truncated: false,
}

function setup(canReorder = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return renderHook(() => useBacklogRowMoves('A', view, canReorder), { wrapper }).result
}

describe('useBacklogRowMoves', () => {
  it('백로그 이슈 — 순서 4종 + 완료 안 된 스프린트로만, 「백로그로」는 없다', () => {
    const labels = setup().current?.('A-1').map((m) => m.label)
    expect(labels).toEqual(['맨 위로', '위로', '아래로', '맨 아래로', '스프린트 1에 추가', '스프린트 2에 추가'])
  })

  it('스프린트 이슈 — 다른 스프린트와 「백로그로」', () => {
    const labels = setup().current?.('A-3').map((m) => m.label)
    expect(labels).toEqual(['맨 위로', '위로', '아래로', '맨 아래로', '스프린트 2에 추가', '백로그로'])
  })

  it('「백로그로」는 지금 스프린트에서 배정을 푼다', async () => {
    let hit = ''
    server.use(
      http.delete('/api/v1/sprints/:sprintId/issues/:issueKey', ({ params }) => {
        hit = `${String(params.sprintId)}/${String(params.issueKey)}`
        return new HttpResponse(null, { status: 204 })
      }),
    )
    setup().current?.('A-3').find((m) => m.label === '백로그로')?.onSelect()
    await waitFor(() => expect(hit).toBe(`${sprint(1, 'ACTIVE').sprintId}/A-3`))
  })

  it('재정렬 권한이 없으면 resolver 가 없다 — 메뉴에 이동이 안 뜬다', () => {
    expect(setup(false).current).toBeNull()
  })
})
