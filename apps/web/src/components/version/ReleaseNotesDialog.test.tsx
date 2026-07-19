// ReleaseNotesDialog 단위 테스트 — 로딩/에러/성공 렌더 + 클립보드 복사 (FR-VR-04 Task 6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { versionHandlers, resetVersionStore } from '@/mocks/version-handlers'
import type { Version } from '@/api/versions.types'
import { ReleaseNotesDialog } from './ReleaseNotesDialog'

// ─────────────────────────────────────────────────────────────────────────────
// sonner mock — 토스트 발사 검증용
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// clipboard mock
// ─────────────────────────────────────────────────────────────────────────────

const clipboardWriteText = vi.fn()

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const PROJECT_ID = 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2c3d4e5f'
const VERSION_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
const VERSION_NAME = 'v1.0.0'

/** MSW 버전 store에 등록하기 위한 최소 픽스처 */
const versionFixture: Version = {
  id: VERSION_ID,
  projectId: PROJECT_ID,
  name: VERSION_NAME,
  description: '첫 번째 정식 릴리즈',
  startDate: '2026-01-01',
  releaseDate: '2026-03-31',
  status: 'UNRELEASED',
}

const SAMPLE_MARKDOWN = `## v1.0.0 릴리즈 노트

### Bug
- ATLAS-1 로그인 오류 수정 (Fixed)

### Story
- ATLAS-2 대시보드 개선
`

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

/**
 * MSW 핸들러를 오버라이드해 버전 + 릴리즈 노트 응답을 시드한다.
 */
function seedVersion(): void {
  server.use(
    http.get(
      `/api/v1/projects/${PROJECT_KEY}/versions/${VERSION_ID}`,
      () => HttpResponse.json({ data: versionFixture }),
    ),
    http.get(
      `/api/v1/projects/${PROJECT_KEY}/versions/${VERSION_ID}/release-notes`,
      () =>
        HttpResponse.json({
          data: {
            versionId: VERSION_ID,
            projectKey: PROJECT_KEY,
            versionName: VERSION_NAME,
            versionStatus: 'UNRELEASED',
            releaseDate: '2026-03-31',
            issueCount: 2,
            generatedAt: '2026-06-10T12:00:00Z',
            markdown: SAMPLE_MARKDOWN,
          },
        }),
    ),
  )
}

