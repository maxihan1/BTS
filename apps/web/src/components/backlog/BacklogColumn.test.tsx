// BacklogColumn 컴포넌트 단위 테스트 — 헤더·카드 목록·드롭 영역·섹션 접기
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DndContext } from '@dnd-kit/core'
import type { BacklogIssue } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import {
  backlogCollapsedStorageKey,
  useBacklogCollapsedStore,
} from '@/hooks/use-backlog-collapsed'

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

import { BacklogColumn } from './BacklogColumn'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const issue1: BacklogIssue = {
  key: 'ATLAS-1',
  summary: '첫 번째 백로그 이슈',
  currentStateKey: 'todo',
  assigneeId: 'user-uuid-001',
  priority: 1,
  rank: 'aaa',
  version: 1,
  epicKey: null,
}

const issue2: BacklogIssue = {
  key: 'ATLAS-2',
  summary: '두 번째 백로그 이슈',
  currentStateKey: 'todo',
  assigneeId: null,
  priority: 3,
  rank: 'bbb',
  version: 1,
  epicKey: null,
}

const assigneeNames = new Map<string, string>([['ATLAS-1', '박지현']])

/** 테스트 프로젝트 키 — 접힘 영속 키(`bts.backlog.collapsed.{projectKey}`)의 네임스페이스 */
const PROJECT_KEY = 'ATLAS'

/** 백로그 섹션의 접기 토글 접근 이름 (하드코딩 금지 — i18n 경유) */
const COLLAPSE_TOGGLE_NAME = backlogLabels.collapseSection(backlogLabels.backlogTitle)

function renderColumn(
  issues: BacklogIssue[] = [issue1, issue2],
  names: Map<string, string> = assigneeNames,
  isOver = false,
  entry?: { canCreateIssue?: boolean; onCreateIssue?: () => void },
  projectKey: string = PROJECT_KEY,
  sprint?: { onCreateSprint?: (name: string) => void; createSprintDisabled?: boolean },
) {
  return render(
    <DndContext>
      <BacklogColumn
        projectKey={projectKey}
        issues={issues}
        assigneeNames={names}
        isOver={isOver}
        canCreateIssue={entry?.canCreateIssue}
        onCreateIssue={entry?.onCreateIssue}
        onCreateSprint={sprint?.onCreateSprint}
        createSprintDisabled={sprint?.createSprintDisabled}
      />
    </DndContext>,
  )
}

