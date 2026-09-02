// 스프린트 칸 헤더 테스트 — `⋯` 메뉴 게이팅 · 편집 다이얼로그 이름 고유 · 기존 액션 무회귀 (FR-BL-02 D6)
import { describe, it, expect, vi } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { SprintMeta } from '@/api/backlog'

// TanStack Router Link mock — 헤더의 번다운 링크만 쓴다 (`SprintColumn.test.tsx` 와 같은 형태)
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    children,
    className,
    'aria-label': ariaLabel,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
    'aria-label'?: string
  }) => (
    <a
      href={
        params
          ? Object.entries(params).reduce((acc, [key, value]) => acc.replace(`$${key}`, value), to)
          : to
      }
      className={className}
      aria-label={ariaLabel}
    >
      {children}
    </a>
  ),
}))

import { SprintColumnHeader } from './SprintColumnHeader'
import { backlogLabels } from '@/i18n/backlog-labels'
import { burndownLabels } from '@/i18n/burndown-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** 화면이 보고 있는 보드 — `?board=` 로 들어온 값 (FR-BD-04) */
const BOARD_ID = 'b0000000-0000-4000-8000-00000000000b'

const PLANNED_SPRINT: SprintMeta = {
  sprintId: '11111111-1111-4111-8111-111111111111',
  name: '스프린트 1',
  goal: null,
  status: 'PLANNED',
  startDate: null,
  endDate: null,
  version: 1,
}

const COMPLETED_SPRINT: SprintMeta = {
  ...PLANNED_SPRINT,
  sprintId: '33333333-3333-4333-8333-333333333333',
  name: '스프린트 3',
  status: 'COMPLETED',
  startDate: '2026-08-01',
  endDate: '2026-08-14',
}

const A = backlogLabels.sprintActions

/** 재시도를 끈 QueryClient — 편집 다이얼로그의 `useUpdateSprint` 가 마운트될 자리 */
function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

/** 헤더 렌더 옵션 */
interface RenderHeaderOptions {
  sprint?: SprintMeta
  canManageSprint?: boolean
  issueCount?: number
}

/**
 * 스프린트 칸 헤더를 그린다.
 *
 * @param opts 스프린트·권한·이슈 수 (전부 기본값 있음)
 */
function renderHeader(opts: RenderHeaderOptions = {}) {
  const { sprint = PLANNED_SPRINT, canManageSprint = true, issueCount = 2 } = opts
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <SprintColumnHeader
        projectKey={PROJECT_KEY}
        sprint={sprint}
        boardId={BOARD_ID}
        issueCount={issueCount}
        collapsed={false}
        onToggleCollapsed={() => {
          /* 이 파일의 관심사가 아니다 — 접힘은 SprintColumn.test 가 잰다 */
        }}
        canCreateIssue={false}
        canManageSprint={canManageSprint}
      />
    </QueryClientProvider>,
  )
}

/** `⋯` 트리거를 눌러 메뉴를 편다 */
async function openMenu(
  user: ReturnType<typeof userEvent.setup>,
  sprint: SprintMeta,
): Promise<void> {
  await user.click(await screen.findByRole('button', { name: A.triggerAriaLabel(sprint.name) }))
}

// ─────────────────────────────────────────────────────────────────────────────
// H1. `⋯` 메뉴 배선 — 권한이 없으면 부재 (FR-5)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumnHeader — H1 `⋯` 메뉴 배선', () => {
  it('H1-1: 권한이 있으면 헤더에 `⋯` 트리거가 있다', () => {
    renderHeader({ canManageSprint: true })

    expect(
      screen.getByRole('button', { name: A.triggerAriaLabel(PLANNED_SPRINT.name) }),
    ).toBeInTheDocument()
  })

  it('H1-2: 권한이 없으면 헤더에 `⋯` 트리거가 없다', () => {
    renderHeader({ canManageSprint: false })

    expect(
      screen.queryByRole('button', { name: A.triggerAriaLabel(PLANNED_SPRINT.name) }),
    ).toBeNull()
  })

  /**
   * H1-3. **COMPLETED 스프린트에도 메뉴가 남는다.**
   *
   * 시작·완료 버튼은 상태로 갈리지만 편집·삭제는 갈리지 않는다 — 완료된 스프린트도
   * 이름·목표를 고치고(FR-2 · J1) 지울 수 있다(FR-3 「전 상태」).
   */
  it('H1-3: COMPLETED 스프린트에도 `⋯` 트리거가 있다', () => {
    renderHeader({ sprint: COMPLETED_SPRINT })

    expect(
      screen.getByRole('button', { name: A.triggerAriaLabel(COMPLETED_SPRINT.name) }),
    ).toBeInTheDocument()
    // 짝 단언 — 상태로 갈리는 쪽은 여전히 갈린다
    expect(screen.queryByRole('button', { name: backlogLabels.startSprint })).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// H2. 편집 다이얼로그 — 진짜가 열리고 이름이 고유하다 (즉사 계약 §2)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumnHeader — H2 편집 다이얼로그', () => {
  /**
   * H2-1. 「스프린트 편집」을 누르면 **진짜** 다이얼로그가 그 이름으로 열린다.
   *
   * 이름이 곧 Playwright `getByRole('dialog', { name })` 의 조회 키다 — 여기서 이름이
   * 갈리면 T8 e2e 가 통째로 즉사한다.
   */
  it('H2-1: 「스프린트 편집」을 누르면 같은 이름의 다이얼로그가 열린다', async () => {
    const user = userEvent.setup()
    renderHeader()

    await openMenu(user, PLANNED_SPRINT)
    await user.click(await screen.findByRole('menuitem', { name: backlogLabels.editSprint }))

    expect(
      await screen.findByRole('dialog', { name: backlogLabels.editSprint }),
    ).toBeInTheDocument()
  })

  /**
   * H2-2. 삭제 확인 창의 이름은 편집 창과 **다르다.**
   *
   * 같은 이름의 dialog 가 둘이면 strict mode 로 즉사한다(계약 §2).
   */
  it('H2-2: 삭제 확인 창 이름이 편집 창 이름과 겹치지 않는다', () => {
    expect(backlogLabels.deleteSprint).not.toBe(backlogLabels.editSprint)
    expect(backlogLabels.deleteSprint.includes(backlogLabels.editSprint)).toBe(false)
    expect(backlogLabels.editSprint.includes(backlogLabels.deleteSprint)).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// H3. 기존 액션 무회귀 — `⋯` 가 같은 줄에 얹힌다
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintColumnHeader — H3 기존 액션 무회귀', () => {
  it('H3-1: 시작 버튼과 번다운 링크가 그대로 있다', () => {
    renderHeader({ sprint: PLANNED_SPRINT })

    expect(
      screen.getByRole('button', { name: backlogLabels.startSprint }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', {
        name: `${PLANNED_SPRINT.name} ${burndownLabels.toggle.burndown} 보기`,
      }),
    ).toBeInTheDocument()
  })

  /** H3-2. 모바일 터치 타깃 44px (NFR-4) — 헤더 기존 규율을 `⋯` 도 따른다 */
  it('H3-2: `⋯` 트리거가 모바일 터치 타깃 규율을 따른다', () => {
    renderHeader()

    const trigger = screen.getByRole('button', {
      name: A.triggerAriaLabel(PLANNED_SPRINT.name),
    })
    expect(trigger.className).toContain('min-h-11')
    expect(trigger.className).toContain('min-w-11')
    expect(trigger.className).toContain('md:min-h-0')
    expect(trigger.className).toContain('md:min-w-0')
  })
})
