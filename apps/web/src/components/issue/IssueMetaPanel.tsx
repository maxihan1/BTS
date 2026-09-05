// 이슈 상세 우측 메타패널 컴포넌트 — 상태 배지·전환 셀렉터·우선순위·영향도·환경·라벨·담당자·감시자·보안등급·커스텀필드·보고자·프로젝트·유형·버전·날짜 + 삭제 버튼
import type { JSX, RefObject } from 'react'
import { useState } from 'react'
import type { IssueResponse, IssueTransition, CustomFieldValues } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { UserSummary } from '@/api/users'
import type { Component } from '@/api/components'
import type { Version } from '@/api/versions.types'
import { Button } from '@/components/ui/button'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { VersionMultiSelect } from '@/components/issue/VersionMultiSelect'
import { IssueSecurityLevelSelect } from '@/components/issue/IssueSecurityLevelSelect'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { useDateFormat } from '@/hooks/use-date-format'
import { issueDetailStrings } from '@/i18n/ko'
import { useIssueCreatePermissionGate } from '@/components/issue/create/use-issue-create-permission-gate'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { WatchersSection } from '@/components/issue/WatchersSection'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'
import { IssuePrioritySelect } from '@/components/issue/meta/IssuePrioritySelect'
import { IssueImpactSelect } from '@/components/issue/meta/IssueImpactSelect'
import { IssueTypeSelect } from '@/components/issue/meta/IssueTypeSelect'
import { IssueEnvironmentEdit } from '@/components/issue/meta/IssueEnvironmentEdit'
import { IssueLabelsEdit } from '@/components/issue/meta/IssueLabelsEdit'
import { IssueCustomFieldsEdit } from '@/components/issue/meta/IssueCustomFieldsEdit'
import { IssueAssigneeSelect } from '@/components/issue/meta/IssueAssigneeSelect'
import { IssueReporterRow } from '@/components/issue/meta/IssueReporterRow'
import { IssueStateTransition } from '@/components/issue/meta/IssueStateTransition'

// ─────────────────────────────────────────────────────────────────────────────
// IssueMetaPanel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전환 컨트롤을 노출할 수 없는 사유.
 * - 'no-workflow': GET /transitions 422 — 워크플로우 미설정 (스펙 E5 S5)
 * - 'terminal': 200 + 빈 배열 — 종료상태, 더 이상 전환 없음 (스펙 E5 S6)
 * - null: 전환 가용 (정상 흐름)
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
  /** 클론 버튼 클릭 핸들러 */
  onCloneClick: () => void
  /** 현재 상태에서 가용한 전환 목록 */
  transitions: IssueTransition[]
  /** 전환 실행 핸들러 — 선택한 **전환 자체**를 전달 (같은 상태쌍의 두 전환을 가르기 위함) */
  onTransition: (transition: IssueTransition) => void
  /** 전환 진행 중 여부 — true 시 셀렉터 disabled (NFR3 중복클릭 방지) */
  isTransitioning: boolean
  /**
   * 전환 컨트롤을 노출할 수 없는 사유 (스펙 E5).
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
  /**
   * 보고자 UserSummary — `currentAssignee` 와 같은 `useUsersByIds` 경로로 route 가 해석해 넘긴다.
   *
   * ★옵셔널로 두지 않는다. 기본값 null 을 주면 route 가 안 넘겨도 컴파일이 통과하고,
   * 화면은 오늘과 똑같이 원시 UUID 를 그린다 — 고치려던 버그가 조용히 그대로 남는다.
   * null 은 「해석에 실패했다」(탈퇴·비활성 사용자, 조회 미완)만을 뜻한다.
   */
  reporter: UserSummary | null
  /** 현재 이슈에 할당된 컴포넌트 UUID 목록 — route에서 전달. 미전달 시 빈 배열. */
  componentIds?: string[]
  /** 프로젝트 컴포넌트 전체 목록 — fetchComponents(projectKey) 결과. 미전달 시 빈 배열. */
  components?: Component[]
  /** 컴포넌트 변경 콜백 — 새 UUID 배열 전달 (route가 useChangeComponents mutation 소유). 미전달 시 no-op. */
  onComponentsChange?: (ids: string[]) => void
  /** 프로젝트 버전 전체 목록 — useVersions(projectKey) 결과. 미전달 시 빈 배열. */
  versions?: Version[]
  /** 현재 이슈에 연결된 영향 버전 UUID 목록 — route에서 전달. 미전달 시 빈 배열. */
  affectsVersionIds?: string[]
  /** 영향 버전 변경 콜백 — 새 UUID 배열 전달 (route가 useChangeAffectsVersions mutation 소유). 미전달 시 no-op. */
  onAffectsVersionsChange?: (ids: string[]) => void
  /** 현재 이슈에 연결된 수정 버전 UUID 목록 — route에서 전달. 미전달 시 빈 배열. */
  fixVersionIds?: string[]
  /** 수정 버전 변경 콜백 — 새 UUID 배열 전달 (route가 useChangeFixVersions mutation 소유). 미전달 시 no-op. */
  onFixVersionsChange?: (ids: string[]) => void
  /**
   * 보안등급 변경 콜백 — 선택한 등급 UUID 또는 null(해제)을 전달.
   * route가 useChangeSecurityLevel mutation 소유. 미전달 시 no-op.
   */
  onSecurityLevelChange?: (levelId: string | null) => void
  /**
   * 커스텀 필드 저장 콜백 — 편집된 키-값 맵을 전달. (FR-IS-10)
   * route가 customFieldsMutation 소유. 미전달 시 no-op.
   */
  onCustomFieldsSave?: (customFields: CustomFieldValues) => void
  // ── FR-UX-10 F11 단축키 손잡이 4종 ──────────────────────────────────────────
  // 아래 넷은 이 패널이 **소비하지 않고 그대로 통과시키는** ref다. 실제 소비처는
  // routes/issues.$key.tsx의 useContextShortcuts('issue-detail') 핸들러다.
  // 미전달이면 해당 컨트롤에 aria-keyshortcuts도 붙지 않는다(하위 컴포넌트 규칙).
  /** 담당자 검색 input으로 통과시킬 ref — 단축키 `a` */
  assigneeSearchRef?: RefObject<HTMLInputElement | null>
  /** 라벨 입력으로 통과시킬 ref — 단축키 `l` (IssueLabelsEdit에서 2단 더 내려간다) */
  labelsInputRef?: RefObject<HTMLInputElement | null>
  /** 즐겨찾기 토글 버튼으로 통과시킬 ref — 단축키 `s` */
  favoriteToggleRef?: RefObject<HTMLButtonElement | null>
  /** Watch 토글 버튼으로 통과시킬 ref — 단축키 `w` */
  watchToggleRef?: RefObject<HTMLButtonElement | null>
}

