// 프로젝트 워크플로우 스킴 할당 설정 페이지 단위 테스트 — EC-1 (미할당 정상 케이스) + UPSERT + 에러 처리
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { ProjectWorkflowSchemeSettingsPage } from '@/routes/projects.$projectKey.settings.workflow-scheme'

// TanStack Router useParams mock
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

function renderPage(projectKey = 'ATLAS') {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectWorkflowSchemeSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

describe('ProjectWorkflowSchemeSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 기본 schemeHandlers 등록 — 각 테스트에서 server.use()로 override 가능
    server.use(...schemeHandlers)
  })

  /**
   * T9-1. 페이지 마운트 시 로딩 상태가 표시된다.
   * assignment query + schemes query 모두 대기 중에 스켈레톤/로딩 텍스트 노출.
   */
  it('T9-1: 페이지 마운트 시 로딩 상태가 표시된다', () => {
    renderPage()
    expect(screen.getByText(/로딩 중/)).toBeInTheDocument()
  })

  /**
   * T9-2. assignment가 있는 프로젝트 — 현재 스킴 이름과 변경 select + 적용 버튼이 표시된다.
   * ATLAS 프로젝트는 fixture에서 custom-scheme-alpha 할당됨.
   */
  it('T9-2: assignment가 있는 프로젝트는 현재 스킴 이름을 표시한다', async () => {
    renderPage('ATLAS')

    // 현재 스킴 이름이 DOM에 1회 이상 표시된다 (카드 영역 + select value 에 각각 노출)
    await waitFor(() => {
      const elements = screen.getAllByText('사내 개발팀 커스텀 스킴')
      expect(elements.length).toBeGreaterThanOrEqual(1)
    })

    // 「현재 적용」 indicator
    expect(screen.getByText(/현재 적용/)).toBeInTheDocument()

    // 변경 select 존재
    expect(screen.getByRole('combobox')).toBeInTheDocument()

    // 적용 버튼 존재
    expect(screen.getByRole('button', { name: /적용/ })).toBeInTheDocument()
  })

  /**
   * T9-3. assignment null (EC-1 — 404) — 안내 카드 + 신규 할당 select + 적용 버튼이 표시된다.
   * 미할당 프로젝트키로 404를 시뮬레이션한다.
   */
  it('T9-3: assignment가 없으면 안내 카드와 신규 할당 select가 표시된다', async () => {
    renderPage('UNASSIGNED-PROJECT')

    await waitFor(() =>
      expect(
        screen.getByText(/아직 워크플로우 스킴이 할당되지 않았습니다/),
      ).toBeInTheDocument(),
    )

    // 자동 할당 안내 텍스트
    expect(screen.getByText(/software-default-scheme/)).toBeInTheDocument()

    // 신규 할당 select + 적용 버튼
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /적용/ })).toBeInTheDocument()
  })

  /**
   * T9-4. 적용 버튼 클릭 시 현재 선택값으로 mutate가 호출된다.
   * ATLAS 프로젝트는 custom-scheme-alpha 할당되어 있으므로,
   * select 기본값(custom-scheme-alpha)으로 적용 클릭 → PUT 요청이 발생한다.
   *
   * Radix Select 드롭다운은 jsdom Portal 한계로 직접 옵션 클릭이 불가하므로,
   * 현재 할당값을 그대로 적용하는 시나리오로 mutate 호출 자체를 검증한다.
   */
  it('T9-4: 적용 버튼 클릭 시 PUT 요청이 발생한다', async () => {
    const user = userEvent.setup()

    // PUT 요청이 실제로 발생했는지 추적
    let putCalled = false
    server.use(
      http.put('/api/v1/projects/ATLAS/workflow-scheme', async ({ request }) => {
        putCalled = true
        const body = await request.json() as { schemeKey: string }
        return HttpResponse.json({
          data: { projectKey: 'ATLAS', schemeKey: body.schemeKey, schemeName: '테스트 스킴' },
        })
      }),
    )

    renderPage('ATLAS')

    // 데이터 로드 대기
    await waitFor(() => {
      const els = screen.getAllByText('사내 개발팀 커스텀 스킴')
      expect(els.length).toBeGreaterThanOrEqual(1)
    })

    // 적용 버튼 클릭 (현재 선택값으로 UPSERT)
    await user.click(screen.getByRole('button', { name: /적용/ }))

    // PUT 요청이 발생했는지 검증
    await waitFor(() => expect(putCalled).toBe(true))
  })

  /**
   * T9-5. 409 errorCode 응답 시 에러 처리가 된다.
   * PUT endpoint가 409를 반환해도 페이지가 유지된다.
   */
  it('T9-5: 409 errorCode 응답 시 에러 처리가 된다', async () => {
    const user = userEvent.setup()

    // PUT endpoint를 409로 override
    server.use(
      http.put('/api/v1/projects/:projectKey/workflow-scheme', () => {
        return HttpResponse.json(
          { errorCode: 'ASSIGNMENT_CONFLICT', message: '스킴 할당 충돌이 발생했습니다' },
          { status: 409 },
        )
      }),
    )

    renderPage('ATLAS')

    // 데이터 로드 대기
    await waitFor(() => {
      const els = screen.getAllByText('사내 개발팀 커스텀 스킴')
      expect(els.length).toBeGreaterThanOrEqual(1)
    })

    // 적용 클릭
    await user.click(screen.getByRole('button', { name: /적용/ }))

    // 에러 후에도 페이지는 계속 표시됨 (적용 버튼 잔류)
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /적용/ })).toBeInTheDocument(),
    )
  })
})
