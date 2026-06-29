// 이슈 검색 결과 CSV/XLSX 내보내기 다이얼로그 — FR-EX-01 D6 Task-7
import type { JSX } from 'react'
import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { exportIssues } from '@/api/search'
import { triggerBlobDownload } from '@/lib/download'
import { ApiError } from '@/api/client'

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
// 에러 메시지 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ApiError body에서 ProblemDetail `detail` 문자열을 추출한다.
 * 상한초과(SEARCH_EXPORT_LIMIT_EXCEEDED) 시 서버 제공 자연어 메시지를 그대로 표시.
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

// ─────────────────────────────────────────────────────────────────────────────
// ExportForm — 내부 폼 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface ExportFormProps {
  readonly projectKey: string
  readonly query: string
  readonly onClose: () => void
}

/**
 * 내보내기 폼 내부 컴포넌트.
 *
 * Radix Dialog Content가 닫히면 자동 언마운트되어 형식/컬럼 상태가 초기화된다
 * (key prop 없이 자연스러운 초기화).
 * submitError는 이 컴포넌트가 소유 (dialog-submiterror-ownership-dead-path 교훈).
 */
function ExportForm({ projectKey, query, onClose }: ExportFormProps): JSX.Element {
  const [format, setFormat] = useState<'CSV' | 'XLSX'>('CSV')
  const [selectedColumns, setSelectedColumns] = useState<string[]>(ALL_COLUMN_TOKENS.slice())
  const [submitError, setSubmitError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => exportIssues({ projectKey, query, format, columns: selectedColumns }),
    onSuccess: ({ blob, filename }) => {
      setSubmitError(null)
      triggerBlobDownload(blob, filename)
      onClose()
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

  const canSubmit = selectedColumns.length > 0 && !mutation.isPending

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
            mutation.mutate()
          }}
        >
          {mutation.isPending ? '내보내는 중...' : '내보내기'}
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
 * - CSV(UTF-8 BOM) 또는 XLSX 형식 선택 (기본값: CSV)
 * - 9개 컬럼 중 부분 선택 가능 (기본값: 전체 선택)
 * - 내보내기 성공 시 Content-Disposition 헤더의 파일명으로 브라우저 다운로드
 * - 상한초과(SEARCH_EXPORT_LIMIT_EXCEEDED) 에러 시 ProblemDetail detail 메시지 표시
 *
 * SaveFilterDialog Radix Dialog 패턴 답습 (같은 /search 페이지 — FR-SR-03 선례).
 */
export const ExportDialog = ({
  open,
  onOpenChange,
  projectKey,
  query,
}: ExportDialogProps): JSX.Element => {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="mb-4 text-base font-semibold">
            내보내기
          </DialogPrimitive.Title>

          <ExportForm
            projectKey={projectKey}
            query={query}
            onClose={() => {
              onOpenChange(false)
            }}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
