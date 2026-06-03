// VersionRow 단위 테스트 — 렌더/날짜표시/수정콜백/삭제확인/aria-label/날짜 null 분기
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { versionHandlers, resetVersionStore } from '@/mocks/version-handlers'
import type { Version } from '@/api/versions.types'
import { VersionRow } from './VersionRow'

// ─────────────────────────────────────────────────────────────────────────────
// sonner mock — 토스트 중복 발사 검증용 (행에서 쏘면 FAIL)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** 날짜가 모두 지정된 버전 픽스처 */
const versionWithDates: Version = {
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  projectId: 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2c3d4e5f',
  name: 'v1.0.0',
  description: '첫 번째 정식 릴리즈',
  startDate: '2026-01-01',
  releaseDate: '2026-03-31',
}

/** 날짜가 모두 null인 버전 픽스처 */
const versionNoDates: Version = {
  id: 'b2c3d4e5-f6a7-4b8c-9d0e-f1a2b3c4d5e6',
  projectId: 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2c3d4e5f',
  name: 'v2.0.0-beta',
  description: null,
  startDate: null,
  releaseDate: null,
}

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

beforeEach(() => {
  resetVersionStore()
  vi.clearAllMocks()
  server.use(...versionHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionRow — 렌더', () => {
  it('버전 이름과 설명을 표시한다', () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByText('v1.0.0')).toBeInTheDocument()
    expect(screen.getByText('첫 번째 정식 릴리즈')).toBeInTheDocument()
  })

  it('설명이 null이면 설명 영역을 표시하지 않는다', () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionNoDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.queryByText('첫 번째 정식 릴리즈')).not.toBeInTheDocument()
  })

  it('날짜가 지정되면 시작일과 릴리즈 예정일을 표시한다', () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByText('2026-01-01')).toBeInTheDocument()
    expect(screen.getByText('2026-03-31')).toBeInTheDocument()
  })

  it('startDate가 null이면 "—"를 표시한다', () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionNoDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    // 시작일, 릴리즈 예정일 모두 null → "—" 2개
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(2)
  })
})

describe('VersionRow — 수정 버튼', () => {
  it('수정 버튼 클릭 시 onEdit(version)을 호출한다', async () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const row = screen.getByRole('listitem')
    const editBtn = within(row).getByRole('button', {
      name: `${versionWithDates.name} 수정`,
    })
    await user.click(editBtn)

    expect(onEdit).toHaveBeenCalledWith(versionWithDates)
    expect(onEdit).toHaveBeenCalledTimes(1)
  })
})

describe('VersionRow — 삭제 액션', () => {
  it('삭제 버튼 클릭 후 확인 시 onDelete를 호출한다', async () => {
    server.use(
      http.delete(
        `/api/v1/projects/${PROJECT_KEY}/versions/${versionWithDates.id}`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    )

    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const row = screen.getByRole('listitem')
    const deleteBtn = within(row).getByRole('button', {
      name: `${versionWithDates.name} 삭제`,
    })
    await user.click(deleteBtn)

    // 삭제 확인 UI가 나타나야 한다
    await waitFor(() => {
      expect(screen.getByText('정말 삭제하시겠습니까?')).toBeInTheDocument()
    })

    const confirmBtn = screen.getByRole('button', { name: '삭제' })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(onDelete).toHaveBeenCalledWith(versionWithDates.id)
    })
  })

  it('삭제 취소 시 onDelete를 호출하지 않는다', async () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const row = screen.getByRole('listitem')
    const deleteBtn = within(row).getByRole('button', {
      name: `${versionWithDates.name} 삭제`,
    })
    await user.click(deleteBtn)

    await waitFor(() => {
      expect(screen.getByText('정말 삭제하시겠습니까?')).toBeInTheDocument()
    })

    const cancelBtn = screen.getByRole('button', { name: '취소' })
    await user.click(cancelBtn)

    expect(onDelete).not.toHaveBeenCalled()
    expect(screen.queryByText('정말 삭제하시겠습니까?')).not.toBeInTheDocument()
  })

  it('삭제 확인 UI가 나타나면 수정/삭제 버튼이 숨겨진다', async () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const row = screen.getByRole('listitem')
    const deleteBtn = within(row).getByRole('button', {
      name: `${versionWithDates.name} 삭제`,
    })
    await user.click(deleteBtn)

    await waitFor(() => {
      expect(screen.getByText('정말 삭제하시겠습니까?')).toBeInTheDocument()
    })

    // 수정/삭제 aria-label 버튼이 사라져야 한다
    expect(
      screen.queryByRole('button', { name: `${versionWithDates.name} 수정` }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: `${versionWithDates.name} 삭제` }),
    ).not.toBeInTheDocument()
  })
})

describe('VersionRow — aria-label', () => {
  it('수정 버튼의 aria-label에 버전 이름이 포함된다', () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    expect(
      screen.getByRole('button', { name: `${versionWithDates.name} 수정` }),
    ).toBeInTheDocument()
  })

  it('삭제 버튼의 aria-label에 버전 이름이 포함된다', () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    expect(
      screen.getByRole('button', { name: `${versionWithDates.name} 삭제` }),
    ).toBeInTheDocument()
  })
})

describe('VersionRow — http.delete 통합', () => {
  it('삭제 확인 후 DELETE API가 호출된다', async () => {
    const deleteSpy = vi.fn()
    server.use(
      http.delete(
        `/api/v1/projects/${PROJECT_KEY}/versions/${versionWithDates.id}`,
        () => {
          deleteSpy()
          return new HttpResponse(null, { status: 204 })
        },
      ),
    )

    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionRow
        version={versionWithDates}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const row = screen.getByRole('listitem')
    const deleteBtn = within(row).getByRole('button', {
      name: `${versionWithDates.name} 삭제`,
    })
    await user.click(deleteBtn)

    await waitFor(() => {
      expect(screen.getByText('정말 삭제하시겠습니까?')).toBeInTheDocument()
    })

    const confirmBtn = screen.getByRole('button', { name: '삭제' })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalled()
    })
  })
})
