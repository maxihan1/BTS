// CSV 필드 매핑 단계 — 소스 헤더별 대상 필드 Select + 샘플 미리보기 + validate 호출 (FR-IM-02 D6 Task 3)
import type { JSX, ReactNode } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ApiError } from '@/api/client'
import { validateFieldMapping } from '@/api/import-mappings'
import type { FieldMappingEntry, MappingIssue, MappingValidationResponse } from '@/api/import-mappings'
import { suggestFieldMappings, FIELD_MAPPING_IGNORE } from './field-mapping-suggest'
import type { TargetFieldCatalogEntry } from './field-mapping-suggest'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** FieldMappingStep Props */
export interface FieldMappingStepProps {
  /** 검증 대상 Import 작업 UUID */
  readonly jobId: string
  /** 업로드 파일의 소스 헤더 목록 (표시 순서) */
  readonly sourceFields: string[]
  /** 샘플 미리보기 행 — sourceFields와 같은 순서로 정렬된 값. 빈 배열이면 표를 생략한다 */
  readonly sampleRows: string[][]
  /** 대상 필드 카탈로그 (analyze 응답에서 전달됨) */
  readonly targetFields: TargetFieldCatalogEntry[]
  /** 현재 매핑 값 — 소스 필드 → 대상 필드 key(또는 IGNORE). 부모가 소유하는 controlled 값 */
  readonly value: Record<string, string>
  /** 매핑 값이 바뀔 때 호출된다 */
  readonly onChange: (next: Record<string, string>) => void
  /** 검증을 통과하면 확정된 필드 매핑 목록과 함께 호출된다 */
  readonly onNext: (entries: FieldMappingEntry[]) => void
  /** 이전 단계로 돌아가는 콜백. 있으면 [이전] 버튼을 노출한다 */
  readonly onBack?: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 이슈 귀속 헬퍼
// backend MappingValidator KDoc — field는 대부분 소스 필드명, DUPLICATE_TARGET은 대상 key,
// 전역 이슈(SUMMARY_NOT_MAPPED)는 field=null. sourceField와 일치하는 것만 행에 귀속하고
// 나머지(전역+대상 key 귀속)는 공통 영역에 렌더한다.
// ─────────────────────────────────────────────────────────────────────────────

/** field가 주어진 sourceField와 일치하는 이슈만 반환한다 (행별 귀속) */
function issuesForSourceField(issues: MappingIssue[], sourceField: string): MappingIssue[] {
  return issues.filter((issue) => issue.field === sourceField)
}

/** field가 null이거나 어떤 sourceField와도 일치하지 않는 이슈를 전역 이슈로 반환한다 */
function globalIssues(issues: MappingIssue[], sourceFields: string[]): MappingIssue[] {
  const known = new Set(sourceFields)
  return issues.filter((issue) => issue.field == null || !known.has(issue.field))
}

/** ApiError body에서 ProblemDetail detail을 추출한다 (ImportForm resolveImportError 미러) */
function resolveMappingError(error: unknown): string {
  if (error instanceof ApiError) {
    const body = error.body
    if (body !== null && typeof body === 'object') {
      const detail = (body as Record<string, unknown>)['detail']
      if (typeof detail === 'string') return detail
    }
  }
  return '매핑 검증 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.'
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 인라인 렌더 — 에러(role=alert)/경고(muted, 비차단)
// ─────────────────────────────────────────────────────────────────────────────

function ErrorAlert({ children }: { readonly children: ReactNode }): JSX.Element {
  return (
    <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
      {children}
    </p>
  )
}

/** errors/warnings 목록을 톤에 맞춰 렌더한다. 빈 배열이면 null */
function IssueList({
  issues,
  tone,
}: {
  readonly issues: MappingIssue[]
  readonly tone: 'error' | 'warning'
}): JSX.Element | null {
  if (issues.length === 0) return null

  if (tone === 'error') {
    return (
      <div className="mt-1 space-y-1">
        {issues.map((issue, i) => (
          <ErrorAlert key={`${issue.code}-${issue.field ?? 'global'}-${i}`}>{issue.message}</ErrorAlert>
        ))}
      </div>
    )
  }

  return (
    <div className="mt-1 space-y-1">
      {issues.map((issue, i) => (
        <p key={`${issue.code}-${issue.field ?? 'global'}-${i}`} className="text-xs text-muted-foreground">
          {issue.message}
        </p>
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 소스 헤더별 매핑 row — DR-2 시각 위계(required=강조, 추천=muted 배지, 미매핑=primary 강조)
// ─────────────────────────────────────────────────────────────────────────────

interface FieldMappingRowProps {
  readonly sourceField: string
  readonly targetFields: TargetFieldCatalogEntry[]
  readonly selectedKey: string
  readonly isSuggested: boolean
  readonly errors: MappingIssue[]
  readonly warnings: MappingIssue[]
  readonly onSelect: (nextKey: string) => void
}

function FieldMappingRow({
  sourceField,
  targetFields,
  selectedKey,
  isSuggested,
  errors,
  warnings,
  onSelect,
}: FieldMappingRowProps): JSX.Element {
  const isIgnored = selectedKey === FIELD_MAPPING_IGNORE
  const isRequiredTarget = targetFields.find((target) => target.key === selectedKey)?.required === true

  return (
    <div
      data-testid={`field-mapping-row-${sourceField}`}
      className={cn(
        'rounded-md border p-2',
        isIgnored ? 'border-primary/30 bg-primary/5' : 'border-border',
      )}
    >
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <span
            className={cn(
              'truncate text-sm',
              isRequiredTarget ? 'font-semibold text-foreground' : 'text-foreground',
            )}
          >
            {sourceField}
          </span>
          {isSuggested && <span className="text-xs text-muted-foreground">추천</span>}
          {isIgnored && <span className="text-xs font-medium text-primary">매핑 필요</span>}
        </div>

        <Select value={selectedKey} onValueChange={onSelect}>
          <SelectTrigger aria-label={`${sourceField} 매핑 대상`} className="w-full sm:w-48">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={FIELD_MAPPING_IGNORE}>매핑 안 함</SelectItem>
            {targetFields.map((target) => (
              <SelectItem key={target.key} value={target.key}>
                {target.label}
                {target.required && <span className="ml-1 text-primary">필수</span>}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <IssueList issues={errors} tone="error" />
      <IssueList issues={warnings} tone="warning" />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 샘플 행 미리보기 표 — sourceFields 순서
// ─────────────────────────────────────────────────────────────────────────────

function SampleRowsTable({
  sourceFields,
  sampleRows,
}: {
  readonly sourceFields: string[]
  readonly sampleRows: string[][]
}): JSX.Element | null {
  if (sampleRows.length === 0) return null

  return (
    <div className="overflow-auto rounded-md border border-border">
      <table className="w-full border-collapse text-left text-sm">
        <thead className="border-b border-border bg-muted/80">
          <tr>
            {sourceFields.map((field) => (
              <th
                key={field}
                className="px-3 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground"
              >
                {field}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sampleRows.map((row, rowIndex) => (
            <tr key={rowIndex} className="border-b border-border last:border-b-0">
              {row.map((cell, cellIndex) => (
                <td key={cellIndex} className="px-3 py-2 text-muted-foreground">
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FieldMappingStep (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CSV Import 필드 매핑 단계.
 *
 * - 소스 헤더별로 대상 필드(카탈로그 + "매핑 안 함") Select를 렌더한다. value/onChange로 controlled — 초기값
 *   채우기는 부모 책임(`suggestFieldMappings` 결과를 최초 value로 준다).
 * - 샘플 행 미리보기 표(sourceFields 순서). sampleRows가 빈 배열(JSON 등)이면 생략한다.
 * - [다음] 클릭 시 `validateFieldMapping(jobId, entries)`를 호출한다(react-query mutation).
 *   검증 중에는 버튼이 비활성화되고 "검증 중..." 라벨을 보여준다(DR-1).
 *   valid=false면 errors를 인라인 표시하고 `onNext`를 호출하지 않는다. warnings는 비차단으로 표시한다.
 *   valid=true면 `onNext(entries)`를 호출한다.
 * - DR-2 시각 위계: required 대상에 매핑된 행은 강조, 아직 초기 추천값 그대로인 행은 "추천" muted 배지,
 *   미매핑(IGNORE) 행은 `primary` 톤으로 눈에 띄게 한다 — `foreground`/`muted-foreground`/`primary`만 사용.
 */
export const FieldMappingStep = ({
  jobId,
  sourceFields,
  sampleRows,
  targetFields,
  value,
  onChange,
  onNext,
  onBack,
}: FieldMappingStepProps): JSX.Element => {
  const suggestions = suggestFieldMappings(sourceFields, targetFields)

  const validateMutation = useMutation<MappingValidationResponse, unknown, FieldMappingEntry[]>({
    mutationFn: (entries) => validateFieldMapping(jobId, entries),
  })

  function buildEntries(): FieldMappingEntry[] {
    return sourceFields.map((sourceField) => ({
      sourceField,
      targetField: value[sourceField] ?? FIELD_MAPPING_IGNORE,
    }))
  }

  function handleNext(): void {
    const entries = buildEntries()
    validateMutation.mutate(entries, {
      onSuccess: (result) => {
        if (result.valid) {
          onNext(entries)
        }
      },
    })
  }

  const errors = validateMutation.data?.errors ?? []
  const warnings = validateMutation.data?.warnings ?? []

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        {sourceFields.map((sourceField) => {
          const selectedKey = value[sourceField] ?? FIELD_MAPPING_IGNORE
          return (
            <FieldMappingRow
              key={sourceField}
              sourceField={sourceField}
              targetFields={targetFields}
              selectedKey={selectedKey}
              isSuggested={
                suggestions[sourceField] === selectedKey && selectedKey !== FIELD_MAPPING_IGNORE
              }
              errors={issuesForSourceField(errors, sourceField)}
              warnings={issuesForSourceField(warnings, sourceField)}
              onSelect={(nextKey) => {
                onChange({ ...value, [sourceField]: nextKey })
              }}
            />
          )
        })}
      </div>

      <SampleRowsTable sourceFields={sourceFields} sampleRows={sampleRows} />

      <IssueList issues={globalIssues(errors, sourceFields)} tone="error" />
      <IssueList issues={globalIssues(warnings, sourceFields)} tone="warning" />

      {validateMutation.isError && <ErrorAlert>{resolveMappingError(validateMutation.error)}</ErrorAlert>}

      <div className="flex justify-end gap-2">
        {onBack !== undefined && (
          <Button type="button" variant="outline" size="sm" onClick={onBack}>
            이전
          </Button>
        )}
        <Button type="button" size="sm" disabled={validateMutation.isPending} onClick={handleNext}>
          {validateMutation.isPending ? '검증 중...' : '다음'}
        </Button>
      </div>
    </div>
  )
}
