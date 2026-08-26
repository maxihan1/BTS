// 워크플로우 전환별 전환 규칙(validator) 목록 + 추가/편집/삭제 섹션 — isSystemAdmin 게이팅 (FR-WF-06 D6)
import type { JSX } from 'react'
import { useState } from 'react'
import { useAuthUser } from '@/auth/authStore'
import { ValidatorApiError, validatorConfigFormSchema } from '@/api/validators'
import type { ValidatorPhase, ValidatorResponse } from '@/api/validators'
import {
  useAddValidator,
  useDeleteValidator,
  useUpdateValidator,
  useValidators,
} from '@/hooks/use-validators'
import { ValidatorFormDialog } from '@/components/workflow/ValidatorFormDialog'
import type { ValidatorFormValues } from '@/components/workflow/ValidatorFormDialog'
import type { WorkflowTransitionView } from '@/components/workflow/workflow.types'
import {
  validatorDeleteButtonLabel,
  validatorEditButtonLabel,
  validatorErrorMessage,
  validatorLabels,
} from '@/i18n/validator-labels'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ValidatorConfigSection 컴포넌트 props */
export interface ValidatorConfigSectionProps {
  /** 워크플로우 키 */
  workflowKey: string
  /** 상위(workflows.$key)가 로드한 전환 목록 */
  transitions: WorkflowTransitionView[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 표시 유틸리티
// ─────────────────────────────────────────────────────────────────────────────

/** 요약 문자열 최대 길이 — 넘치면 말줄임하고 전체는 `title` 로 남긴다. */
const SUMMARY_MAX = 60

/**
 * 행의 config 를 표시용 문자열로 줄인다.
 *
 * 아는 type 이면 폼 스키마의 키만 뽑아 `키=값` 으로 잇고, 모르는 type 이면 원본 JSON 을 보인다 —
 * 화면이 type 별 키 목록을 따로 들지 않는다(제약 C1).
 *
 * @param validator 규칙 행.
 * @return 표시 문자열.
 */
function configSummary(validator: ValidatorResponse): string {
  const schema = validatorConfigFormSchema(validator.type)
  if (schema === undefined) {
    return JSON.stringify(validator.config)
  }
  const parts = Object.keys(schema.shape)
    .filter((key) => validator.config[key] !== undefined)
    .map((key) => `${key}=${String(validator.config[key])}`)
  return parts.length > 0 ? parts.join(' · ') : JSON.stringify(validator.config)
}

/**
 * 편집이 막힌 이유를 돌려준다.
 *
 * ★ 판정은 **응답의 `editable` 뿐**이다. `type === 'CustomExpression'` 같은 조건을 여기 적으면
 * backend 허용 목록의 두 번째 사본이 되고, 네 번째 편집 가능 type 이 생기는 날 화면만 조용히
 * 낡는다 — 이 PR 이 막으려던 결함이 그것이다(부채 2 · 제약 C1).
 *
 * @param validator 규칙 행.
 * @return 사유 문구. 편집할 수 있으면 `undefined`.
 */
function notEditableReason(validator: ValidatorResponse): string | undefined {
  if (validator.editable) {
    return undefined
  }
  return validator.phase === null
    ? validatorLabels.list.notEditableBrokenReason
    : validatorLabels.list.notEditableTypeReason
}

/** 에러에서 backend errorCode 를 꺼낸다. 봉투가 아니면 `null` 이다. */
function errorCodeOf(error: unknown): string | null {
  return error instanceof ValidatorApiError ? error.errorCode : null
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 평가 시점 배지.
 *
 * **색만으로 뜻을 전하지 않는다** — 문구를 함께 싣는다(NFR 접근성). 값은 응답의 `phase` 이고
 * 화면은 `type → phase` 표를 만들지 않는다.
 */
function PhaseBadge({ phase }: { phase: ValidatorPhase | null }): JSX.Element {
  if (phase === null) {
    return <Badge variant="red">{validatorLabels.list.phase.unknown}</Badge>
  }
  return (
    <Badge variant={phase === 'EXECUTION' ? 'yellow' : 'blue'}>
      {validatorLabels.list.phase[phase]}
    </Badge>
  )
}

interface ValidatorTableProps {
  validators: ValidatorResponse[]
  onEditClick: (validator: ValidatorResponse) => void
  onDeleteClick: (validator: ValidatorResponse) => void
  isDeleting: boolean
}

/**
 * 규칙 목록 테이블.
 *
 * **편집 불가 행을 숨기지 않는다** — 반쪽 목록은 관리자를 속인다(FR-2). 대신 편집 버튼을 잠그고
 * 이유를 행에 남긴다(툴팁 단독 금지).
 */
function ValidatorTable({
  validators,
  onEditClick,
  onDeleteClick,
  isDeleting,
}: ValidatorTableProps): JSX.Element {
  return (
    <table className="w-full border-collapse text-left text-sm">
      <thead className="border-b border-border bg-muted/50">
        <tr>
          {[
            validatorLabels.list.typeColumn,
            validatorLabels.list.configColumn,
            validatorLabels.list.phaseColumn,
            validatorLabels.list.actionColumn,
          ].map((column) => (
            <th
              key={column}
              className="px-4 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground"
            >
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {validators.map((validator, index) => {
          const summary = configSummary(validator)
          const reason = notEditableReason(validator)
          const rowNumber = index + 1
          return (
            <tr key={validator.id} className="border-b border-border transition-colors hover:bg-muted/30">
              <td className="px-4 py-2.5 font-mono text-xs text-foreground">{validator.type}</td>
              <td
                className="max-w-[280px] truncate px-4 py-2.5 text-muted-foreground"
                title={summary.length > SUMMARY_MAX ? summary : undefined}
              >
                {summary.length > SUMMARY_MAX ? `${summary.slice(0, SUMMARY_MAX)}…` : summary}
              </td>
              <td className="px-4 py-2.5">
                <PhaseBadge phase={validator.phase} />
              </td>
              <td className="px-4 py-2.5">
                <div className="flex items-center gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="xs"
                    onClick={() => onEditClick(validator)}
                    disabled={!validator.editable}
                    aria-label={validatorEditButtonLabel(validator.type, rowNumber)}
                    title={reason}
                    className={cn('rounded', validator.editable ? 'text-primary hover:bg-primary/10' : '')}
                  >
                    {validatorLabels.list.editButton}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="xs"
                    onClick={() => onDeleteClick(validator)}
                    disabled={isDeleting}
                    aria-label={validatorDeleteButtonLabel(validator.type, rowNumber)}
                    className="rounded text-destructive hover:bg-destructive/10"
                  >
                    {validatorLabels.list.deleteButton}
                  </Button>
                </div>
                {reason !== undefined && (
                  <p className="mt-1 max-w-[240px] text-xs text-muted-foreground">{reason}</p>
                )}
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

/** 목록 로딩 스켈레톤 — 빈 `<div/>` 를 돌려주지 않는다(상태 3종 중 로딩). */
function ValidatorTableSkeleton(): JSX.Element {
  return (
    <div className="space-y-2 px-4 py-4" aria-label={validatorLabels.list.loadingLabel} role="status">
      <Skeleton className="h-4 w-1/3" />
      <Skeleton className="h-4 w-2/3" />
      <Skeleton className="h-4 w-1/2" />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — isSystemAdmin 게이팅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 전환별 전환 규칙 설정 섹션.
 *
 * - `isSystemAdmin=true` 인 경우에만 렌더한다(아니면 `null`). 형제 `PostActionConfigSection` 승계.
 * - 전환을 고르면 그 전환의 규칙 목록을 로딩/에러/빈/목록 4갈래로 그린다.
 * - 편집 가능 여부는 **응답의 `editable`** 만 본다(제약 C1).
 */
export function ValidatorConfigSection({
  workflowKey,
  transitions,
}: ValidatorConfigSectionProps): JSX.Element | null {
  const user = useAuthUser()
  if (user?.isSystemAdmin !== true) {
    return null
  }
  return <ValidatorConfigSectionContent workflowKey={workflowKey} transitions={transitions} />
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 (게이팅 통과 후) — hook 규칙 준수를 위해 분리
// ─────────────────────────────────────────────────────────────────────────────

interface DialogState {
  open: boolean
  mode: 'create' | 'edit'
  /** edit 모드 대상 id. create 면 빈 문자열. */
  editingId: string
  /** edit 모드 프리필 — `type` 과 baseline `config`. */
  initialValue: ValidatorFormValues | undefined
}

const CLOSED_DIALOG: DialogState = {
  open: false,
  mode: 'create',
  editingId: '',
  initialValue: undefined,
}

function ValidatorConfigSectionContent({
  workflowKey,
  transitions,
}: ValidatorConfigSectionProps): JSX.Element {
  // 선택 값은 전환 id(UUID) 다 — 그 문자열이 경로 세그먼트로 그대로 나간다(제약 C4).
  const [selectedTxId, setSelectedTxId] = useState<string>('')
  const [dialog, setDialog] = useState<DialogState>(CLOSED_DIALOG)
  const [serverError, setServerError] = useState<string | undefined>(undefined)
  const [deleteTarget, setDeleteTarget] = useState<ValidatorResponse | null>(null)
  const [deleteError, setDeleteError] = useState<string | undefined>(undefined)

  const { data: validators = [], isLoading, isError, refetch } = useValidators(workflowKey, selectedTxId)
  const addMutation = useAddValidator(workflowKey, selectedTxId)
  const updateMutation = useUpdateValidator(workflowKey, selectedTxId, dialog.editingId)
  const deleteMutation = useDeleteValidator(workflowKey, selectedTxId)

  /** 추가 다이얼로그를 연다. */
  function handleAddClick(): void {
    setServerError(undefined)
    setDialog({ open: true, mode: 'create', editingId: '', initialValue: undefined })
  }

  /** 편집 다이얼로그를 연다. baseline 은 로드한 config 그대로다(제약 C6). */
  function handleEditClick(validator: ValidatorResponse): void {
    setServerError(undefined)
    setDialog({
      open: true,
      mode: 'edit',
      editingId: validator.id,
      initialValue: { type: validator.type, config: validator.config },
    })
  }

  /** 저장 — create 는 최대 displayOrder + 1, edit 는 로드한 값 그대로다(제약 C7). */
  function handleDialogSubmit(values: ValidatorFormValues): void {
    setServerError(undefined)
    const callbacks = {
      onSuccess: () => setDialog(CLOSED_DIALOG),
      onError: (error: unknown) => setServerError(validatorErrorMessage(errorCodeOf(error))),
    }
    if (dialog.mode === 'create') {
      const nextOrder =
        validators.length > 0 ? Math.max(...validators.map((v) => v.displayOrder)) + 1 : 0
      addMutation.mutate({ ...values, displayOrder: nextOrder }, callbacks)
      return
    }
    const current = validators.find((v) => v.id === dialog.editingId)
    updateMutation.mutate({ ...values, displayOrder: current?.displayOrder ?? 0 }, callbacks)
  }

  /** 삭제 확인 — 하드 삭제라 되돌릴 수 없다(ADR 2026-08-25). */
  function handleDeleteConfirm(): void {
    if (deleteTarget === null) return
    setDeleteError(undefined)
    deleteMutation.mutate(deleteTarget.id, {
      // 모르는 코드일 때 기본 문구는 「규칙을 **저장**하지 못했습니다. **값을 확인하고**」다 —
      // 삭제에는 고칠 값이 없어 사용자가 무엇을 하라는 말인지 알 수 없다. 삭제 전용 문구로 갈아 끼운다.
      // ★ 봉투를 못 읽으면 `null`, 봉투는 읽었는데 code 가 없으면 `'UNKNOWN'` 이 온다 —
      //   둘 다 fallback 으로 떨어지므로 호출부가 그 문자열을 비교하지 않는다.
      onError: (error: unknown) =>
        setDeleteError(
          validatorErrorMessage(errorCodeOf(error), validatorLabels.error.removeFailed),
        ),
    })
    // ★ 공용 `ConfirmDialog` 가 `onConfirm()` 직후 스스로 `onOpenChange(false)` 를 부른다
    //   (`confirm-dialog.tsx:55-56`). 그래서 여기서 닫는 것은 그 호출과 같은 커밋에 들어가고,
    //   `onSuccess` 로 옮겨도 다이얼로그는 이미 닫힌 뒤다 — 실패 시 확인 맥락을 남기려면
    //   프리미티브를 고쳐야 하고 그것은 이 PR 범위 밖이다(부채 등재).
    setDeleteTarget(null)
  }

  const isSubmitting = addMutation.isPending || updateMutation.isPending

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="space-y-0.5">
          <h3 className="text-sm font-semibold text-foreground">{validatorLabels.section.title}</h3>
          <p className="text-xs text-muted-foreground">{validatorLabels.section.description}</p>
        </div>
        <Button
          type="button"
          variant="default"
          size="xs"
          onClick={handleAddClick}
          disabled={selectedTxId === ''}
          title={selectedTxId === '' ? validatorLabels.error.selectTransitionFirst : undefined}
          className="rounded-md px-3"
        >
          {validatorLabels.section.addButton}
        </Button>
      </div>

      <div className="space-y-1">
        <label className="text-xs font-medium text-muted-foreground" htmlFor="validator-transition-select">
          {validatorLabels.section.transitionSelectLabel}
        </label>
        {/*
          value 는 전환의 `id`(UUID) 다. `key`(`from__to`)는 하위호환용 계산 프로퍼티로 강등돼
          더 이상 유일하지 않고(ADR 2026-08-18 §D1), 보내면 backend 가 대상을 특정하지 못해
          404 로 거절하는 경로가 새로 열린다(제약 C4).
        */}
        <select
          id="validator-transition-select"
          value={selectedTxId}
          onChange={(e) => { setSelectedTxId(e.target.value); setDeleteError(undefined) }}
          className={cn(
            'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
            'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
          )}
        >
          <option value="">{validatorLabels.section.transitionSelectPlaceholder}</option>
          {transitions.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </div>

      {deleteError !== undefined && (
        <div role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive ring-1 ring-foreground/10">
          {deleteError}
        </div>
      )}

      {selectedTxId !== '' && (
        <div className="overflow-hidden rounded-lg ring-1 ring-foreground/10">
          <ValidatorListArea
            validators={validators}
            isLoading={isLoading}
            isError={isError}
            onRetry={() => { void refetch() }}
            onAddClick={handleAddClick}
            onEditClick={handleEditClick}
            onDeleteClick={setDeleteTarget}
            isDeleting={deleteMutation.isPending}
          />
        </div>
      )}

      {/* key 로 열릴 때마다 재마운트해 직전 행의 값이 남는 것을 막는다(형제 B1 회귀). */}
      <ValidatorFormDialog
        key={dialog.open ? `${dialog.mode}-${dialog.editingId === '' ? 'new' : dialog.editingId}` : 'closed'}
        open={dialog.open}
        mode={dialog.mode}
        initialValue={dialog.initialValue}
        submitting={isSubmitting}
        serverError={serverError}
        onSubmit={handleDialogSubmit}
        onCancel={() => { setDialog(CLOSED_DIALOG); setServerError(undefined) }}
      />

      <ConfirmDialog
        open={deleteTarget !== null}
        onOpenChange={(next) => { if (!next) setDeleteTarget(null) }}
        title={validatorLabels.dialog.deleteTitle}
        description={validatorLabels.dialog.deleteDescription}
        confirmLabel={validatorLabels.dialog.deleteConfirmButton}
        cancelLabel={validatorLabels.dialog.deleteCancelButton}
        onConfirm={handleDeleteConfirm}
        confirming={deleteMutation.isPending}
        destructive
      />
    </section>
  )
}

interface ValidatorListAreaProps {
  validators: ValidatorResponse[]
  isLoading: boolean
  isError: boolean
  onRetry: () => void
  onAddClick: () => void
  onEditClick: (validator: ValidatorResponse) => void
  onDeleteClick: (validator: ValidatorResponse) => void
  isDeleting: boolean
}

/**
 * 목록 영역의 4갈래 — 로딩 / 에러(재시도) / 빈 / 표.
 *
 * 빈 `<div/>` 를 돌려주는 갈래가 없어야 한다. 백로그 에러가 빈 화면으로 침묵한 실사고가
 * 로드맵 결함 목록에 있다.
 */
function ValidatorListArea({
  validators,
  isLoading,
  isError,
  onRetry,
  onAddClick,
  onEditClick,
  onDeleteClick,
  isDeleting,
}: ValidatorListAreaProps): JSX.Element {
  if (isLoading) {
    return <ValidatorTableSkeleton />
  }
  if (isError) {
    return (
      <div role="alert" className="flex flex-col items-center gap-3 px-4 py-8 text-center text-sm text-destructive">
        <p>{validatorLabels.error.loadFailed}</p>
        <Button type="button" variant="outline" size="sm" onClick={onRetry}>
          {validatorLabels.error.retryButton}
        </Button>
      </div>
    )
  }
  if (validators.length === 0) {
    return (
      <EmptyState
        title={validatorLabels.list.emptyTitle}
        description={validatorLabels.list.emptyDescription}
        action={
          <Button type="button" variant="default" size="sm" onClick={onAddClick}>
            {validatorLabels.list.emptyActionButton}
          </Button>
        }
      />
    )
  }
  return (
    <ValidatorTable
      validators={validators}
      onEditClick={onEditClick}
      onDeleteClick={onDeleteClick}
      isDeleting={isDeleting}
    />
  )
}