// 접힘 스토어는 모듈 전역 zustand 싱글턴이라 테스트 간 상태가 샌다.
// localStorage 도 함께 비운다 — 훅이 스토어에 없는 프로젝트만 저장값을 읽기 때문이다.
beforeEach(() => {
  window.localStorage.clear()
  useBacklogCollapsedStore.setState({ byProject: {} })
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 헤더 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S1 헤더 렌더', () => {
  it('S1a: "백로그" 헤더 텍스트를 표시한다', () => {
    renderColumn()
    expect(screen.getByText('백로그')).toBeInTheDocument()
  })

  it('S1b: 카드 수를 헤더에 표시한다', () => {
    renderColumn()
    expect(screen.getByText('2')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 카드 목록 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S2 카드 목록 렌더', () => {
  it('S2a: 각 카드의 issueKey를 렌더한다', () => {
    renderColumn()
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
  })

  it('S2b: 각 카드의 summary를 렌더한다', () => {
    renderColumn()
    expect(screen.getByText('첫 번째 백로그 이슈')).toBeInTheDocument()
    expect(screen.getByText('두 번째 백로그 이슈')).toBeInTheDocument()
  })

  it('S2c: assigneeNames에 이름이 있는 카드는 이니셜을 표시한다', () => {
    renderColumn()
    expect(screen.getByText('박')).toBeInTheDocument()
  })

  it('S2d: assigneeId=null 카드는 "미배정"을 표시한다', () => {
    renderColumn()
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 드롭 영역
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S3 드롭 영역', () => {
  it('S3a: 드롭 영역에 data-droppable="backlog" 속성이 있다', () => {
    renderColumn()
    expect(document.querySelector('[data-droppable="backlog"]')).toBeInTheDocument()
  })

  it('S3b: 빈 목록에도 드롭 영역이 있다', () => {
    renderColumn([], new Map())
    expect(document.querySelector('[data-droppable="backlog"]')).toBeInTheDocument()
  })

  it('S3c: isOver=true일 때 드롭 하이라이트 클래스가 적용된다', () => {
    renderColumn([issue1], assigneeNames, true)
    const dropZone = document.querySelector('[data-droppable="backlog"]')
    expect(dropZone?.className).toMatch(/ring|bg-accent/)
  })

  it('S3d: isOver=false일 때 하이라이트 클래스가 없다', () => {
    renderColumn([issue1], assigneeNames, false)
    const dropZone = document.querySelector('[data-droppable="backlog"]')
    expect(dropZone?.className).not.toMatch(/ring-2/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 빈 목록 placeholder
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S4 빈 목록 placeholder', () => {
  it('S4a: 이슈가 없으면 빈 상태 메시지를 표시한다', () => {
    renderColumn([], new Map())
    expect(screen.getByText('이슈 없음')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. concern-1 RED — orderedKeys droppable data 결선
// BacklogColumn의 useDroppable data에 orderedKeys가 포함되어야 한다.
// 현재는 { context: 'backlog' }만 등록하므로 이 테스트는 실패한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S5 concern-1 orderedKeys droppable data 결선', () => {
  it('S5a red: 이슈 목록의 key 배열이 droppable data에 orderedKeys로 포함된다', () => {
    // @dnd-kit/core의 useDroppable이 등록한 data를 직접 읽을 방법이 없으므로
    // data-ordered-keys 속성으로 DOM에 노출시키는 방식으로 검증한다.
    // 현재 구현에는 data-ordered-keys 속성이 없으므로 이 테스트는 실패한다.
    renderColumn([issue1, issue2])
    const dropZone = document.querySelector('[data-droppable="backlog"]')
    expect(dropZone).toBeInTheDocument()
    // orderedKeys가 DOM data 속성으로 노출되어야 한다 (구현 후 통과)
    expect(dropZone?.getAttribute('data-ordered-keys')).toBe('ATLAS-1,ATLAS-2')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-09 F3 — 이슈 생성 진입점 (FR-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — 이슈 생성 진입점 (F3 FR-1)', () => {
  it('onCreateIssue 를 주면 칸 헤더에 진입점이 있고 누르면 콜백이 발화한다', async () => {
    const user = userEvent.setup()
    const onCreateIssue = vi.fn()
    renderColumn(undefined, undefined, false, { canCreateIssue: true, onCreateIssue })

    const button = screen.getByRole('button', { name: backlogLabels.createIssueInBacklog })
    await user.click(button)

    expect(onCreateIssue).toHaveBeenCalledTimes(1)
  })

  it('★canCreateIssue=false 면 비활성이다 (fail-closed)', async () => {
    const user = userEvent.setup()
    const onCreateIssue = vi.fn()
    renderColumn(undefined, undefined, false, { canCreateIssue: false, onCreateIssue })

    const button = screen.getByRole('button', { name: backlogLabels.createIssueInBacklog })
    expect(button).toBeDisabled()

    await user.click(button)
    expect(onCreateIssue).not.toHaveBeenCalled()
  })

  it('onCreateIssue 를 안 주면 진입점을 렌더하지 않는다 (기존 소비처 무회귀)', () => {
    renderColumn()

    expect(
      screen.queryByRole('button', { name: backlogLabels.createIssueInBacklog }),
    ).toBeNull()
  })

  it('진입점은 드롭 영역 밖(헤더)에 있다 — 드래그 앤 드롭 무회귀 (NFR-5)', () => {
    const { container } = renderColumn(undefined, undefined, false, {
      canCreateIssue: true,
      onCreateIssue: vi.fn(),
    })

    const dropArea = container.querySelector('[data-droppable="backlog"]')
    const button = screen.getByRole('button', { name: backlogLabels.createIssueInBacklog })
    expect(dropArea).not.toBeNull()
    expect(dropArea?.contains(button)).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 — 세로 스택 레이아웃 (FR-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — 세로 스택 레이아웃 (F15 FR-1)', () => {
  it('섹션이 전폭(w-full)이고 288px 고정폭 클래스를 갖지 않는다', () => {
    renderColumn()
    const region = screen.getByRole('region')

    // 짝 단언 — 「없다」만 재면 클래스명을 오타 내도 통과한다.
    expect(region.className).toContain('w-full')
    expect(region.className).not.toMatch(/\bmin-w-72\b/)
    expect(region.className).not.toMatch(/\bw-72\b/)
  })

  it('★섹션 textContent 가 여전히 "백로그" 로 시작한다 — e2e 의 ^ 앵커 보호 (FR-2)', () => {
    // `backlog.spec.ts` 의 getColumnLocator 는 filter({ hasText: /^백로그/ }) 로 칸을 찾는다.
    // 접기 토글에 sr-only 텍스트를 넣으면 그 글자가 맨 앞에 끼어 이 정규식이 즉사한다.
    renderColumn()
    const region = screen.getByRole('region')

    expect(region.textContent?.startsWith(backlogLabels.backlogTitle)).toBe(true)
    // 토글은 접근 이름을 갖되 보이는 텍스트는 없다 — 두 조건을 함께 재야 공허하지 않다.
    const toggle = screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })
    expect(toggle.textContent).toBe('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F16 — 스프린트 생성 폼이 백로그 섹션 헤더로 들어온다 (F16-11)
//
// 종전에는 `BacklogBoard` 가 세로 스택 **바깥**에서 폼을 직접 렌더했다. Jira 는 스프린트
// 생성 진입이 백로그 섹션 헤더에 붙어 있고, 스펙 S7 이 그 배치를 요구한다.
//
// ★배선은 `ReactNode` 슬롯이 아니라 **원시 콜백 prop** 이다 — 이 컴포넌트는 `memo` 인데
//   JSX 노드 prop 은 매 렌더 새 참조라 memo 를 통째로 무력화한다. 백로그는 최대 1,000건이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — 스프린트 생성 폼 (F16 F16-11)', () => {
  it('onCreateSprint 를 주면 헤더 줄에 폼이 있고 제출이 콜백을 발화한다', async () => {
    const user = userEvent.setup()
    const onCreateSprint = vi.fn()
    renderColumn(undefined, undefined, false, undefined, PROJECT_KEY, { onCreateSprint })

    const form = screen.getByRole('form', { name: backlogLabels.createSprintFormLabel })
    // ★판별식 — 헤더 div 에는 role 이 없으므로 접기 토글을 앵커로 「같은 줄」을 잰다.
    //   region 안이기만 하면 통과하는 느슨한 단언으로 두면 폼이 드롭 영역 옆으로
    //   내려가도 초록이 된다.
    const toggle = screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })
    expect(toggle.parentElement).toContainElement(form)

    await user.type(screen.getByPlaceholderText(backlogLabels.sprintNamePlaceholder), '스프린트 3')
    await user.click(screen.getByRole('button', { name: backlogLabels.createSprint }))

    expect(onCreateSprint).toHaveBeenCalledWith('스프린트 3')
  })

  it('onCreateSprint 를 안 주면 폼을 렌더하지 않는다 (기존 소비처 무회귀)', () => {
    renderColumn()

    expect(
      screen.queryByRole('form', { name: backlogLabels.createSprintFormLabel }),
    ).toBeNull()
  })

  it('createSprintDisabled=true 면 입력과 버튼이 함께 비활성이다', () => {
    renderColumn(undefined, undefined, false, undefined, PROJECT_KEY, {
      onCreateSprint: vi.fn(),
      createSprintDisabled: true,
    })

    expect(screen.getByPlaceholderText(backlogLabels.sprintNamePlaceholder)).toBeDisabled()
    expect(screen.getByRole('button', { name: backlogLabels.createSprint })).toBeDisabled()
  })

  it('★폼은 드롭 영역 밖(헤더)에 있다 — 드래그 앤 드롭 무회귀 (NFR-5 승계)', () => {
    const { container } = renderColumn(undefined, undefined, false, undefined, PROJECT_KEY, {
      onCreateSprint: vi.fn(),
    })

    const dropArea = container.querySelector('[data-droppable="backlog"]')
    const form = screen.getByRole('form', { name: backlogLabels.createSprintFormLabel })
    expect(dropArea).not.toBeNull()
    expect(dropArea?.contains(form)).toBe(false)
  })

  it('★E3 승계: 섹션을 접어도 폼은 남는다 — 접기는 카드 목록을 접는 것이다', async () => {
    const user = userEvent.setup()
    renderColumn(undefined, undefined, false, undefined, PROJECT_KEY, {
      onCreateSprint: vi.fn(),
    })

    await user.click(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME }))

    expect(screen.queryByText('ATLAS-1')).toBeNull()
    expect(
      screen.getByRole('form', { name: backlogLabels.createSprintFormLabel }),
    ).toBeInTheDocument()
  })

  it('★★헤더 순서 계약: 폼이 있어도 region textContent 가 "백로그" 로 시작한다', () => {
    // `backlog.spec.ts:253` 의 getColumnLocator 가 `hasText: /^백로그/` 로 칸을 찾는다.
    // 폼을 제목 span **앞**에 두면 textContent 선두가 "스프린트 생성…" 이 되어 e2e 가
    // 통째로 즉사한다. e2e 는 느리고 늦게 도니 유닛에서 먼저 잡는다.
    renderColumn(undefined, undefined, false, undefined, PROJECT_KEY, {
      onCreateSprint: vi.fn(),
    })
    const region = screen.getByRole('region')

    // 짝 단언 — 폼이 실제로 텍스트를 기여하고 있어야 위 계약이 공허하지 않다.
    // (폼을 아예 안 그리면 startsWith 는 당연히 통과한다)
    expect(region.textContent).toContain(backlogLabels.createSprint)
    expect(region.textContent?.startsWith(backlogLabels.backlogTitle)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 — 섹션 접기/펼치기 (FR-2 · E3 · E4 · C-3 · C-9 · C-10)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — 섹션 접기/펼치기 (F15 FR-2)', () => {
  it('기본은 펼침이다 — aria-expanded=true 이고 카드 목록이 보인다', () => {
    renderColumn()

    expect(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
  })

  it('★E3: 접으면 카드 목록이 사라지고 헤더의 이름·개수·액션은 남는다', async () => {
    const user = userEvent.setup()
    renderColumn(undefined, undefined, false, {
      canCreateIssue: true,
      onCreateIssue: vi.fn(),
    })

    await user.click(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME }))

    // 카드 목록은 통째로 사라진다
    expect(screen.queryByText('ATLAS-1')).toBeNull()
    expect(screen.queryByText('ATLAS-2')).toBeNull()
    // 헤더는 남는다 — 이름·개수·생성 진입점·토글
    expect(screen.getByText(backlogLabels.backlogTitle)).toBeInTheDocument()
    expect(screen.getByLabelText('이슈 2개')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: backlogLabels.createIssueInBacklog }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })

  it('★E4: 접힌 섹션은 드롭 후보에서 빠진다 — droppable div 가 렌더되지 않는다', async () => {
    const user = userEvent.setup()
    renderColumn()

    // 펼침 상태에서는 있다 (짝 단언 — 「없다」만 재면 셀렉터 오타로 공허해진다)
    expect(document.querySelector('[data-droppable="backlog"]')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME }))

    expect(document.querySelector('[data-droppable="backlog"]')).toBeNull()
  })

  it('다시 누르면 펼쳐진다 — 카드와 드롭 영역이 함께 돌아온다', async () => {
    const user = userEvent.setup()
    renderColumn()
    const toggle = screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })

    await user.click(toggle)
    await user.click(toggle)

    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    expect(document.querySelector('[data-droppable="backlog"]')).toBeInTheDocument()
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('★C-3: aria-controls 를 갖지 않는다 — 접히면 대상 id 가 사라져 dangling IDREF 가 된다', async () => {
    const user = userEvent.setup()
    renderColumn()
    const toggle = screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })

    expect(toggle).not.toHaveAttribute('aria-controls')
    await user.click(toggle)
    expect(toggle).not.toHaveAttribute('aria-controls')
  })

  it('★C-9: 토글 이름은 접힘/펼침에서 동일하다 — 상태는 aria-expanded 가 말한다', async () => {
    const user = userEvent.setup()
    renderColumn()
    const toggle = screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })
    const nameWhenExpanded = toggle.getAttribute('aria-label')

    await user.click(toggle)

    expect(toggle.getAttribute('aria-label')).toBe(nameWhenExpanded)
    expect(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })).toBeInTheDocument()
  })

  it('★C-10: 모바일 터치 타깃이 44px 이상이고 md 부터 해제된다 (NFR-6)', () => {
    renderColumn()
    const toggle = screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })

    expect(toggle.className).toContain('min-h-11')
    expect(toggle.className).toContain('min-w-11')
    expect(toggle.className).toContain('md:min-h-0')
    expect(toggle.className).toContain('md:min-w-0')
  })

  it('★FR-12: 접기는 권한과 무관하다 — 생성 권한이 없어도 토글은 활성이다', async () => {
    const user = userEvent.setup()
    renderColumn(undefined, undefined, false, {
      canCreateIssue: false,
      onCreateIssue: vi.fn(),
    })

    // 생성 진입점은 fail-closed 로 비활성인데
    expect(screen.getByRole('button', { name: backlogLabels.createIssueInBacklog })).toBeDisabled()

    // 접기 토글은 활성이고 실제로 동작한다
    const toggle = screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })
    expect(toggle).toBeEnabled()
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('저장된 접힘 상태를 첫 렌더에서 복원한다 — 새로고침해도 접힌 채', () => {
    window.localStorage.setItem(backlogCollapsedStorageKey(PROJECT_KEY), JSON.stringify(['backlog']))

    renderColumn()

    expect(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(screen.queryByText('ATLAS-1')).toBeNull()
  })

  it('다른 프로젝트에서 접은 기억이 이 프로젝트를 접지 않는다', () => {
    window.localStorage.setItem(backlogCollapsedStorageKey('OTHER'), JSON.stringify(['backlog']))

    renderColumn()

    expect(screen.getByRole('button', { name: COLLAPSE_TOGGLE_NAME })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
  })
})
