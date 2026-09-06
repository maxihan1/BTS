// 프로젝트 헤더 계약 — 브레드크럼 + 프로젝트명 h1 + 즐겨찾기 + 액션 자리 (Jira 패리티 J5-8·J5-9·J5-10)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'

const mockUseProject = vi.fn<(key: string) => { data?: { name: string } }>()

// 실 라우터·실 쿼리를 세우지 않는다 — 이 컴포넌트의 책임은 **배치 4개**뿐이다.
vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}))

vi.mock('@/hooks/use-project', () => ({
  useProject: (key: string) => mockUseProject(key),
}))

vi.mock('@/components/favorite/FavoriteButton', () => ({
  FavoriteButton: ({ targetType, targetId }: { targetType: string; targetId: string }) => (
    <button type="button" data-testid="favorite">{`${targetType}:${targetId}`}</button>
  ),
}))

const { ProjectViewHeader } = await import('@/components/project/ProjectViewHeader')

beforeEach(() => {
  mockUseProject.mockReturnValue({ data: { name: 'Atlas 프로젝트' } })
})

/** 액션 호스트를 받아 두는 렌더 헬퍼 — 콜백 ref 계약을 그대로 쓴다 */
function renderHeader(onActionHost: (node: HTMLElement | null) => void = () => {}): void {
  render(<ProjectViewHeader projectKey="ATLAS" onActionHost={onActionHost} />)
}

describe('ProjectViewHeader — 제목 소유 (J5-8)', () => {
  it('프로젝트 이름을 문서 h1 로 그린다', () => {
    renderHeader()

    expect(screen.getByRole('heading', { level: 1, name: 'Atlas 프로젝트' })).toBeInTheDocument()
  })

  it('이름이 아직 없으면 프로젝트 키로 대신한다', () => {
    // 🛑 로딩 창에 h1 을 비워 두면 그 사이 문서에 h1 이 0개가 된다(랜드마크 계약).
    mockUseProject.mockReturnValue({})
    renderHeader()

    expect(screen.getByRole('heading', { level: 1, name: 'ATLAS' })).toBeInTheDocument()
  })

  it('h1 은 문서에 하나뿐이다', () => {
    renderHeader()

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })
})

describe('ProjectViewHeader — 탐색 경로 (J5-8)', () => {
  it('「프로젝트 → 프로젝트명」 경로를 그린다', () => {
    renderHeader()

    const breadcrumb = screen.getByRole('navigation', { name: '탐색 경로' })
    expect(breadcrumb).toHaveTextContent('프로젝트')
    expect(breadcrumb).toHaveTextContent('Atlas 프로젝트')
  })

  it('첫 항목은 프로젝트 목록으로 가는 링크다', () => {
    renderHeader()

    const breadcrumb = screen.getByRole('navigation', { name: '탐색 경로' })
    expect(breadcrumb.querySelector('a')).toHaveAttribute('href', '/projects')
  })
})

describe('ProjectViewHeader — 즐겨찾기·액션 자리 (J5-9)', () => {
  it('프로젝트 즐겨찾기 토글이 제목 옆에 있다', () => {
    renderHeader()

    expect(screen.getByTestId('favorite')).toHaveTextContent('PROJECT:ATLAS')
  })

  it('액션 호스트 노드를 콜백으로 넘긴다', () => {
    // 🛑 `useRef` 로 잡으면 첫 렌더에 null 이고 채워져도 재렌더가 없어 포털이 영영 안 붙는다.
    //    `ProjectNavTabs` 의 `portalHost` 가 같은 이유로 콜백 ref 다.
    const seen: (HTMLElement | null)[] = []
    renderHeader((node) => seen.push(node))

    expect(seen.at(-1)).toBeInstanceOf(HTMLElement)
  })
})
