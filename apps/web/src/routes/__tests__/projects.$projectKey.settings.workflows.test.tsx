// 프로젝트 워크플로우 목록 판정 — 소유 갈림 · 복제가 소유를 싣는가 · 403 안내 (FR-WF-08 Task 4-1)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { workflowEditorLabels } from '@/i18n/workflow-editor-labels'
import { ProjectWorkflowsSettingsPage } from '../projects.$projectKey.settings.workflows'

const labels = workflowEditorLabels.projectSettings

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
  useParams: () => ({ projectKey: 'ATLAS' }),
}))
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

const PROJECT_ID = '11111111-1111-4111-8111-111111111111'

/** 목록 응답 한 벌 — 전역 템플릿 1 + 이 프로젝트 전용 1. */
function listBody() {
  return {
    data: [
      {
        key: 'software-default',
        name: '기본 소프트웨어',
        description: '',
        states: [{ key: 'todo', name: '할 일', category: 'TODO', displayOrder: 0 }],
        transitions: [],
        projectId: null,
      },
      {
        key: 'atlas-flow',
        name: '아틀라스 흐름',
        description: '',
        states: [{ key: 'todo', name: '할 일', category: 'TODO', displayOrder: 0 }],
        transitions: [],
        projectId: PROJECT_ID,
      },
    ],
  }
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
      <ProjectWorkflowsSettingsPage projectKey="ATLAS" />
    </QueryClientProvider>,
  )
}

describe('프로젝트 워크플로우 목록', () => {
  it('프로젝트 스코프 창구를 탄다 — 전량 목록이 아니다', async () => {
    let hitPath = ''
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', ({ request }) => {
        hitPath = new URL(request.url).pathname
        return HttpResponse.json(listBody())
      }),
    )
    renderPage()

    await screen.findByText('아틀라스 흐름')
    // ★`/api/v1/workflows` 는 권한 게이트가 없는 전량 목록이다. 그쪽을 타면 남의 프로젝트
    //  워크플로우가 이름째 온다 — 어느 경로를 탔는지가 판정 대상이다.
    expect(hitPath).toBe('/api/v1/projects/ATLAS/workflows')
  })

  it('전역과 이 프로젝트 것을 배지로 가른다', async () => {
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () => HttpResponse.json(listBody())),
    )
    renderPage()

    await screen.findByText('아틀라스 흐름')
    expect(screen.getByText(labels.ownerGlobal)).toBeInTheDocument()
    expect(screen.getByText(labels.ownerProject)).toBeInTheDocument()
  })

  it('전역 행은 편집이 아니라 복제를 준다', async () => {
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () => HttpResponse.json(listBody())),
    )
    renderPage()

    // 전역은 시스템 관리자만 고친다 — 편집을 주면 눌러서 403 을 받고서야 알게 된다.
    expect(await screen.findByRole('button', { name: `${labels.copyToProject} 기본 소프트웨어` }))
      .toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: `${workflowEditorLabels.list.edit} 기본 소프트웨어` }),
    ).not.toBeInTheDocument()
  })

  it('이 프로젝트 것은 편집으로 보낸다', async () => {
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () => HttpResponse.json(listBody())),
    )
    renderPage()

    fireEvent.click(
      await screen.findByRole('button', { name: `${workflowEditorLabels.list.edit} 아틀라스 흐름` }),
    )

    expect(navigate).toHaveBeenCalledWith({ to: '/projects/ATLAS/settings/workflows/atlas-flow' })
  })

  it('★복제는 사본에 프로젝트를 싣는다', async () => {
    let body: unknown = null
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () => HttpResponse.json(listBody())),
      http.post('*/api/v1/workflows/:key/duplicate', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ data: { key: 'software-default' } }, { status: 201 })
      }),
    )
    renderPage()

    fireEvent.click(
      await screen.findByRole('button', { name: `${labels.copyToProject} 기본 소프트웨어` }),
    )

    // ★안 실으면 사본도 전역이 되어 여전히 못 고친다 — 이 화면이 존재하는 이유가 사라진다.
    await waitFor(() => expect(body).not.toBeNull())
    expect(body).toMatchObject({ projectKey: 'ATLAS' })
  })

  it('★★사본 key 는 원본과 다르다 — 같으면 그 워크플로우가 통째로 죽는다', async () => {
    /*
     * ★★2026-09-14 운영 실측. 이 버튼이 사본 key 를 **원본과 똑같이** 보냈다.
     *
     *   V209 가 key 유일성을 소유별로 갈라 두어 생성은 성공한다(전역 1 + 프로젝트 1).
     *   그런데 조회는 아직 key 만 본다 — `findLiveIdByKey` 가 `fetchOne` 이라
     *   2행에서 `TooManyRowsException` 으로 죽는다. 그 순간부터 그 key 의
     *   **수정·삭제·복제·전환 CRUD 가 전부 500** 이 됐다.
     *
     *   ★위 테스트가 왜 못 잡았나. `projectKey` 만 검증하고 `key` 는 안 봤다.
     *     사본이 소유를 싣는지는 확인하면서, 사본이 **식별될 수 있는지**는 묻지 않았다.
     *
     *   ★서버가 아니라 여기서 막는 이유. 백엔드는 소유별 중복을 **정당하게 허용**한다
     *     (「전역 템플릿을 같은 이름으로 내 프로젝트에」가 Jira 권장 우회로다).
     *     생성이 잘못된 것이 아니라, 이 화면이 사용자 의도 없이 그 상태를 만든 것이다.
     */
    // 원본 key 는 경로로(`/workflows/{sourceKey}/duplicate`), 사본 key 는 바디로 간다.
    let sourceFromPath: string | undefined
    let body: { key?: string } | null = null
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () => HttpResponse.json(listBody())),
      http.post('*/api/v1/workflows/:key/duplicate', async ({ request, params }) => {
        sourceFromPath = params.key as string
        body = (await request.json()) as { key?: string }
        return HttpResponse.json({ data: { key: body.key } }, { status: 201 })
      }),
    )
    renderPage()

    fireEvent.click(
      await screen.findByRole('button', { name: `${labels.copyToProject} 기본 소프트웨어` }),
    )

    await waitFor(() => expect(body).not.toBeNull())
    // 원본은 그대로 지목한다 — 무엇을 베끼는지가 바뀌면 안 된다.
    expect(sourceFromPath).toBe('software-default')
    // ★비-공허 짝. 사본 key 가 비어 있어도 「원본과 다르다」는 참이 되어 검사가 공허해진다.
    expect(body!.key).toBeTruthy()
    expect(body!.key).not.toBe(sourceFromPath)
  })

  it('403 이면 빈 표가 아니라 권한 안내를 낸다', async () => {
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () =>
        HttpResponse.json({ error: { code: 'FORBIDDEN' } }, { status: 403 }),
      ),
    )
    renderPage()

    expect(await screen.findByText(labels.forbiddenTitle)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('404 는 프로젝트 없음으로 안내한다', async () => {
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () =>
        HttpResponse.json({ error: { code: 'NOT_FOUND' } }, { status: 404 }),
      ),
    )
    renderPage()

    expect(await screen.findByText(labels.notFoundTitle)).toBeInTheDocument()
  })

  it('그 밖의 실패는 조회 실패로 안내한다', async () => {
    server.use(
      http.get('*/api/v1/projects/:projectKey/workflows', () =>
        HttpResponse.json({ error: { code: 'BOOM' } }, { status: 500 }),
      ),
    )
    renderPage()

    expect(await screen.findByText(labels.loadErrorTitle)).toBeInTheDocument()
  })
})
