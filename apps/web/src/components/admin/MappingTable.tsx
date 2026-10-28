// 워크플로우 스킴 매핑 테이블 — 화면 안 초안 + 게시(유형별 상태 이관 마법사) + default 강조 + G1 이슈 타입 필터
import type { JSX } from 'react'
import { useState, useMemo, useId, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { SCHEME_KEYS } from '@/hooks/use-workflow-schemes'
import { notifySchemeError } from '@/hooks/workflow-scheme-error'
import { fetchWorkflows } from '@/api/workflows'
import { publishWorkflowScheme, toNullableIssueTypeKey, WorkflowSchemeApiError } from '@/api/workflow-schemes'
import type { MappingDetail, StatusMappingInput, StrandedIssueType } from '@/api/workflow-schemes'
import { SchemeMigrationWizard } from '@/components/admin/SchemeMigrationWizard'
import type { IssueTypeResponse as IssueTypeHookResponse } from '@/api/issue-types'
import { cn } from '@/lib/utils'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** default 매핑 sentinel 값 */
const DEFAULT_SENTINEL = '__default__'

/** default 매핑 표시 레이블 */
const DEFAULT_LABEL = workflowSchemeLabels.mapping.defaultLabel

/** 워크플로우 목록 queryKey */
const WORKFLOW_KEYS = {
  list: ['workflows'] as const,
}

/** default 매핑 row 강조 CSS 클래스 */
const DEFAULT_ROW_CLASS = 'bg-warning/10'

/** default 매핑 텍스트 강조 CSS 클래스 */
const DEFAULT_TEXT_CLASS = 'font-medium text-warning-text'

/** default 매핑 badge CSS 클래스 */
const DEFAULT_BADGE_CLASS =
  'inline-flex items-center rounded-full bg-warning px-2 py-0.5 text-[11px] font-medium text-warning-foreground'

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** MappingTable 컴포넌트 props */
export interface MappingTableProps {
  /** 스킴 키 */
  schemeKey: string
  /** 현재 매핑 목록 */
  mappings: MappingDetail[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 타입 별칭 (IssueTypeResponse 출처 통일)
// ─────────────────────────────────────────────────────────────────────────────
type IssueType = IssueTypeHookResponse

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 확인 dialog
// ─────────────────────────────────────────────────────────────────────────────

interface ConfirmDeleteDialogProps {
  mappingId: number
  onConfirm: (id: number) => void
  onCancel: () => void
}

/** 매핑 삭제 확인 인라인 dialog */
function ConfirmDeleteDialog({ mappingId, onConfirm, onCancel }: ConfirmDeleteDialogProps): JSX.Element {
  return (
    <div
      role="dialog"
      aria-label={workflowSchemeLabels.mapping.confirmDeleteDialogAriaLabel}
      className="flex items-center gap-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-1.5"
    >
      <span className="text-xs text-destructive">{workflowSchemeLabels.mapping.confirmDeleteText}</span>
      <Button
        variant="destructive"
        size="sm"
        className="h-6 px-2 text-xs"
        onClick={() => onConfirm(mappingId)}
      >
        {workflowSchemeLabels.mapping.confirmDeleteButton}
      </Button>
      <Button
        variant="ghost"
        size="sm"
        className="h-6 px-2 text-xs"
        onClick={onCancel}
      >
        {workflowSchemeLabels.mapping.confirmCancelButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 매핑 row
// ─────────────────────────────────────────────────────────────────────────────

interface MappingRowProps {
  mapping: MappingDetail
  onDeleteClick: (id: number) => void
  isConfirming: boolean
  onConfirmDelete: (id: number) => void
  onCancelDelete: () => void
}

/**
 * 단일 매핑 row.
 * default 매핑(isDefault=true)이면 ★ prefix + 강조 배경을 표시한다.
 */
function MappingRow({
  mapping,
  onDeleteClick,
  isConfirming,
  onConfirmDelete,
  onCancelDelete,
}: MappingRowProps): JSX.Element {
  const issueTypeDisplay = mapping.isDefault
    ? workflowSchemeLabels.mapping.defaultRowPrefix
    : (mapping.issueTypeName ?? mapping.issueTypeKey ?? '–')

  return (
    <tr
      className={cn(
        'border-b border-border text-sm transition-colors',
        mapping.isDefault && DEFAULT_ROW_CLASS,
      )}
    >
      <td className="px-4 py-2.5">
        <span className={cn(mapping.isDefault && DEFAULT_TEXT_CLASS)}>
          {issueTypeDisplay}
        </span>
      </td>
      <td className="px-4 py-2.5 text-muted-foreground">{mapping.workflowName}</td>
      <td className="px-4 py-2.5 text-center">
        {mapping.isDefault && (
          <span className={DEFAULT_BADGE_CLASS}>{workflowSchemeLabels.mapping.defaultBadge}</span>
        )}
      </td>
      <td className="px-4 py-2.5">
        {isConfirming ? (
          <ConfirmDeleteDialog
            mappingId={mapping.id}
            onConfirm={onConfirmDelete}
            onCancel={onCancelDelete}
          />
        ) : (
          <Button
            variant="ghost"
            size="sm"
            className="h-7 px-2 text-xs text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => onDeleteClick(mapping.id)}
            aria-label={`매핑 삭제 (${issueTypeDisplay})`}
          >
            {workflowSchemeLabels.mapping.deleteButton}
          </Button>
        )}
      </td>
    </tr>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 추가 row
// ─────────────────────────────────────────────────────────────────────────────

interface AddMappingRowProps {
  onAdd: (issueTypeKey: string | null, workflowKey: string) => void
  availableIssueTypes: IssueType[]
  hasDefaultMapping: boolean
  workflowOptions: Array<{ key: string; name: string }>
}

/**
 * 매핑 추가 row.
 * issueType select + workflow select + 「추가」 버튼으로 구성된다.
 * G1 보강: 이미 매핑된 이슈 타입 + default 매핑 존재 시 해당 옵션을 제외한다.
 */
function AddMappingRow({
  onAdd,
  availableIssueTypes,
  hasDefaultMapping,
  workflowOptions,
}: AddMappingRowProps): JSX.Element {
  const [selectedIssueType, setSelectedIssueType] = useState('')
  const [selectedWorkflow, setSelectedWorkflow] = useState('')
  const issueTypeSelectId = useId()
  const workflowSelectId = useId()

  const handleAdd = () => {
    if (selectedWorkflow === '') return
    onAdd(toNullableIssueTypeKey(selectedIssueType || DEFAULT_SENTINEL), selectedWorkflow)
    setSelectedIssueType('')
    setSelectedWorkflow('')
  }

  return (
    <tr className="border-t-2 border-border/50 bg-muted/30">
      <td className="px-4 py-2.5">
        <select
          id={issueTypeSelectId}
          data-testid="issue-type-select"
          value={selectedIssueType}
          onChange={(e) => setSelectedIssueType(e.target.value)}
          className="h-8 w-full rounded-md border border-input bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/30"
          aria-label={workflowSchemeLabels.mapping.issueTypeSelectAriaLabel}
        >
          <option value="">이슈 타입 선택...</option>
          {!hasDefaultMapping && (
            <option value={DEFAULT_SENTINEL} data-testid="option-default">
              {DEFAULT_LABEL}
            </option>
          )}
          {availableIssueTypes.map((it) => (
            <option
              key={it.key}
              value={it.key}
              data-testid={`option-${it.key}`}
            >
              {it.name}
            </option>
          ))}
        </select>
      </td>
      <td className="px-4 py-2.5">
        <select
          id={workflowSelectId}
          data-testid="workflow-select"
          value={selectedWorkflow}
          onChange={(e) => setSelectedWorkflow(e.target.value)}
          className="h-8 w-full rounded-md border border-input bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/30"
          aria-label={workflowSchemeLabels.mapping.workflowSelectAriaLabel}
        >
          <option value="">워크플로우 선택...</option>
          {workflowOptions.map((wf) => (
            <option key={wf.key} value={wf.key}>
              {wf.name}
            </option>
          ))}
        </select>
      </td>
      <td className="px-4 py-2.5" />
      <td className="px-4 py-2.5">
        <Button
          size="sm"
          className="h-7 px-3 text-xs"
          onClick={handleAdd}
          disabled={selectedWorkflow === ''}
          aria-label={workflowSchemeLabels.mapping.addMappingAriaLabel}
        >
          {workflowSchemeLabels.mapping.addMappingButton}
        </Button>
      </td>
    </tr>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 매핑 테이블.
 *
 * - 현재 매핑 목록을 테이블 rows로 렌더한다.
 * - default 매핑(isDefault=true)에 ★ prefix + 강조 표시.
 * - 추가 · 삭제는 **화면 안 초안**에 쌓인다(결정 D4 — 초안 → 게시는 스킴에만). 「게시」가 매핑 전체를
 *   한 번에 보내고, 새 워크플로우에 없는 상태에 이슈가 남으면 서버 409 로 유형별 상태 이관 마법사를 연다.
 *   초안은 서버에 없다 — 페이지를 떠나면 사라지므로 미게시 변경이 있으면 이탈을 경고한다.
 * - G1 보강: 이미 매핑된 이슈 타입은 select 옵션에서 제외.
 */
export function MappingTable({ schemeKey, mappings: saved }: MappingTableProps): JSX.Element {
  const [confirmingId, setConfirmingId] = useState<number | null>(null)
  const [draft, setDraft] = useState<MappingDetail[] | null>(null)
  const [stranded, setStranded] = useState<StrandedIssueType[]>([])
  const [bulkIds, setBulkIds] = useState<string[] | null>(null)
  const mappings = draft ?? saved
  const queryClient = useQueryClient()

  useEffect(() => {
    if (draft === null) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [draft])

  const publish = useMutation({
    mutationFn: (statusMappings: StatusMappingInput[]) =>
      publishWorkflowScheme(schemeKey, {
        mappings: mappings.map((m) => ({ issueTypeKey: m.issueTypeKey, workflowKey: m.workflowKey })),
        statusMappings,
      }),
    onSuccess: async (ids) => {
      // 서버 목록이 먼저 도착해야 초안을 걷을 때 옛 매핑이 한 번 깜빡이지 않는다.
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
      setDraft(null)
      setStranded([])
      if (ids.length > 0) setBulkIds(ids)
      toast.success(workflowSchemeLabels.draft.published)
    },
    onError: (error) => {
      if (error instanceof WorkflowSchemeApiError && error.strandedByType.length > 0) {
        setStranded(error.strandedByType)
        return
      }
      notifySchemeError(error)
    },
  })

  const { data: issueTypes = [] } = useIssueTypes()
  const { data: workflows = [] } = useQuery({
    queryKey: WORKFLOW_KEYS.list,
    queryFn: fetchWorkflows,
    staleTime: 60 * 60 * 1_000,
  })

  /** 이미 매핑된 이슈 타입 키 집합 */
  const mappedIssueTypeKeys = useMemo<Set<string | null>>(
    () => new Set(mappings.map((m) => m.issueTypeKey)),
    [mappings],
  )

  /** 아직 매핑되지 않은 이슈 타입 목록 */
  const availableIssueTypes = useMemo<IssueType[]>(
    () => (issueTypes as IssueType[]).filter((it) => !mappedIssueTypeKeys.has(it.key)),
    [issueTypes, mappedIssueTypeKeys],
  )

  /** default 매핑(issueTypeKey === null)이 이미 있는지 */
  const hasDefaultMapping = mappedIssueTypeKeys.has(null)

  const handleDeleteClick = (id: number) => {
    setConfirmingId(id)
  }

  const handleConfirmDelete = (id: number) => {
    setDraft(mappings.filter((m) => m.id !== id))
    setConfirmingId(null)
  }

  const handleCancelDelete = () => {
    setConfirmingId(null)
  }

  /** default 매핑을 상단으로, 나머지는 issueTypeName 순 정렬 */
  const sortedMappings = useMemo<MappingDetail[]>(
    () =>
      [...mappings].sort((a, b) => {
        if (a.isDefault) return -1
        if (b.isDefault) return 1
        return (a.issueTypeName ?? '').localeCompare(b.issueTypeName ?? '')
      }),
    [mappings],
  )

  const workflowOptions = (workflows ?? []).map((wf) => ({ key: wf.key, name: wf.name }))

  const handleAdd = (issueTypeKey: string | null, workflowKey: string) => {
    const issueType = (issueTypes as IssueType[]).find((it) => it.key === issueTypeKey)
    setDraft([
      ...mappings,
      {
        // 새 행은 서버 id 가 없다 — 음수로 기존 행과 겹치지 않게 한다(게시 뒤 서버 목록으로 바뀐다).
        id: -(mappings.length + 1) - Date.now(),
        issueTypeKey,
        issueTypeName: issueType?.name ?? null,
        workflowKey,
        workflowName: workflowOptions.find((wf) => wf.key === workflowKey)?.name ?? workflowKey,
        isDefault: issueTypeKey === null,
      },
    ])
  }

  const closeWizard = () => {
    setStranded([])
    setBulkIds(null)
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {draft !== null && (
        <div className="flex items-center gap-2 border-b border-border bg-warning/10 px-4 py-2 text-sm">
          <span className="flex-1">{workflowSchemeLabels.draft.unpublishedNotice}</span>
          <Button variant="ghost" size="sm" onClick={() => setDraft(null)} disabled={publish.isPending}>
            {workflowSchemeLabels.draft.discard}
          </Button>
          <Button size="sm" onClick={() => publish.mutate([])} disabled={publish.isPending}>
            {publish.isPending ? workflowSchemeLabels.draft.publishing : workflowSchemeLabels.draft.publish}
          </Button>
        </div>
      )}
      {(stranded.length > 0 || bulkIds !== null) && (
        <SchemeMigrationWizard
          stranded={stranded}
          bulkOperationIds={bulkIds}
          isSubmitting={publish.isPending}
          onSubmit={(statusMappings) => publish.mutate(statusMappings)}
          onClose={closeWizard}
        />
      )}
      <div className="flex-1 overflow-auto">
        <table className="w-full border-collapse text-left">
          <thead className="sticky top-0 z-10 border-b border-border bg-(--bg-neutral-solid)/80 backdrop-blur-sm">
            <tr>
              <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                {workflowSchemeLabels.mapping.issueTypeColumn}
              </th>
              <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                {workflowSchemeLabels.mapping.workflowColumn}
              </th>
              <th className="px-4 py-2.5 text-center text-xs font-medium uppercase tracking-wide text-muted-foreground">
                {workflowSchemeLabels.mapping.isDefaultColumn}
              </th>
              <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                {workflowSchemeLabels.mapping.actionColumn}
              </th>
            </tr>
          </thead>
          <tbody>
            {sortedMappings.map((mapping) => (
              <MappingRow
                key={mapping.id}
                mapping={mapping}
                onDeleteClick={handleDeleteClick}
                isConfirming={confirmingId === mapping.id}
                onConfirmDelete={handleConfirmDelete}
                onCancelDelete={handleCancelDelete}
              />
            ))}
            <AddMappingRow
              onAdd={handleAdd}
              availableIssueTypes={availableIssueTypes}
              hasDefaultMapping={hasDefaultMapping}
              workflowOptions={workflowOptions}
            />
          </tbody>
        </table>
      </div>
    </div>
  )
}
