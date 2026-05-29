// 이슈 상세 우측 메타패널 컴포넌트 — 상태 배지·보고자·프로젝트·유형·버전·날짜 + 삭제 버튼
import type { JSX } from 'react'
import type React from 'react'
import type { IssueResponse } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import { Button } from '@/components/ui/button'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { formatDate } from '@/lib/date-format'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// IssueMetaPanel
// ─────────────────────────────────────────────────────────────────────────────

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
}

/**
 * 이슈 상세 우측 메타패널 컴포넌트.
 *
 * - 상태: 읽기전용 배지 (전이 UI 없음 — D6 제외)
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
}: IssueMetaPanelProps): JSX.Element {
  /** issue.typeId에 해당하는 타입 항목 — iconName 해석에 사용 */
  const currentType = availableTypes.find((t) => t.id === issue.typeId)

  function handleTypeSelectChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const selectedId = Number(e.target.value)
    if (selectedId !== issue.typeId) {
      onTypeChange(selectedId)
    }
  }

  return (
    <aside className="flex flex-col gap-3">
      {/* 메타 패널 카드 */}
      <div className="border border-border rounded-xl overflow-hidden">
        {/* 상태 — 읽기전용 배지 (D6: 전이 드롭다운 없음) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.statLabel}</p>
          <span
            data-testid="issue-state-badge"
            className="inline-flex items-center gap-1.5 text-sm font-medium"
          >
            <span className="size-2 rounded-full bg-primary/60 shrink-0" aria-hidden="true" />
            {issue.currentStateKey}
          </span>
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
            onChange={handleTypeSelectChange}
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
  /** 변경 이벤트 핸들러 */
  onChange: (e: React.ChangeEvent<HTMLSelectElement>) => void
}

/**
 * 이슈 유형 셀렉터 컴포넌트.
 *
 * - value는 부모 props에서 파생(issue.typeId) — stale key prop 회귀 방지
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label
 */
function IssueTypeSelect({ value, availableTypes, onChange }: IssueTypeSelectProps): JSX.Element {
  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={value}
      onChange={onChange}
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
