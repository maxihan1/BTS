// 이슈 검색 결과 CSV/XLSX 내보내기 다이얼로그 — FR-EX-01/FR-EX-02 D6 4단계 상태머신
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { exportIssues, submitExportJob, downloadExportJobResult } from '@/api/search'
import { triggerBlobDownload } from '@/lib/download'
import { ApiError } from '@/api/client'
import { useExportJobPolling } from '@/hooks/use-export-job-polling'

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 정의 — 백엔드 ExportColumn enum과 1:1 대응 (9개)
// backend 정본: search-export-import ExportColumn.kt — 변경 시 동반 수정
// ─────────────────────────────────────────────────────────────────────────────

const EXPORT_COLUMNS = [
  { token: 'KEY', label: 'Key' },
  { token: 'SUMMARY', label: 'Summary' },
  { token: 'TYPE', label: 'Type' },
  { token: 'STATUS', label: 'Status' },
  { token: 'ASSIGNEE_ID', label: 'Assignee ID' },
  { token: 'PRIORITY', label: 'Priority' },
  { token: 'PRIORITY_NAME', label: 'Priority Name' },
  { token: 'PROJECT', label: 'Project' },
  { token: 'UPDATED_AT', label: 'Updated At' },
] as const satisfies ReadonlyArray<{ readonly token: string; readonly label: string }>

const ALL_COLUMN_TOKENS: string[] = EXPORT_COLUMNS.map(({ token }) => token)

// ─────────────────────────────────────────────────────────────────────────────
// errorCode → 한국어 실패 사유 매핑
// backend 정본: search-export-import SearchErrorCode — 변경 시 동반 수정
// ─────────────────────────────────────────────────────────────────────────────

const EXPORT_FAILURE_MESSAGES: Record<string, string> = {
  SEARCH_EXPORT_LIMIT_EXCEEDED: '결과가 10만건을 초과합니다.',
  SEARCH_EXPORT_STORAGE_ERROR: '저장 중 오류가 발생했습니다.',
}

function getFailureMessage(errorCode: string | null | undefined): string {
  if (errorCode !== null && errorCode !== undefined) {
    return EXPORT_FAILURE_MESSAGES[errorCode] ?? '알 수 없는 오류가 발생했습니다.'
  }
  return '알 수 없는 오류가 발생했습니다.'
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 판별 / 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ApiError body에서 ProblemDetail `detail` 문자열을 추출한다.
 * detail이 없으면 일반 메시지를 반환한다.
 */
function resolveExportError(error: unknown): string {
  if (error instanceof ApiError) {
    const body = error.body
    if (body !== null && typeof body === 'object') {
      const detail = (body as Record<string, unknown>)['detail']
      if (typeof detail === 'string') return detail
    }
  }
  return '내보내기 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.'
}

/**
 * 동기 Export 상한 초과 에러인지 판별한다.
 * CONCERN-A: ApiError instanceof 가드 후 body 타입 가드. as any 금지.
 */
function isLimitExceeded(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  const body = error.body
  if (body === null || typeof body !== 'object') return false
  const code = (body as Record<string, unknown>)['errorCode']
  return code === 'SEARCH_EXPORT_LIMIT_EXCEEDED'
}

/**
 * ApiError body에서 resultCount를 추출한다. 없으면 null.
 */
function extractResultCount(error: ApiError): number | null {
  const body = error.body
  if (body === null || typeof body !== 'object') return null
  const count = (body as Record<string, unknown>)['resultCount']
  return typeof count === 'number' ? count : null
}

// ─────────────────────────────────────────────────────────────────────────────
// 진행률 상태 라벨
// ─────────────────────────────────────────────────────────────────────────────

const STATUS_LABELS: Record<string, string> = {
  PENDING: '대기 중',
  RUNNING: '내보내는 중...',
  COMPLETED: '완료',
  FAILED: '실패',
}

// ─────────────────────────────────────────────────────────────────────────────
// ExportForm — 4단계 상태 머신 내부 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface ExportFormProps {
  readonly projectKey: string
  readonly query: string
  readonly onClose: () => void
}

