// 가젯 컴포넌트 6종 + GadgetRenderer 렌더 테스트 — FR-DB-02 D6/D7 Task-5 RED
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 mock (vi.mock은 Vitest가 파일 상단으로 hoisting)
// ─────────────────────────────────────────────────────────────────────────────

// TanStack Router Link — 라우터 컨텍스트 없이 단위 테스트 가능하도록 mock
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

// useGadgetData 훅 mock — 컴포넌트 렌더 전용 테스트 (hook 내부 로직은 useGadgetData.test.ts 담당)
vi.mock('./useGadgetData', () => ({
  useGadgetData: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// import (vi.mock hoisting 이후)
// ─────────────────────────────────────────────────────────────────────────────

import { useGadgetData } from './useGadgetData'
import type { GadgetDataResult } from './gadget-types'
import { IssueListGadget } from './IssueListGadget'
import { IssueCountGadget } from './IssueCountGadget'
import { TextWidgetGadget } from './TextWidgetGadget'
import { LinkListGadget } from './LinkListGadget'
import { GadgetRenderer } from './GadgetRenderer'

const mockUseGadgetData = vi.mocked(useGadgetData)

// ─────────────────────────────────────────────────────────────────────────────
// 공유 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const loadingResult: GadgetDataResult = { isLoading: true, isError: false, rows: undefined, totalElements: undefined }
const errorResult: GadgetDataResult = { isLoading: false, isError: true, rows: undefined, totalElements: undefined }
const emptyRowsResult: GadgetDataResult = { isLoading: false, isError: false, rows: [], totalElements: undefined }

const PROJECT_CONFIG = { projectKey: 'ATLAS' }
const FILTER_CONFIG = { filterId: '10000000-0000-4000-8000-000000000001' }

// ─────────────────────────────────────────────────────────────────────────────
// IssueListGadget
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueListGadget', () => {
  it('로딩 중에는 로딩 안내 문구를 표시한다', () => {
    mockUseGadgetData.mockReturnValue(loadingResult)
    render(<IssueListGadget gadgetType="assigned_to_me" config={PROJECT_CONFIG} />)
    expect(screen.getByText(/불러오는 중/)).toBeInTheDocument()
  })

  it('에러 시 에러 메시지를 표시한다', () => {
    mockUseGadgetData.mockReturnValue(errorResult)
    render(<IssueListGadget gadgetType="assigned_to_me" config={PROJECT_CONFIG} />)
    expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument()
  })

  it('결과가 없으면 빈 상태 안내를 표시한다', () => {
    mockUseGadgetData.mockReturnValue(emptyRowsResult)
    render(<IssueListGadget gadgetType="recently_created" config={PROJECT_CONFIG} />)
    expect(screen.getByText(/이슈가 없습니다/)).toBeInTheDocument()
  })

  it('이슈 목록을 렌더링하고 키에 이슈 상세 링크를 제공한다', () => {
    mockUseGadgetData.mockReturnValue({
      isLoading: false,
      isError: false,
      rows: [{ key: 'ATLAS-1', summary: '첫 번째 이슈' }],
      totalElements: undefined,
    })
    render(<IssueListGadget gadgetType="recently_created" config={PROJECT_CONFIG} />)
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    expect(screen.getByText('첫 번째 이슈')).toBeInTheDocument()
    expect(screen.getByTestId('issue-link')).toHaveAttribute('href', '/issues/ATLAS-1')
  })

  it('여러 이슈 목록을 렌더링한다', () => {
    mockUseGadgetData.mockReturnValue({
      isLoading: false,
      isError: false,
      rows: [
        { key: 'ATLAS-1', summary: '첫 번째 이슈' },
        { key: 'ATLAS-2', summary: '두 번째 이슈' },
      ],
      totalElements: undefined,
    })
    render(<IssueListGadget gadgetType="assigned_to_me" config={PROJECT_CONFIG} />)
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    expect(screen.getAllByTestId('issue-link')).toHaveLength(2)
  })

  it('filter_result 타입도 동일하게 동작한다', () => {
    mockUseGadgetData.mockReturnValue({
      isLoading: false,
      isError: false,
      rows: [{ key: 'ATLAS-3', summary: '필터 결과 이슈' }],
      totalElements: undefined,
    })
    render(<IssueListGadget gadgetType="filter_result" config={FILTER_CONFIG} />)
    expect(screen.getByText('필터 결과 이슈')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IssueCountGadget
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCountGadget', () => {
  it('로딩 중에는 로딩 안내 문구를 표시한다', () => {
    mockUseGadgetData.mockReturnValue(loadingResult)
    render(<IssueCountGadget config={FILTER_CONFIG} />)
    expect(screen.getByText(/불러오는 중/)).toBeInTheDocument()
  })

  it('에러 시 에러 메시지를 표시한다', () => {
    mockUseGadgetData.mockReturnValue(errorResult)
    render(<IssueCountGadget config={FILTER_CONFIG} />)
    expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument()
  })

  it('totalElements를 큰 숫자로 표시한다', () => {
    mockUseGadgetData.mockReturnValue({ isLoading: false, isError: false, rows: undefined, totalElements: 42 })
    render(<IssueCountGadget config={FILTER_CONFIG} />)
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('totalElements가 0이면 0을 표시한다', () => {
    mockUseGadgetData.mockReturnValue({ isLoading: false, isError: false, rows: undefined, totalElements: 0 })
    render(<IssueCountGadget config={FILTER_CONFIG} />)
    expect(screen.getByText('0')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TextWidgetGadget
// ─────────────────────────────────────────────────────────────────────────────

describe('TextWidgetGadget', () => {
  it('markdown 텍스트를 plain text로 렌더링한다', () => {
    render(<TextWidgetGadget markdown="안녕하세요" />)
    expect(screen.getByText('안녕하세요')).toBeInTheDocument()
  })

  it('줄바꿈을 포함한 텍스트를 모두 렌더링한다', () => {
    render(<TextWidgetGadget markdown={'첫째 줄\n둘째 줄'} />)
    expect(screen.getByText(/첫째 줄/)).toBeInTheDocument()
    expect(screen.getByText(/둘째 줄/)).toBeInTheDocument()
  })

  it('XSS 차단 — <script> 태그가 DOM 요소로 생성되지 않는다', () => {
    const xssInput = '<script>alert("xss")</script>'
    const { container } = render(<TextWidgetGadget markdown={xssInput} />)
    // 실제 script 엘리먼트가 없어야 한다 (React 자동 이스케이프)
    expect(container.querySelector('script')).toBeNull()
  })

  it('HTML 태그를 요소로 해석하지 않는다', () => {
    const { container } = render(<TextWidgetGadget markdown="<b>굵게</b>" />)
    // b 엘리먼트가 생성되지 않아야 한다
    expect(container.querySelector('b')).toBeNull()
    // 텍스트는 그대로 노출된다
    expect(container.textContent).toContain('<b>굵게</b>')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// LinkListGadget
// ─────────────────────────────────────────────────────────────────────────────

describe('LinkListGadget', () => {
  it('http 링크를 렌더링한다', () => {
    render(<LinkListGadget links={[{ label: '홈페이지', url: 'http://example.com' }]} />)
    const link = screen.getByText('홈페이지')
    expect(link.closest('a')).toHaveAttribute('href', 'http://example.com')
  })

  it('https 링크를 렌더링한다', () => {
    render(<LinkListGadget links={[{ label: '보안 사이트', url: 'https://example.com' }]} />)
    const link = screen.getByText('보안 사이트')
    expect(link.closest('a')).toHaveAttribute('href', 'https://example.com')
  })

  it('javascript: 스킴 링크를 무시한다', () => {
    render(<LinkListGadget links={[{ label: 'XSS', url: 'javascript:alert(1)' }]} />)
    expect(screen.queryByText('XSS')).toBeNull()
  })

  it('file: 스킴 링크를 무시한다', () => {
    render(<LinkListGadget links={[{ label: '파일', url: 'file:///etc/passwd' }]} />)
    expect(screen.queryByText('파일')).toBeNull()
  })

  it('data: 스킴 링크를 무시한다', () => {
    render(<LinkListGadget links={[{ label: '데이터', url: 'data:text/html,<h1>XSS</h1>' }]} />)
    expect(screen.queryByText('데이터')).toBeNull()
  })

  it('외부 링크에 rel="noopener noreferrer" 와 target="_blank"를 적용한다', () => {
    render(<LinkListGadget links={[{ label: '외부', url: 'https://example.com' }]} />)
    const a = screen.getByText('외부').closest('a')
    expect(a).toHaveAttribute('rel', 'noopener noreferrer')
    expect(a).toHaveAttribute('target', '_blank')
  })

  it('유효 링크만 렌더링하고 비유효 링크는 건너뛴다', () => {
    render(
      <LinkListGadget
        links={[
          { label: '유효', url: 'https://valid.com' },
          { label: '무효', url: 'javascript:alert(1)' },
        ]}
      />,
    )
    expect(screen.getByText('유효')).toBeInTheDocument()
    expect(screen.queryByText('무효')).toBeNull()
  })

  // C1 RED: isSafeUrl이 대소문자 무관해야 함 — 현재 case-sensitive라 실패
  it('C1: HTTP:// 대문자 스킴 링크를 렌더링한다 (isSafeUrl 대소문자 무관)', () => {
    render(<LinkListGadget links={[{ label: '대문자HTTP', url: 'HTTP://example.com' }]} />)
    const link = screen.getByText('대문자HTTP')
    expect(link.closest('a')).toHaveAttribute('href', 'HTTP://example.com')
  })

  it('C1: HTTPS:// 대문자 스킴 링크를 렌더링한다 (isSafeUrl 대소문자 무관)', () => {
    render(<LinkListGadget links={[{ label: '대문자HTTPS', url: 'HTTPS://example.com' }]} />)
    const link = screen.getByText('대문자HTTPS')
    expect(link.closest('a')).toHaveAttribute('href', 'HTTPS://example.com')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GadgetRenderer
// ─────────────────────────────────────────────────────────────────────────────

describe('GadgetRenderer', () => {
  const TILE_BASE = { i: 'tile-1', x: 0, y: 0, w: 6, h: 4, title: '테스트' }

  beforeEach(() => {
    // 기본 빈 상태 — 각 테스트에서 필요에 따라 override
    mockUseGadgetData.mockReturnValue(emptyRowsResult)
  })

  it('gadgetType 없으면 null을 반환한다 (legacy 타일 — 호출측이 처리)', () => {
    const { container } = render(<GadgetRenderer tile={TILE_BASE} />)
    expect(container.firstChild).toBeNull()
  })

  it('assigned_to_me → IssueListGadget을 렌더링한다 (빈 상태)', () => {
    render(<GadgetRenderer tile={{ ...TILE_BASE, gadgetType: 'assigned_to_me', config: { projectKey: 'ATLAS' } }} />)
    expect(screen.getByText(/이슈가 없습니다/)).toBeInTheDocument()
  })

  it('recently_created → IssueListGadget을 렌더링한다', () => {
    render(<GadgetRenderer tile={{ ...TILE_BASE, gadgetType: 'recently_created', config: { projectKey: 'ATLAS' } }} />)
    expect(screen.getByText(/이슈가 없습니다/)).toBeInTheDocument()
  })

  it('filter_result → IssueListGadget을 렌더링한다', () => {
    render(<GadgetRenderer tile={{ ...TILE_BASE, gadgetType: 'filter_result', config: { filterId: 'some-uuid' } }} />)
    expect(screen.getByText(/이슈가 없습니다/)).toBeInTheDocument()
  })

  it('issue_count → IssueCountGadget을 렌더링한다', () => {
    mockUseGadgetData.mockReturnValue({ isLoading: false, isError: false, rows: undefined, totalElements: 7 })
    render(<GadgetRenderer tile={{ ...TILE_BASE, gadgetType: 'issue_count', config: { filterId: 'some-uuid' } }} />)
    expect(screen.getByText('7')).toBeInTheDocument()
  })

  it('text_widget → TextWidgetGadget을 렌더링한다', () => {
    render(<GadgetRenderer tile={{ ...TILE_BASE, gadgetType: 'text_widget', config: { markdown: '가젯 내용입니다' } }} />)
    expect(screen.getByText('가젯 내용입니다')).toBeInTheDocument()
  })

  it('link_list → LinkListGadget을 렌더링한다', () => {
    render(
      <GadgetRenderer
        tile={{
          ...TILE_BASE,
          gadgetType: 'link_list',
          config: { links: [{ label: '링크 레이블', url: 'https://example.com' }] },
        }}
      />,
    )
    expect(screen.getByText('링크 레이블')).toBeInTheDocument()
  })

  it('미지원 gadgetType은 "지원되지 않는 가젯" 메시지를 표시한다 (EC6)', () => {
    render(<GadgetRenderer tile={{ ...TILE_BASE, gadgetType: 'unknown_gadget_xyz' }} />)
    expect(screen.getByText(/지원되지 않는 가젯/)).toBeInTheDocument()
  })
})
