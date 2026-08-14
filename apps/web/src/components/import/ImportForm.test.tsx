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

function renderForm(onBusyChange: (busy: boolean) => void = () => {}) {
  const user = userEvent.setup()
  const utils = render(createElement(ImportForm, { projectKey: 'ATLAS', onBusyChange }), {
    wrapper: createWrapper(),
  })
  return { user, ...utils }
}

/** `onBusyChange` 가 마지막으로 보고한 값 — 호출 횟수가 아니라 **최종 상태**를 잰다. */
function lastBusy(spy: ReturnType<typeof vi.fn>): boolean | undefined {
  const calls = spy.mock.calls
  return calls.length === 0 ? undefined : (calls[calls.length - 1]?.[0] as boolean)
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

    // ★진행 중 동결(부채 매핑 16)이 기대는 계약이다. 동결은 권한이 회수된 사용자를 화면에
    //   남겨 두고 **최종 판정을 서버 403 에 맡긴다** — 그 403 이 「권한」을 말해야 동결이 성립한다.
    //
    // ★지금은 맞게 동작하지만 **아무것도 검사하지 않았다.**
    //   백엔드 `ImportExceptionHandler.kt:70-76` 이 `detail:"이 작업을 수행할 권한이 없습니다."` 를
    //   보내고 프론트 `resolveImportError`(:44-53)가 그 `detail` 을 꺼내 쓴다. 그 연결이 끊기면
    //   폴백 「Import 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.」가 뜬다 —
    //   권한이 회수된 사용자에게 **영원히 틀린 안내**다(매핑 `17` 이 이동 다이얼로그에서 닫은 결함과 같은 양식).
    //
    // ⚠️ cross-BC 라 이 PR 은 **프론트 반쪽만** 고정한다. 백엔드에서 `detail` 이 사라지는 것은
    //    여기서 못 잡는다 — 등재 후보 3으로 장부에 올린다.
    it('★403 은 권한 문구를 그대로 보여 준다 — 「잠시 후 다시 시도」로 뭉개지 않는다 (부채 매핑 16 · EC4)', async () => {
      // 서버가 실제로 보내는 ProblemDetail 그대로다 (`ImportExceptionHandler.kt:70-76`).
      vi.mocked(submitImportJob).mockRejectedValue(
        new ApiError(403, {
          type: 'import-access-denied',
          title: 'Access Denied',
          detail: '이 작업을 수행할 권한이 없습니다.',
        }),
      )

      const { user } = renderForm()
      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: '검증만 실행' }))

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('이 작업을 수행할 권한이 없습니다.')
      // 비-공허 짝 — 폴백 문구가 새어 나오면 사용자는 「기다리면 되겠지」로 읽고 영원히 재시도한다.
      expect(alert).not.toHaveTextContent('잠시 후 다시 시도')
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

  // (f) 진행 중 신호 — 부채 매핑 16
  //
  // ★부모(임포트 라우트)는 이 신호를 받아 **권한 재조회가 화면을 언마운트하지 못하게** 막는다.
  //   신호가 없으면 창을 30초 넘게 벗어났다 돌아왔을 때 고른 파일·jobId·진행률이 통째로 날아간다.
  //
  // ★「진행 중」은 폴링만이 아니다. 장부가 「파일 선택·jobId·진행률이 날아간다」라 적었으므로
  //   **파일만 고른 사용자**도 잃을 것이 있다 — `file !== null || phase !== 'form'`.
  describe('진행 중 신호 (onBusyChange)', () => {
    it('마운트 직후에는 진행 중이 아니다', () => {
      const onBusyChange = vi.fn()
      renderForm(onBusyChange)

      expect(lastBusy(onBusyChange)).toBe(false)
    })

    it('★파일을 고르기만 해도 진행 중이다 (아직 제출 전이어도 잃을 것이 있다)', async () => {
      const onBusyChange = vi.fn()
      const { user } = renderForm(onBusyChange)

      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))

      expect(lastBusy(onBusyChange)).toBe(true)
    })

    it('제출해 tracking 으로 넘어가면 진행 중이다', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({ status: 'RUNNING', progress: 10, dryRun: false }),
      )
      const onBusyChange = vi.fn()
      const { user } = renderForm(onBusyChange)

      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))

      await waitFor(() => expect(screen.getByRole('progressbar')).toBeInTheDocument())
      expect(lastBusy(onBusyChange)).toBe(true)
    })

    it('★완료 후 [처음으로]로 폼을 비우면 진행 중이 아니다 (비-공허 짝 — 신호가 켜진 채 굳지 않는다)', async () => {
      vi.mocked(submitImportJob).mockResolvedValue(makeStatus({ dryRun: false }))
      vi.mocked(fetchImportJobStatus).mockResolvedValue(
        makeStatus({ status: 'COMPLETED', progress: 100, succeededRows: 3, dryRun: false }),
      )
      const onBusyChange = vi.fn()
      const { user } = renderForm(onBusyChange)

      await user.upload(screen.getByLabelText('가져올 파일'), makeFile('issues.csv', 'text/csv'))
      await user.click(screen.getByRole('button', { name: 'Import 시작' }))

      const backButton = await screen.findByRole('button', { name: '처음으로' })
      await user.click(backButton)

      await waitFor(() => expect(lastBusy(onBusyChange)).toBe(false))
    })
  })
})
