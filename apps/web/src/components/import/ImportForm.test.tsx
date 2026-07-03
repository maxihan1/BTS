// Import 폼 컴포넌트 단위 테스트 — 3단계 상태머신(form/tracking/done) + FR-2a 첨부zip 조건부 노출 (FR-IM-01 D6 Task-3)
//
// 설계 결정: submitImportJob / downloadImportErrorLog 직접 mock (ExportDialog.test.tsx 관례 미러).
// fetchImportJobStatus는 useImportJobPolling(Task-2, 이미 구현됨) 내부에서 호출되므로 함께 mock한다.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/api/client'
import { ImportForm } from './ImportForm'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 mock — submitImportJob / fetchImportJobStatus / downloadImportErrorLog + triggerBlobDownload
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/imports', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/imports')>()
  return {
    ...actual,
    submitImportJob: vi.fn(),
    fetchImportJobStatus: vi.fn(),
    downloadImportErrorLog: vi.fn(),
  }
})

vi.mock('@/lib/download', () => ({
  triggerBlobDownload: vi.fn(),
}))

import { submitImportJob, fetchImportJobStatus, downloadImportErrorLog } from '@/api/imports'
import type { ImportJobStatus } from '@/api/imports'
import { triggerBlobDownload } from '@/lib/download'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const JOB_ID = '00000000-0000-4000-a000-000000000042'

/** importJobStatusSchema 형태의 최소 완전 픽스처 — overrides로 상태별 변형 */
function makeStatus(overrides: Partial<ImportJobStatus> = {}): ImportJobStatus {
  return {
    jobId: JOB_ID,
    status: 'PENDING',
    progress: 0,
    succeededRows: 0,
    failedRows: 0,
    errorLogReady: false,
    dryRun: false,
    ...overrides,
  }
}

