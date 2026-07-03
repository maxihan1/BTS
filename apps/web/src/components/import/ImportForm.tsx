// Import(CSV/JSON) 3단계 상태머신 폼 컴포넌트 — ExportForm(FR-EX-02) 미러 적응 (FR-IM-01 D6 Task-3)
import type { JSX, ChangeEvent } from 'react'
import { useState, useEffect } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { submitImportJob, downloadImportErrorLog, importFailureMessage } from '@/api/imports'
import type { ImportJobStatus } from '@/api/imports'
import { triggerBlobDownload } from '@/lib/download'
import { ApiError } from '@/api/client'
import { useImportJobPolling } from '@/hooks/use-import-job-polling'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

type ImportFormat = 'CSV' | 'JSON'
type ImportPhase = 'form' | 'tracking' | 'done'

/** submitMutation 호출 변수 */
interface SubmitVariables {
  readonly dryRun: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 진행률 상태 라벨
// ─────────────────────────────────────────────────────────────────────────────

const STATUS_LABELS: Record<string, string> = {
  PENDING: '대기 중',
  RUNNING: '가져오는 중...',
  COMPLETED: '완료',
  FAILED: '실패',
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 판별 / 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ApiError body에서 ProblemDetail `detail` 문자열을 추출한다.
 * detail이 없으면 일반 메시지를 반환한다 (resolveExportError 미러).
 */
function resolveImportError(error: unknown): string {
  if (error instanceof ApiError) {
    const body = error.body
    if (body !== null && typeof body === 'object') {
      const detail = (body as Record<string, unknown>)['detail']
      if (typeof detail === 'string') return detail
    }
  }
  return 'Import 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.'
}

/** 잡 실패 errorCode를 한글 메시지로 변환한다 (importFailureMessage 위임 — getFailureMessage 명명은 ExportForm 미러) */
function getFailureMessage(errorCode: string | null | undefined): string {
  return importFailureMessage(errorCode)
}

// ─────────────────────────────────────────────────────────────────────────────
// FormPhase — 파일/형식 선택 단계
// ─────────────────────────────────────────────────────────────────────────────

interface FormPhaseProps {
  readonly format: ImportFormat
  readonly onFormatChange: (format: ImportFormat) => void
  readonly onFileChange: (file: File | null) => void
  readonly onZipChange: (zip: File | null) => void
  readonly canSubmit: boolean
  readonly isPendingDryRun: boolean
  readonly isPendingReal: boolean
  readonly submitError: string | null
  readonly onSubmitDryRun: () => void
  readonly onSubmitReal: () => void
}

function FormPhase({
  format,
  onFormatChange,
  onFileChange,
  onZipChange,
  canSubmit,
  isPendingDryRun,
  isPendingReal,
  submitError,
  onSubmitDryRun,
  onSubmitReal,
}: FormPhaseProps): JSX.Element {
  return (
    <div>
      <fieldset className="mb-4">
        <legend className="mb-2 text-sm font-medium">형식</legend>
        <div className="flex items-center gap-4">
          {(['CSV', 'JSON'] as const).map((fmt) => (
            <label key={fmt} className="flex cursor-pointer items-center gap-2">
              <input
                type="radio"
                name="import-format"
                value={fmt}
                checked={format === fmt}
                onChange={() => {
                  onFormatChange(fmt)
                }}
                className="accent-primary"
              />
              <span className="text-sm">{fmt}</span>
            </label>
          ))}
        </div>
      </fieldset>

      <div className="mb-4">
        <label htmlFor="import-file-input" className="mb-2 block text-sm font-medium">
          가져올 파일
        </label>
        <Input
          id="import-file-input"
          type="file"
          accept=".csv,.json"
          aria-label="가져올 파일"
          onChange={(e: ChangeEvent<HTMLInputElement>) => {
            onFileChange(e.target.files?.[0] ?? null)
          }}
        />
      </div>

      {/* FR-2a: format=JSON일 때만 첨부 zip 입력 노출 (백엔드가 CSV+zip을 조용히 무시하므로) */}
      {format === 'JSON' && (
        <div className="mb-4">
          <label htmlFor="import-zip-input" className="mb-2 block text-sm font-medium">
            첨부 zip 파일 (선택)
          </label>
          <Input
            id="import-zip-input"
            type="file"
            accept=".zip"
            aria-label="첨부 zip 파일"
            onChange={(e: ChangeEvent<HTMLInputElement>) => {
              onZipChange(e.target.files?.[0] ?? null)
            }}
          />
        </div>
      )}

      <p className="mb-4 text-sm text-muted-foreground">먼저 검증을 실행하는 것을 권장합니다.</p>

      {submitError !== null && (
        <p
          role="alert"
          className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {submitError}
        </p>
      )}

      <div className="mt-2 flex justify-end gap-2">
        <Button type="button" size="sm" disabled={!canSubmit} onClick={onSubmitDryRun}>
          {isPendingDryRun ? '접수 중...' : '검증만 실행'}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={!canSubmit}
          onClick={onSubmitReal}
        >
          {isPendingReal ? '접수 중...' : 'Import 시작'}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// TrackingPhase — 진행률 폴링 단계
// ─────────────────────────────────────────────────────────────────────────────

interface TrackingPhaseProps {
  readonly pollData: ImportJobStatus | undefined
  readonly pollIsError: boolean
  readonly onRetry: () => void
}

function TrackingPhase({ pollData, pollIsError, onRetry }: TrackingPhaseProps): JSX.Element {
  if (pollIsError) {
    return (
      <div>
        <p
          role="alert"
          className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          Import 상태를 조회하지 못했습니다. 잠시 후 다시 시도하세요.
        </p>
        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" size="sm" onClick={onRetry}>
            다시 시도
          </Button>
        </div>
      </div>
    )
  }

  const progress = pollData?.progress ?? 0
  const statusLabel = STATUS_LABELS[pollData?.status ?? 'PENDING'] ?? '처리 중...'

  return (
    <div>
      <p role="status" aria-live="polite" className="mb-2 text-sm font-medium text-foreground">
        {statusLabel}
      </p>
      <div
        role="progressbar"
        aria-valuenow={progress}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`Import 진행률 ${progress}%`}
        className="mb-4 h-2 w-full overflow-hidden rounded-full bg-muted"
      >
        <div
          className="h-full rounded-full bg-primary transition-all duration-300"
          style={{ width: `${progress}%` }}
          aria-hidden="true"
        />
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// DonePhase — 완료/실패 단계
// ─────────────────────────────────────────────────────────────────────────────

interface DonePhaseProps {
  readonly pollData: ImportJobStatus | undefined
  readonly lastDryRun: boolean
  readonly submitError: string | null
  readonly isDownloadPending: boolean
  readonly isResubmitPending: boolean
  readonly onDownload: () => void
  readonly onResubmitReal: () => void
  readonly onBackToForm: (clearFile: boolean) => void
}

function DonePhase({
  pollData,
  lastDryRun,
  submitError,
  isDownloadPending,
  isResubmitPending,
  onDownload,
  onResubmitReal,
  onBackToForm,
}: DonePhaseProps): JSX.Element {
  const isFailed = pollData?.status === 'FAILED'

  if (isFailed) {
    return (
      <div>
        <p
          role="alert"
          className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          Import에 실패했습니다. 사유: {getFailureMessage(pollData?.errorCode)}
        </p>
        {/* dead-path 방지: 다운로드 실패 submitError도 done(FAILED)에서 함께 표시 */}
        {submitError !== null && (
          <p
            role="alert"
            className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {submitError}
          </p>
        )}
        <div className="mt-2 flex justify-end gap-2">
          <Button
            type="button"
            size="sm"
            onClick={() => {
              onBackToForm(false)
            }}
          >
            다시 시도
          </Button>
        </div>
      </div>
    )
  }

  const succeededRows = pollData?.succeededRows ?? 0
  const failedRows = pollData?.failedRows ?? 0
  const showErrorLog = pollData?.errorLogReady === true
  const resultLabel = lastDryRun ? '검증 완료' : 'Import 완료'

  return (
    <div>
      <p role="status" aria-live="polite" className="mb-2 text-sm font-medium text-foreground">
        {resultLabel}
      </p>
      <p className="mb-4 text-sm text-foreground">
        성공 {succeededRows.toLocaleString()}건 /{' '}
        <span className={failedRows > 0 ? 'font-medium text-destructive' : ''}>
          실패 {failedRows.toLocaleString()}건
        </span>
      </p>

      {/* C1 dead-path 방지: done 단계도 submitError(다운로드/재제출 실패)를 렌더한다 */}
      {submitError !== null && (
        <p
          role="alert"
          className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {submitError}
        </p>
      )}

      <div className="mt-2 flex flex-wrap justify-end gap-2">
        {showErrorLog && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={isDownloadPending}
            onClick={onDownload}
          >
            {isDownloadPending ? '다운로드 중...' : '에러 로그 다운로드'}
          </Button>
        )}
        {lastDryRun && (
          <Button type="button" size="sm" disabled={isResubmitPending} onClick={onResubmitReal}>
            {isResubmitPending ? '접수 중...' : '이 파일로 실제 Import'}
          </Button>
        )}
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => {
            // 성공적 real(non-dry-run) 완료 후에만 file을 초기화한다 (spec 상태머신)
            onBackToForm(!lastDryRun)
          }}
        >
          처음으로
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ImportForm Props */
export interface ImportFormProps {
  /** Import 대상 프로젝트 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// ImportForm (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CSV/JSON Import 폼 — 3단계 상태 머신.
 *
 * ```
 * phase: 'form' | 'tracking' | 'done'
 *
 * form     ──[검증만 실행]────▶ submit(dryRun=true)  ──onSuccess──▶ tracking (lastDryRun=true)
 * form     ──[Import 시작]───▶ submit(dryRun=false) ──onSuccess──▶ tracking (lastDryRun=false)
 * form     ──[submit 에러]───▶ form (submitError, role=alert)
 * tracking ──[폴링 COMPLETED/FAILED]──▶ done
 * tracking ──[폴링 error]─────────────▶ (에러 UI: 다시 시도→form)
 * done(COMPLETED, dryRun) ──[이 파일로 실제 Import]──▶ submit(dryRun=false, 같은 file) ──▶ tracking
 * done(COMPLETED) ──[처음으로]──▶ form (real 성공이면 file 초기화)
 * done(FAILED) ──[다시 시도]──▶ form (jobId=null)
 * ```
 *
 * file/attachmentsZip은 phase 전환에도 state로 보존한다 ("이 파일로 실제 Import" 재사용).
 * submitError는 이 컴포넌트가 소유한다 — done 단계도 다운로드/재제출 실패를 렌더해야 한다
 * (dialog-submiterror-ownership-dead-path 교훈: 부모 미전달 시 dead path 방지).
 *
 * FR-EX-02(Export) `ExportDialog.tsx`의 `ExportForm` 역방향 미러 — 상태머신/폴링 hook/Tailwind 토큰 동일.
 */
export const ImportForm = ({ projectKey }: ImportFormProps): JSX.Element => {
  const [format, setFormat] = useState<ImportFormat>('CSV')
  const [file, setFile] = useState<File | null>(null)
  const [attachmentsZip, setAttachmentsZip] = useState<File | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [phase, setPhase] = useState<ImportPhase>('form')
  const [jobId, setJobId] = useState<string | null>(null)
  const [lastDryRun, setLastDryRun] = useState<boolean>(false)

  const submitMutation = useMutation<ImportJobStatus, unknown, SubmitVariables>({
    mutationFn: ({ dryRun }) => {
      if (file === null) {
        return Promise.reject(new Error('파일을 선택하세요.'))
      }
      return submitImportJob({
        projectKey,
        format,
        file,
        attachmentsZip: format === 'JSON' ? attachmentsZip : null,
        dryRun,
      })
    },
    onSuccess: (data, variables) => {
      setSubmitError(null)
      setJobId(data.jobId)
      setLastDryRun(variables.dryRun)
      setPhase('tracking')
    },
    onError: (error: unknown) => {
      setSubmitError(resolveImportError(error))
    },
  })

  // 폴링 — jobId가 null이면 비활성 (enabled 내부 게이트, useImportJobPolling 참고)
  const { data: pollData, isError: pollIsError } = useImportJobPolling(jobId, true)

  // 폴링 종단 상태(COMPLETED/FAILED) → done 전환
  useEffect(() => {
    if (phase !== 'tracking') return
    if (pollData?.status === 'COMPLETED' || pollData?.status === 'FAILED') {
      setPhase('done')
    }
  }, [phase, pollData?.status])

  const downloadMutation = useMutation({
    mutationFn: () => downloadImportErrorLog(jobId as string),
    onSuccess: ({ blob, filename }) => {
      triggerBlobDownload(blob, filename)
    },
    onError: (error: unknown) => {
      setSubmitError(resolveImportError(error))
    },
  })

  function handleBackToForm(clearFile: boolean): void {
    setJobId(null)
    setSubmitError(null)
    setPhase('form')
    if (clearFile) {
      setFile(null)
      setAttachmentsZip(null)
    }
  }

  function handleFormatChange(next: ImportFormat): void {
    setFormat(next)
    if (next === 'CSV') {
      // FR-2a: CSV로 되돌리면 선택된 zip을 초기화한다 (무시되는 파일이 남지 않도록)
      setAttachmentsZip(null)
    }
  }

  if (phase === 'tracking') {
    return (
      <TrackingPhase
        pollData={pollData}
        pollIsError={pollIsError}
        onRetry={() => {
          handleBackToForm(false)
        }}
      />
    )
  }

  if (phase === 'done') {
    return (
      <DonePhase
        pollData={pollData}
        lastDryRun={lastDryRun}
        submitError={submitError}
        isDownloadPending={downloadMutation.isPending}
        isResubmitPending={submitMutation.isPending}
        onDownload={() => {
          downloadMutation.mutate()
        }}
        onResubmitReal={() => {
          submitMutation.mutate({ dryRun: false })
        }}
        onBackToForm={handleBackToForm}
      />
    )
  }

  return (
    <FormPhase
      format={format}
      onFormatChange={handleFormatChange}
      onFileChange={setFile}
      onZipChange={setAttachmentsZip}
      canSubmit={file !== null && !submitMutation.isPending}
      isPendingDryRun={submitMutation.isPending && submitMutation.variables?.dryRun === true}
      isPendingReal={submitMutation.isPending && submitMutation.variables?.dryRun === false}
      submitError={submitError}
      onSubmitDryRun={() => {
        submitMutation.mutate({ dryRun: true })
      }}
      onSubmitReal={() => {
        submitMutation.mutate({ dryRun: false })
      }}
    />
  )
}
