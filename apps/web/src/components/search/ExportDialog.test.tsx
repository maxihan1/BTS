// 이슈 내보내기 다이얼로그 단위 테스트 — 형식/컬럼/성공/에러 경로 (FR-EX-01 Task-7)
//
// 설계 결정: exportIssues 모듈을 직접 mock.
// 컴포넌트 단위 테스트는 "API 응답 처리" 행동을 검증하는 게 목적이다.
// content-disposition 헤더 파싱(HTTP 레이어)은 jsdom 환경에서 헤더 접근 제한이 있어
// 컴포넌트 레벨이 아닌 exportIssues 함수 레벨(E2E/통합)에서 검증한다.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { useAuthStore } from '@/auth/authStore'
import { ApiError } from '@/api/client'
import { ExportDialog } from './ExportDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 mock — triggerBlobDownload + exportIssues
// ─────────────────────────────────────────────────────────────────────────────

// triggerBlobDownload는 jsdom에 URL.createObjectURL이 없으므로 mock 처리
vi.mock('@/lib/download', () => ({
  triggerBlobDownload: vi.fn(),
}))

// exportIssues를 mock해서 컴포넌트 행동(응답 처리)만 단위 테스트
vi.mock('@/api/search', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/search')>()
  return {
    ...actual,
    exportIssues: vi.fn(),
  }
})

import { triggerBlobDownload } from '@/lib/download'
import { exportIssues } from '@/api/search'
import type { ExportIssuesResult } from '@/api/search'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const USER_ID = '00000000-0000-4000-8000-000000000001'

/** 성공 응답 픽스처 — blob + 서버 생성 파일명 */
const EXPORT_RESULT: ExportIssuesResult = {
  blob: new Blob(['﻿Key,Summary\r\nATLAS-1,Test issue\r\n'], { type: 'text/csv' }),
  filename: 'ATLAS-issues-20260629T000000Z.csv',
}

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
      expect(screen.getByRole('heading', { name: '내보내기' })).toBeInTheDocument()
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
    beforeEach(() => {
      vi.mocked(exportIssues).mockResolvedValue(EXPORT_RESULT)
    })

    it('내보내기 성공 시 triggerBlobDownload가 호출된다', async () => {
      const { user } = renderDialog()
      await user.click(screen.getByRole('button', { name: '내보내기' }))
      await waitFor(() => {
        expect(vi.mocked(triggerBlobDownload)).toHaveBeenCalledTimes(1)
      })
    })

    it('서버 제공 파일명(Content-Disposition 파싱값)으로 다운로드한다', async () => {
      const { user } = renderDialog()
      await user.click(screen.getByRole('button', { name: '내보내기' }))
      await waitFor(() => {
        expect(vi.mocked(triggerBlobDownload)).toHaveBeenCalledWith(
          EXPORT_RESULT.blob,
          'ATLAS-issues-20260629T000000Z.csv',
        )
      })
    })

    it('exportIssues가 현재 선택된 형식/컬럼 파라미터로 호출된다', async () => {
      const { user } = renderDialog()

      // XLSX 선택 + KEY 컬럼 해제
      await user.click(screen.getByRole('radio', { name: 'XLSX' }))
      await user.click(screen.getByRole('checkbox', { name: 'Key' }))
      await user.click(screen.getByRole('button', { name: '내보내기' }))

      await waitFor(() => {
        expect(vi.mocked(exportIssues)).toHaveBeenCalledWith(
          expect.objectContaining({
            projectKey: 'ATLAS',
            query: 'status = open',
            format: 'XLSX',
            columns: expect.not.arrayContaining(['KEY']),
          }),
        )
      })
    })

    it('성공 후 onOpenChange(false)가 호출된다', async () => {
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
      vi.mocked(exportIssues).mockRejectedValue(
        new ApiError(400, {
          errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
          detail: '내보내기 한도(10,000건)를 초과했습니다. 쿼리를 좁혀 다시 시도하세요.',
          resultCount: 15000,
          limit: 10000,
          status: 400,
        }),
      )

      const { user } = renderDialog()
      await user.click(screen.getByRole('button', { name: '내보내기' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent(
          '내보내기 한도(10,000건)를 초과했습니다. 쿼리를 좁혀 다시 시도하세요.',
        )
      })
    })

    it('일반 에러 시 폴백 메시지가 표시된다', async () => {
      vi.mocked(exportIssues).mockRejectedValue(new Error('Network error'))

      const { user } = renderDialog()
      await user.click(screen.getByRole('button', { name: '내보내기' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent(
          '내보내기 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.',
        )
      })
    })
  })
})
