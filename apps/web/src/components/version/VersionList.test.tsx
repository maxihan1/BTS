// VersionList 단위 테스트 — 4분기(로딩/에러/빈/목록) + 생성/수정 Dialog 연동 (FR-VR-01)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { versionHandlers, resetVersionStore } from '@/mocks/version-handlers'
import { versionLabels } from '@/i18n/version-labels'
import { VersionList } from './VersionList'

// ─────────────────────────────────────────────────────────────────────────────
// sonner mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const PROJECT_UUID = '00000000-0000-4000-8000-000000000010'

const VERSION_A = {
  id: '00000000-0000-4000-8000-000000000021',
  projectId: PROJECT_UUID,
  name: 'v1.0',
  description: '첫 번째 릴리즈',
  startDate: null,
  releaseDate: null,
}

const VERSION_B = {
  id: '00000000-0000-4000-8000-000000000022',
  projectId: PROJECT_UUID,
  name: 'v2.0',
  description: '두 번째 릴리즈',
  startDate: null,
  releaseDate: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

beforeEach(() => {
  resetVersionStore()
  vi.clearAllMocks()
  server.use(...versionHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionList — 4분기 렌더', () => {
  it('로딩 중에는 스켈레톤(role=status)을 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/versions', async () => {
        await new Promise(() => { /* pending forever */ })
        return HttpResponse.json({ data: [] })
      }),
    )

    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    expect(
      screen.getByRole('status', { name: versionLabels.page.loadingStatus }),
    ).toBeInTheDocument()
  })

  it('에러 발생 시 에러 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/versions', () =>
        HttpResponse.json({ error: 'internal' }, { status: 500 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(/요청을 처리하지 못했습니다/)).toBeInTheDocument()
    })
  })

  it('버전이 없으면 빈 상태 안내와 추가 버튼을 표시한다', async () => {
    // versionHandlers의 GET 핸들러 — 빈 목록 반환 (resetVersionStore 후 초기 상태)
    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(versionLabels.page.emptyMessage)).toBeInTheDocument()
    })

    expect(
      screen.getByRole('button', { name: versionLabels.actions.addButton }),
    ).toBeInTheDocument()
  })

  it('서버가 name 오름차순으로 내려준 목록을 그대로 렌더한다', async () => {
    // 서버(versionHandlers)가 정렬된 순서로 응답 — [v1.0, v2.0] 순
    server.use(
      http.get('/api/v1/projects/:projectKey/versions', () =>
        HttpResponse.json({
          data: [VERSION_A, VERSION_B], // v1.0, v2.0 (오름차순)
        }),
      ),
    )

    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('v1.0')).toBeInTheDocument()
    })
    expect(screen.getByText('v2.0')).toBeInTheDocument()

    // v1.0이 v2.0보다 DOM 앞에 위치하는지 확인
    const v1El = screen.getByText('v1.0')
    const v2El = screen.getByText('v2.0')
    const position = v1El.compareDocumentPosition(v2El)
    // DOCUMENT_POSITION_FOLLOWING(4): v2El이 v1El 뒤에 있음
    expect(position & Node.DOCUMENT_POSITION_FOLLOWING).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })
})

describe('VersionList — "버전 추가" 버튼', () => {
  it('목록 있을 때도 "버전 추가" 버튼을 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/versions', () =>
        HttpResponse.json({ data: [VERSION_A] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('v1.0')).toBeInTheDocument()
    })

    expect(
      screen.getByRole('button', { name: versionLabels.actions.addButton }),
    ).toBeInTheDocument()
  })

  it('"버전 추가" 클릭 시 create 모드 Dialog가 열린다', async () => {
    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(versionLabels.page.emptyMessage)).toBeInTheDocument()
    })

    const user = userEvent.setup()
    const addButton = screen.getByRole('button', { name: versionLabels.actions.addButton })
    await user.click(addButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
    // 생성 모드 — 이름 필드가 빈 상태
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    expect(nameInput).toHaveValue('')
  })

  it('생성 Dialog에서 저장 후 목록에 새 버전이 반영된다', async () => {
    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(versionLabels.page.emptyMessage)).toBeInTheDocument()
    })

    const user = userEvent.setup()
    // Dialog 열기
    const addButton = screen.getByRole('button', { name: versionLabels.actions.addButton })
    await user.click(addButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // 이름 입력
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.type(nameInput, '새 버전', { delay: null })

    // 저장
    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    // 목록 반영 확인
    await waitFor(() => {
      expect(screen.getByText('새 버전')).toBeInTheDocument()
    })
  })
})

