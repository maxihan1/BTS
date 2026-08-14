// Import 매핑 마법사 컨테이너 — 상태머신·동적 스킵·검토·폴링·dry-run 재적용 (FR-IM-02 D6 Task-6)
import type { JSX, ReactNode, ChangeEvent } from 'react'
import { useState, useEffect, useRef } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { ApiError } from '@/api/client'
import {
  analyzeImport,
  collectUsers,
  collectValues,
  confirmMapping,
  importMappingFailureMessage,
} from '@/api/import-mappings'
import type {
  ImportAnalysisResponse,
  FieldMappingEntry,
  UserMappingEntry,
  ValueMappingEntry,
  ConfirmMappingParams,
  UserCollectionResponse,
  ValueCollectionResponse,
  MappingIssue,
} from '@/api/import-mappings'
import { suggestFieldMappings, FIELD_MAPPING_IGNORE } from './field-mapping-suggest'
import { FieldMappingStep } from './FieldMappingStep'
import { UserMappingStep } from './UserMappingStep'
import { ValueMappingStep } from './ValueMappingStep'
import { useImportJobPolling } from '@/hooks/use-import-job-polling'
import { downloadImportErrorLog, importFailureMessage } from '@/api/imports'
import type { ImportJobStatus } from '@/api/imports'
import { triggerBlobDownload } from '@/lib/download'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

type WizardStep = 'upload' | 'fields' | 'users' | 'values' | 'review' | 'tracking' | 'done'
type ImportFormat = 'CSV' | 'JSON'

