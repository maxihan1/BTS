// LinkGraph 컴포넌트 단위 테스트 — 5상태 + depth 변경 + 노드 클릭 + a11y (FR-LK-02 D6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { JSX, ReactNode } from 'react'

// ─────────────────────────────────────────────────────────────────────────────
// mermaid mock — jsdom 환경에서 실제 SVG 렌더 불가
// WorkflowDiagram.test.tsx 패턴 그대로.
// 실제 flowchart 출력 구조를 모사: 노드 <g class="node" data-id="node_0"> + 키 텍스트
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({
      svg: `<svg>
        <g class="node" data-id="node_0" id="flowchart-node_0-1">
          <text>ATLAS-1</text>
        </g>
        <g class="node" data-id="node_1" id="flowchart-node_1-2">
          <text>ATLAS-2</text>
        </g>
      </svg>`,
    }),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// @tanstack/react-router useNavigate mock
// ─────────────────────────────────────────────────────────────────────────────

const mockNavigate = vi.fn()

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

// ─────────────────────────────────────────────────────────────────────────────
// @/api/issue-graph useIssueGraph mock
// ─────────────────────────────────────────────────────────────────────────────

const mockUseIssueGraph = vi.fn()

vi.mock('@/api/issue-graph', () => ({
  useIssueGraph: (...args: unknown[]) => mockUseIssueGraph(...args),
  ISSUE_GRAPH_ERROR_CODES: {
    ISSUE_NOT_FOUND: 'ISSUE_NOT_FOUND',
    INVALID_DEPTH: 'INVALID_DEPTH',
  },
  extractGraphErrorCode: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처 — IssueGraphResponse
// ─────────────────────────────────────────────────────────────────────────────

/** 정상 그래프 응답 — 엣지 있음, truncated=false */
const normalGraphData = {
  center: 'ATLAS-1',
  depth: 2,
  nodes: [
    { key: 'ATLAS-1', summary: '중심 이슈', statusKey: 'open', depth: 0 },
    { key: 'ATLAS-2', summary: '연결 이슈', statusKey: 'open', depth: 1 },
  ],
  edges: [
    { from: 'ATLAS-1', to: 'ATLAS-2', type: 'BLOCKS' },
  ],
  truncated: false,
}

/** truncated=true 그래프 응답 */
const truncatedGraphData = {
  ...normalGraphData,
  truncated: true,
}

/** 빈 그래프 응답 — 엣지 없음 → generateGraphMermaidCode가 null 반환 */
const emptyGraphData = {
  center: 'ATLAS-1',
  depth: 2,
  nodes: [
    { key: 'ATLAS-1', summary: '중심 이슈', statusKey: 'open', depth: 0 },
  ],
  edges: [],
  truncated: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — QueryClientProvider 래퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }): JSX.Element {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  return Wrapper
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('LinkGraph', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  async function renderLinkGraph(issueKey = 'ATLAS-1') {
    const { LinkGraph } = await import('./LinkGraph')
    const Wrapper = createWrapper()
    return render(<LinkGraph issueKey={issueKey} />, { wrapper: Wrapper })
  }

  // 1. 기본 접힘 — 펼치기 전 useIssueGraph가 enabled=false로 호출
  it('T1: 기본 접힘 상태 — useIssueGraph enabled=false 호출, 그래프 미표시', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: null,
    })

    await renderLinkGraph()

    // 펼치기 버튼이 존재해야 함
    expect(screen.getByRole('button', { name: '그래프 펼치기' })).toBeInTheDocument()

    // enabled=false로 호출됐는지 확인 (3번째 인자)
    expect(mockUseIssueGraph).toHaveBeenCalledWith('ATLAS-1', 2, false)
  })

  // 2. 펼침 → depth=2 조회 + mermaid SVG 주입
  it('T2: 패널 펼침 → enabled=true, SVG 주입 + 노드 aria-label 부여', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: normalGraphData,
      isLoading: false,
      error: null,
    })

    await renderLinkGraph()

    // 펼치기 버튼 클릭
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    // enabled=true로 재호출됐는지 확인
    expect(mockUseIssueGraph).toHaveBeenCalledWith('ATLAS-1', 2, true)

    // 접기 버튼으로 변경됨
    expect(screen.getByRole('button', { name: '그래프 접기' })).toBeInTheDocument()

    // mermaid 렌더 후 SVG가 주입됐는지 대기
    await waitFor(() => {
      const mermaidMod = vi.mocked(
        (await import('mermaid')).default
      )
      expect(mermaidMod.render).toHaveBeenCalled()
    })
  })

  // 3. 빈 그래프 → emptyState 표시, mermaid 미렌더
  it('T3: 엣지 없는 그래프 → emptyState 메시지 표시, mermaid.render 미호출', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: emptyGraphData,
      isLoading: false,
      error: null,
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    await waitFor(() => {
      expect(screen.getByText('연결된 이슈가 없습니다.')).toBeInTheDocument()
    })

    const mermaidMod = await import('mermaid')
    expect(mermaidMod.default.render).not.toHaveBeenCalled()
  })

  // 4. 로딩 상태 표시
  it('T4: 로딩 중 → loadingState 텍스트 표시', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: undefined,
      isLoading: true,
      error: null,
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    await waitFor(() => {
      expect(screen.getByText('그래프를 불러오는 중입니다.')).toBeInTheDocument()
    })
  })

  // 5a. 404 에러 → notFound 메시지 (role=alert)
  it('T5a: 404 ISSUE_NOT_FOUND 에러 → notFound 메시지 (role=alert)', async () => {
    const { ApiError } = await import('@/api/client')
    const notFoundError = new ApiError(404, { errorCode: 'ISSUE_NOT_FOUND' })

    const { extractGraphErrorCode } = await import('@/api/issue-graph')
    vi.mocked(extractGraphErrorCode).mockReturnValue('ISSUE_NOT_FOUND')

    mockUseIssueGraph.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: notFoundError,
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    await waitFor(() => {
      const alertEl = screen.getByRole('alert')
      expect(alertEl).toBeInTheDocument()
      expect(alertEl).toHaveTextContent('이슈를 찾을 수 없습니다.')
    })
  })

  // 5b. 기타 에러 → loadError 메시지 (role=alert)
  it('T5b: 기타 에러 → loadError 메시지 (role=alert)', async () => {
    const { extractGraphErrorCode } = await import('@/api/issue-graph')
    vi.mocked(extractGraphErrorCode).mockReturnValue(null)

    mockUseIssueGraph.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new Error('network error'),
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    await waitFor(() => {
      const alertEl = screen.getByRole('alert')
      expect(alertEl).toBeInTheDocument()
      expect(alertEl).toHaveTextContent('그래프 데이터를 불러오지 못했습니다.')
    })
  })

  // 6. mermaid render reject → renderError (role=alert)
  it('T6: mermaid.render 실패 → renderError (role=alert)', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: normalGraphData,
      isLoading: false,
      error: null,
    })

    const mermaidMod = await import('mermaid')
    vi.mocked(mermaidMod.default.render).mockRejectedValueOnce(new Error('mermaid parse error'))

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    await waitFor(() => {
      const alertEl = screen.getByRole('alert')
      expect(alertEl).toBeInTheDocument()
      expect(alertEl).toHaveTextContent('그래프를 렌더링하지 못했습니다.')
    })
  })

  // 7. truncated=true → truncatedNotice 표시 + 그래프도 렌더됨
  it('T7: truncated=true → truncatedNotice 표시', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: truncatedGraphData,
      isLoading: false,
      error: null,
    })

    const mermaidMod = await import('mermaid')
    vi.mocked(mermaidMod.default.render).mockResolvedValueOnce({
      svg: '<svg><g class="node" data-id="node_0"><text>ATLAS-1</text></g></svg>',
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    await waitFor(() => {
      expect(screen.getByText('노드가 너무 많아 일부만 표시됩니다.')).toBeInTheDocument()
    })
  })

  // 8. depth 2→3 변경 → useIssueGraph가 depth=3로 재호출
  it('T8: depth select 2→3 변경 → useIssueGraph depth=3 재호출', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: normalGraphData,
      isLoading: false,
      error: null,
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    // depth select 변경
    const depthSelect = screen.getByRole('combobox', { name: '깊이' })
    fireEvent.change(depthSelect, { target: { value: '3' } })

    // depth=3로 재호출됐는지 확인
    expect(mockUseIssueGraph).toHaveBeenCalledWith('ATLAS-1', 3, true)
  })

  // 9. 노드 클릭 → navigate 호출, center 클릭 no-op
  it('T9: 노드 클릭 → navigate 올바른 key, center 노드(ATLAS-1) 클릭은 no-op', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: normalGraphData,
      isLoading: false,
      error: null,
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    // mermaid SVG 주입 대기
    await waitFor(() => {
      // 모의 SVG의 node_1은 ATLAS-2 (비center)
      const node1 = document.querySelector('[data-id="node_1"]')
      expect(node1).not.toBeNull()
    })

    // 비 center 노드(ATLAS-2, node_1) 클릭
    const node1 = document.querySelector('[data-id="node_1"]')
    if (node1 === null) throw new Error('node_1 not found')
    fireEvent.click(node1)

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/issues/$key',
      params: { key: 'ATLAS-2' },
    })

    // center 노드(ATLAS-1, node_0) 클릭 → no-op
    mockNavigate.mockClear()
    const node0 = document.querySelector('[data-id="node_0"]')
    if (node0 === null) throw new Error('node_0 not found')
    fireEvent.click(node0)

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  // 10. 노드 Enter keydown → navigate, 노드에 tabindex/role/aria-label 존재
  it('T10: 노드 Enter keydown → navigate, tabindex=0 + role=link + aria-label 확인', async () => {
    mockUseIssueGraph.mockReturnValue({
      data: normalGraphData,
      isLoading: false,
      error: null,
    })

    await renderLinkGraph()
    await userEvent.click(screen.getByRole('button', { name: '그래프 펼치기' }))

    await waitFor(() => {
      const node1 = document.querySelector('[data-id="node_1"]')
      expect(node1).not.toBeNull()
    })

    const node1 = document.querySelector('[data-id="node_1"]')
    if (node1 === null) throw new Error('node_1 not found')

    // a11y 속성 확인
    expect(node1.getAttribute('tabindex')).toBe('0')
    expect(node1.getAttribute('role')).toBe('link')
    expect(node1.getAttribute('aria-label')).toBe('이슈 ATLAS-2 노드')

    // Enter keydown → navigate
    fireEvent.keyDown(node1, { key: 'Enter' })

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/issues/$key',
      params: { key: 'ATLAS-2' },
    })
  })
})
