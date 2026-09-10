// admin 워크플로우 스킴 상세 페이지 통합 테스트 — 3컬럼 렌더/매핑/표준보호/삭제모달
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { WorkflowSchemeDetailPage } from '@/routes/admin.workflow-schemes.$schemeKey'

// vi.hoisted로 mock 함수 선언
const { mockNavigate } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ schemeKey: 'software-default-scheme' }),
}))

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

function renderDetailPage(schemeKey = 'software-default-scheme') {
  server.use(...schemeHandlers, ...issueTypeHandlers, ...workflowHandlers)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <WorkflowSchemeDetailPage schemeKey={schemeKey} />
    </QueryClientProvider>,
  )
}

describe('WorkflowSchemeDetailPage', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
  })

  /**
   * S2-1. 페이지 마운트 시 로딩 상태가 표시된다.
   * 사이드바 로딩 + 상세 스켈레톤 두 개 이상의 status role이 존재할 수 있음.
   */
  it('S2-1: 마운트 시 로딩 스켈레톤이 표시된다', () => {
    renderDetailPage()
    // detail-skeleton data-testid로 정확히 확인
    const detailSkeleton = screen.queryByTestId('detail-skeleton')
    const anyStatus = screen.queryAllByRole('status')
    expect(detailSkeleton !== null || anyStatus.length > 0).toBe(true)
  })

  /**
   * S2-2. 데이터 로드 후 3컬럼 레이아웃이 렌더된다 (사이드바 + 매핑 테이블 + 메타 패널).
   */
  it('S2-2: 데이터 로드 후 MappingTable과 SchemeMetaPanel이 렌더된다', async () => {
    renderDetailPage()

    // 매핑 테이블 헤더 확인
    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())
    // 메타 패널 — 스킴 이름이 표시됨
    await waitFor(() =>
      expect(screen.getByDisplayValue('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
  })

  /**
   * S2-3. 매핑 목록이 렌더된다.
   */
  it('S2-3: 스킴 매핑 rows가 렌더된다', async () => {
    renderDetailPage()

    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())
    // software-default-scheme는 5 매핑 (bug, story, task, epic, default)
    await waitFor(() =>
      expect(screen.getByText('버그 추적 워크플로우')).toBeInTheDocument(),
    )
  })

  /**
   * S7-1. isStandard === true 시 name input이 disabled.
   */
  it('S7-1: 표준 스킴에서 name input이 disabled이다', async () => {
    renderDetailPage('software-default-scheme')

    await waitFor(() =>
      expect(screen.getByDisplayValue('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
    const nameInput = screen.getByDisplayValue('소프트웨어 개발 기본 스킴')
    expect(nameInput).toBeDisabled()
  })

  /**
   * S7-2. isStandard === true 시 MetaPanel 의 「삭제」 버튼이 disabled.
   * MappingTable에도 삭제 버튼이 있으므로 MetaPanel 영역(aside)에서 찾는다.
   */
  it('S7-2: 표준 스킴에서 삭제 버튼이 disabled이다', async () => {
    renderDetailPage('software-default-scheme')

    await waitFor(() =>
      expect(screen.getByDisplayValue('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
    // MetaPanel 내 삭제 버튼 — aside 안에 위치
    const aside = document.querySelector('aside')
    expect(aside).not.toBeNull()
    // aside 내 모든 버튼 중 '삭제'를 찾음
    const allBtns = aside?.querySelectorAll('button') ?? []
    const deleteBtnEl = Array.from(allBtns).find((b) => b.textContent?.includes('삭제'))
    expect(deleteBtnEl).toBeTruthy()
    expect(deleteBtnEl).toBeDisabled()
  })

  /**
   * S7-3. isStandard === true여도 「+ 매핑 추가」 row는 존재한다 (매핑 변경 가능).
   * 「추가」 버튼이 테이블 안에 존재함.
   */
  it('S7-3: 표준 스킴에서도 매핑 추가 행이 존재한다', async () => {
    renderDetailPage('software-default-scheme')

    await waitFor(() =>
      expect(screen.getByRole('table')).toBeInTheDocument(),
    )
    // 「추가」 버튼이 1개 이상 존재해야 함 (테이블 내 AddMappingRow의 버튼)
    await waitFor(() => {
      const addBtns = screen.getAllByRole('button', { name: /추가/ })
      expect(addBtns.length).toBeGreaterThan(0)
    })
  })

  /**
   * S8-1. 커스텀 스킴 + usedByProjectsCount > 0 → 삭제 시도 → SCHEME_IN_USE 모달.
   * MetaPanel의 aside 내 「삭제」 버튼을 찾아 클릭.
   */
  it('S8-1: SCHEME_IN_USE 삭제 시도 시 SchemeInUseModal이 열린다', async () => {
    // custom-scheme-alpha: isStandard=false, usedByProjectsCount=2
    renderDetailPage('custom-scheme-alpha')

    await waitFor(() =>
      expect(screen.getByDisplayValue('사내 개발팀 커스텀 스킴')).toBeInTheDocument(),
    )

    // aside 내 「삭제」 버튼 찾기
    const aside = document.querySelector('aside')
    expect(aside).not.toBeNull()
    const allBtns = aside?.querySelectorAll('button') ?? []
    const metaDeleteBtn = Array.from(allBtns).find((b) => b.textContent?.includes('삭제'))
    expect(metaDeleteBtn).toBeTruthy()
    expect(metaDeleteBtn).not.toBeDisabled()
    fireEvent.click(metaDeleteBtn!)

    await waitFor(() =>
      expect(screen.getByRole('alertdialog')).toBeInTheDocument(),
    )
    expect(screen.getAllByText(/사용 중인 프로젝트/).length).toBeGreaterThan(0)
  })

  /**
   * S8-2. SchemeInUseModal 「확인」 버튼 클릭 시 모달이 닫힌다.
   */
  it('S8-2: SchemeInUseModal 확인 버튼 클릭 시 모달이 닫힌다', async () => {
    renderDetailPage('custom-scheme-alpha')

    await waitFor(() =>
      expect(screen.getByDisplayValue('사내 개발팀 커스텀 스킴')).toBeInTheDocument(),
    )

    // aside 내 「삭제」 버튼 클릭
    const aside = document.querySelector('aside')
    const allBtns = aside?.querySelectorAll('button') ?? []
    const metaDeleteBtn2 = Array.from(allBtns).find((b) => b.textContent?.includes('삭제'))
    fireEvent.click(metaDeleteBtn2!)

    await waitFor(() => expect(screen.getByRole('alertdialog')).toBeInTheDocument())

    // 모달 내 「확인」 버튼 클릭
    const dialog = screen.getByRole('alertdialog')
    const confirmBtn = dialog.querySelector('button')
    expect(confirmBtn).toBeTruthy()
    fireEvent.click(confirmBtn!)

    await waitFor(() =>
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument(),
    )
  })

  /**
   * PL7-2 (FR-UX-06 PR13 Task 7). 컴포넌트는 자체 <main>을 렌더하지 않는다.
   * 문서 <main>은 ShellLayout이 단독 소유(WCAG 1.3.1 — 문서당 main 1개).
   */
  it('PL7-2: 컴포넌트는 자체 <main>을 렌더하지 않는다', () => {
    const { container } = renderDetailPage()
    expect(container.querySelector('main')).toBeNull()
  })

  /**
   * description 의 null↔'' 왕복 회귀 방지 (TODOS §description 의 null↔'' 왕복).
   *
   * DB 컬럼은 `description TEXT` (V201, NOT NULL 없음)라 **NULL 과 '' 는 서로 다른 값**이고,
   * 백엔드 DTO 도 `String?` 이라 받은 값을 그대로 저장한다.
   *
   * 폼은 textarea 가 null 을 못 받아 `scheme.description ?? ''` 로 정규화해 들고 있는데,
   * 저장할 때 **되돌리지 않고 그대로** 보냈다. ⇒ 이름만 고쳐도 DB 의 NULL 이 '' 로 바뀐다
   * (조용한 데이터 변질 — 어떤 화면도 이 차이를 보여주지 않아 눈치채기 어렵다).
   */
  it('S9-1: 설명이 비어 있으면 null 로 보낸다 (빈 문자열이 아니라)', async () => {
    let capturedBody: unknown = null

    // ★ 순서가 중요하다. MSW 는 먼저 등록된 핸들러가 이긴다 —
    //   ...schemeHandlers 를 앞에 두면 그 catch-all 이 아래 전용 핸들러를 가린다
    //   (메모리 msw-dual-handler-e2e-shadow).
    server.use(
      // description 이 null 인 스킴을 상세로 돌려준다.
      http.get('/api/v1/workflow-schemes/nullable-desc-scheme', () =>
        HttpResponse.json({
          data: {
            id: 42,
            key: 'nullable-desc-scheme',
            name: '설명 없는 스킴',
            description: null,
            isStandard: false,
            projectId: null,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
            usedByProjectsCount: 0,
            mappingsCount: 0,
            mappings: [],
          },
        }),
      ),
      http.put('/api/v1/workflow-schemes/nullable-desc-scheme', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({
          data: {
            id: 42,
            key: 'nullable-desc-scheme',
            name: '이름만 바꿈',
            description: null,
            isStandard: false,
            projectId: null,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-02T00:00:00Z',
          },
        })
      }),
      ...schemeHandlers,
      ...issueTypeHandlers,
      ...workflowHandlers,
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <WorkflowSchemeDetailPage schemeKey="nullable-desc-scheme" />
      </QueryClientProvider>,
    )

    const nameInput = await screen.findByLabelText('이름')
    fireEvent.change(nameInput, { target: { value: '이름만 바꿈' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(capturedBody).not.toBeNull())
    expect(capturedBody).toEqual({ name: '이름만 바꿈', description: null })
  })
})