/** collectUsers/collectValues 공유 mutation 변수 */
interface CollectVariables {
  readonly jobId: string
  readonly entries: FieldMappingEntry[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const STEP_LABELS: Record<WizardStep, string> = {
  upload: '업로드',
  fields: '필드 매핑',
  users: '사용자 매핑',
  values: '값 매핑',
  review: '검토',
  tracking: '진행',
  done: '완료',
}

const TRACKING_STATUS_LABELS: Record<string, string> = {
  PENDING: '대기 중',
  RUNNING: '가져오는 중...',
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 — 매핑 대상 스텝 계산 / 페이로드 조립 / 에러 해석
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재까지 알려진 정보로 동적 stepper에 노출할 단계 목록을 계산한다(DR-7).
 * 아직 수집하지 않은 단계(collectedUsers/collectedValues=null)는 일단 노출하고,
 * 수집 결과가 빈 목록으로 확인되면 그 순간부터 제외한다.
 */
function computeVisibleSteps(
  format: ImportFormat,
  collectedUsers: UserCollectionResponse['users'] | null,
  collectedValues: ValueCollectionResponse['fields'] | null,
): WizardStep[] {
  const steps: WizardStep[] = ['upload']
  if (format === 'CSV') steps.push('fields')
  if (collectedUsers === null || collectedUsers.length > 0) steps.push('users')
  if (collectedValues === null || countValues(collectedValues) > 0) steps.push('values')
  steps.push('review', 'tracking', 'done')
  return steps
}

/** 값 매핑 대상 필드 전체에 걸친 소스 값 총 개수 */
function countValues(fields: ValueCollectionResponse['fields']): number {
  return fields.reduce((sum, field) => sum + field.values.length, 0)
}

/** 두 필드 매핑 목록이(순서 포함) 동일한지 비교한다 — G2 재수집 감지용 */
function isSameFieldEntries(a: FieldMappingEntry[], b: FieldMappingEntry[]): boolean {
  if (a.length !== b.length) return false
  return a.every((entry, i) => entry.sourceField === b[i]?.sourceField && entry.targetField === b[i]?.targetField)
}

/** collectUsers 결과로부터 sourceIdentifier → 추천 사용자 override 시드 map을 만든다 */
function seedUserOverrides(users: UserCollectionResponse['users']): Record<string, string | null> {
  const result: Record<string, string | null> = {}
  for (const entry of users) result[entry.sourceIdentifier] = entry.suggestedUserId ?? null
  return result
}

/** collectValues 결과로부터 `targetField::sourceValue` → 추천 값 override 시드 map을 만든다 */
function seedValueOverrides(fields: ValueCollectionResponse['fields']): Record<string, string> {
  const result: Record<string, string> = {}
  for (const field of fields) {
    for (const entry of field.values) {
      result[`${field.targetField}::${entry.sourceValue}`] = entry.suggestedTargetValue ?? ''
    }
  }
  return result
}

/**
 * 재수집된 사용자 목록과 이전 override map을 병합한다(게이트2 리뷰 BLOCKER 수정).
 * 생존 키(재수집 결과에도 존재)는 사용자가 지정한 이전 override를 그대로 유지하고,
 * 신규 키만 추천값으로 시드한다. 사라진 키(재수집 결과에 없음)는 폐기한다 — 통째 재-seed는
 * 사용자가 명시적으로 선택한 override(예: "미매핑")를 조용히 되돌리는 회귀였다.
 */
function mergeUserOverrides(
  prev: Record<string, string | null>,
  users: UserCollectionResponse['users'],
): Record<string, string | null> {
  const seeded = seedUserOverrides(users)
  const next: Record<string, string | null> = {}
  for (const entry of users) {
    next[entry.sourceIdentifier] = Object.prototype.hasOwnProperty.call(prev, entry.sourceIdentifier)
      ? (prev[entry.sourceIdentifier] ?? null)
      : (seeded[entry.sourceIdentifier] ?? null)
  }
  return next
}

/** 값 매핑 override map 버전의 mergeUserOverrides — 키는 `targetField::sourceValue` */
function mergeValueOverrides(
  prev: Record<string, string>,
  fields: ValueCollectionResponse['fields'],
): Record<string, string> {
  const seeded = seedValueOverrides(fields)
  const next: Record<string, string> = {}
  for (const field of fields) {
    for (const entry of field.values) {
      const key = `${field.targetField}::${entry.sourceValue}`
      next[key] = Object.prototype.hasOwnProperty.call(prev, key) ? (prev[key] ?? '') : (seeded[key] ?? '')
    }
  }
  return next
}

/** 사용자 매핑 override map을 confirmMapping 페이로드 항목으로 변환한다 */
function buildUserMappingsPayload(
  users: UserCollectionResponse['users'],
  overrides: Record<string, string | null>,
): UserMappingEntry[] {
  return users.map((entry) => ({
    sourceIdentifier: entry.sourceIdentifier,
    targetUserId: Object.prototype.hasOwnProperty.call(overrides, entry.sourceIdentifier)
      ? (overrides[entry.sourceIdentifier] ?? null)
      : (entry.suggestedUserId ?? null),
  }))
}

/** 값 매핑 override map을 confirmMapping 페이로드 항목으로 변환한다(effective 값이 빈 문자열이면 제외) */
function buildValueMappingsPayload(
  fields: ValueCollectionResponse['fields'],
  overrides: Record<string, string>,
): ValueMappingEntry[] {
  const result: ValueMappingEntry[] = []
  for (const field of fields) {
    for (const entry of field.values) {
      const key = `${field.targetField}::${entry.sourceValue}`
      const effective = overrides[key] ?? entry.suggestedTargetValue ?? ''
      if (effective !== '') {
        result.push({ targetField: field.targetField, sourceValue: entry.sourceValue, targetValue: effective })
      }
    }
  }
  return result
}

/** confirmMapping/reapply 호출 페이로드를 조립한다 */
function buildConfirmParams(
  fieldEntries: FieldMappingEntry[],
  collectedUsers: UserCollectionResponse['users'] | null,
  userOverrides: Record<string, string | null>,
  collectedValues: ValueCollectionResponse['fields'] | null,
  valueOverrides: Record<string, string>,
  dryRun: boolean,
): ConfirmMappingParams {
  return {
    fieldMappings: fieldEntries,
    dryRun,
    userMappings: buildUserMappingsPayload(collectedUsers ?? [], userOverrides),
    valueMappings: buildValueMappingsPayload(collectedValues ?? [], valueOverrides),
  }
}

/**
 * ApiError body에서 ProblemDetail detail 또는 errorCode를 추출해 한글 메시지로 변환한다.
 * 매핑 마법사 전용 에러코드 테이블(importMappingFailureMessage)을 공유 util로 경유한다 —
 * 화면별 인라인 매핑을 만들지 않는다(회귀 방지: error-key 매핑은 공유 util 경유).
 */
function resolveMappingError(error: unknown): string {
  if (error instanceof ApiError) {
    const body = error.body
    if (body !== null && typeof body === 'object') {
      const record = body as Record<string, unknown>
      const detail = record['detail']
      if (typeof detail === 'string') return detail
      const errorCode = record['errorCode']
      if (typeof errorCode === 'string') return importMappingFailureMessage(errorCode)
    }
  }
  return importMappingFailureMessage(undefined)
}

/** value가 MappingIssue 형태(`code`/`message` 문자열 필드 보유)인지 구조적으로 확인한다 */
function isMappingIssueLike(value: unknown): value is MappingIssue {
  if (value === null || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record['code'] === 'string' && typeof record['message'] === 'string'
}

/**
 * confirm 실패(422 등) ApiError body의 ProblemDetail `errors[]` 확장 속성을 추출한다(게이트2 리뷰
 * 높은 CONCERN 수정). 어느 소스값/식별자가 왜 실패했는지(`field` 포함)를 사용자에게 보여주기 위함 —
 * 기존에는 이 목록을 버리고 제네릭 문구만 노출해 사용자가 무엇을 고쳐야 할지 알 수 없었다.
 */
function extractConfirmErrors(error: unknown): MappingIssue[] {
  if (!(error instanceof ApiError)) return []
  const body = error.body
  if (body === null || typeof body !== 'object') return []
  const errors = (body as Record<string, unknown>)['errors']
  if (!Array.isArray(errors)) return []
  return errors.filter(isMappingIssueLike)
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 인라인 렌더
// ─────────────────────────────────────────────────────────────────────────────

function ErrorAlert({ children }: { readonly children: ReactNode }): JSX.Element {
  return (
    <p role="alert" className="mb-4 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
      {children}
    </p>
  )
}

/**
 * confirm 실패 시 ProblemDetail `errors[]` 각 사유를 role=alert 목록으로 표시한다(게이트2 리뷰
 * 높은 CONCERN 수정). field가 있으면 어느 값/필드가 문제인지 함께 노출한다.
 */
function ConfirmErrorList({ errors }: { readonly errors: MappingIssue[] }): JSX.Element | null {
  if (errors.length === 0) return null
  return (
    <div className="mb-4 space-y-1">
      {errors.map((issue, i) => (
        <p
          key={`${issue.code}-${issue.field ?? 'global'}-${i}`}
          role="alert"
          className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {issue.field != null ? `값/필드: ${issue.field} — ${issue.message}` : issue.message}
        </p>
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// WizardStepper — DR-7 동적 단계 표시(aria-current + 스킵 단계 제외)
// ─────────────────────────────────────────────────────────────────────────────

function WizardStepper({
  steps,
  current,
}: {
  readonly steps: WizardStep[]
  readonly current: WizardStep
}): JSX.Element {
  return (
    <ol aria-label="Import 매핑 진행 단계" className="mb-4 flex flex-wrap gap-2 text-xs">
      {steps.map((step) => (
        <li
          key={step}
          aria-current={step === current ? 'step' : undefined}
          className={cn(
            'rounded-full border px-2 py-1',
            step === current ? 'border-primary font-medium text-primary' : 'border-border text-muted-foreground',
          )}
        >
          {STEP_LABELS[step]}
        </li>
      ))}
    </ol>
  )
}

/** stepper + 단계별 본문을 함께 렌더하는 레이아웃 래퍼 */
function WizardLayout({
  steps,
  current,
  children,
}: {
  readonly steps: WizardStep[]
  readonly current: WizardStep
  readonly children: ReactNode
}): JSX.Element {
  return (
    <div>
      <WizardStepper steps={steps} current={current} />
      {children}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// UploadStep — 형식/파일 선택 + 분석 트리거
// ─────────────────────────────────────────────────────────────────────────────

interface UploadStepProps {
  readonly format: ImportFormat
  readonly onFormatChange: (format: ImportFormat) => void
  readonly onFileChange: (file: File | null) => void
  readonly canAnalyze: boolean
  readonly isAnalyzing: boolean
  readonly submitError: string | null
  readonly onAnalyze: () => void
}

function UploadStep({
  format,
  onFormatChange,
  onFileChange,
  canAnalyze,
  isAnalyzing,
  submitError,
  onAnalyze,
}: UploadStepProps): JSX.Element {
  return (
    <div>
      <fieldset className="mb-4">
        <legend className="mb-2 text-sm font-medium">형식</legend>
        <div className="flex items-center gap-4">
          {(['CSV', 'JSON'] as const).map((fmt) => (
            <label key={fmt} className="flex cursor-pointer items-center gap-2">
              <input
                type="radio"
                name="import-mapping-format"
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
        <label htmlFor="import-mapping-file-input" className="mb-2 block text-sm font-medium">
          분석할 파일
        </label>
        <Input
          id="import-mapping-file-input"
          type="file"
          accept=".csv,.json"
          aria-label="분석할 파일"
          onChange={(e: ChangeEvent<HTMLInputElement>) => {
            onFileChange(e.target.files?.[0] ?? null)
          }}
        />
      </div>

      {submitError !== null && <ErrorAlert>{submitError}</ErrorAlert>}

      <div className="mt-2 flex justify-end">
        <Button type="button" size="sm" disabled={!canAnalyze} onClick={onAnalyze}>
          {isAnalyzing ? '분석 중...' : '분석'}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ReviewStep — 매핑 요약 + 검증/실행 확정
// ─────────────────────────────────────────────────────────────────────────────

interface ReviewStepProps {
  readonly fieldCount: number
  readonly userCount: number
  readonly valueCount: number
  readonly isPending: boolean
  readonly pendingDryRun: boolean | null
  readonly submitError: string | null
  readonly confirmErrors: MappingIssue[]
  readonly onConfirm: (dryRun: boolean) => void
  readonly onBack: () => void
}

function ReviewStep({
  fieldCount,
  userCount,
  valueCount,
  isPending,
  pendingDryRun,
  submitError,
  confirmErrors,
  onConfirm,
  onBack,
}: ReviewStepProps): JSX.Element {
  return (
    <div>
      <p className="mb-4 text-sm text-foreground">
        필드 매핑 {fieldCount}건 · 사용자 매핑 {userCount}건 · 값 매핑 {valueCount}건을 확정합니다.
      </p>
      {confirmErrors.length > 0 ? (
        <ConfirmErrorList errors={confirmErrors} />
      ) : (
        submitError !== null && <ErrorAlert>{submitError}</ErrorAlert>
      )}
      <div className="mt-2 flex justify-end gap-2">
        <Button type="button" variant="outline" size="sm" disabled={isPending} onClick={onBack}>
          이전
        </Button>
        <Button
          type="button"
          size="sm"
          disabled={isPending}
          onClick={() => {
            onConfirm(true)
          }}
        >
          {isPending && pendingDryRun === true ? '확정 중...' : '검증만 실행'}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={isPending}
          onClick={() => {
            onConfirm(false)
          }}
        >
          {isPending && pendingDryRun === false ? '확정 중...' : '가져오기 실행'}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// TrackingStep — 진행률 폴링 단계 (ImportForm TrackingPhase 미러)
// ─────────────────────────────────────────────────────────────────────────────

function TrackingStep({
  pollData,
  pollIsError,
}: {
  readonly pollData: ImportJobStatus | undefined
  readonly pollIsError: boolean
}): JSX.Element {
  if (pollIsError) {
    return <ErrorAlert>Import 상태를 조회하지 못했습니다. 잠시 후 다시 시도하세요.</ErrorAlert>
  }

  const progress = pollData?.progress ?? 0
  const statusLabel = TRACKING_STATUS_LABELS[pollData?.status ?? 'PENDING'] ?? '처리 중...'

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
        className="h-2 w-full overflow-hidden rounded-full bg-muted"
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
// DoneStep — 완료/실패 + dry-run 재적용(G1/DR-5)
// ─────────────────────────────────────────────────────────────────────────────

interface DoneStepProps {
  readonly pollData: ImportJobStatus | undefined
  readonly lastDryRun: boolean
  readonly submitError: string | null
  readonly isDownloadPending: boolean
  readonly isReapplyPending: boolean
  readonly onDownload: () => void
  readonly onReapplyReal: () => void
  readonly onReset: () => void
}

function DoneStep({
  pollData,
  lastDryRun,
  submitError,
  isDownloadPending,
  isReapplyPending,
  onDownload,
  onReapplyReal,
  onReset,
}: DoneStepProps): JSX.Element {
  const isFailed = pollData?.status === 'FAILED'

  if (isFailed) {
    return (
      <div>
        <ErrorAlert>Import에 실패했습니다. 사유: {importFailureMessage(pollData?.errorCode)}</ErrorAlert>
        {submitError !== null && <ErrorAlert>{submitError}</ErrorAlert>}
        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" size="sm" onClick={onReset}>
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

      {submitError !== null && <ErrorAlert>{submitError}</ErrorAlert>}

      <div className="mt-2 flex flex-wrap justify-end gap-2">
        {showErrorLog && (
          <Button type="button" variant="outline" size="sm" disabled={isDownloadPending} onClick={onDownload}>
            {isDownloadPending ? '다운로드 중...' : '에러 로그 다운로드'}
          </Button>
        )}
        {lastDryRun && (
          <Button type="button" size="sm" disabled={isReapplyPending} onClick={onReapplyReal}>
            {isReapplyPending ? '동일 매핑으로 실제 Import 준비 중...' : '이 매핑으로 실제 가져오기'}
          </Button>
        )}
        <Button type="button" variant="outline" size="sm" onClick={onReset}>
          처음으로
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// WizardStepContent — 단계별 렌더 스위치(REFACTOR: 메인 컴포넌트에서 분리)
// state/handlers 두 객체로 나눠 props 폭발을 완화한다.
// ─────────────────────────────────────────────────────────────────────────────

interface WizardStepContentState {
  readonly step: WizardStep
  readonly visibleSteps: WizardStep[]
  readonly format: ImportFormat
  readonly analysis: ImportAnalysisResponse | null
  readonly jobId: string | null
  readonly fieldMappings: Record<string, string>
  readonly fieldEntries: FieldMappingEntry[]
  readonly userOverrides: Record<string, string | null>
  readonly valueOverrides: Record<string, string>
  readonly collectedUsers: UserCollectionResponse['users'] | null
  readonly collectedValues: ValueCollectionResponse['fields'] | null
  readonly lastDryRun: boolean
  readonly submitError: string | null
  readonly confirmErrors: MappingIssue[]
  readonly recollectNotice: boolean
  readonly pollData: ImportJobStatus | undefined
  readonly pollIsError: boolean
  readonly canAnalyze: boolean
  readonly isAnalyzing: boolean
  readonly isConfirmPending: boolean
  readonly pendingDryRun: boolean | null
  readonly isDownloadPending: boolean
  readonly isReapplyPending: boolean
}

interface WizardStepContentHandlers {
  readonly onFormatChange: (format: ImportFormat) => void
  readonly onFileChange: (file: File | null) => void
  readonly onAnalyze: () => void
  readonly onFieldMappingsChange: (next: Record<string, string>) => void
  readonly onFieldsNext: (entries: FieldMappingEntry[]) => void
  readonly onFieldsBack: () => void
  readonly onUserOverridesChange: (next: Record<string, string | null>) => void
  readonly onUsersNext: () => void
  readonly onUsersBack: () => void
  readonly onValueOverridesChange: (next: Record<string, string>) => void
  readonly onValuesNext: () => void
  readonly onValuesBack: () => void
  readonly onConfirm: (dryRun: boolean) => void
  readonly onReviewBack: () => void
  readonly onDownload: () => void
  readonly onReapplyReal: () => void
  readonly onReset: () => void
}

function WizardStepContent({
  state,
  handlers,
}: {
  readonly state: WizardStepContentState
  readonly handlers: WizardStepContentHandlers
}): JSX.Element {
  const { step, visibleSteps } = state

  if (step === 'upload') {
    return (
      <WizardLayout steps={visibleSteps} current={step}>
        <UploadStep
          format={state.format}
          onFormatChange={handlers.onFormatChange}
          onFileChange={handlers.onFileChange}
          canAnalyze={state.canAnalyze}
          isAnalyzing={state.isAnalyzing}
          submitError={state.submitError}
          onAnalyze={handlers.onAnalyze}
        />
      </WizardLayout>
    )
  }

  if (step === 'fields') {
    return (
      <WizardLayout steps={visibleSteps} current={step}>
        {state.analysis !== null && state.jobId !== null ? (
          <FieldMappingStep
            jobId={state.jobId}
            sourceFields={state.analysis.sourceFields.map((f) => f.name)}
            sampleRows={state.analysis.sampleRows}
            targetFields={state.analysis.targetFields}
            value={state.fieldMappings}
            onChange={handlers.onFieldMappingsChange}
            onNext={handlers.onFieldsNext}
            onBack={handlers.onFieldsBack}
          />
        ) : (
          <ErrorAlert>분석 정보를 불러오지 못했습니다. 처음부터 다시 시도하세요.</ErrorAlert>
        )}
      </WizardLayout>
    )
  }

  if (step === 'users') {
    return (
      <WizardLayout steps={visibleSteps} current={step}>
        {state.recollectNotice && (
          <p className="mb-2 text-xs text-muted-foreground">필드 매핑이 바뀌어 작성자를 다시 수집했습니다.</p>
        )}
        <UserMappingStep
          users={state.collectedUsers ?? []}
          value={state.userOverrides}
          onChange={handlers.onUserOverridesChange}
          onNext={handlers.onUsersNext}
          onBack={handlers.onUsersBack}
        />
      </WizardLayout>
    )
  }

  if (step === 'values') {
    return (
      <WizardLayout steps={visibleSteps} current={step}>
        {state.recollectNotice && (
          <p className="mb-2 text-xs text-muted-foreground">필드 매핑이 바뀌어 값을 다시 수집했습니다.</p>
        )}
        <ValueMappingStep
          fields={state.collectedValues ?? []}
          value={state.valueOverrides}
          onChange={handlers.onValueOverridesChange}
          onNext={handlers.onValuesNext}
          onBack={handlers.onValuesBack}
        />
      </WizardLayout>
    )
  }

  if (step === 'review') {
    return (
      <WizardLayout steps={visibleSteps} current={step}>
        <ReviewStep
          fieldCount={state.fieldEntries.filter((entry) => entry.targetField !== FIELD_MAPPING_IGNORE).length}
          userCount={
            buildUserMappingsPayload(state.collectedUsers ?? [], state.userOverrides).filter(
              (entry) => entry.targetUserId !== null,
            ).length
          }
          valueCount={buildValueMappingsPayload(state.collectedValues ?? [], state.valueOverrides).length}
          isPending={state.isConfirmPending}
          pendingDryRun={state.pendingDryRun}
          submitError={state.submitError}
          confirmErrors={state.confirmErrors}
          onConfirm={handlers.onConfirm}
          onBack={handlers.onReviewBack}
        />
      </WizardLayout>
    )
  }

  if (step === 'tracking') {
    return (
      <WizardLayout steps={visibleSteps} current={step}>
        <TrackingStep pollData={state.pollData} pollIsError={state.pollIsError} />
      </WizardLayout>
    )
  }

  return (
    <WizardLayout steps={visibleSteps} current={step}>
      <DoneStep
        pollData={state.pollData}
        lastDryRun={state.lastDryRun}
        submitError={state.submitError}
        isDownloadPending={state.isDownloadPending}
        isReapplyPending={state.isReapplyPending}
        onDownload={handlers.onDownload}
        onReapplyReal={handlers.onReapplyReal}
        onReset={handlers.onReset}
      />
    </WizardLayout>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ImportMappingWizard Props */
export interface ImportMappingWizardProps {
  /** Import 대상 프로젝트 키 */
  readonly projectKey: string
  /**
   * 「잃을 것이 있는 상태」인지를 부모에게 보고한다 (부채 매핑 16 · EC6).
   *
   * 근거·계약은 `ImportForm` 의 같은 prop KDoc 이 정본이다. **이름·의미·required 여부가 같아야**
   * 부모가 한쪽만 배선하는 것을 타입이 잡는다 — 게이트가 페이지 1곳이라 두 모드가 같은 보호를 받는다.
   */
  readonly onBusyChange: (busy: boolean) => void
}

/**
 * 지금 잃을 것이 있는가.
 *
 * 업로드 단계에서 파일을 고르기만 해도 참이다 — 분석 전이어도 되돌리면 다시 골라야 한다.
 * 컴포넌트 밖에 두어 동결된 줄수 베이스라인(`Arrow function` 288)을 건드리지 않는다.
 *
 * @param file 선택된 분석 대상 파일. 없으면 `null`
 * @param step 마법사 단계
 */
function isWizardBusy(file: File | null, step: WizardStep): boolean {
  return file !== null || step !== 'upload'
}

// ─────────────────────────────────────────────────────────────────────────────
// ImportMappingWizard (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CSV/JSON Import 매핑 마법사 — analyze → (CSV만) 필드 매핑 → 사용자 매핑 → 값 매핑 → 검토·확정 →
 * 진행률 폴링(FR-IM-01 재사용) → 완료 7단계 상태머신.
 *
 * - 사용자/값 매핑은 collect 결과가 빈 목록이면 자동으로 스킵한다(G4).
 * - 필드 매핑 변경 후 재진입 시 하위(사용자) 수집을 다시 트리거하고 인라인 통지를 보여준다(G2/DR-4).
 * - 검토에서 dry-run 완료 후 "이 매핑으로 실제 가져오기"는 보존한 file+매핑으로 재-analyze 후
 *   새 jobId로 즉시 confirm(dryRun=false)한다 — 마법사를 처음부터 재순회하지 않는다(G1/DR-5).
 * - DR-6: analyze 결과 sourceFields가 빈 배열이면 에러를 표시하고 업로드 단계에 머무른다.
 */
export const ImportMappingWizard = ({
  projectKey,
  onBusyChange,
}: ImportMappingWizardProps): JSX.Element => {
  const [step, setStep] = useState<WizardStep>('upload')
  const [format, setFormat] = useState<ImportFormat>('CSV')
  const [file, setFile] = useState<File | null>(null)
  const [jobId, setJobId] = useState<string | null>(null)
  const [analysis, setAnalysis] = useState<ImportAnalysisResponse | null>(null)
  const [fieldMappings, setFieldMappings] = useState<Record<string, string>>({})
  const [fieldEntries, setFieldEntries] = useState<FieldMappingEntry[]>([])
  const [userOverrides, setUserOverrides] = useState<Record<string, string | null>>({})
  const [valueOverrides, setValueOverrides] = useState<Record<string, string>>({})
  const [collectedUsers, setCollectedUsers] = useState<UserCollectionResponse['users'] | null>(null)
  const [collectedValues, setCollectedValues] = useState<ValueCollectionResponse['fields'] | null>(null)
  const [lastDryRun, setLastDryRun] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [confirmErrors, setConfirmErrors] = useState<MappingIssue[]>([])
  const [recollectNotice, setRecollectNotice] = useState(false)
  const hasVisitedUsersRef = useRef(false)

  // 진행 중 신호 (부채 매핑 16 · EC6). deps 규율은 `ImportForm` 의 같은 훅 주석이 정본이다.
  useEffect(() => {
    onBusyChange(isWizardBusy(file, step))
  }, [file, step, onBusyChange])

  const analyzeMutation = useMutation<ImportAnalysisResponse, unknown, File>({
    mutationFn: (selectedFile) => analyzeImport({ projectKey, format, file: selectedFile }),
  })
  const collectUsersMutation = useMutation<UserCollectionResponse, unknown, CollectVariables>({
    mutationFn: ({ jobId: id, entries }) => collectUsers(id, entries),
  })
  const collectValuesMutation = useMutation<ValueCollectionResponse, unknown, CollectVariables>({
    mutationFn: ({ jobId: id, entries }) => collectValues(id, entries),
  })
  const confirmMutation = useMutation<ImportJobStatus, unknown, boolean>({
    mutationFn: (dryRun) => {
      if (jobId === null) return Promise.reject(new Error('작업이 없습니다.'))
      return confirmMapping(jobId, buildConfirmParams(fieldEntries, collectedUsers, userOverrides, collectedValues, valueOverrides, dryRun))
    },
    onSuccess: (_data, dryRun) => {
      setSubmitError(null)
      setConfirmErrors([])
      setLastDryRun(dryRun)
      setStep('tracking')
    },
    onError: (error: unknown) => {
      // errors[] 확장속성이 있으면 그 사유들만 보여주고, 없을 때만 제네릭 detail 폴백을 쓴다
      // (게이트2 리뷰 높은 CONCERN 수정 — 중복 알림 방지).
      const issues = extractConfirmErrors(error)
      setConfirmErrors(issues)
      setSubmitError(issues.length > 0 ? null : resolveMappingError(error))
    },
  })
  const reapplyMutation = useMutation<ImportJobStatus, unknown, File>({
    mutationFn: async (selectedFile) => {
      const newAnalysis = await analyzeImport({ projectKey, format, file: selectedFile })
      return confirmMapping(
        newAnalysis.jobId,
        buildConfirmParams(fieldEntries, collectedUsers, userOverrides, collectedValues, valueOverrides, false),
      )
    },
    onSuccess: (data) => {
      setSubmitError(null)
      setJobId(data.jobId)
      setLastDryRun(false)
      setStep('tracking')
    },
    onError: (error: unknown) => {
      setSubmitError(resolveMappingError(error))
    },
  })
  const downloadMutation = useMutation<{ blob: Blob; filename: string }, unknown, void>({
    mutationFn: () => {
      if (jobId === null) return Promise.reject(new Error('작업이 없습니다.'))
      return downloadImportErrorLog(jobId)
    },
    onSuccess: ({ blob, filename }) => {
      triggerBlobDownload(blob, filename)
    },
    onError: (error: unknown) => {
      setSubmitError(resolveMappingError(error))
    },
  })

  // 폴링은 tracking 단계에서만 활성화한다 — jobId는 confirm 이전(fields/users/values/review)에도
  // 이미 세팅돼 있어 상수 true로 켜두면 confirm 전 미등록 jobId로 즉시 GET을 쏘아 404를 받고,
  // retry:false 환경에서 쿼리가 error로 굳어 refetchInterval이 영구 false를 반환한다(회귀 방지 —
  // confirm 이후 재등록된 잡을 되살릴 refetch/invalidate가 없어 tracking 화면이 영원히 고착).
  const { data: pollData, isError: pollIsError } = useImportJobPolling(jobId, step === 'tracking')

  useEffect(() => {
    if (step !== 'tracking') return
    if (pollData?.status === 'COMPLETED' || pollData?.status === 'FAILED') {
      setStep('done')
    }
  }, [step, pollData?.status])

  /** collectUsers 진입점 — 결과가 빈 목록이면 값 매핑으로 자동 스킵한다(G4) */
  function enterUsersStep(jobIdParam: string, entries: FieldMappingEntry[]): void {
    collectUsersMutation.mutate(
      { jobId: jobIdParam, entries },
      {
        onSuccess: (result) => {
          setSubmitError(null)
          setCollectedUsers(result.users)
          hasVisitedUsersRef.current = true
          if (result.users.length === 0) {
            enterValuesStep(jobIdParam, entries)
            return
          }
          // 재수집 시 생존 키는 이전 override를 유지하고, 신규 키만 추천값으로 시드한다
          // (게이트2 리뷰 BLOCKER 수정 — 통째 재-seed는 사용자 override를 조용히 되돌렸다).
          setUserOverrides((prev) => mergeUserOverrides(prev, result.users))
          setStep('users')
        },
        onError: (error: unknown) => {
          setSubmitError(resolveMappingError(error))
        },
      },
    )
  }

  /** collectValues 진입점 — 결과가 빈 목록이면 검토 단계로 자동 스킵한다(G4) */
  function enterValuesStep(jobIdParam: string, entries: FieldMappingEntry[]): void {
    collectValuesMutation.mutate(
      { jobId: jobIdParam, entries },
      {
        onSuccess: (result) => {
          setSubmitError(null)
          setCollectedValues(result.fields)
          if (countValues(result.fields) === 0) {
            setStep('review')
            return
          }
          // 값 매핑도 동형 — 생존 키(동일 targetField::sourceValue)는 이전 override를 유지한다.
          setValueOverrides((prev) => mergeValueOverrides(prev, result.fields))
          setStep('values')
        },
        onError: (error: unknown) => {
          setSubmitError(resolveMappingError(error))
        },
      },
    )
  }

  function handleAnalyze(): void {
    if (file === null) return
    setSubmitError(null)
    analyzeMutation.mutate(file, {
      onSuccess: (data) => {
        if (data.sourceFields.length === 0) {
          setSubmitError('분석 결과 인식된 필드가 없습니다. 파일 형식을 확인하세요.')
          return
        }
        setAnalysis(data)
        setJobId(data.jobId)
        if (format === 'CSV') {
          setFieldMappings(suggestFieldMappings(data.sourceFields.map((f) => f.name), data.targetFields))
          setFieldEntries([])
          setStep('fields')
          return
        }
        setFieldEntries([])
        enterUsersStep(data.jobId, [])
      },
      onError: (error: unknown) => {
        setSubmitError(resolveMappingError(error))
      },
    })
  }

  function handleFieldsNext(entries: FieldMappingEntry[]): void {
    if (jobId === null) return
    setRecollectNotice(hasVisitedUsersRef.current && !isSameFieldEntries(fieldEntries, entries))
    setFieldEntries(entries)
    enterUsersStep(jobId, entries)
  }

  function handleUsersNext(): void {
    if (jobId === null) return
    enterValuesStep(jobId, fieldEntries)
  }

  /** 검토 확정 클릭 — 재시도 시 이전 confirm 실패 사유가 남아있지 않도록 지우고 mutate한다 */
  function handleConfirm(dryRun: boolean): void {
    setConfirmErrors([])
    confirmMutation.mutate(dryRun)
  }

  /**
   * 검토 [이전] — 직전에 실제로 거친 활성 단계로 복귀한다(게이트2 리뷰 높은 CONCERN 수정).
   * 값 매핑이 존재하면 values, 아니면 사용자 매핑이 존재하면 users, 아니면(CSV) fields,
   * 그것도 아니면 upload로 복귀한다. 422 등 confirm 실패 후 새로고침 없이 되돌아갈 수단이 없던
   * dead-end를 해소한다.
   */
  function handleReviewBack(): void {
    setSubmitError(null)
    setConfirmErrors([])
    if (collectedValues !== null && countValues(collectedValues) > 0) {
      setStep('values')
      return
    }
    if (collectedUsers !== null && collectedUsers.length > 0) {
      setStep('users')
      return
    }
    setStep(format === 'CSV' ? 'fields' : 'upload')
  }

  function handleReapplyReal(): void {
    if (file === null) return
    reapplyMutation.mutate(file)
  }

  function handleReset(clearFile: boolean): void {
    setStep('upload')
    setJobId(null)
    setAnalysis(null)
    setFieldMappings({})
    setFieldEntries([])
    setUserOverrides({})
    setValueOverrides({})
    setCollectedUsers(null)
    setCollectedValues(null)
    setLastDryRun(false)
    setSubmitError(null)
    setConfirmErrors([])
    setRecollectNotice(false)
    hasVisitedUsersRef.current = false
    if (clearFile) setFile(null)
  }

  const visibleSteps = computeVisibleSteps(format, collectedUsers, collectedValues)

  return (
    <WizardStepContent
      state={{
        step,
        visibleSteps,
        format,
        analysis,
        jobId,
        fieldMappings,
        fieldEntries,
        userOverrides,
        valueOverrides,
        collectedUsers,
        collectedValues,
        lastDryRun,
        submitError,
        confirmErrors,
        recollectNotice,
        pollData,
        pollIsError,
        canAnalyze: file !== null && !analyzeMutation.isPending,
        isAnalyzing: analyzeMutation.isPending,
        isConfirmPending: confirmMutation.isPending,
        pendingDryRun: confirmMutation.isPending ? (confirmMutation.variables ?? null) : null,
        isDownloadPending: downloadMutation.isPending,
        isReapplyPending: reapplyMutation.isPending,
      }}
      handlers={{
        onFormatChange: setFormat,
        onFileChange: setFile,
        onAnalyze: handleAnalyze,
        onFieldMappingsChange: setFieldMappings,
        onFieldsNext: handleFieldsNext,
        onFieldsBack: () => {
          setStep('upload')
        },
        onUserOverridesChange: setUserOverrides,
        onUsersNext: handleUsersNext,
        onUsersBack: () => {
          setStep(format === 'CSV' ? 'fields' : 'upload')
        },
        onValueOverridesChange: setValueOverrides,
        onValuesNext: () => {
          setStep('review')
        },
        onValuesBack: () => {
          setStep('users')
        },
        onConfirm: handleConfirm,
        onReviewBack: handleReviewBack,
        onDownload: () => {
          downloadMutation.mutate()
        },
        onReapplyReal: handleReapplyReal,
        onReset: () => {
          handleReset(pollData?.status === 'COMPLETED' && !lastDryRun)
        },
      }}
    />
  )
}
