// 이슈 내보내기 다이얼로그 단위 테스트 — 동기/비동기 경로 + 4단계 상태머신 (FR-EX-01/FR-EX-02 Task-2)
//
// 설계 결정: exportIssues / submitExportJob / fetchExportJobStatus / downloadExportJobResult 직접 mock.
// 컴포넌트 단위 테스트는 "API 응답 처리" 행동을 검증하는 게 목적이다.
// content-disposition 헤더 파싱(HTTP 레이어)은 jsdom 환경에서 헤더 접근 제한이 있어
// 컴포넌트 레벨이 아닌 exportIssues 함수 레벨(E2E/통합)에서 검증한다.
import { describe, it, expect, vi, beforeEach, afterEach, act } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { useAuthStore } from '@/auth/authStore'
import { ApiError } from '@/api/client'
import { ExportDialog } from './ExportDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 mock — triggerBlobDownload + exportIssues + 비동기 export-jobs 3종
// ─────────────────────────────────────────────────────────────────────────────

// triggerBlobDownload는 jsdom에 URL.createObjectURL이 없으므로 mock 처리
vi.mock('@/lib/download', () => ({
  triggerBlobDownload: vi.fn(),
}))

// exportIssues / submitExportJob / fetchExportJobStatus / downloadExportJobResult mock
vi.mock('@/api/search', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/search')>()
  return {
    ...actual,
    exportIssues: vi.fn(),
    submitExportJob: vi.fn(),
    fetchExportJobStatus: vi.fn(),
    downloadExportJobResult: vi.fn(),
  }
})

import { triggerBlobDownload } from '@/lib/download'
import {
  exportIssues,
  submitExportJob,
  fetchExportJobStatus,
  downloadExportJobResult,
} from '@/api/search'
import type { ExportIssuesResult } from '@/api/search'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const USER_ID = '00000000-0000-4000-8000-000000000001'
const JOB_ID = '00000000-0000-4000-8000-000000000099'

/** 동기 성공 응답 픽스처 — blob + 서버 생성 파일명 */
const EXPORT_RESULT: ExportIssuesResult = {
  blob: new Blob(['﻿Key,Summary\r\nATLAS-1,Test issue\r\n'], { type: 'text/csv' }),
  filename: 'ATLAS-issues-20260629T000000Z.csv',
}

/** 비동기 Export 잡 완료 픽스처 */
const JOB_COMPLETED = {
  jobId: JOB_ID,
  status: 'COMPLETED' as const,
  progress: 100,
  rowCount: 15000,
  format: 'CSV',
  downloadReady: true,
}

