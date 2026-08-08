// SprintColumn 컴포넌트 단위 테스트 — 헤더(name+status)·드롭 영역·COMPLETED 비활성화·버튼 슬롯·섹션 접기
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { DndContext } from '@dnd-kit/core'
import type { BacklogIssue, SprintMeta } from '@/api/backlog'
import type { IssueTypeResponse } from '@/api/issue-types'
import { taskIssueTypeFixture } from '@/mocks/issue-type-fixtures'

// TanStack Router Link mock — params 객체의 모든 키($projectKey, $sprintId 등)를 치환한다
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
      href={
        params
          ? Object.entries(params).reduce((acc, [key, value]) => acc.replace(`$${key}`, value), to)
          : to
      }
      className={className}
      onClick={onClick}
      data-testid="issue-link"
    >
      {children}
    </a>
  ),
}))

import { SprintColumn } from './SprintColumn'
import { backlogLabels } from '@/i18n/backlog-labels'
import {
  backlogCollapsedStorageKey,
  useBacklogCollapsedStore,
} from '@/hooks/use-backlog-collapsed'

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
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

function renderSprintColumn(
  sprint: SprintMeta = plannedSprint,
  issues: BacklogIssue[] = [issue1],
  names: Map<string, string> = new Map(),
  isOver = false,
  onStart?: () => void,
  onComplete?: () => void,
  projectKey = 'ATLAS',
  entry?: { canCreateIssue?: boolean; onCreateIssue?: () => void },
  issueTypesByKey: Map<string, IssueTypeResponse> = new Map(),
) {
  return render(
    <DndContext>
      <SprintColumn
        projectKey={projectKey}
        sprint={sprint}
        issues={issues}
        assigneeNames={names}
        issueTypesByKey={issueTypesByKey}
        isOver={isOver}
        onStart={onStart}
        onComplete={onComplete}
        canCreateIssue={entry?.canCreateIssue}
        onCreateIssue={entry?.onCreateIssue}
      />
    </DndContext>,
  )
}

/** 스프린트 섹션의 접기 토글 접근 이름 (하드코딩 금지 — i18n 경유) */
function collapseToggleName(sprint: SprintMeta): string {
  return backlogLabels.collapseSection(sprint.name)
}

