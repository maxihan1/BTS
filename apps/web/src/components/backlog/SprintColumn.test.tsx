// SprintColumn 컴포넌트 단위 테스트 — 헤더(name+status)·드롭 영역·COMPLETED 비활성화·버튼 슬롯
import { describe, it, expect, vi } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { DndContext } from '@dnd-kit/core'
import type { BacklogIssue, SprintMeta } from '@/api/backlog'

// TanStack Router Link mock
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
    onClick?: React.MouseEventHandler
  }) => (
    <a
      href={params ? to.replace('$key', params['key'] ?? '') : to}
      className={className}
      onClick={onClick}
      data-testid="issue-link"
    >
      {children}
    </a>
  ),
}))

import { SprintColumn } from './SprintColumn'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const plannedSprint: SprintMeta = {
  sprintId: 'sprint-uuid-0001',
  name: '스프린트 1',
  goal: '목표: MVP 출시',
  status: 'PLANNED',
  startDate: null,
  endDate: null,
  version: 1,
}

const activeSprint: SprintMeta = {
  ...plannedSprint,
  sprintId: 'sprint-uuid-0002',
  name: '스프린트 2',
  status: 'ACTIVE',
  startDate: '2026-06-01',
  endDate: '2026-06-14',
}

const completedSprint: SprintMeta = {
  ...plannedSprint,
  sprintId: 'sprint-uuid-0003',
  name: '스프린트 3',
  status: 'COMPLETED',
}

const issue1: BacklogIssue = {
  key: 'ATLAS-5',
  summary: '스프린트 이슈',
  currentStateKey: 'todo',
  assigneeId: null,
  priority: 2,
  rank: 'aaa',
  version: 1,
  epicKey: null,
}