/**
 * 내보내기 폼 내부 컴포넌트 — 4단계 상태 머신.
 *
 * ```
 * phase: 'form' | 'confirmAsync' | 'tracking' | 'done'
 *
 * form         ──[제출, 동기 성공]──────────────▶ (blob 다운로드 + onClose)
 * form         ──[제출, LIMIT_EXCEEDED]─────────▶ confirmAsync (resultCount 보관)
 * form         ──[제출, 기타 에러]──────────────▶ form (submitError 표시)
 * confirmAsync ──[백그라운드 내보내기]──────────▶ tracking (POST → jobId)
 * confirmAsync ──[취소]────────────────────────▶ form
 * tracking     ──[폴링 COMPLETED]──────────────▶ done (downloadReady)
 * tracking     ──[폴링 FAILED]─────────────────▶ done (errorCode)
 * done         ──[다운로드]────────────────────▶ GET /{id}/download → blob 다운로드
 * done(실패)   ──[다시 시도]────────────────────▶ form + jobId null 초기화
 * ```
 *
 * Radix Dialog Content가 닫히면 자동 언마운트되어 useExportJobPolling cleanup이 실행된다 (FR-7).
 * submitError는 이 컴포넌트가 소유 (dialog-submiterror-ownership-dead-path 교훈).
 */
