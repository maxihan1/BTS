// 이슈 상세 우측 메타패널 컴포넌트 — 상태 배지·전이 셀렉터·우선순위·영향도·환경·라벨·담당자·보고자·프로젝트·유형·버전·날짜 + 삭제 버튼
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import type { IssueResponse, IssueTransition } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { UserSummary } from '@/api/users'
import { Button } from '@/components/ui/button'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { formatDate } from '@/lib/date-format'
import { issueDetailStrings } from '@/i18n/ko'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// IssueMetaPanel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전이 컨트롤을 노출할 수 없는 사유.
 * - 'no-workflow': GET /transitions 422 — 워크플로우 미설정 (스펙 E5 S5)
 * - 'terminal': 200 + 빈 배열 — 종료상태, 더 이상 전이 없음 (스펙 E5 S6)
 * - null: 전이 가용 (정상 흐름)
 */
export type TransitionUnavailableReason = 'no-workflow' | 'terminal' | null

/** IssueMetaPanel props */
export interface IssueMetaPanelProps {
  /** 렌더할 이슈 데이터 */
  issue: IssueResponse
  /** 활성 이슈 타입 목록 — 유형 셀렉터 옵션 */
  availableTypes: IssueTypeResponse[]
  /** 유형 변경 핸들러 — 선택한 타입의 id(number)를 전달 */
  onTypeChange: (typeId: number) => void
  /** 삭제 버튼 클릭 핸들러 */
  onDeleteClick: () => void
  /** 현재 상태에서 가용한 전이 목록 */
  transitions: IssueTransition[]
  /** 전이 실행 핸들러 — 선택한 toStateKey를 전달 */
  onTransition: (toStateKey: string) => void
  /** 전이 진행 중 여부 — true 시 셀렉터 disabled (NFR3 중복클릭 방지) */
  isTransitioning: boolean
  /**
   * 전이 컨트롤을 노출할 수 없는 사유 (스펙 E5).
   * 'no-workflow' → 미설정 안내, 'terminal' | null → 종료상태 안내.
   * transitions.length > 0 이면 이 값과 관계없이 셀렉터가 노출된다.
   */
  unavailableReason?: TransitionUnavailableReason
  /** 우선순위 변경 핸들러 — 선택한 priority(1~5, number)를 전달 */
  onPriorityChange: (priority: number) => void
  /** 영향도 변경 핸들러 — 선택한 impact(1~3, number)를 전달 */
  onImpactChange: (impact: number) => void
  /** 환경 저장 핸들러 — 편집된 environment 문자열을 전달 */
  onEnvironmentSave: (environment: string) => void
  /** 라벨 저장 핸들러 — 편집된 labels 배열을 전달 */
  onLabelsSave: (labels: string[]) => void
  /** 사용자 검색 결과 목록 — 담당자 셀렉터에 노출 (routes에서 useUsers 결과 전달) */
  users: UserSummary[]
  /** 담당자 검색어 변경 핸들러 — 검색 input의 onChange 값을 전달 */
  onAssigneeSearch: (query: string) => void
  /** 담당자 변경 핸들러 — 선택한 사용자 UUID 또는 null(해제)을 전달 */
  onAssigneeChange: (userId: string | null) => void
  /**
   * 현재 담당자 UserSummary — useUsersByIds로 별도 조회한 값 (C1 버그 수정).
   * 검색결과(users)와 분리해 현재 담당자 이름을 안정적으로 표시한다.
   * null이면 "미지정" 표시.
   */
  currentAssignee: UserSummary | null
}

