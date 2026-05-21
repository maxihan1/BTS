// 워크플로우 상세 페이지 단위 테스트 — T5-1 (다이어그램 마운트) + T5-2 (에러 폴백)
import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { WorkflowDetailPage } from './workflows.$key'

// WorkflowDetailPage는 Route.useParams()가 아니라 props로 key를 받는 것이 단위 테스트 가능.
// 만약 내부에서 Route.useParams()를 사용한다면 테스트용 wrapper가 필요하다.
// 이 테스트는 RED 단계이므로, 컴포넌트 미존재 상태에서 import 실패를 기대한다.

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
  it('T5-1: key prop을 받으면 fetchWorkflow를 호출하고 WorkflowDiagram을 마운트한다', async () => {
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
  it('T5-2: fetch 실패(404) 시 에러 폴백 텍스트를 렌더한다', async () => {
    // workflowHandlers 등록 후 unknown-key에 대해 404가 자동으로 반환된다 (workflow-handlers.ts 참조)
    server.use(...workflowHandlers)

    renderPage('unknown-key-that-does-not-exist')

    await waitFor(() =>
      expect(screen.getByText(/워크플로우를 찾을 수 없습니다/)).toBeInTheDocument(),
    )
  })
})