function renderSprintColumn(
  sprint: SprintMeta = plannedSprint,
  issues: BacklogIssue[] = [issue1],
  names: Map<string, string> = new Map(),
  isOver = false,
  onStart?: () => void,
  onComplete?: () => void,
) {
  return render(
    <DndContext>
      <SprintColumn
        sprint={sprint}
        issues={issues}
        assigneeNames={names}
        isOver={isOver}
        onStart={onStart}
        onComplete={onComplete}
      />
    </DndContext>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 헤더 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — S1 헤더 렌더', () => {
  it('S1a: 스프린트 name을 헤더에 표시한다', () => {
    renderSprintColumn()
    expect(screen.getByText('스프린트 1')).toBeInTheDocument()
  })

  it('S1b: PLANNED 상태 배지를 표시한다', () => {
    renderSprintColumn(plannedSprint)
    expect(screen.getByText('PLANNED')).toBeInTheDocument()
  })

  it('S1c: ACTIVE 상태 배지를 표시한다', () => {
    renderSprintColumn(activeSprint)
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
  })

  it('S1d: COMPLETED 상태 배지를 표시한다', () => {
    renderSprintColumn(completedSprint, [])
    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
  })

  it('S1e: 카드 수를 헤더에 표시한다', () => {
    renderSprintColumn(plannedSprint, [issue1])
    // 이슈 1개
    expect(screen.getByText('1')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 버튼 슬롯
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — S2 버튼 슬롯', () => {
  it('S2a: PLANNED 상태이면 시작 버튼이 표시된다', () => {
    renderSprintColumn(plannedSprint)
    expect(screen.getByRole('button', { name: /시작/ })).toBeInTheDocument()
  })

  it('S2b: ACTIVE 상태이면 완료 버튼이 표시된다', () => {
    renderSprintColumn(activeSprint)
    expect(screen.getByRole('button', { name: /완료/ })).toBeInTheDocument()
  })

  it('S2c: COMPLETED 상태이면 버튼이 표시되지 않는다', () => {
    renderSprintColumn(completedSprint, [])
    expect(screen.queryByRole('button', { name: /시작|완료/ })).not.toBeInTheDocument()
  })

  it('S2d: 시작 버튼 클릭 시 onStart 콜백이 호출된다', async () => {
    const onStart = vi.fn()
    renderSprintColumn(plannedSprint, [issue1], new Map(), false, onStart)
    await userEvent.click(screen.getByRole('button', { name: /시작/ }))
    expect(onStart).toHaveBeenCalledOnce()
  })

  it('S2e: 완료 버튼 클릭 시 onComplete 콜백이 호출된다', async () => {
    const onComplete = vi.fn()
    renderSprintColumn(activeSprint, [issue1], new Map(), false, undefined, onComplete)
    await userEvent.click(screen.getByRole('button', { name: /완료/ }))
    expect(onComplete).toHaveBeenCalledOnce()
  })

  it('S2f: onStart가 없어도 PLANNED 시작 버튼이 렌더된다 (슬롯 비어도 crash 없음)', () => {
    renderSprintColumn(plannedSprint)
    expect(screen.getByRole('button', { name: /시작/ })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 드롭 영역
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — S3 드롭 영역', () => {
  it('S3a: PLANNED 스프린트는 드롭 영역이 활성화된다', () => {
    renderSprintColumn(plannedSprint)
    const dropZone = document.querySelector(`[data-droppable="sprint-${plannedSprint.sprintId}"]`)
    expect(dropZone).toBeInTheDocument()
  })

  it('S3b: ACTIVE 스프린트는 드롭 영역이 활성화된다', () => {
    renderSprintColumn(activeSprint)
    const dropZone = document.querySelector(`[data-droppable="sprint-${activeSprint.sprintId}"]`)
    expect(dropZone).toBeInTheDocument()
  })

  it('S3c: COMPLETED 스프린트는 드롭 영역이 비활성화된다 (data-droppable-disabled 속성)', () => {
    renderSprintColumn(completedSprint, [])
    const dropZone = document.querySelector('[data-droppable-disabled="true"]')
    expect(dropZone).toBeInTheDocument()
  })

  it('S3d: isOver=true일 때 PLANNED 스프린트에 하이라이트 클래스가 적용된다', () => {
    renderSprintColumn(plannedSprint, [issue1], new Map(), true)
    const dropZone = document.querySelector(`[data-droppable="sprint-${plannedSprint.sprintId}"]`)
    expect(dropZone?.className).toMatch(/ring|bg-accent/)
  })

  it('S3e: isOver=false일 때 하이라이트 클래스가 없다', () => {
    renderSprintColumn(plannedSprint, [issue1], new Map(), false)
    const dropZone = document.querySelector(`[data-droppable="sprint-${plannedSprint.sprintId}"]`)
    expect(dropZone?.className).not.toMatch(/ring-2/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 카드 목록 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — S4 카드 목록 렌더', () => {
  it('S4a: 스프린트 이슈 카드를 렌더한다', () => {
    renderSprintColumn(plannedSprint, [issue1])
    expect(screen.getByText('ATLAS-5')).toBeInTheDocument()
    expect(screen.getByText('스프린트 이슈')).toBeInTheDocument()
  })

  it('S4b: 이슈가 없으면 빈 상태 메시지를 표시한다', () => {
    renderSprintColumn(plannedSprint, [])
    expect(screen.getByText('이슈 없음')).toBeInTheDocument()
  })

  it('S4c: assigneeNames에 이름이 있는 카드는 이니셜을 표시한다', () => {
    const names = new Map<string, string>([['ATLAS-5', '이민지']])
    renderSprintColumn(plannedSprint, [issue1], names)
    expect(screen.getByText('이')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. concern-1 RED — orderedKeys droppable data 결선
// SprintColumn의 useDroppable data에 orderedKeys가 포함되어야 한다.
// 현재는 { context: 'sprint', sprintId } 만 등록하므로 이 테스트는 실패한다.
// ─────────────────────────────────────────────────────────────────────────────

const issue2Sprint: BacklogIssue = {
  key: 'ATLAS-6',
  summary: '두 번째 스프린트 이슈',
  currentStateKey: 'todo',
  assigneeId: null,
  priority: 3,
  rank: 'bbb',
  version: 1,
  epicKey: null,
}

describe('SprintColumn — S5 concern-1 orderedKeys droppable data 결선', () => {
  it('S5a red: 이슈 목록의 key 배열이 droppable data에 orderedKeys로 포함된다', () => {
    renderSprintColumn(plannedSprint, [issue1, issue2Sprint])
    const dropZone = document.querySelector(`[data-droppable="sprint-${plannedSprint.sprintId}"]`)
    expect(dropZone).toBeInTheDocument()
    // orderedKeys가 DOM data 속성으로 노출되어야 한다 (구현 후 통과)
    expect(dropZone?.getAttribute('data-ordered-keys')).toBe('ATLAS-5,ATLAS-6')
  })
})