describe('VersionList — 행 수정 버튼', () => {
  it('수정 버튼 클릭 시 edit 모드 Dialog가 열리고 initial 값이 prefill된다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/versions', () =>
        HttpResponse.json({ data: [VERSION_A] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('v1.0')).toBeInTheDocument()
    })

    const user = userEvent.setup()

    // 행 컨테이너에서 수정 버튼 탐색 — strict-mode 회피 (행마다 수정/삭제 버튼 존재)
    const v1Row = screen.getByText('v1.0').closest('li')
    if (v1Row === null) throw new Error('v1.0 행을 찾을 수 없습니다')

    const editButton = within(v1Row).getByRole('button', {
      name: `v1.0 ${versionLabels.actions.editButton}`,
    })
    await user.click(editButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // edit 모드 — 이름 prefill 확인
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    expect(nameInput).toHaveValue('v1.0')
  })

  it('수정 Dialog에서 저장 시 버전 이름이 갱신된다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/versions', () =>
        HttpResponse.json({ data: [VERSION_A] }),
      ),
    )
    // PATCH 핸들러 — 수정 적용 + GET 재응답에 반영
    server.use(
      http.patch('/api/v1/projects/:projectKey/versions/:id', async ({ request }) => {
        const body = (await request.json()) as { name?: string; description?: string }
        const updated = {
          ...VERSION_A,
          name: body.name ?? VERSION_A.name,
          description: body.description ?? VERSION_A.description,
        }
        // GET도 업데이트된 값 반환하도록 재등록
        server.use(
          http.get('/api/v1/projects/:projectKey/versions', () =>
            HttpResponse.json({ data: [updated] }),
          ),
        )
        return HttpResponse.json({ data: updated })
      }),
    )

    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('v1.0')).toBeInTheDocument()
    })

    const user = userEvent.setup()
    const v1Row = screen.getByText('v1.0').closest('li')
    if (v1Row === null) throw new Error('v1.0 행을 찾을 수 없습니다')

    const editButton = within(v1Row).getByRole('button', {
      name: `v1.0 ${versionLabels.actions.editButton}`,
    })
    await user.click(editButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, 'v1.1', { delay: null })

    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    await waitFor(() => {
      expect(screen.getByText('v1.1')).toBeInTheDocument()
    })
  })
})

describe('VersionList — 행 삭제 버튼', () => {
  it('삭제 버튼 클릭 후 확인 시 목록에서 제거된다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/versions', () =>
        HttpResponse.json({ data: [VERSION_A] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<VersionList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('v1.0')).toBeInTheDocument()
    })

    const user = userEvent.setup()
    const v1Row = screen.getByText('v1.0').closest('li')
    if (v1Row === null) throw new Error('v1.0 행을 찾을 수 없습니다')

    // 삭제 버튼 클릭 — 인라인 확인 UI 표시
    const deleteButton = within(v1Row).getByRole('button', {
      name: `v1.0 ${versionLabels.actions.deleteButton}`,
    })
    await user.click(deleteButton)

    // 인라인 확인 — "정말 삭제하시겠습니까?" 텍스트 표시
    await waitFor(() => {
      expect(screen.getByText(versionLabels.actions.deleteConfirm)).toBeInTheDocument()
    })

    // 확인 버튼(삭제) 클릭
    // 인라인 확인 영역에서 "삭제" 버튼 클릭
    const confirmRow = screen.getByText(versionLabels.actions.deleteConfirm).closest('div')
    if (confirmRow === null) throw new Error('확인 영역을 찾을 수 없습니다')
    const confirmDeleteButton = within(confirmRow).getByRole('button', {
      name: versionLabels.actions.deleteButton,
    })
    await user.click(confirmDeleteButton)

    // GET 재조회 후 목록에서 제거 확인
    await waitFor(() => {
      expect(screen.queryByText('v1.0')).not.toBeInTheDocument()
    })
  })
})