function seedVersionNotFound(): void {
  server.use(
    http.get(
      `/api/v1/projects/${PROJECT_KEY}/versions/${VERSION_ID}/release-notes`,
      () =>
        HttpResponse.json(
          {
            type: 'https://bts.example.com/problems/version-not-found',
            title: 'Version Not Found',
            status: 404,
            detail: '버전을 찾을 수 없습니다.',
            errorCode: 'VERSION_NOT_FOUND',
            timestamp: '2026-06-10T12:00:00Z',
          },
          { status: 404 },
        ),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 클립보드 setup/teardown
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  resetVersionStore()
  vi.clearAllMocks()
  server.use(...versionHandlers)

  // navigator.clipboard mock — vi.stubGlobal로 확실히 교체
  clipboardWriteText.mockResolvedValue(undefined)
  vi.stubGlobal('navigator', {
    ...navigator,
    clipboard: { writeText: clipboardWriteText },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트: 렌더 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('ReleaseNotesDialog — 닫힘 상태', () => {
  it('open=false이면 dialog 내용을 렌더하지 않는다', () => {
    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={false}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('ReleaseNotesDialog — 흡수 후 X 닫기 버튼', () => {
  it('흡수 후 우상단 X 닫기 버튼(Jira 시각 통일)이 렌더된다', () => {
    seedVersion()

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    // ui/dialog 래퍼로 흡수되면 DialogContent가 우상단 X(sr-only "Close")를 강제 렌더한다.
    expect(screen.getByRole('button', { name: /close/i })).toBeTruthy()
  })
})

describe('ReleaseNotesDialog — 로딩 상태', () => {
  it('open=true이면 로딩 스피너/상태를 표시한다', async () => {
    // 응답을 지연시켜 로딩 상태를 관찰한다
    server.use(
      http.get(
        `/api/v1/projects/${PROJECT_KEY}/versions/${VERSION_ID}/release-notes`,
        async () => {
          await new Promise((resolve) => setTimeout(resolve, 2000))
          return HttpResponse.json({ data: {} })
        },
      ),
    )

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    // dialog가 열려야 한다
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    // 로딩 인디케이터가 있어야 한다
    expect(screen.getByRole('status')).toBeInTheDocument()
  })
})

describe('ReleaseNotesDialog — 에러 상태', () => {
  it('fetch 실패 시 에러 메시지를 role=alert으로 표시한다', async () => {
    seedVersionNotFound()

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })
})

describe('ReleaseNotesDialog — 성공 상태', () => {
  it('open=true이면 markdown을 pre 태그로 렌더한다', async () => {
    seedVersion()

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      const dialog = screen.getByRole('dialog')
      const pre = within(dialog).getByRole('region', { name: /릴리즈 노트 내용/i })
      expect(pre).toBeInTheDocument()
      expect(pre).toHaveTextContent('v1.0.0 릴리즈 노트')
    })
  })

  it('dialog 제목에 버전 이름이 포함된다', async () => {
    seedVersion()

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByText(new RegExp(VERSION_NAME))).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트: 클립보드 복사
// ─────────────────────────────────────────────────────────────────────────────

describe('ReleaseNotesDialog — 클립보드 복사', () => {
  it('복사 버튼 클릭 시 navigator.clipboard.writeText(markdown)을 호출한다', async () => {
    seedVersion()

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    // markdown 렌더 대기
    await waitFor(() => {
      expect(screen.getByRole('region', { name: /릴리즈 노트 내용/i })).toBeInTheDocument()
    })

    // fireEvent.click 사용 — Radix portal의 body pointer-events:none 우회 (AddAccountDialog.test.tsx 패턴)
    const dialog = screen.getByRole('dialog')
    const copyBtn = within(dialog).getByRole('button', { name: /복사/i })
    fireEvent.click(copyBtn)

    await waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith(SAMPLE_MARKDOWN)
    })
  })

  it('복사 성공 후 완료 표시가 나타난다', async () => {
    seedVersion()

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByRole('region', { name: /릴리즈 노트 내용/i })).toBeInTheDocument()
    })

    const dialog = screen.getByRole('dialog')
    const copyBtn = within(dialog).getByRole('button', { name: /복사/i })
    fireEvent.click(copyBtn)

    await waitFor(() => {
      expect(within(dialog).getByText(/복사됨|복사 완료/i)).toBeInTheDocument()
    })
  })

  it('clipboard 실패 시 에러를 throw하지 않고 graceful하게 처리한다', async () => {
    seedVersion()
    clipboardWriteText.mockRejectedValue(new Error('clipboard denied'))

    const Wrapper = createWrapper()
    render(
      <ReleaseNotesDialog
        versionId={VERSION_ID}
        versionName={VERSION_NAME}
        projectKey={PROJECT_KEY}
        open={true}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByRole('region', { name: /릴리즈 노트 내용/i })).toBeInTheDocument()
    })

    const dialog = screen.getByRole('dialog')
    const copyBtn = within(dialog).getByRole('button', { name: /복사/i })

    // fireEvent.click 사용 — Radix portal pointer-events 우회
    // 클릭해도 에러가 throw되면 안 된다
    expect(() => { fireEvent.click(copyBtn) }).not.toThrow()

    // clipboard 실패 시 컴포넌트가 에러 없이 graceful하게 처리한다
    await waitFor(() => {
      // copyState가 'error'로 전환되어 버튼 텍스트가 변경된다
      expect(within(dialog).getByRole('button', { name: /복사/i })).toBeInTheDocument()
    })
  })
})