// ─────────────────────────────────────────────────────────────────────────────
// 필드 권한 헬퍼 (FR-PM-07) — meta/IssueCustomFieldsEdit.tsx가 재사용하므로 export 유지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 해당 필드가 열람 제한 대상인지 판정한다 (FR-PM-07 PR-B).
 *
 * restrictedFields에 포함된 필드는 UI에서 섹션 자체를 숨긴다.
 *
 * @param fieldKey - 판정할 필드 키 (예: 'environment', 'labels', 커스텀 필드 key)
 * @param restrictedFields - 열람 불가 필드 키 목록 (IssueResponse.restrictedFields)
 * @returns 해당 필드를 숨겨야 하면 true
 */
// eslint-disable-next-line react-refresh/only-export-components -- meta/IssueCustomFieldsEdit.tsx가 재사용하는 순수 함수 공개 (분해 A, 파일 분리는 files 범위 밖 — ShareFilterDialog.tsx 선례 동형)
export function isFieldHidden(fieldKey: string, restrictedFields: string[]): boolean {
  return restrictedFields.includes(fieldKey)
}

/**
 * 해당 필드의 편집 컨트롤을 비활성해야 하는지 판정한다 (FR-PM-07 PR-B).
 *
 * canEdit(useIssuePermissions UPDATE 권한)과 noneditableFields 중 하나라도
 * 막으면 disabled — AND 조합.
 *
 * @param fieldKey - 판정할 필드 키
 * @param canEdit - UPDATE 권한 여부 (fail-closed: 권한 미확정 시 false)
 * @param noneditableFields - 편집 불가 필드 키 목록 (IssueResponse.noneditableFields)
 * @returns 편집 컨트롤을 disabled로 설정해야 하면 true
 */
// eslint-disable-next-line react-refresh/only-export-components -- meta/IssueCustomFieldsEdit.tsx가 재사용하는 순수 함수 공개 (분해 A, 파일 분리는 files 범위 밖 — ShareFilterDialog.tsx 선례 동형)
export function isFieldDisabled(fieldKey: string, canEdit: boolean, noneditableFields: string[]): boolean {
  return !canEdit || noneditableFields.includes(fieldKey)
}

