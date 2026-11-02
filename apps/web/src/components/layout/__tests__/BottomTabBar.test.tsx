// 휴대폰 아래 탭 — 자주 쓰는 넷 + 「전체」 · 「전체」는 메뉴판(모바일 드로어)을 연다 (공통 정책 §6, Q5)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useAuthStore } from '@/auth/authStore'
import { useSidebarDrawer } from '@/hooks/use-sidebar-drawer'
import { navLabels } from '@/i18n/nav-labels'
import type { WhoamiResponse } from '@/api/schemas'
import { BottomTabBar } from '../BottomTabBar'

vi.mock('@tanstack/react-router', () => ({
  // search 를 href 로 직렬화한다 — 「내 작업」이 assignee 를 싣는지 href 로 본다(Sidebar.test 관례)
  Link: ({ to, search, children, className }: {
    to: string
    search?: Record<string, string>
    activeOptions?: unknown
    children: React.ReactNode
    className?: string
  }) => (
    <a href={`${to}${search === undefined ? '' : `?${new URLSearchParams(search).toString()}`}`} className={className}>
      {children}
    </a>
  ),
}))

const USER: WhoamiResponse = {
  username: 'alice',
  email: 'alice@bts.local',
  authMethod: 'local',
  userId: 'u1',
  mustChangePassword: false,
  isSystemAdmin: false,
  mfaEnrollmentRequired: false,
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'test-token', user: USER })
  useSidebarDrawer.setState({ open: false })
})

function tabs() {
  return screen.getByRole('navigation', { name: navLabels.bottomTabs })
}

describe('BottomTabBar (Q5)', () => {
  it('자주 쓰는 넷과 「전체」를 이 순서로 보인다', () => {
    render(<BottomTabBar />)
    // 링크 넷 · 단추 하나가 섞여 있어 역할 조회 대신 칸(자식) 순서를 그대로 읽는다
    const names = [...tabs().children].map((el) => el.textContent)
    expect(names).toEqual([
      navLabels.myWork,
      navLabels.issues,
      navLabels.dashboards,
      navLabels.calendar,
      navLabels.allMenu,
    ])
  })

  it('「내 작업」은 내 사용자 id 로 거른 이슈 목록이다', () => {
    render(<BottomTabBar />)
    expect(within(tabs()).getByRole('link', { name: navLabels.myWork })).toHaveAttribute(
      'href',
      '/issues?assignee=u1',
    )
  })

  it('「전체」를 누르면 모든 메뉴가 담긴 메뉴판(모바일 드로어)이 열린다', async () => {
    render(<BottomTabBar />)
    await userEvent.click(within(tabs()).getByRole('button', { name: navLabels.allMenu }))
    expect(useSidebarDrawer.getState().open).toBe(true)
  })

  it('620px 이상에서는 보이지 않는다 — PC 짜임은 그대로', () => {
    render(<BottomTabBar />)
    expect(tabs().className).toContain('min-[620px]:hidden')
  })
})
