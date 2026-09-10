// 프로젝트 설정 스킴 관리 판정 — 스코프 창구 · 전역 읽기 전용 · 생성이 소유를 싣는가 (FR-WF-08 PR ⑤)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'
import { ProjectWorkflowSchemeSettingsPage } from '../projects.$projectKey.settings.workflow-scheme'

const labels = workflowSchemeLabels.projectManagement

vi.mock('@tanstack/react-router', () => ({ useParams: () => ({ projectKey: 'ATLAS' }) }))
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

const PROJECT_ID = '11111111-1111-4111-8111-111111111111'

/** 전역 템플릿 1 + 이 프로젝트 전용 1. 관리 목록 형태(카운트·소유 포함). */
function managedBody() {
  return {
    data: [
      {
        id: 1,
        key: 'software-scheme',
        name: '전역 템플릿',
        description: null,
        isStandard: true,
        projectId: null,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        usedByProjectsCount: 3,
        mappingsCount: 2,
        mappings: [],
      },
      {
        id: 2,
        key: 'atlas-scheme',
        name: '아틀라스 스킴',
        description: null,
        isStandard: false,
        projectId: PROJECT_ID,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        usedByProjectsCount: 1,
        mappingsCount: 1,
        mappings: [],
      },
    ],
  }
}

/** 배정 화면이 함께 뜨므로 그쪽 요청도 막아 둔다 — 관리 구역만 재는 것이 목적이다. */
function assignmentHandlers() {
  return [
    http.get('*/api/v1/projects/:projectKey/workflow-scheme', () =>
      HttpResponse.json({
        data: { id: 1, key: 'software-scheme', name: '전역 템플릿', description: null, isStandard: true },
      }),
    ),
    http.get('*/api/v1/projects/:projectKey/assignable-workflow-schemes', () =>
      HttpResponse.json({ data: [] }),
    ),
  ]
}

beforeEach(() => {
  vi.clearAllMocks()
})

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <ProjectWorkflowSchemeSettingsPage projectKey="ATLAS" />
    </QueryClientProvider>,
  )
}

describe('프로젝트 스킴 관리', () => {
  it('전역 목록이 아니라 프로젝트 스코프 창구를 탄다', async () => {
    let managedPath = ''
    let globalListHit = false
    server.use(
      ...assignmentHandlers(),
      http.get('*/api/v1/workflow-schemes', () => {
        globalListHit = true
        return HttpResponse.json({ data: [] })
      }),
      http.get('*/api/v1/projects/:projectKey/workflow-schemes', ({ request }) => {
        managedPath = new URL(request.url).pathname
        return HttpResponse.json(managedBody())
      }),
    )
    renderPage()

    await screen.findByRole('button', { name: '아틀라스 스킴' })
    expect(managedPath).toBe('/api/v1/projects/ATLAS/workflow-schemes')
    // ★전역 목록은 MANAGE_SCHEME + Global 게이트라 프로젝트 관리자에게 403 이다.
    //  사이드바가 자체 조회를 안 끄면 이 요청이 조용히 나가 콘솔이 403 으로 덮인다.
    expect(globalListHit).toBe(false)
  })

  it('전역 템플릿을 고르면 읽기 전용 안내를 낸다', async () => {
    server.use(
      ...assignmentHandlers(),
      http.get('*/api/v1/projects/:projectKey/workflow-schemes', () => HttpResponse.json(managedBody())),
      http.get('*/api/v1/workflow-schemes/:schemeKey', () =>
        HttpResponse.json({
          data: {
            id: 1,
            key: 'software-scheme',
            name: '전역 템플릿',
            description: null,
            isStandard: true,
            projectId: null,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
            usedByProjectsCount: 3,
            mappingsCount: 1,
            mappings: [
              {
                id: 10,
                issueTypeKey: 'bug',
                issueTypeName: '버그',
                workflowKey: 'bug-flow',
                workflowName: '버그 흐름',
                isDefault: false,
              },
            ],
          },
        }),
      ),
    )
    renderPage()

    // 「전역 템플릿」이라는 이름은 배정 카드에도 있다 — 사이드바 nav 로 좁혀야 strict mode 를 안 깬다.
    const sidebar = await screen.findByRole('navigation', { name: workflowSchemeLabels.sidebar.nav })
    fireEvent.click(await within(sidebar).findByRole('button', { name: '전역 템플릿' }))

    // 전역 편집은 SYSTEM_ADMIN 소관이라 편집 컨트롤을 띄우면 눌러 보고 403 을 받는 자리가 남는다.
    expect(await screen.findByText(labels.globalReadOnlyNotice)).toBeInTheDocument()
    expect(await screen.findByText('버그 흐름')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: workflowSchemeLabels.mapping.addMappingButton }))
      .not.toBeInTheDocument()
  })

  it('★생성은 사본에 프로젝트를 싣는다', async () => {
    let body: unknown = null
    server.use(
      ...assignmentHandlers(),
      http.get('*/api/v1/projects/:projectKey/workflow-schemes', () => HttpResponse.json(managedBody())),
      http.post('*/api/v1/workflow-schemes', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(
          {
            data: {
              id: 9,
              key: 'new-scheme',
              name: '새 스킴',
              description: null,
              isStandard: false,
              projectId: PROJECT_ID,
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            },
          },
          { status: 201 },
        )
      }),
    )
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: workflowSchemeLabels.sidebar.addSchemeAriaLabel }))
    fireEvent.change(await screen.findByLabelText(labels.createKeyLabel), {
      target: { value: 'new-scheme' },
    })
    fireEvent.change(screen.getByLabelText(labels.createNameLabel), { target: { value: '새 스킴' } })
    fireEvent.click(screen.getByRole('button', { name: labels.createSubmit }))

    // ★안 실으면 전역 스킴이 만들어지고, 만든 사람조차 못 고친다(전역 편집은 SYSTEM_ADMIN).
    await waitFor(() => expect(body).not.toBeNull())
    expect(body).toMatchObject({ key: 'new-scheme', projectKey: 'ATLAS' })
  })

  it('403 이면 빈 사이드바가 아니라 권한 안내를 낸다', async () => {
    server.use(
      ...assignmentHandlers(),
      http.get('*/api/v1/projects/:projectKey/workflow-schemes', () =>
        HttpResponse.json({ error: { code: 'FORBIDDEN' } }, { status: 403 }),
      ),
    )
    renderPage()

    expect(await screen.findByText(workflowSchemeLabels.assignment.forbiddenMessage)).toBeInTheDocument()
  })
})