// 접힘 스토어는 모듈 전역 zustand 싱글턴이라 테스트 간 상태가 샌다.
beforeEach(() => {
  window.localStorage.clear()
  useBacklogCollapsedStore.setState({ byProject: {} })
})

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
// S4b. 이슈 타입 배선 (FR-UX-14 F14 Task 4)
// ★두 칸(BacklogColumn·SprintColumn) 모두 issueTypesByKey 를 카드에 넘기는지 각자 잰다.
//   한쪽만 배선하면 스프린트 칸(또는 백로그 칸) 카드만 아이콘이 없는 절반 봉합이 된다.
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — 이슈 타입 배선 (F14 Task 4)', () => {
  it('issueTypesByKey 에 있으면 카드가 해석된 유형 이름으로 아이콘을 그린다', () => {
    renderSprintColumn(
      plannedSprint,
      [issue1],
      new Map(),
      false,
      undefined,
      undefined,
      'ATLAS',
      undefined,
      new Map([[taskIssueTypeFixture.key, taskIssueTypeFixture]]),
    )
    expect(screen.getByRole('img', { name: taskIssueTypeFixture.name })).toBeInTheDocument()
  })

  it('맵에 없으면 typeKey 원문이 접근성 이름이 된다 (FR6 fallback)', () => {
    renderSprintColumn(plannedSprint, [issue1])
    expect(screen.getByRole('img', { name: issue1.typeKey })).toBeInTheDocument()
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
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

/** 화면에 실제로 그려진 카드의 이슈 키 — `data-card-droppable` 은 `card:{context}:{key}` 다 */
function renderedCardKeys(): string[] {
  return Array.from(document.querySelectorAll('[data-card-droppable]')).map(
    (element) => String(element.getAttribute('data-card-droppable')).split(':')[2] ?? '',
  )
}

describe('SprintColumn — S5 concern-1 orderedKeys droppable data 결선', () => {
  it('S5a red: 이슈 목록의 key 배열이 droppable data에 orderedKeys로 포함된다', () => {
    renderSprintColumn(plannedSprint, [issue1, issue2Sprint])
    const dropZone = document.querySelector(`[data-droppable="sprint-${plannedSprint.sprintId}"]`)
    expect(dropZone).toBeInTheDocument()
    // orderedKeys가 DOM data 속성으로 노출되어야 한다 (구현 후 통과)
    expect(dropZone?.getAttribute('data-ordered-keys')).toBe('ATLAS-5,ATLAS-6')
  })

  it('★S5b: 부모가 필터해 좁힌 목록이면 이 값도 그만큼만 싣는다 — 「보이는 것」이다 (F16)', () => {
    // `BacklogColumn.test.tsx` 의 같은 이름 테스트와 **짝**이다. F16 필터는 백로그 칸과
    // 스프린트 칸에 동시에 걸리므로 한 칸만 재면 반쪽 봉합(F5 가 겪은 형태)이 다시 열린다.
    //
    // 이 값이 좁혀지는 것은 계약이다 — 키보드 방향키의 「빈 칸인가」 판정이 이 값을 읽는다
    // (`lib/backlog-keyboard-coordinates.ts:56` isEmptyColumn).
    // ★rank 계산은 이 값을 쓰지 않는다. 원본 `view` 를 보는 짝은
    // `BacklogBoard.test.tsx` 의 「EC7 ②칸 빈 영역 드롭」이다.
    renderSprintColumn(plannedSprint, [issue2Sprint])

    const dropZone = document.querySelector(`[data-droppable="sprint-${plannedSprint.sprintId}"]`)
    expect(dropZone?.getAttribute('data-ordered-keys')).toBe('ATLAS-6')
    expect(renderedCardKeys()).toEqual(['ATLAS-6'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 번다운 진입 링크 (FR-RP-01 D6/D7 Task-5)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — S6 번다운 진입 링크', () => {
  it('S6a: 번다운 링크가 projectKey/sprintId params로 올바른 경로를 렌더한다', () => {
    renderSprintColumn(plannedSprint, [issue1], new Map(), false, undefined, undefined, 'ATLAS')
    const link = screen.getByRole('link', { name: /번다운/ })
    expect(link).toHaveAttribute('href', `/projects/ATLAS/sprints/${plannedSprint.sprintId}/burndown`)
  })

  it('S6b: 다른 projectKey를 전달하면 링크 href에 그대로 반영된다', () => {
    renderSprintColumn(activeSprint, [issue1], new Map(), false, undefined, undefined, 'BTS2')
    const link = screen.getByRole('link', { name: /번다운/ })
    expect(link).toHaveAttribute('href', `/projects/BTS2/sprints/${activeSprint.sprintId}/burndown`)
  })

  it('S6c: COMPLETED 스프린트에서도 번다운 링크가 렌더된다', () => {
    renderSprintColumn(completedSprint, [])
    expect(screen.getByRole('link', { name: /번다운/ })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-09 F3 — 스프린트 칸 이슈 생성 진입점 (FR-2, FR-10, E-3)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — 이슈 생성 진입점 (F3 FR-2)', () => {
  it('진입점 이름에 스프린트 이름이 들어간다 — 화면에 N개가 공존한다 (FR-10)', async () => {
    const user = userEvent.setup()
    const onCreateIssue = vi.fn()
    renderSprintColumn(plannedSprint, [issue1], new Map(), false, undefined, undefined, 'ATLAS', {
      canCreateIssue: true,
      onCreateIssue,
    })

    const button = screen.getByRole('button', {
      name: backlogLabels.createIssueInSprint(plannedSprint.name),
    })
    await user.click(button)

    expect(onCreateIssue).toHaveBeenCalledTimes(1)
  })

  it('★canCreateIssue=false 면 비활성이다 (fail-closed)', async () => {
    const user = userEvent.setup()
    const onCreateIssue = vi.fn()
    renderSprintColumn(plannedSprint, [issue1], new Map(), false, undefined, undefined, 'ATLAS', {
      canCreateIssue: false,
      onCreateIssue,
    })

    const button = screen.getByRole('button', {
      name: backlogLabels.createIssueInSprint(plannedSprint.name),
    })
    expect(button).toBeDisabled()

    await user.click(button)
    expect(onCreateIssue).not.toHaveBeenCalled()
  })

  it('★COMPLETED 스프린트에는 진입점이 없다 (E-3 — 드롭이 막힌 것과 같은 기준)', () => {
    renderSprintColumn(completedSprint, [], new Map(), false, undefined, undefined, 'ATLAS', {
      canCreateIssue: true,
      onCreateIssue: vi.fn(),
    })

    expect(
      screen.queryByRole('button', {
        name: backlogLabels.createIssueInSprint(completedSprint.name),
      }),
    ).toBeNull()
  })

  it('onCreateIssue 를 안 주면 진입점을 렌더하지 않는다 (기존 소비처 무회귀)', () => {
    renderSprintColumn()

    expect(
      screen.queryByRole('button', {
        name: backlogLabels.createIssueInSprint(plannedSprint.name),
      }),
    ).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 — 세로 스택 레이아웃 + 헤더 한 줄 (FR-1 · §시각 사양)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — 세로 스택 레이아웃 (F15 FR-1)', () => {
  it('섹션이 전폭(w-full)이고 288px 고정폭 클래스를 갖지 않는다', () => {
    renderSprintColumn()
    const region = screen.getByRole('region')

    // 짝 단언 — 「없다」만 재면 클래스명 오타로 조용히 공허해진다.
    expect(region.className).toContain('w-full')
    expect(region.className).not.toMatch(/\bmin-w-72\b/)
    expect(region.className).not.toMatch(/\bw-72\b/)
  })

  it('★섹션 textContent 가 여전히 스프린트 이름으로 시작한다 — e2e 의 ^ 앵커 보호 (FR-2)', () => {
    // backlog.spec.ts·sprint-burndown.spec.ts 가 filter({ hasText: /^스프린트 1/ }) 로 칸을 찾는다.
    renderSprintColumn(plannedSprint)
    const region = screen.getByRole('region')

    expect(region.textContent?.startsWith(plannedSprint.name)).toBe(true)
    const toggle = screen.getByRole('button', { name: collapseToggleName(plannedSprint) })
    expect(toggle.textContent).toBe('')
  })

  it('헤더 액션이 제목과 같은 줄에 선다 — self-start 를 쓰지 않고 헤더가 한 줄 flex 다', () => {
    renderSprintColumn(plannedSprint)
    const region = screen.getByRole('region')
    const header = region.firstElementChild

    expect(header).not.toBeNull()
    expect(header?.className).toContain('items-center')
    expect(header?.className).toContain('flex-wrap')
    expect(header?.className).not.toContain('flex-col')

    // 시작 버튼·번다운 링크가 세로 스택 잔재(self-start)를 갖지 않는다
    const startButton = screen.getByRole('button', { name: backlogLabels.startSprint })
    const burndownLink = screen.getByRole('link', { name: /번다운/ })
    expect(startButton.className).not.toContain('self-start')
    expect(burndownLink.className).not.toContain('self-start')
  })

  it('★NFR-6: 헤더 액션이 모바일에서 44px 터치 타깃을 갖고 md 부터 해제된다', () => {
    renderSprintColumn(plannedSprint)

    for (const el of [
      screen.getByRole('button', { name: backlogLabels.startSprint }),
      screen.getByRole('link', { name: /번다운/ }),
    ]) {
      expect(el.className).toContain('min-h-11')
      expect(el.className).toContain('md:min-h-0')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 — 섹션 접기/펼치기 (FR-2 · E3 · E4 · C-3 · C-9 · C-10)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumn — 섹션 접기/펼치기 (F15 FR-2)', () => {
  it('기본은 펼침이다 — aria-expanded=true 이고 카드 목록이 보인다', () => {
    renderSprintColumn(plannedSprint, [issue1])

    expect(
      screen.getByRole('button', { name: collapseToggleName(plannedSprint) }),
    ).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('ATLAS-5')).toBeInTheDocument()
  })

  it('★E3: 접으면 카드 목록이 사라지고 헤더의 이름·상태·개수·액션은 남는다', async () => {
    const user = userEvent.setup()
    renderSprintColumn(plannedSprint, [issue1])

    await user.click(screen.getByRole('button', { name: collapseToggleName(plannedSprint) }))

    expect(screen.queryByText('ATLAS-5')).toBeNull()
    // 헤더는 전부 남는다 — 이름·상태 배지·개수·시작 버튼·번다운 링크
    expect(screen.getByText(plannedSprint.name)).toBeInTheDocument()
    expect(screen.getByLabelText('스프린트 상태: PLANNED')).toBeInTheDocument()
    expect(screen.getByLabelText('이슈 1개')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: backlogLabels.startSprint })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /번다운/ })).toBeInTheDocument()
  })

  it('★E4: 접힌 섹션은 드롭 후보에서 빠진다 — droppable div 가 렌더되지 않는다', async () => {
    const user = userEvent.setup()
    const selector = `[data-droppable="sprint-${plannedSprint.sprintId}"]`
    renderSprintColumn(plannedSprint, [issue1])

    // 펼침 상태에서는 있다 (짝 단언)
    expect(document.querySelector(selector)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: collapseToggleName(plannedSprint) }))

    expect(document.querySelector(selector)).toBeNull()
  })

  it('다시 누르면 펼쳐진다 — 카드와 드롭 영역이 함께 돌아온다', async () => {
    const user = userEvent.setup()
    renderSprintColumn(plannedSprint, [issue1])
    const toggle = screen.getByRole('button', { name: collapseToggleName(plannedSprint) })

    await user.click(toggle)
    await user.click(toggle)

    expect(screen.getByText('ATLAS-5')).toBeInTheDocument()
    expect(
      document.querySelector(`[data-droppable="sprint-${plannedSprint.sprintId}"]`),
    ).toBeInTheDocument()
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('★C-3: aria-controls 를 갖지 않는다 — 접히면 대상 id 가 사라져 dangling IDREF 가 된다', async () => {
    const user = userEvent.setup()
    renderSprintColumn(plannedSprint, [issue1])
    const toggle = screen.getByRole('button', { name: collapseToggleName(plannedSprint) })

    expect(toggle).not.toHaveAttribute('aria-controls')
    await user.click(toggle)
    expect(toggle).not.toHaveAttribute('aria-controls')
  })

  it('★C-9: 토글 이름은 접힘/펼침에서 동일하다 — 상태는 aria-expanded 가 말한다', async () => {
    const user = userEvent.setup()
    renderSprintColumn(plannedSprint, [issue1])
    const toggle = screen.getByRole('button', { name: collapseToggleName(plannedSprint) })
    const nameWhenExpanded = toggle.getAttribute('aria-label')

    await user.click(toggle)

    expect(toggle.getAttribute('aria-label')).toBe(nameWhenExpanded)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('★토글 이름은 스프린트마다 다르다 — 같은 화면에 N개가 공존한다 (FR-10)', () => {
    expect(collapseToggleName(plannedSprint)).not.toBe(collapseToggleName(activeSprint))
  })

  it('★C-10: 모바일 터치 타깃이 44px 이상이고 md 부터 해제된다 (NFR-6)', () => {
    renderSprintColumn(plannedSprint, [issue1])
    const toggle = screen.getByRole('button', { name: collapseToggleName(plannedSprint) })

    expect(toggle.className).toContain('min-h-11')
    expect(toggle.className).toContain('min-w-11')
    expect(toggle.className).toContain('md:min-h-0')
    expect(toggle.className).toContain('md:min-w-0')
  })

  it('★COMPLETED 스프린트도 접을 수 있다 — 접기는 상태·권한과 무관하다 (FR-12)', async () => {
    const user = userEvent.setup()
    renderSprintColumn(completedSprint, [])

    // 펼침 상태에서는 비활성 드롭 영역이 그려져 있다 (짝 단언)
    expect(document.querySelector('[data-droppable-disabled="true"]')).toBeInTheDocument()

    const toggle = screen.getByRole('button', { name: collapseToggleName(completedSprint) })
    expect(toggle).toBeEnabled()
    await user.click(toggle)

    expect(document.querySelector('[data-droppable-disabled="true"]')).toBeNull()
    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
  })

  it('저장된 접힘 상태를 첫 렌더에서 복원한다 — 새로고침해도 접힌 채', () => {
    window.localStorage.setItem(
      backlogCollapsedStorageKey('ATLAS'),
      JSON.stringify([`sprint-${plannedSprint.sprintId}`]),
    )

    renderSprintColumn(plannedSprint, [issue1])

    expect(
      screen.getByRole('button', { name: collapseToggleName(plannedSprint) }),
    ).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('ATLAS-5')).toBeNull()
  })

  it('★다른 스프린트를 접은 기억이 이 스프린트를 접지 않는다 — 섹션 id 는 sprintId 로 갈린다', () => {
    window.localStorage.setItem(
      backlogCollapsedStorageKey('ATLAS'),
      JSON.stringify([`sprint-${activeSprint.sprintId}`]),
    )

    renderSprintColumn(plannedSprint, [issue1])

    expect(
      screen.getByRole('button', { name: collapseToggleName(plannedSprint) }),
    ).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('ATLAS-5')).toBeInTheDocument()
  })
})