/**
 * 이슈 상세 우측 메타패널 컴포넌트.
 *
 * - 상태: 읽기전용 배지 + IssueStateTransition 전환 셀렉터
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
  onCloneClick,
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
  reporter,
  componentIds = [],
  components = [],
  onComponentsChange = () => { /* no-op */ },
  versions = [],
  affectsVersionIds = [],
  onAffectsVersionsChange = () => { /* no-op */ },
  fixVersionIds = [],
  onFixVersionsChange = () => { /* no-op */ },
  onSecurityLevelChange = () => { /* no-op */ },
  onCustomFieldsSave = () => { /* no-op */ },
  assigneeSearchRef,
  labelsInputRef,
  favoriteToggleRef,
  watchToggleRef,
}: IssueMetaPanelProps): JSX.Element {
  // 프로젝트 커스텀 필드 정의 조회 (FR-IS-10)
  const { data: customFieldDefs = [] } = useCustomFields(issue.projectKey)

  /**
   * 이 프로젝트에 이슈를 만들 권한이 **명시적으로** 없는가.
   *
   * 클론이 요구하는 권한은 UPDATE 가 아니라 **대상 프로젝트의 CREATE** 다.
   * 미지(로딩·조회 실패)는 거부로 읽지 않는다 — 아래 클론 버튼 주석에 근거가 있다.
   */
  const isCloneExplicitlyDenied = useIssueCreatePermissionGate(issue.projectKey)

  // 로그인 사용자의 date_format 환경설정을 반영한 날짜 포맷터 (FR-PF-01 Task 8)
  const { formatDateTime } = useDateFormat()

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
   * 전환 셀렉터 제어값 — 전환 시도 후(성공/실패 모두) placeholder로 리셋.
   * 실패 후 같은 옵션 재선택 시 onChange 재발화를 보장한다 (C1 회귀 방지).
   */
  const [selectedTransition, setSelectedTransition] = useState('')

  function handleTransition(transition: IssueTransition) {
    onTransition(transition)
    // 전환 요청 직후 즉시 리셋 — 성공/실패 모두 placeholder로 복귀
    setSelectedTransition('')
  }

  return (
    <aside className="flex flex-col gap-3">
      {/* 메타 패널 카드 */}
      <div className="border border-border rounded-xl overflow-hidden">
        {/* 상태 — 읽기전용 배지 + 전환 셀렉터 (FR-IS-01) */}
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

        {/* 해결 결과 — DONE 전환 후 resolution이 있을 때만 표시 (FR-IS-07) */}
        {issue.resolution != null && (
          <div className="px-3.5 py-3 border-b border-border">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.resolutionLabel}</p>
            <p className="text-sm font-medium" data-testid="issue-resolution">{issue.resolution.name}</p>
          </div>
        )}

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

        {/* 환경 — IssueEnvironmentEdit (FR-IS-04). restrictedFields에 "environment"가 있으면 섹션 전체 숨김 (FR-PM-07) */}
        {!isFieldHidden('environment', issue.restrictedFields) && (
          <div className="px-3.5 py-3 border-b border-border" data-testid="environment-section">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.environmentLabel}</p>
            <IssueEnvironmentEdit
              value={issue.environment}
              onSave={onEnvironmentSave}
              canEdit={!isFieldDisabled('environment', canEdit, issue.noneditableFields)}
            />
          </div>
        )}

        {/* 라벨 — IssueLabelsEdit (FR-IS-04). restrictedFields에 "labels"가 있으면 섹션 전체 숨김 (FR-PM-07) */}
        {!isFieldHidden('labels', issue.restrictedFields) && (
          <div className="px-3.5 py-3 border-b border-border" data-testid="labels-section">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.labelsLabel}</p>
            <IssueLabelsEdit
              value={issue.labels}
              onSave={onLabelsSave}
              canEdit={!isFieldDisabled('labels', canEdit, issue.noneditableFields)}
              focusRef={labelsInputRef}
            />
          </div>
        )}

        {/* 담당자 — IssueAssigneeSelect (FR-IS-03). restrictedFields에 "assigneeId"가 있으면 섹션 전체 숨김 (FR-PM-07) */}
        {!isFieldHidden('assigneeId', issue.restrictedFields) && (
          <div className="px-3.5 py-3 border-b border-border" data-testid="assignee-section">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.assigneeLabel}</p>
            <IssueAssigneeSelect
              value={issue.assigneeId ?? null}
              currentAssignee={currentAssignee}
              users={users}
              onSearch={onAssigneeSearch}
              onAssigneeChange={onAssigneeChange}
              canEdit={!isFieldDisabled('assigneeId', canEdit, issue.noneditableFields)}
              focusRef={assigneeSearchRef}
            />
          </div>
        )}

        {/* 즐겨찾기 — FavoriteButton (FR-UX-02). 감시자 섹션 위 배치. */}
        <div className="px-3.5 py-2 border-b border-border flex items-center" data-testid="favorite-section">
          <FavoriteButton targetType="ISSUE" targetId={issue.key} focusRef={favoriteToggleRef} />
        </div>

        {/* 감시자 — WatchersSection (FR-WT-01). 담당자/사람 관련 섹션 근처에 배치. */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="watchers-section-wrapper">
          <WatchersSection issueKey={issue.key} focusRef={watchToggleRef} />
        </div>

        {/* 컴포넌트 — ComponentMultiSelect (FR-CM-02) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="components-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.componentsLabel}</p>
          <ComponentMultiSelect
            value={componentIds}
            options={components}
            onChange={onComponentsChange}
            disabled={!canEdit}
          />
        </div>

        {/* 영향 버전 — VersionMultiSelect affects (FR-VR-03) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="affects-versions-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.affectsVersionsLabel}</p>
          <VersionMultiSelect
            variant="affects"
            value={affectsVersionIds}
            options={versions}
            onChange={onAffectsVersionsChange}
            disabled={!canEdit}
          />
        </div>

        {/* 수정 버전 — VersionMultiSelect fix (FR-VR-03) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="fix-versions-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.fixVersionsLabel}</p>
          <VersionMultiSelect
            variant="fix"
            value={fixVersionIds}
            options={versions}
            onChange={onFixVersionsChange}
            disabled={!canEdit}
          />
        </div>

        {/* 보안등급 — IssueSecurityLevelSelect (FR-PM-06 PR-B) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="security-level-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.securityLevelLabel}</p>
          <IssueSecurityLevelSelect
            projectKey={issue.projectKey}
            value={issue.securityLevelId ?? null}
            onChange={onSecurityLevelChange}
            disabled={!canEdit}
          />
        </div>

        {/* 커스텀 필드 — 활성 정의 기준 렌더, 미정의 잔존 키 생략 (FR-IS-10 E-2).
            restrictedFields에 포함된 커스텀 필드는 IssueCustomFieldsEdit 내부에서 필터링 (FR-PM-07) */}
        {customFieldDefs.length > 0 && (
          <IssueCustomFieldsEdit
            fieldDefs={customFieldDefs}
            values={issue.customFields}
            onSave={onCustomFieldsSave}
            canEdit={canEdit}
            restrictedFields={issue.restrictedFields}
            noneditableFields={issue.noneditableFields}
          />
        )}

        <IssueReporterRow reporter={reporter} fallbackId={issue.reporterId} />

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
          <p className="text-sm font-medium">{formatDateTime(issue.createdAt)}</p>
        </div>

        {/* 수정일 */}
        <div className="px-3.5 py-3">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.updatedAtLabel}</p>
          <p className="text-sm font-medium">{formatDateTime(issue.updatedAt)}</p>
        </div>
      </div>

      {/* 클론 버튼 — 삭제 버튼 바로 위 배치.

          클론은 **대상 프로젝트의 CREATE 권한**을 요구한다(`IssueApplicationService.cloneIssue`).
          예전 주석은 「프론트 권한 API 가 CREATE 를 안 줘서 게이트가 불가능하다」였는데
          그 근거는 **거짓이 됐다** — `project-permissions.ts` 가 `CREATE` 를 내준다.

          ★판정식은 `CREATE === false`(**명시 거부만**)다. `!isLoading && === true`(미지=거부)를
          쓰면 권한 조회가 아직 안 끝났거나 실패한 구간에서 **CREATE 를 실제로 가진 사용자를
          영구 차단**한다 — `use-project-permissions.ts` 에 retry 도 에러 폴백도 없다.
          정본 논거는 `components/issue/create/use-issue-create-permission-gate.ts` KDoc.

          미지에서는 버튼을 열어 두고 서버 403 + `useCloneIssue.onError` 토스트가 최종 판정한다
          (기존 fail-safe 유지). 여기서 막는 것은 「서버가 확실히 거절할 액션」뿐이다. */}
      <Button
        variant="secondary"
        className="w-full min-h-[44px]"
        onClick={onCloneClick}
        disabled={isCloneExplicitlyDenied}
        aria-label={
          isCloneExplicitlyDenied
            ? issueDetailStrings.cloneButtonNoPermission
            : issueDetailStrings.cloneButton
        }
        title={isCloneExplicitlyDenied ? issueDetailStrings.cloneButtonNoPermission : undefined}
        data-testid="issue-clone"
      >
        {issueDetailStrings.cloneButton}
      </Button>

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