/** 비동기 Export 잡 실패 픽스처 */
const JOB_FAILED = {
  jobId: JOB_ID,
  status: 'FAILED' as const,
  progress: 0,
  format: 'CSV',
  downloadReady: false,
  errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
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
    // ★ B3 교체: 기존 '상한초과(SEARCH_EXPORT_LIMIT_EXCEEDED) 에러 시 detail 메시지가 표시된다'
    // (ExportDialog.test.tsx:225-243) 를 S2 자동분기 테스트로 대체.
    // 사유: 자동분기 동작(LIMIT_EXCEEDED → confirmAsync 전환)과 role=alert 표시가 양립 불가 (리뷰 BLOCKER-3).
    it('S2 자동분기: LIMIT_EXCEEDED → confirmAsync(15,000건 정확 표시) → 백그라운드 → COMPLETED → 다운로드', async () => {
      vi.mocked(exportIssues).mockRejectedValue(
        new ApiError(400, {
          errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
          detail: '검색 결과가 동기 Export 상한(10,000건)을 초과합니다.',
          resultCount: 15000,
          limit: 10000,
          status: 400,
        }),
      )
      vi.mocked(submitExportJob).mockResolvedValue({ jobId: JOB_ID, status: 'PENDING' })
      vi.mocked(fetchExportJobStatus).mockResolvedValue(JOB_COMPLETED)
      vi.mocked(downloadExportJobResult).mockResolvedValue({
        blob: new Blob(['csv-data']),
        filename: 'ATLAS-export.csv',
      })

      const { user } = renderDialog()
      await user.click(screen.getByRole('button', { name: '내보내기' }))

      // confirmAsync 전환: 정확한 건수 "15,000건" 노출 (CONCERN-B — NaN/undefined 가짜그린 차단)
      await waitFor(() => {
        expect(screen.getByText(/15,000건/)).toBeInTheDocument()
      })
      expect(screen.getByRole('button', { name: '백그라운드 내보내기' })).toBeInTheDocument()

      // 백그라운드 내보내기 클릭 → submitExportJob 호출
      await user.click(screen.getByRole('button', { name: '백그라운드 내보내기' }))
      await waitFor(() => {
        expect(vi.mocked(submitExportJob)).toHaveBeenCalledTimes(1)
      })

      // 폴링 COMPLETED → done phase → 다운로드 버튼
      await waitFor(() => {
        expect(screen.getByRole('button', { name: '다운로드' })).toBeInTheDocument()
      })

      // 다운로드 클릭 → downloadExportJobResult + triggerBlobDownload
      await user.click(screen.getByRole('button', { name: '다운로드' }))
      await waitFor(() => {
        expect(vi.mocked(downloadExportJobResult)).toHaveBeenCalledWith(JOB_ID)
        expect(vi.mocked(triggerBlobDownload)).toHaveBeenCalledTimes(1)
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

  // (e) 비동기 경로 — S3(FAILED)/S4(취소)/FR-7(cleanup)
  describe('비동기 경로', () => {
    it('S3 FAILED: 폴링 실패 → 에러(role=alert) → 다시 시도 → form 복귀 + jobId null 초기화(stale 폴링 차단)', async () => {
      vi.mocked(exportIssues).mockRejectedValue(
        new ApiError(400, {
          errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
          resultCount: 5000,
          limit: 10000,
          status: 400,
          detail: '초과',
        }),
      )
      vi.mocked(submitExportJob).mockResolvedValue({ jobId: JOB_ID, status: 'PENDING' })
      vi.mocked(fetchExportJobStatus).mockResolvedValue(JOB_FAILED)

      const { user } = renderDialog()

      // confirmAsync 진입
      await user.click(screen.getByRole('button', { name: '내보내기' }))
      await waitFor(() => screen.getByRole('button', { name: '백그라운드 내보내기' }))

      // tracking → done(FAILED) 전환
      await user.click(screen.getByRole('button', { name: '백그라운드 내보내기' }))
      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })
      // 실패 메시지: 에러 사유 포함
      expect(screen.getByRole('alert')).toHaveTextContent(/실패|오류|초과/)

      // "다시 시도" 클릭 → form 복귀
      await user.click(screen.getByRole('button', { name: '다시 시도' }))
      await waitFor(() => {
        expect(screen.getByRole('button', { name: '내보내기' })).toBeInTheDocument()
      })

      // CONCERN-D: jobId null 초기화 확인 — form 복귀 후 fetchExportJobStatus 호출 횟수 불변
      const countAfterRetry = vi.mocked(fetchExportJobStatus).mock.calls.length
      await new Promise((r) => setTimeout(r, 200))
      expect(vi.mocked(fetchExportJobStatus).mock.calls.length).toBe(countAfterRetry)
    })

    it('S4 취소: confirmAsync에서 취소 → form 복귀 + 형식/컬럼 상태 유지(CONCERN-C)', async () => {
      vi.mocked(exportIssues).mockRejectedValue(
        new ApiError(400, {
          errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
          resultCount: 5000,
          limit: 10000,
          status: 400,
          detail: '초과',
        }),
      )

      const onOpenChange = vi.fn()
      const { user } = renderDialog({ onOpenChange })

      // XLSX 선택 + Key 컬럼 해제
      await user.click(screen.getByRole('radio', { name: 'XLSX' }))
      await user.click(screen.getByRole('checkbox', { name: 'Key' }))

      // 내보내기 → LIMIT_EXCEEDED → confirmAsync
      await user.click(screen.getByRole('button', { name: '내보내기' }))
      await waitFor(() => screen.getByRole('button', { name: '백그라운드 내보내기' }))

      // confirmAsync에서 "취소" 클릭 → form 복귀 (다이얼로그 닫힘 아님)
      await user.click(screen.getByRole('button', { name: '취소' }))

      // form phase 복귀
      await waitFor(() => {
        expect(screen.getByRole('button', { name: '내보내기' })).toBeInTheDocument()
      })

      // 형식 상태 유지 (XLSX)
      expect(screen.getByRole('radio', { name: 'XLSX' })).toBeChecked()
      // 컬럼 상태 유지 (Key 해제)
      expect(screen.getByRole('checkbox', { name: 'Key' })).not.toBeChecked()

      // 다이얼로그 닫힘 없음 (onOpenChange(false) 미호출)
      expect(onOpenChange).not.toHaveBeenCalledWith(false)
    })

    it('FR-7 다이얼로그 닫으면 추가 폴링 호출 없음', async () => {
      vi.useFakeTimers()
      const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })

      vi.mocked(exportIssues).mockRejectedValue(
        new ApiError(400, {
          errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
          resultCount: 5000,
          limit: 10000,
          status: 400,
          detail: '초과',
        }),
      )
      vi.mocked(submitExportJob).mockResolvedValue({ jobId: JOB_ID, status: 'PENDING' })
      // RUNNING 유지 — 종단 아니라 refetchInterval이 계속 동작
      vi.mocked(fetchExportJobStatus).mockResolvedValue({
        jobId: JOB_ID,
        status: 'RUNNING' as const,
        progress: 50,
        format: 'CSV',
        downloadReady: false,
      })

      const qc = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })

      const TestApp = ({ open }: { readonly open: boolean }) =>
        createElement(
          QueryClientProvider,
          { client: qc },
          createElement(ExportDialog, {
            open,
            onOpenChange: () => {},
            projectKey: 'ATLAS',
            query: 'status = open',
          }),
        )

      const { rerender } = render(createElement(TestApp, { open: true }))

      // confirmAsync 진입
      await user.click(screen.getByRole('button', { name: '내보내기' }))
      await waitFor(() => screen.getByRole('button', { name: '백그라운드 내보내기' }))

      // tracking 진입
      await user.click(screen.getByRole('button', { name: '백그라운드 내보내기' }))

      // 초기 폴링 실행 대기
      await act(async () => {
        await vi.runAllTimersAsync()
      })
      await waitFor(() => expect(vi.mocked(fetchExportJobStatus)).toHaveBeenCalledTimes(1))

      const callsBefore = vi.mocked(fetchExportJobStatus).mock.calls.length

      // 다이얼로그 닫기 (Content 언마운트 → useQuery cleanup)
      rerender(createElement(TestApp, { open: false }))

      // refetchInterval(1500ms) × 3 = 4500ms 진행
      await act(async () => {
        vi.advanceTimersByTime(5000)
        await vi.runAllTimersAsync()
      })

      // 추가 폴링 없음
      expect(vi.mocked(fetchExportJobStatus).mock.calls.length).toBe(callsBefore)

      vi.useRealTimers()
    })
  })
})
