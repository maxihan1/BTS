// 워크플로우 스킴 매핑 테이블 — CRUD + 낙관적 업데이트 + default 강조 + G1 이슈 타입 필터
import type { JSX } from 'react'
import { useState, useMemo, useId } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { useIssueTypes } from '@/hooks/use-issue-types'
import {
  useAddMapping,
  useRemoveMapping,
} from '@/hooks/use-workflow-schemes'
import { fetchWorkflows } from '@/api/workflows'
import { toNullableIssueTypeKey } from '@/api/workflow-schemes'
import type { MappingResponse } from '@/api/workflow-schemes'
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
const DEFAULT_ROW_CLASS = 'bg-amber-50/50 dark:bg-amber-950/10'

/** default 매핑 텍스트 강조 CSS 클래스 */
const DEFAULT_TEXT_CLASS = 'font-medium text-amber-700 dark:text-amber-400'

/** default 매핑 badge CSS 클래스 */
const DEFAULT_BADGE_CLASS =
  'inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-medium text-amber-800 dark:bg-amber-900/30 dark:text-amber-400'

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** MappingTable 컴포넌트 props */
export interface MappingTableProps {
  /** 스킴 키 */
  schemeKey: string
  /** 현재 매핑 목록 */
  mappings: MappingResponse[]
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
  mapping: MappingResponse
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
  schemeKey: string
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
  schemeKey,
  availableIssueTypes,
  hasDefaultMapping,
  workflowOptions,
}: AddMappingRowProps): JSX.Element {
  const [selectedIssueType, setSelectedIssueType] = useState('')
  const [selectedWorkflow, setSelectedWorkflow] = useState('')
  const addMapping = useAddMapping(schemeKey)
  const issueTypeSelectId = useId()
  const workflowSelectId = useId()

  const handleAdd = () => {
    if (selectedWorkflow === '') return

    addMapping.mutate(
      {
        issueTypeKey: toNullableIssueTypeKey(selectedIssueType || DEFAULT_SENTINEL),
        workflowKey: selectedWorkflow,
      },
      {
        onSuccess: () => {
          setSelectedIssueType('')
          setSelectedWorkflow('')
        },
      },
    )
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
          disabled={selectedWorkflow === '' || addMapping.isPending}
          aria-label={workflowSchemeLabels.mapping.addMappingAriaLabel}
        >
          {addMapping.isPending ? '추가 중...' : workflowSchemeLabels.mapping.addMappingButton}
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
 * - 「삭제」 버튼 클릭 → 인라인 확인 dialog → useRemoveMapping.mutate (낙관적 삭제).
 * - 「+ 매핑 추가」 row — issueType select + workflow select + 「추가」 버튼.
 * - G1 보강: 이미 매핑된 이슈 타입은 select 옵션에서 제외.
 */
export function MappingTable({ schemeKey, mappings }: MappingTableProps): JSX.Element {
  const [confirmingId, setConfirmingId] = useState<number | null>(null)

  const { data: issueTypes = [] } = useIssueTypes()
  const { data: workflows = [] } = useQuery({
    queryKey: WORKFLOW_KEYS.list,
    queryFn: fetchWorkflows,
    staleTime: 60 * 60 * 1_000,
  })

  const removeMapping = useRemoveMapping(schemeKey)

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
    removeMapping.mutate(id, {
      onSettled: () => setConfirmingId(null),
    })
  }

  const handleCancelDelete = () => {
    setConfirmingId(null)
  }

  /** default 매핑을 상단으로, 나머지는 issueTypeName 순 정렬 */
  const sortedMappings = useMemo<MappingResponse[]>(
    () =>
      [...mappings].sort((a, b) => {
        if (a.isDefault) return -1
        if (b.isDefault) return 1
        return (a.issueTypeName ?? '').localeCompare(b.issueTypeName ?? '')
      }),
    [mappings],
  )

  const workflowOptions = (workflows ?? []).map((wf) => ({ key: wf.key, name: wf.name }))

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <div className="flex-1 overflow-auto">
        <table className="w-full border-collapse text-left">
          <thead className="sticky top-0 z-10 border-b border-border bg-muted/80 backdrop-blur-sm">
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
              schemeKey={schemeKey}
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