function ExportForm({ projectKey, query, onClose }: ExportFormProps): JSX.Element {
  const [format, setFormat] = useState<'CSV' | 'XLSX'>('CSV')
  const [selectedColumns, setSelectedColumns] = useState<string[]>(ALL_COLUMN_TOKENS.slice())
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [phase, setPhase] = useState<'form' | 'confirmAsync' | 'tracking' | 'done'>('form')
  const [jobId, setJobId] = useState<string | null>(null)
  const [resultCount, setResultCount] = useState<number | null>(null)

  // 동기 Export mutation (FR-EX-01 경로 보존)
  const syncMutation = useMutation({
    mutationFn: () => exportIssues({ projectKey, query, format, columns: selectedColumns }),
    onSuccess: ({ blob, filename }) => {
      setSubmitError(null)
      triggerBlobDownload(blob, filename)
      onClose()
    },
    onError: (error: unknown) => {
      if (isLimitExceeded(error)) {
        // LIMIT_EXCEEDED → confirmAsync 전환 (FR-EX-02 자동분기)
        setResultCount(extractResultCount(error as ApiError))
        setSubmitError(null)
        setPhase('confirmAsync')
      } else {
        setSubmitError(resolveExportError(error))
      }
    },
  })

  // 비동기 잡 제출 mutation
  const asyncMutation = useMutation({
    mutationFn: () => submitExportJob({ projectKey, query, format, columns: selectedColumns }),
    onSuccess: ({ jobId: id }) => {
      setJobId(id)
      setPhase('tracking')
    },
    onError: (error: unknown) => {
      // EC2: 잡 생성도 실패한 경우 confirmAsync에 에러 표시
      setSubmitError(resolveExportError(error))
    },
  })

  // 폴링 — jobId가 null이면 비활성 (enabled 내부 게이트)
  const { data: pollData, isError: pollIsError } = useExportJobPolling(jobId, true)

  // 폴링 종단 상태 → done 전환
  useEffect(() => {
    if (phase !== 'tracking') return
    if (pollData?.status === 'COMPLETED' || pollData?.status === 'FAILED') {
      setPhase('done')
    }
  }, [phase, pollData?.status])

  // 다운로드 mutation
  const downloadMutation = useMutation({
    mutationFn: () => downloadExportJobResult(jobId as string),
    onSuccess: ({ blob, filename }) => {
      triggerBlobDownload(blob, filename)
    },
    onError: (error: unknown) => {
      setSubmitError(resolveExportError(error))
    },
  })

  function toggleColumn(token: string): void {
    setSelectedColumns((prev) =>
      prev.includes(token) ? prev.filter((t) => t !== token) : [...prev, token],
    )
  }

  // ── confirmAsync 단계 ────────────────────────────────────────────────────

  if (phase === 'confirmAsync') {
    const countText = resultCount !== null ? resultCount.toLocaleString() : '대량'

    return (
      <div>
        {/* 상태 전환 고지 (role=status aria-live=polite — 스크린리더 폴라이트 고지) */}
        <p role="status" aria-live="polite" className="mb-4 text-sm text-foreground">
          검색 결과{' '}
          <strong>
            {countText}건
          </strong>
          은 대용량입니다. 백그라운드로 내보내시겠습니까?
        </p>

        {/* 잡 생성 실패 에러 (EC2) */}
        {submitError !== null && (
          <p
            role="alert"
            className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {submitError}
          </p>
        )}

        <div className="mt-4 flex justify-end gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              setSubmitError(null)
              setPhase('form')
            }}
          >
            취소
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={asyncMutation.isPending}
            onClick={() => {
              asyncMutation.mutate()
            }}
          >
            {asyncMutation.isPending ? '접수 중...' : '백그라운드 내보내기'}
          </Button>
        </div>
      </div>
    )
  }

  // ── tracking 단계 ────────────────────────────────────────────────────────

  if (phase === 'tracking') {
    const progress = pollData?.progress ?? 0
    const statusLabel = STATUS_LABELS[pollData?.status ?? 'PENDING'] ?? '처리 중...'

    // C2: 폴링 HTTP 에러(403/404/재시도 소진 등) — spec EC5 "폴링 지속 실패 시 에러 상태 표시(다시 시도)"
    if (pollIsError) {
      return (
        <div>
          <p
            role="alert"
            className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            내보내기 상태를 조회하지 못했습니다. 잠시 후 다시 시도하세요.
          </p>
          <div className="mt-2 flex justify-end gap-2">
            <Button type="button" variant="outline" size="sm" onClick={onClose}>
              닫기
            </Button>
            {/* done(FAILED) 다시 시도와 동일한 경로 재사용 */}
            <Button
              type="button"
              size="sm"
              onClick={() => {
                setJobId(null)
                setSubmitError(null)
                setPhase('form')
              }}
            >
              다시 시도
            </Button>
          </div>
        </div>
      )
    }

    return (
      <div>
        {/* 상태 라벨 (role=status — 폴링 중 스크린리더 갱신) */}
        <p role="status" aria-live="polite" className="mb-2 text-sm font-medium text-foreground">
          {statusLabel}
        </p>

        {/* 진행률 바 — NFR-2 접근성 */}
        <div
          role="progressbar"
          aria-valuenow={progress}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`내보내기 진행률 ${progress}%`}
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

  // ── done 단계 ────────────────────────────────────────────────────────────

  if (phase === 'done') {
    const isSuccess = pollData?.status === 'COMPLETED' && pollData.downloadReady
    const rowCountText =
      pollData?.rowCount !== null && pollData?.rowCount !== undefined
        ? `${pollData.rowCount.toLocaleString()}행`
        : ''

    return (
      <div>
        {isSuccess ? (
          /* 완료 — role=status aria-live=polite — 스크린리더 갱신 */
          <p role="status" aria-live="polite" className="mb-4 text-sm text-foreground">
            ✓ 완료{rowCountText !== '' ? ` (${rowCountText})` : ''}
          </p>
        ) : (
          /* 실패 — role=alert */
          <p
            role="alert"
            className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            내보내기에 실패했습니다. 사유: {getFailureMessage(pollData?.errorCode)}
          </p>
        )}

        {/* C1: 다운로드 실패 에러 표시 — dialog-submiterror-ownership-dead-path 교훈 적용
            done 단계는 submitError를 렌더하지 않아 다운로드 실패 시 사용자가 메시지를 못 봤음 */}
        {submitError !== null && (
          <p
            role="alert"
            className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {submitError}
          </p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            닫기
          </Button>
          {isSuccess ? (
            <Button
              type="button"
              size="sm"
              disabled={downloadMutation.isPending}
              onClick={() => {
                downloadMutation.mutate()
              }}
            >
              {downloadMutation.isPending ? '다운로드 중...' : '다운로드'}
            </Button>
          ) : (
            // CONCERN-D: jobId null 초기화로 stale 폴링 차단
            <Button
              type="button"
              size="sm"
              onClick={() => {
                setJobId(null)
                setSubmitError(null)
                setPhase('form')
              }}
            >
              다시 시도
            </Button>
          )}
        </div>
      </div>
    )
  }

  // ── form 단계 (기본) ─────────────────────────────────────────────────────

  const canSubmit = selectedColumns.length > 0 && !syncMutation.isPending

  return (
    <div>
      {/* 형식 선택 */}
      <fieldset className="mb-4">
        <legend className="mb-2 text-sm font-medium">형식</legend>
        <div className="flex items-center gap-4">
          {(['CSV', 'XLSX'] as const).map((fmt) => (
            <label key={fmt} className="flex cursor-pointer items-center gap-2">
              <input
                type="radio"
                name="export-format"
                value={fmt}
                checked={format === fmt}
                onChange={() => {
                  setFormat(fmt)
                }}
                className="accent-primary"
              />
              <span className="text-sm">{fmt}</span>
            </label>
          ))}
        </div>
      </fieldset>

      {/* 컬럼 선택 */}
      <fieldset className="mb-4">
        <legend className="mb-2 text-sm font-medium">컬럼</legend>
        <div className="grid grid-cols-2 gap-1 sm:grid-cols-3">
          {EXPORT_COLUMNS.map(({ token, label }) => (
            <label key={token} className="flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={selectedColumns.includes(token)}
                onChange={() => {
                  toggleColumn(token)
                }}
                aria-label={label}
                className="accent-primary"
              />
              <span className="text-sm">{label}</span>
            </label>
          ))}
        </div>
      </fieldset>

      {/* 제출 오류 인라인 표시 (role=alert — 스크린리더 즉시 고지) */}
      {submitError !== null && (
        <p
          role="alert"
          className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {submitError}
        </p>
      )}

      {/* 액션 버튼 */}
      <div className="mt-2 flex justify-end gap-2">
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          취소
        </Button>
        <Button
          type="button"
          size="sm"
          disabled={!canSubmit}
          onClick={() => {
            syncMutation.mutate()
          }}
        >
          {syncMutation.isPending ? '내보내는 중...' : '내보내기'}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ExportDialog Props */
export interface ExportDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 열림 상태 변경 핸들러 */
  readonly onOpenChange: (open: boolean) => void
  /** 내보낼 이슈의 프로젝트 키 */
  readonly projectKey: string
  /** 현재 AQL 쿼리 문자열 */
  readonly query: string
}

// ─────────────────────────────────────────────────────────────────────────────
// ExportDialog (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 검색 결과 내보내기 Dialog.
 *
 * 4단계 상태 머신 (ExportForm 내부 관리):
 * - form: 형식(CSV/XLSX) + 9컬럼 선택 UI
 * - confirmAsync: 대용량(>1만건) 비동기 제안 단계
 * - tracking: 잡 진행률 폴링 단계 (1500ms 간격)
 * - done: 완료(다운로드) 또는 실패(다시 시도) 단계
 *
 * SaveFilterDialog Radix Dialog 패턴 답습 (같은 /search 페이지 — FR-SR-03 선례).
 * Content unmount 시 useExportJobPolling cleanup 자동 실행 (FR-7 NFR-3).
 */
export const ExportDialog = ({
  open,
  onOpenChange,
  projectKey,
  query,
}: ExportDialogProps): JSX.Element => {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>내보내기</DialogTitle>
        </DialogHeader>

        <ExportForm
          projectKey={projectKey}
          query={query}
          onClose={() => {
            onOpenChange(false)
          }}
        />
      </DialogContent>
    </Dialog>
  )
}