function makeFile(name: string, type: string): File {
  return new File(['dummy-content'], name, { type })
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

function renderForm() {
  const user = userEvent.setup()
  const utils = render(createElement(ImportForm, { projectKey: 'ATLAS' }), {
    wrapper: createWrapper(),
  })
  return { user, ...utils }
}

// ─────────────────────────────────────────────────────────────────────────────
// 셋업 / 정리
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.mocked(submitImportJob).mockReset()
  vi.mocked(fetchImportJobStatus).mockReset()
  vi.mocked(downloadImportErrorLog).mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ImportForm', () => {
  // (a) 폼 — 파일/형식 선택
  describe('폼 — 파일/형식 선택', () => {
    it('파일 미선택 시 [검증만 실행]/[Import 시작] 버튼이 비활성화된다', () => {
      renderForm()
      expect(screen.getByRole('button', { name: '검증만 실행' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Import 시작' })).toBeDisabled()
    })

    it('format=CSV(기본)면 첨부 zip 입력이 노출되지 않는다', () => {
      renderForm()
      expect(screen.queryByLabelText('첨부 zip 파일')).not.toBeInTheDocument()
    })

    it('format=JSON을 선택하면 첨부 zip 입력이 노출된다', async () => {
      const { user } = renderForm()
      await user.click(screen.getByRole('radio', { name: 'JSON' }))
      expect(screen.getByLabelText('첨부 zip 파일')).toBeInTheDocument()
    })

    it('JSON에서 zip을 선택 후 CSV로 되돌리면 선택된 zip이 초기화된다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: true }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(makeStatus({ dryRun: true }))

      const { user } = renderForm()

      await user.click(screen.getByRole('radio', { name: 'JSON' }))
      await user.upload(
        screen.getByLabelText('가져올 파일'),
        makeFile('issues.json', 'application/json'),
      )
      await user.upload(
        screen.getByLabelText('첨부 zip 파일'),
        makeFile('attachments.zip', 'application/zip'),
      )

      // CSV로 되돌림 — zip 입력 자체가 사라진다 (FR-2a)
      await user.click(screen.getByRole('radio', { name: 'CSV' }))
      expect(screen.queryByLabelText('첨부 zip 파일')).not.toBeInTheDocument()

      // 다시 JSON으로 전환 — 이전 zip 선택이 초기화됐는지 submitImportJob 호출 파라미터로 확인
      await user.click(screen.getByRole('radio', { name: 'JSON' }))
      await user.click(screen.getByRole('button', { name: '검증만 실행' }))

      await waitFor(() => {
        expect(vi.mocked(submitImportJob)).toHaveBeenCalledWith(
          expect.objectContaining({ attachmentsZip: null }),
        )
      })
    })
  })

  // (b) 제출 — dry-run/real
  describe('제출 — dry-run/real', () => {
    it('[검증만 실행] 클릭 시 submitImportJob이 dryRun:true로 호출되고 tracking으로 전환된다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: true }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(makeStatus({ dryRun: true }))

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: '검증만 실행' }))

      await waitFor(() => {
        expect(vi.mocked(submitImportJob)).toHaveBeenCalledWith(
          expect.objectContaining({ projectKey: 'ATLAS', format: 'CSV', dryRun: true }),
        )
      })
      await waitFor(() => {
        expect(screen.getByRole('progressbar')).toBeInTheDocument()
      })
    })

    it('[Import 시작] 클릭 시 submitImportJob이 dryRun:false로 호출된다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(makeStatus({ dryRun: false }))

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))

      await waitFor(() => {
        expect(vi.mocked(submitImportJob)).toHaveBeenCalledWith(
          expect.objectContaining({ dryRun: false }),
        )
      })
    })

    it('submit 에러(403) 발생 시 폼에 인라인 role=alert 메시지가 표시된다', async () => {
      vi.mocked(submitImportJob).mockRejectedValue(
        new ApiError(403, { detail: '이 프로젝트에 이슈를 생성할 권한이 없습니다.' }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: '검증만 실행' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent(
          '이 프로젝트에 이슈를 생성할 권한이 없습니다.',
        )
      })
    })
  })

  // (c) 완료 — dry-run
  describe('완료 — dry-run', () => {
    it('폴링 COMPLETED(dryRun) 도달 시 "검증 완료" + 성공/실패 수 + [이 파일로 실제 Import] + [에러 로그 다운로드]가 표시된다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: true }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({
          status: 'COMPLETED',
          progress: 100,
          totalRows: 10,
          succeededRows: 8,
          failedRows: 2,
          errorLogReady: true,
          dryRun: true,
        }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: '검증만 실행' }))

      await waitFor(() => {
        expect(screen.getByText('검증 완료')).toBeInTheDocument()
      })
      expect(screen.getByText(/성공 8건/)).toBeInTheDocument()
      expect(screen.getByText(/실패 2건/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '이 파일로 실제 Import' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '에러 로그 다운로드' })).toBeInTheDocument()
    })

    it('[이 파일로 실제 Import] 클릭 시 같은 file로 dryRun:false 재제출된다', async () => {
      vi.mocked(submitImportJob).mockResolvedValueOnce(makeStatus({ dryRun: true }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({ status: 'COMPLETED', succeededRows: 8, failedRows: 2, dryRun: true }),
      )

      const { user } = renderForm()
      const file = makeFile('issues.csv', 'text/csv')
      await user.upload(screen.getByLabelText('가져올 파일'), file)
      await user.click(screen.getByRole('button', { name: '검증만 실행' }))
      await waitFor(() => screen.getByRole('button', { name: '이 파일로 실제 Import' }))

      vi.mocked(submitImportJob).mockResolvedValueOnce(makeStatus({ dryRun: false }))
      await user.click(screen.getByRole('button', { name: '이 파일로 실제 Import' }))

      await waitFor(() => {
        expect(vi.mocked(submitImportJob)).toHaveBeenLastCalledWith(
          expect.objectContaining({ dryRun: false, file }),
        )
      })
    })
  })

  // (d) 완료 — real
  describe('완료 — real', () => {
    it('폴링 COMPLETED(non-dryRun) 도달 시 "Import 완료"가 표시되고 재제출 버튼은 없다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({ status: 'COMPLETED', succeededRows: 10, failedRows: 0, dryRun: false }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))

      await waitFor(() => {
        expect(screen.getByText('Import 완료')).toBeInTheDocument()
      })
      expect(
        screen.queryByRole('button', { name: '이 파일로 실제 Import' }),
      ).not.toBeInTheDocument()
    })
  })

  // (e) 완료 — FAILED
  describe('완료 — FAILED', () => {
    it('폴링 FAILED 도달 시 errorCode 한국어 메시지 + [다시 시도]가 표시되고, 클릭하면 폼으로 복귀한다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({ status: 'FAILED', errorCode: 'IMPORT_PARSE_FAILED', dryRun: false }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent(
          '파일을 파싱하지 못했습니다. 형식을 확인하세요.',
        )
      })
      expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: '다시 시도' }))
      await waitFor(() => {
        expect(screen.getByRole('button', { name: 'Import 시작' })).toBeInTheDocument()
      })
    })
  })

  // (f) done 다운로드 실패 — dead-path 방지
  describe('done 다운로드 실패', () => {
    it('done 단계 에러 로그 다운로드 실패 시 submitError가 role=alert로 렌더된다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({
          status: 'COMPLETED',
          succeededRows: 8,
          failedRows: 2,
          errorLogReady: true,
          dryRun: false,
        }),
      )
      vi.mocked(downloadImportErrorLog).mockRejectedValue(
        new ApiError(409, { detail: '에러 로그가 아직 준비되지 않았습니다.' }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))
      await waitFor(() => screen.getByRole('button', { name: '에러 로그 다운로드' }))

      await user.click(screen.getByRole('button', { name: '에러 로그 다운로드' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent('에러 로그가 아직 준비되지 않았습니다.')
      })
      expect(vi.mocked(triggerBlobDownload)).not.toHaveBeenCalled()
    })
  })

  // (g) 폴링 HTTP 에러 — tracking 에러 UI + 다시 시도 (spec 엣지케이스)
  describe('폴링 에러', () => {
    it('폴링이 HTTP 에러로 실패하면 tracking 단계에 role=alert + [다시 시도]가 표시된다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockRejectedValue(
        new ApiError(403, { detail: '접근 권한이 없습니다.' }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })
      expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: '다시 시도' }))
      await waitFor(() => {
        expect(screen.getByRole('button', { name: 'Import 시작' })).toBeInTheDocument()
      })
    })
  })

  // (h) 접근성
  describe('접근성', () => {
    it('tracking 단계 progressbar가 aria-valuenow/min/max를 노출한다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({ status: 'RUNNING', progress: 42, dryRun: false }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))

      await waitFor(() => {
        const bar = screen.getByRole('progressbar')
        expect(bar).toHaveAttribute('aria-valuenow', '42')
        expect(bar).toHaveAttribute('aria-valuemin', '0')
        expect(bar).toHaveAttribute('aria-valuemax', '100')
      })
    })
  })
})
