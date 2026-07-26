// 프로젝트 워크플로우 스킴 할당 설정 페이지 단위 테스트 — EC-1 (미할당 정상 케이스) + UPSERT + 에러 처리
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { ProjectWorkflowSchemeSettingsPage } from '@/routes/projects.$projectKey.settings.workflow-scheme'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'

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

  /**
   * R1. 배정 Select가 관리자 목록(useWorkflowSchemes)이 아니라
   * 프로젝트 assignable 엔드포인트(useAssignableWorkflowSchemes)의 스킴으로 채워진다.
   *
   * 구 관리자 엔드포인트(/api/v1/workflow-schemes)를 같은 schemeKey에 대해
   * 다른 이름(미끼)으로 override하고, assignable 엔드포인트는 기본 fixture(실제 이름)를 유지한다.
   * Select trigger에 실제 이름이 보이면 assignable 엔드포인트를 참조한다는 뜻이다.
   */
  it('R1: 배정 Select가 assignable 엔드포인트의 스킴으로 채워진다', async () => {
    server.use(
      http.get('/api/v1/workflow-schemes', () => {
        return HttpResponse.json({
          data: [
            {
              schemeKey: 'custom-scheme-alpha',
              name: '미끼-관리자-이름',
              description: '',
              isStandard: false,
              usedByProjectsCount: 0,
              mappingsCount: 0,
            },
          ],
        })
      }),
    )

    renderPage('ATLAS')

    const combobox = await screen.findByRole('combobox', {
      name: workflowSchemeLabels.assignment.schemeSelectAriaLabel,
    })

    await waitFor(() => {
      expect(within(combobox).getByText('사내 개발팀 커스텀 스킴')).toBeInTheDocument()
    })
  })

  /**
   * R2. ★CRITICAL 회귀 테스트 — assignable 엔드포인트가 403을 반환하면
   * 권한 안내 카드를 보여주고 빈 Select로 방치하지 않는다.
   * ASSIGN_SCHEME 권한이 없는 사용자가 배정 화면에 도달했을 때의 무음 실패 방지.
   */
  it('R2: 403이면 권한 안내를 보여주고 빈 Select로 두지 않는다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/assignable-workflow-schemes', () => {
        return HttpResponse.json({ code: 'FORBIDDEN', detail: '권한이 없습니다' }, { status: 403 })
      }),
    )

    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByText(workflowSchemeLabels.assignment.forbiddenMessage)).toBeInTheDocument()
    })

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  /**
   * R3 (권장). 스킴 0건(200 + [])은 403과 다른 상태다 — 권한 안내가 뜨지 않고
   * Select는 정상 렌더(빈 옵션)된다. 같은 증상(빈 Select)의 두 원인이 실제로 구분됨을 못 박는다.
   */
  it('R3: 스킴 0건(200 + [])이면 권한 안내가 뜨지 않는다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/assignable-workflow-schemes', () => {
        return HttpResponse.json({ data: [] })
      }),
    )

    renderPage('ATLAS')

    const combobox = await screen.findByRole('combobox', {
      name: workflowSchemeLabels.assignment.schemeSelectAriaLabel,
    })
    expect(combobox).toBeInTheDocument()

    expect(
      screen.queryByText(workflowSchemeLabels.assignment.forbiddenMessage),
    ).not.toBeInTheDocument()
  })

  /**
   * N4-1. ★hotfix — assignable 엔드포인트가 404를 반환해도(오타 프로젝트 키 등)
   * 빈 Select로 방치하지 않고 오류 안내를 보여준다. 403과는 원인이 다르므로 문구도 달라야 한다.
   */
  it('404 여도 빈 Select 로 방치하지 않고 오류 안내를 보여준다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/assignable-workflow-schemes', () => {
        return HttpResponse.json({ code: 'NOT_FOUND', detail: '프로젝트를 찾을 수 없습니다' }, { status: 404 })
      }),
    )

    renderPage('TYPO')

    await waitFor(() => {
      expect(screen.getByText(workflowSchemeLabels.assignment.loadErrorMessage)).toBeInTheDocument()
    })

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()

    // 403 안내와는 다른 문구여야 한다 (원인이 다르므로)
    expect(
      screen.queryByText(workflowSchemeLabels.assignment.forbiddenMessage),
    ).not.toBeInTheDocument()
  })

  /**
   * N4-2. ★hotfix — assignable 엔드포인트가 500을 반환해도 빈 Select로 방치하지 않고
   * 오류 안내를 보여준다. 403과는 원인이 다르므로 문구도 달라야 한다.
   */
  it('500 이어도 빈 Select 로 방치하지 않고 오류 안내를 보여준다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/assignable-workflow-schemes', () => {
        return HttpResponse.json({ code: 'INTERNAL_SERVER_ERROR', detail: '서버 오류' }, { status: 500 })
      }),
    )

    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByText(workflowSchemeLabels.assignment.loadErrorMessage)).toBeInTheDocument()
    })

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()

    // 403 안내와는 다른 문구여야 한다 (원인이 다르므로)
    expect(
      screen.queryByText(workflowSchemeLabels.assignment.forbiddenMessage),
    ).not.toBeInTheDocument()
  })
})