/**
 * 이슈 상세 우측 메타패널 컴포넌트.
 *
 * - 상태: 읽기전용 배지 + IssueStateTransition 전이 셀렉터
 * - 유형: IssueTypeIcon + typeName 표시 + 셀렉터(availableTypes 옵션)
 * - 셀렉터 현재값은 issue.typeId props 파생 (useState 초기화 금지 — stale key prop 회귀 방지)
 * - 우선순위: IssuePrioritySelect — 1~5 즉시 콜백
 * - 영향도: IssueImpactSelect — 1~3 즉시 콜백, impact null이면 미지정 활성, 설정 후 미지정 disabled
 * - 환경: IssueEnvironmentEdit — 로컬상태 + 저장 버튼 (refetch 시 props로 seed)
 * - 라벨: IssueLabelsEdit — 칩 추가/삭제 + 저장 버튼 (refetch 시 props로 seed)
 * - 보고자 UUID, 프로젝트 키, 버전(낙관락), 생성/수정 날짜 표시
 * - 생성/수정 날짜 null → "—" 표기
 * - 하단 "이슈 삭제" 버튼 → onDeleteClick 호출 (WCAG AA: min-h-[44px])
 */
export function IssueMetaPanel({
  issue,
  availableTypes,
  onTypeChange,
  onDeleteClick,
  transitions,
  onTransition,
  isTransitioning,
  unavailableReason = null,
  onPriorityChange,
  onImpactChange,
  onEnvironmentSave,
  onLabelsSave,
  users,
  onAssigneeSearch,
  onAssigneeChange,
  currentAssignee,
}: IssueMetaPanelProps): JSX.Element {
  // 권한 조회 — fail-closed: 로딩 중·에러·미확정이면 false(비활성)
  const { data: permissionsData, isLoading: isPermissionsLoading, isError: isPermissionsError } =
    useIssuePermissions(issue.key)
  const canEdit =
    !isPermissionsLoading && !isPermissionsError && permissionsData?.permissions.UPDATE === true
  const canDelete =
    !isPermissionsLoading && !isPermissionsError && permissionsData?.permissions.SOFT_DELETE === true

  /** issue.typeId에 해당하는 타입 항목 — iconName 해석에 사용 */
  const currentType = availableTypes.find((t) => t.id === issue.typeId)

  /**
   * 전이 셀렉터 제어값 — 전이 시도 후(성공/실패 모두) placeholder로 리셋.
   * 실패 후 같은 옵션 재선택 시 onChange 재발화를 보장한다 (C1 회귀 방지).
   */
  const [selectedTransition, setSelectedTransition] = useState('')

  function handleTransition(toStateKey: string) {
    onTransition(toStateKey)
    // 전이 요청 직후 즉시 리셋 — 성공/실패 모두 placeholder로 복귀
    setSelectedTransition('')
  }

  return (
    <aside className="flex flex-col gap-3">
      {/* 메타 패널 카드 */}
      <div className="border border-border rounded-xl overflow-hidden">
        {/* 상태 — 읽기전용 배지 + 전이 셀렉터 (FR-IS-01) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.statLabel}</p>
          <span
            data-testid="issue-state-badge"
            className="inline-flex items-center gap-1.5 text-sm font-medium mb-1.5"
          >
            <span className="size-2 rounded-full bg-primary/60 shrink-0" aria-hidden="true" />
            {issue.currentStateKey}
          </span>
          <IssueStateTransition
            transitions={transitions}
            onTransition={handleTransition}
            isTransitioning={isTransitioning}
            unavailableReason={unavailableReason}
            selectedValue={selectedTransition}
            onSelectedValueChange={setSelectedTransition}
          />
        </div>

        {/* 유형 — 아이콘 + typeName 표시 + 셀렉터 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.typeLabel}</p>
          <div className="flex items-center gap-1.5 mb-1.5">
            <IssueTypeIcon
              iconName={currentType?.iconName ?? null}
              typeName={issue.typeName}
            />
            <span className="text-sm font-medium" data-testid="issue-type-name">{issue.typeName}</span>
          </div>
          <IssueTypeSelect
            value={issue.typeId}
            availableTypes={availableTypes}
            onTypeChange={onTypeChange}
            currentTypeId={issue.typeId}
          />
        </div>

        {/* 우선순위 — IssuePrioritySelect (FR-IS-04) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.priorityLabel}</p>
          <IssuePrioritySelect value={issue.priority} onPriorityChange={onPriorityChange} />
        </div>

        {/* 영향도 — IssueImpactSelect (FR-IS-04) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.impactLabel}</p>
          <IssueImpactSelect value={issue.impact} onImpactChange={onImpactChange} />
        </div>

        {/* 환경 — IssueEnvironmentEdit (FR-IS-04) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="environment-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.environmentLabel}</p>
          <IssueEnvironmentEdit value={issue.environment} onSave={onEnvironmentSave} canEdit={canEdit} />
        </div>

        {/* 라벨 — IssueLabelsEdit (FR-IS-04) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="labels-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.labelsLabel}</p>
          <IssueLabelsEdit value={issue.labels} onSave={onLabelsSave} canEdit={canEdit} />
        </div>

        {/* 담당자 — IssueAssigneeSelect (FR-IS-03) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="assignee-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.assigneeLabel}</p>
          <IssueAssigneeSelect
            value={issue.assigneeId ?? null}
            currentAssignee={currentAssignee}
            users={users}
            onSearch={onAssigneeSearch}
            onAssigneeChange={onAssigneeChange}
          />
        </div>

        {/* 보고자 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.reporterLabel}</p>
          <p className="text-sm font-medium truncate">{issue.reporterId}</p>
        </div>

        {/* 프로젝트 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.projectLabel}</p>
          <p className="text-sm font-medium">{issue.projectKey}</p>
        </div>

        {/* 버전 (낙관락 OCC 버전 번호) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.versionLabel}</p>
          <p className="text-sm font-medium">v{issue.version}</p>
        </div>

        {/* 생성일 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.createdAtLabel}</p>
          <p className="text-sm font-medium">{formatDate(issue.createdAt)}</p>
        </div>

        {/* 수정일 */}
        <div className="px-3.5 py-3">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.updatedAtLabel}</p>
          <p className="text-sm font-medium">{formatDate(issue.updatedAt)}</p>
        </div>
      </div>

      {/* 삭제 버튼 — WCAG AA 44px 터치 타깃, 권한 없으면 disabled + 사유 표시 */}
      <Button
        variant="destructive"
        className="w-full min-h-[44px]"
        onClick={onDeleteClick}
        disabled={!canDelete}
        aria-label={canDelete ? issueDetailStrings.deleteButton : issueDetailStrings.deleteButtonNoPermission}
        title={canDelete ? undefined : issueDetailStrings.deleteButtonNoPermission}
        data-testid="issue-delete"
      >
        {issueDetailStrings.deleteButton}
      </Button>
    </aside>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssuePrioritySelect — 우선순위 셀렉터 서브 컴포넌트 (FR-IS-04)
// ─────────────────────────────────────────────────────────────────────────────

/** IssuePrioritySelect props */
interface IssuePrioritySelectProps {
  /** 현재 우선순위 — issue.priority props 파생, useState 초기화 금지 */
  value: number
  /** 우선순위 변경 콜백 — number 전달 */
  onPriorityChange: (priority: number) => void
}

/**
 * 이슈 우선순위 셀렉터.
 *
 * - value는 부모 props에서 파생(issue.priority) — stale key prop 회귀 방지
 * - 1(가장 높음) ~ 5(가장 낮음) 옵션
 * - 변경 즉시 onPriorityChange 호출
 * - WCAG AA: min-h-[44px], aria-label
 */
function IssuePrioritySelect({ value, onPriorityChange }: IssuePrioritySelectProps): JSX.Element {
  const PRIORITIES = [1, 2, 3, 4, 5] as const

  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    onPriorityChange(Number(e.target.value))
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={value}
      onChange={handleChange}
      aria-label={issueDetailStrings.prioritySelectLabel}
    >
      {PRIORITIES.map((p) => (
        <option key={p} value={p}>
          {issueDetailStrings.priorityNames[p]}
        </option>
      ))}
    </select>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueImpactSelect — 영향도 셀렉터 서브 컴포넌트 (FR-IS-04)
// ─────────────────────────────────────────────────────────────────────────────

/** IssueImpactSelect props */
interface IssueImpactSelectProps {
  /** 현재 영향도 — null이면 미지정. issue.impact props 파생, useState 초기화 금지 */
  value: number | null
  /** 영향도 변경 콜백 — number 전달 */
  onImpactChange: (impact: number) => void
}

/**
 * 이슈 영향도 셀렉터.
 *
 * - value=null → 미지정 옵션 활성, 셀렉터 value=''
 * - value 설정 후 → 미지정 옵션 disabled (클리어 불가 백엔드 제약)
 * - 1(높음) ~ 3(낮음) 옵션
 * - 변경 즉시 onImpactChange 호출
 * - WCAG AA: min-h-[44px], aria-label
 */
function IssueImpactSelect({ value, onImpactChange }: IssueImpactSelectProps): JSX.Element {
  const IMPACTS = [1, 2, 3] as const
  const isUnset = value === null

  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const selected = e.target.value
    if (selected === '') return
    onImpactChange(Number(selected))
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={isUnset ? '' : value}
      onChange={handleChange}
      aria-label={issueDetailStrings.impactSelectLabel}
    >
      <option value="" disabled={!isUnset}>
        {issueDetailStrings.impactUnset}
      </option>
      {IMPACTS.map((i) => (
        <option key={i} value={i}>
          {issueDetailStrings.impactNames[i]}
        </option>
      ))}
    </select>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueEnvironmentEdit — 환경 편집 서브 컴포넌트 (FR-IS-04)
// ─────────────────────────────────────────────────────────────────────────────

/** IssueEnvironmentEdit props */
interface IssueEnvironmentEditProps {
  /** 현재 환경 값 — null이면 빈 문자열로 표시 */
  value: string | null
  /** 저장 콜백 — 편집된 문자열 전달 */
  onSave: (environment: string) => void
  /** 수정 권한 — false이면 저장 버튼 disabled (fail-closed) */
  canEdit: boolean
}

/**
 * 이슈 환경 편집 컴포넌트.
 *
 * - 로컬 상태로 편집, 저장 버튼 클릭 시 onSave 호출
 * - issue.environment props가 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지)
 * - WCAG AA: aria-label
 */
function IssueEnvironmentEdit({ value, onSave, canEdit }: IssueEnvironmentEditProps): JSX.Element {
  const [draft, setDraft] = useState(value ?? '')

  // props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      setDraft(value ?? '')
    }
  }, [value])

  return (
    <div className="flex flex-col gap-1.5">
      <textarea
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-ring"
        rows={3}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder={issueDetailStrings.environmentPlaceholder}
        aria-label={issueDetailStrings.environmentLabel}
        maxLength={1000}
      />
      <Button
        variant="outline"
        size="sm"
        className="self-end min-h-[44px]"
        onClick={() => onSave(draft)}
        disabled={!canEdit}
        aria-label={issueDetailStrings.environmentSaveButton}
        data-testid="environment-save"
      >
        {issueDetailStrings.environmentSaveButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueLabelsEdit — 라벨 칩 편집 서브 컴포넌트 (FR-IS-04)
// ─────────────────────────────────────────────────────────────────────────────

/** 라벨 최대 개수 */
const MAX_LABELS = 20
/** 라벨 최대 길이 */
const MAX_LABEL_LENGTH = 50

/** IssueLabelsEdit props */
interface IssueLabelsEditProps {
  /** 현재 라벨 목록 — issue.labels props 파생 */
  value: string[]
  /** 저장 콜백 — 편집된 labels 배열 전달 */
  onSave: (labels: string[]) => void
  /** 수정 권한 — false이면 저장 버튼 disabled (fail-closed) */
  canEdit: boolean
}

/**
 * 이슈 라벨 칩 편집 컴포넌트.
 *
 * - 로컬 상태로 편집(추가/삭제), 저장 버튼 클릭 시 onSave 호출
 * - issue.labels props가 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지)
 * - 추가 시 클라이언트 검증: 공백 trim, 50자 초과 거부, 20개 초과 거부, 중복 거부
 * - WCAG AA: aria-label
 */
function IssueLabelsEdit({ value, onSave, canEdit }: IssueLabelsEditProps): JSX.Element {
  const [chips, setChips] = useState<string[]>(value)
  const [inputValue, setInputValue] = useState('')

  // props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      setChips(value)
    }
  }, [value])

  /** 라벨 추가 — trim, 길이, 개수, 중복 검증 */
  function addLabel() {
    const trimmed = inputValue.trim()
    if (trimmed === '') return
    if (trimmed.length > MAX_LABEL_LENGTH) return
    if (chips.length >= MAX_LABELS) return
    if (chips.includes(trimmed)) return
    setChips((prev) => [...prev, trimmed])
    setInputValue('')
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') {
      e.preventDefault()
      addLabel()
    }
  }

  function removeLabel(label: string) {
    setChips((prev) => prev.filter((c) => c !== label))
  }

  const isAtMax = chips.length >= MAX_LABELS

  return (
    <div className="flex flex-col gap-1.5">
      {/* 현재 라벨 칩 목록 */}
      {chips.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {chips.map((chip) => (
            <LabelChip key={chip} label={chip} onRemove={() => removeLabel(chip)} />
          ))}
        </div>
      )}

      {/* 라벨 추가 입력 */}
      <input
        type="text"
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
        value={inputValue}
        onChange={(e) => setInputValue(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={issueDetailStrings.labelAddPlaceholder}
        aria-label={issueDetailStrings.labelAddPlaceholder}
        disabled={isAtMax}
        maxLength={MAX_LABEL_LENGTH + 1}
      />

      {/* 저장 버튼 */}
      <Button
        variant="outline"
        size="sm"
        className="self-end min-h-[44px]"
        onClick={() => onSave(chips)}
        disabled={!canEdit}
        aria-label={issueDetailStrings.labelsSaveButton}
        data-testid="labels-save"
      >
        {issueDetailStrings.labelsSaveButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// LabelChip — 라벨 칩 개별 아이템
// ─────────────────────────────────────────────────────────────────────────────

/** LabelChip props */
interface LabelChipProps {
  /** 라벨 텍스트 */
  label: string
  /** 제거 콜백 */
  onRemove: () => void
}

/**
 * 라벨 칩 컴포넌트.
 * - 라벨 텍스트 + 제거 버튼
 * - WCAG AA: aria-label on remove button
 */
function LabelChip({ label, onRemove }: LabelChipProps): JSX.Element {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium">
      {label}
      <button
        type="button"
        onClick={onRemove}
        aria-label={issueDetailStrings.labelRemoveLabel}
        className="ml-0.5 rounded-full hover:bg-muted-foreground/20 focus:outline-none focus:ring-1 focus:ring-ring"
      >
        ×
      </button>
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueTypeSelect — 유형 셀렉터 서브 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** IssueTypeSelect props */
interface IssueTypeSelectProps {
  /** 현재 선택된 typeId — props 파생, useState 초기화 금지 */
  value: number
  /** 셀렉터에 표시할 타입 목록 */
  availableTypes: IssueTypeResponse[]
  /** 현재 typeId — 변경 여부 비교용 */
  currentTypeId: number
  /** 타입 변경 콜백 — number typeId 전달 */
  onTypeChange: (typeId: number) => void
}

/**
 * 이슈 유형 셀렉터 컴포넌트.
 *
 * - value는 부모 props에서 파생(issue.typeId) — stale key prop 회귀 방지
 * - 현재 값과 동일한 선택은 onTypeChange를 호출하지 않는다.
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label
 */
function IssueTypeSelect({
  value,
  availableTypes,
  currentTypeId,
  onTypeChange,
}: IssueTypeSelectProps): JSX.Element {
  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const selectedId = Number(e.target.value)
    if (selectedId !== currentTypeId) {
      onTypeChange(selectedId)
    }
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={value}
      onChange={handleChange}
      aria-label={issueDetailStrings.typeSelectLabel}
    >
      {availableTypes.map((type) => (
        <option key={type.id} value={type.id}>
          {type.name}
        </option>
      ))}
    </select>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueAssigneeSelect — 담당자 셀렉터 서브컴포넌트 (FR-IS-03)
// ─────────────────────────────────────────────────────────────────────────────

/** IssueAssigneeSelect props */
interface IssueAssigneeSelectProps {
  /**
   * 현재 담당자 UUID — issue.assigneeId props 파생, useState 초기화 금지 (stale key prop 회귀 방지).
   * null이면 미할당.
   */
  value: string | null
  /**
   * 현재 담당자 UserSummary — useUsersByIds로 별도 조회한 값 (C1 버그 수정).
   * 검색결과(users)와 분리해 담당자 이름을 안정적으로 표시한다.
   * null이면 "미지정" 표시.
   */
  currentAssignee: UserSummary | null
  /** 사용자 검색 결과 목록 — 드롭다운 후보 전용 */
  users: UserSummary[]
  /** 검색어 변경 콜백 */
  onSearch: (query: string) => void
  /** 담당자 변경 콜백 — UUID 또는 null(해제) */
  onAssigneeChange: (userId: string | null) => void
}

/**
 * 이슈 담당자 셀렉터 컴포넌트.
 *
 * - value는 부모 props에서 파생(issue.assigneeId) — stale key prop 회귀 방지
 * - currentAssignee prop으로 현재 담당자 이름 표시 (C1 버그 수정)
 *   users(검색결과)가 아닌 별도 id 조회 결과를 사용해 50건 한도 이외 담당자도 정확히 표시
 * - 미할당 시 "미지정" 텍스트 표시
 * - 검색 input: native input, onChange 시 onSearch 호출
 * - 사용자 목록(users): 드롭다운 후보 전용 — 선택 시 onAssigneeChange(id)
 * - 담당자 해제 버튼: value !== null이면 노출, 클릭 시 onAssigneeChange(null)
 * - WCAG AA: min-h-[44px], aria-label
 */
function IssueAssigneeSelect({
  value,
  currentAssignee,
  users,
  onSearch,
  onAssigneeChange,
}: IssueAssigneeSelectProps): JSX.Element {
  /** 현재 담당자 표시 이름 — displayName 우선, 없으면 username */
  function getDisplayName(user: UserSummary): string {
    return user.displayName ?? user.username
  }

  return (
    <div className="flex flex-col gap-1.5">
      {/* 현재 담당자 표시 — currentAssignee prop 기반 (C1 수정: users 검색결과 의존 제거) */}
      <div className="flex items-center justify-between gap-1">
        <span className="text-sm font-medium truncate" data-testid="assignee-current-name">
          {currentAssignee !== null
            ? getDisplayName(currentAssignee)
            : issueDetailStrings.assigneeUnassigned}
        </span>
        {/* 담당자 해제 버튼 — 할당된 경우에만 노출 */}
        {value !== null && (
          <button
            type="button"
            onClick={() => onAssigneeChange(null)}
            className="text-xs text-muted-foreground hover:text-destructive focus:outline-none focus:ring-1 focus:ring-ring min-h-[44px] px-1 shrink-0"
            aria-label={issueDetailStrings.assigneeUnassignButton}
          >
            {issueDetailStrings.assigneeUnassignButton}
          </button>
        )}
      </div>

      {/* 검색 input */}
      <input
        type="text"
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
        placeholder={issueDetailStrings.assigneeSearchPlaceholder}
        aria-label={issueDetailStrings.assigneeSearchPlaceholder}
        onChange={(e) => onSearch(e.target.value)}
      />

      {/* 검색 결과 사용자 목록 */}
      {users.length > 0 && (
        <AssigneeUserList users={users} onSelect={onAssigneeChange} />
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AssigneeUserList — 담당자 검색 결과 목록 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** AssigneeUserList props */
interface AssigneeUserListProps {
  /** 사용자 목록 */
  users: UserSummary[]
  /** 선택 콜백 */
  onSelect: (userId: string) => void
}

/**
 * 담당자 검색 결과 사용자 목록 컴포넌트.
 * - 각 사용자를 버튼으로 렌더
 * - displayName 우선, 없으면 username 표시
 * - WCAG AA: min-h-[44px]
 */
function AssigneeUserList({ users, onSelect }: AssigneeUserListProps): JSX.Element {
  return (
    <ul className="flex flex-col gap-0.5 max-h-48 overflow-y-auto">
      {users.map((user) => {
        const displayName = user.displayName ?? user.username
        return (
          <li key={user.id}>
            <button
              type="button"
              onClick={() => onSelect(user.id)}
              className="w-full text-left text-sm px-2 py-1.5 rounded-md hover:bg-muted focus:outline-none focus:ring-1 focus:ring-ring min-h-[44px]"
              aria-label={displayName}
            >
              {displayName}
            </button>
          </li>
        )
      })}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueStateTransition — 상태 전이 셀렉터 서브컴포넌트 (FR-IS-01)
// ─────────────────────────────────────────────────────────────────────────────

/** IssueStateTransition props */
interface IssueStateTransitionProps {
  /** 현재 상태에서 가용한 전이 목록 */
  transitions: IssueTransition[]
  /** 전이 실행 콜백 — toStateKey 전달 */
  onTransition: (toStateKey: string) => void
  /** 전이 진행 중 여부 — true 시 셀렉터 disabled (NFR3) */
  isTransitioning: boolean
  /** 전이 컨트롤을 노출할 수 없는 사유 (스펙 E5) */
  unavailableReason: TransitionUnavailableReason
  /** 제어값 — 전이 시도 후 리셋에 사용 (C1 회귀 방지) */
  selectedValue: string
  /** 제어값 변경 콜백 */
  onSelectedValueChange: (value: string) => void
}

/**
 * 이슈 상태 전이 셀렉터 컴포넌트.
 *
 * - 가용전이 0건 + unavailableReason='no-workflow' → 미설정 안내 (스펙 E5 S5)
 * - 가용전이 0건 + unavailableReason='terminal'|null → "더 진행할 전이 없음" 안내 (스펙 E5 S6)
 * - 가용전이 있으면 네이티브 select — IssueTypeSelect 동일 패턴
 * - 첫 옵션은 placeholder(비선택 상태), 전이 선택 시 onTransition(toStateKey) 호출
 * - isTransitioning=true → disabled (중복클릭 방지, NFR3)
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label
 */
function IssueStateTransition({
  transitions,
  onTransition,
  isTransitioning,
  unavailableReason,
  selectedValue,
  onSelectedValueChange,
}: IssueStateTransitionProps): JSX.Element {
  // 가용전이 0건 → 사유에 따라 안내문구 분기 (스펙 E5)
  if (transitions.length === 0) {
    if (unavailableReason === 'no-workflow') {
      return (
        <p className="text-xs text-muted-foreground mt-1">
          {issueDetailStrings.transitionWorkflowNotConfiguredError}
        </p>
      )
    }
    return (
      <p className="text-xs text-muted-foreground mt-1">
        {issueDetailStrings.noTransitionsAvailable}
      </p>
    )
  }

  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const toStateKey = e.target.value
    // placeholder 옵션 선택 무시
    if (toStateKey === '') return
    onSelectedValueChange(toStateKey)
    onTransition(toStateKey)
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={selectedValue}
      onChange={handleChange}
      disabled={isTransitioning}
      aria-label={issueDetailStrings.transitionSelectLabel}
    >
      <option value="" disabled>
        {issueDetailStrings.transitionSelectLabel}
      </option>
      {transitions.map((t) => (
        <option key={t.key} value={t.toStateKey}>
          {t.name}
        </option>
      ))}
    </select>
  )
}
