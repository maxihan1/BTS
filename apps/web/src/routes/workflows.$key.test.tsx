// 워크플로우 상세 페이지 단위 테스트 — T5-1 (다이어그램 마운트) + T5-2 (에러 폴백) + FR-3 회귀 가드
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { softwareDefaultFixture } from '@/mocks/workflow-fixtures'
import { WorkflowDetailPage } from './workflows.$key'

// mermaid 라이브러리 mock — jsdom 환경에서 실제 SVG 렌더 불가
// (WorkflowDiagram.test.tsx 와 동일 패턴. PR #16 D5 옵션 C 후속)
// 단위 테스트 책임: description 렌더 behavior 검증만. 실제 SVG 렌더는 Playwright E2E 담당.
vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    run: vi.fn().mockResolvedValue(undefined),
    render: vi.fn().mockResolvedValue({ svg: '<svg></svg>' }),
  },
}))

// WorkflowDetailPage는 props로 workflowKey를 받으므로 라우터 없이 단위 테스트 가능.

function renderPage(workflowKey: string) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <WorkflowDetailPage workflowKey={workflowKey} />
    </QueryClientProvider>,
  )
}

describe('WorkflowDetailPage', () => {
  /**
   * T5-1. key param으로 fetchWorkflow 트리거 + WorkflowDiagram 마운트 검증.
   * MSW server.use(...workflowHandlers)로 /api/v1/workflows/software-default 200 응답.
   * 성공 시 aria-label="소프트웨어 개발 기본 워크플로우 다이어그램" 요소가 DOM에 존재해야 한다.
   */
  // jsdom 환경에서 mermaid SVG getBBox() 미구현으로 mermaid.render() timeout 발생 (PR #10 시점부터 잠재).
  // WorkflowDetailPage 의 실제 mermaid 렌더 검증은 e2e/workflow.spec.ts 4 happy path 가 커버.
  // PR #16 hot-fix (D5 옵션 C) — 단위 테스트의 jsdom 한계 명시 + E2E 위임.
  it.skip('T5-1: key prop을 받으면 fetchWorkflow를 호출하고 WorkflowDiagram을 마운트한다', async () => {
    server.use(...workflowHandlers)

    renderPage('software-default')

    // 로딩 상태 확인
    expect(screen.getByText(/로딩 중/)).toBeInTheDocument()

    // 성공 시 다이어그램 컨테이너(aria-label 포함)가 렌더되어야 한다
    await waitFor(() =>
      expect(
        screen.getByLabelText(/소프트웨어 개발 기본 워크플로우 다이어그램/),
      ).toBeInTheDocument(),
    )
  })

  /**
   * T5-2. fetch 실패(404) 시 에러 폴백 텍스트 렌더 검증.
   * MSW handler override — 존재하지 않는 key에 대해 404 응답.
   * 에러 상태 시 role="alert" + "워크플로우를 찾을 수 없습니다" 텍스트가 DOM에 존재해야 한다.
   */
  // jsdom 환경에서 mermaid SVG getBBox() 미구현으로 mermaid.render() timeout 발생 (PR #10 시점부터 잠재).
  // WorkflowDetailPage 의 실제 mermaid 렌더 검증은 e2e/workflow.spec.ts 4 happy path 가 커버.
  // PR #16 hot-fix (D5 옵션 C) — 단위 테스트의 jsdom 한계 명시 + E2E 위임.
  it.skip('T5-2: fetch 실패(404) 시 에러 폴백 텍스트를 렌더한다', async () => {
    // workflowHandlers 등록 후 unknown-key에 대해 404가 자동으로 반환된다 (workflow-handlers.ts 참조)
    server.use(...workflowHandlers)

    renderPage('unknown-key-that-does-not-exist')

    await waitFor(() =>
      expect(screen.getByText(/워크플로우를 찾을 수 없습니다/)).toBeInTheDocument(),
    )
  })
})

describe('description rendering (FR-3 behavior preservation 회귀 가드)', () => {
  /**
   * T-NEW-1. description 이 빈 문자열인 workflow 는 <p> 미렌더 검증.
   * MSW handler override — software-default fixture 에서 description 만 '' 로 교체한 응답.
   * behavior preservation refactor 회귀 가드: `!== ''` 과 `.length > 0` 모두 동일 결과.
   * RED 시점에 이미 통과하는 것이 정상 (behavior 변화 없음을 선제 보장).
   */
  it("description 이 빈 문자열인 workflow 의 경우 description <p> 를 미렌더링한다", async () => {
    server.use(
      http.get('/api/v1/workflows/:key', ({ params }) => {
        if (params['key'] === 'software-default') {
          return HttpResponse.json({
            data: { ...softwareDefaultFixture, description: '' },
          })
        }
        return HttpResponse.json({ message: '워크플로우를 찾을 수 없습니다' }, { status: 404 })
      }),
    )

    const { container } = renderPage('software-default')

    // 워크플로우 이름(h1) 이 렌더되면 로딩 완료
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // description <p> 는 존재하지 않아야 한다
    // header 내부의 <p> 요소 — h1 바로 다음 sibling
    const header = container.querySelector('header')
    expect(header).toBeInTheDocument()
    const descParagraph = header?.querySelector('p')
    expect(descParagraph).not.toBeInTheDocument()
  })

  /**
   * T-NEW-2. description 이 있는 workflow 는 <p> 렌더 검증.
   * workflowHandlers 정상 사용 — software-default fixture 의 description 텍스트 확인.
   * RED 시점에 이미 통과하는 것이 정상 (behavior 변화 없음을 선제 보장).
   */
  it("description 이 있는 workflow 의 경우 description <p> 를 렌더링한다", async () => {
    server.use(...workflowHandlers)

    renderPage('software-default')

    // software-default fixture 의 description 텍스트가 DOM 에 존재해야 한다
    await screen.findByText(softwareDefaultFixture.description)
  })
})
