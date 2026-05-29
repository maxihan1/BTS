// 이슈 상세 우측 메타패널 컴포넌트 — 상태 배지·전이 셀렉터·보고자·프로젝트·유형·버전·날짜 + 삭제 버튼
import type { JSX } from 'react'
import type { IssueResponse, IssueTransition } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import { Button } from '@/components/ui/button'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { formatDate } from '@/lib/date-format'
import { issueDetailStrings } from '@/i18n/ko'

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
}

/**
 * 이슈 상세 우측 메타패널 컴포넌트.
 *
 * - 상태: 읽기전용 배지 + IssueStateTransition 전이 셀렉터
 * - 유형: IssueTypeIcon + typeName 표시 + 셀렉터(availableTypes 옵션)
 * - 셀렉터 현재값은 issue.typeId props 파생 (useState 초기화 금지 — stale key prop 회귀 방지)
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
}: IssueMetaPanelProps): JSX.Element {
  /** issue.typeId에 해당하는 타입 항목 — iconName 해석에 사용 */
  const currentType = availableTypes.find((t) => t.id === issue.typeId)

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
            onTransition={onTransition}
            isTransitioning={isTransitioning}
            unavailableReason={unavailableReason}
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

      {/* 삭제 버튼 — WCAG AA 44px 터치 타깃 */}
      <Button
        variant="destructive"
        className="w-full min-h-[44px]"
        onClick={onDeleteClick}
        aria-label={issueDetailStrings.deleteButton}
      >
        {issueDetailStrings.deleteButton}
      </Button>
    </aside>
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
    onTransition(toStateKey)
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      defaultValue=""
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
