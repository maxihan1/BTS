// 이슈 내보내기 다이얼로그 단위 테스트 — 형식/컬럼/성공/에러 경로 (FR-EX-01 Task-7 RED)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { ExportDialog } from './ExportDialog'

// triggerBlobDownload는 jsdom에 URL.createObjectURL이 없으므로 mock 처리
vi.mock('@/lib/download', () => ({
  triggerBlobDownload: vi.fn(),
}))

import { triggerBlobDownload } from '@/lib/download'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — 각 테스트에서 server.use()로 등록
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정상 응답 핸들러 — CSV blob + Content-Disposition 헤더 반환.
 * BOM(EF BB BF) 포함 최소 CSV 바이트.
 */
const exportSuccessHandler = http.post('/api/v1/search/export', () =>
  new HttpResponse(new TextEncoder().encode('﻿Key,Summary\r\nATLAS-1,Test issue\r\n'), {
    headers: {
      'Content-Type': 'text/csv; charset=UTF-8',
      'Content-Disposition': 'attachment; filename="ATLAS-issues-20260629T000000Z.csv"',
    },
  }),
)

/**
 * 상한초과 에러 핸들러 — 400 SEARCH_EXPORT_LIMIT_EXCEEDED + ProblemDetail.
 */
const exportLimitExceededHandler = http.post('/api/v1/search/export', () =>
  HttpResponse.json(
    {
      errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
      detail: '내보내기 한도(10,000건)를 초과했습니다. 쿼리를 좁혀 다시 시도하세요.',
      resultCount: 15000,
      limit: 10000,
      status: 400,
    },
    { status: 400 },
  ),
)

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient 래퍼 — 테스트 간 캐시 격리
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: qc }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 셋업 / 정리
// ─────────────────────────────────────────────────────────────────────────────

const USER_ID = '00000000-0000-4000-8000-000000000001'

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'test-token',
    user: {
      userId: USER_ID,
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    },
  })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderDialog(overrides: { open?: boolean; onOpenChange?: (v: boolean) => void } = {}) {
  const onOpenChange = overrides.onOpenChange ?? vi.fn()
  const user = userEvent.setup()
  const utils = render(
    <ExportDialog
      open={overrides.open ?? true}
      onOpenChange={onOpenChange}
      projectKey="ATLAS"
      query="status = open"
    />,
    { wrapper: createWrapper() },
  )
  return { user, onOpenChange, ...utils }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ExportDialog', () => {
  // (a) 다이얼로그 구조
  describe('구조', () => {
    it('다이얼로그 역할과 제목이 표시된다', () => {
      renderDialog()
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(screen.getByText('내보내기')).toBeInTheDocument()
    })

    it('CSV 라디오가 기본 선택된다', () => {
      renderDialog()
      expect(screen.getByRole('radio', { name: 'CSV' })).toBeChecked()
    })

    it('XLSX 라디오가 존재하며 기본 미선택 상태다', () => {
      renderDialog()
      expect(screen.getByRole('radio', { name: 'XLSX' })).not.toBeChecked()
    })

    it('9개 컬럼 체크박스가 모두 기본 선택된다', () => {
      renderDialog()
      const checkboxes = screen.getAllByRole('checkbox')
      expect(checkboxes).toHaveLength(9)
      checkboxes.forEach((cb) => {
        expect(cb).toBeChecked()
      })
    })

    it('내보내기/취소 버튼이 표시된다', () => {
      renderDialog()
      expect(screen.getByRole('button', { name: '내보내기' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument()
    })
  })

  // (b) 상호작용
  describe('상호작용', () => {
    it('XLSX 라디오를 선택하면 CSV 선택이 해제된다', async () => {
      const { user } = renderDialog()
      await user.click(screen.getByRole('radio', { name: 'XLSX' }))
      expect(screen.getByRole('radio', { name: 'XLSX' })).toBeChecked()
      expect(screen.getByRole('radio', { name: 'CSV' })).not.toBeChecked()
    })

    it('컬럼 체크박스를 클릭하면 해제된다', async () => {
      const { user } = renderDialog()
      const keyCheckbox = screen.getByRole('checkbox', { name: 'Key' })
      await user.click(keyCheckbox)
      expect(keyCheckbox).not.toBeChecked()
    })

    it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
      const { user, onOpenChange } = renderDialog()
      await user.click(screen.getByRole('button', { name: '취소' }))
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  // (c) 성공 경로
  describe('성공 경로', () => {
    it('내보내기 성공 시 triggerBlobDownload가 호출된다', async () => {
      server.use(exportSuccessHandler)
      const { user } = renderDialog()

      await user.click(screen.getByRole('button', { name: '내보내기' }))

      await waitFor(() => {
        expect(vi.mocked(triggerBlobDownload)).toHaveBeenCalledTimes(1)
      })
    })

    it('Content-Disposition 헤더에서 파싱한 파일명으로 다운로드한다', async () => {
      server.use(exportSuccessHandler)
      const { user } = renderDialog()

      await user.click(screen.getByRole('button', { name: '내보내기' }))

      await waitFor(() => {
        expect(vi.mocked(triggerBlobDownload)).toHaveBeenCalledWith(
          expect.any(Blob),
          'ATLAS-issues-20260629T000000Z.csv',
        )
      })
    })

    it('성공 후 onOpenChange(false)가 호출된다', async () => {
      server.use(exportSuccessHandler)
      const { user, onOpenChange } = renderDialog()

      await user.click(screen.getByRole('button', { name: '내보내기' }))

      await waitFor(() => {
        expect(onOpenChange).toHaveBeenCalledWith(false)
      })
    })
  })

  // (d) 에러 경로
  describe('에러 경로', () => {
    it('상한초과(SEARCH_EXPORT_LIMIT_EXCEEDED) 에러 시 detail 메시지가 표시된다', async () => {
      server.use(exportLimitExceededHandler)
      const { user } = renderDialog()

      await user.click(screen.getByRole('button', { name: '내보내기' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent(
          '내보내기 한도(10,000건)를 초과했습니다. 쿼리를 좁혀 다시 시도하세요.',
        )
      })
    })
  })
})
