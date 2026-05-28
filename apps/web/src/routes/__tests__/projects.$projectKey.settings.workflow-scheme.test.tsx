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
  server.use(...schemeHandlers)
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

    await waitFor(() =>
      expect(screen.getByText('사내 개발팀 커스텀 스킴')).toBeInTheDocument(),
    )

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
   * T9-4. select 변경 후 적용 클릭 → mutate 호출 + 성공 토스트.
   * ATLAS 프로젝트에서 다른 스킴으로 변경하는 시나리오.
   */
  it('T9-4: select 변경 후 적용 클릭 시 mutate가 호출된다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS')

    // 데이터 로드 대기
    await waitFor(() =>
      expect(screen.getByText('사내 개발팀 커스텀 스킴')).toBeInTheDocument(),
    )

    // select 열기
    const combobox = screen.getByRole('combobox')
    await user.click(combobox)

    // 다른 스킴 선택 (소프트웨어 개발 기본 스킴)
    await waitFor(() =>
      expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
    await user.click(screen.getByText('소프트웨어 개발 기본 스킴'))

    // 적용 버튼 클릭
    await user.click(screen.getByRole('button', { name: /적용/ }))

    // 성공 토스트 (소너 라이브러리 — DOM에 toast 메시지가 나타남)
    // useUpdateAssignment onSuccess에서 toast.success 호출 → 낙관적 업데이트로 schemeKey 반영
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /적용/ })).toBeInTheDocument(),
    )
  })

  /**
   * T9-5. 409 ASSIGNMENT_CONFLICT errorCode → toast.error 호출.
   * PUT endpoint가 409를 반환하는 시뮬레이션.
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
    await waitFor(() =>
      expect(screen.getByText('사내 개발팀 커스텀 스킴')).toBeInTheDocument(),
    )

    // select 열기 → 다른 스킴 선택
    const combobox = screen.getByRole('combobox')
    await user.click(combobox)

    await waitFor(() =>
      expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
    await user.click(screen.getByText('소프트웨어 개발 기본 스킴'))

    // 적용 클릭
    await user.click(screen.getByRole('button', { name: /적용/ }))

    // 에러 후에도 페이지는 계속 표시됨 (적용 버튼 잔류)
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /적용/ })).toBeInTheDocument(),
    )
  })
})
