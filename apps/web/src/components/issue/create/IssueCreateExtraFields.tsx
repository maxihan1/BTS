// 이슈 생성 폼 「추가」 그룹 — 컴포넌트·보안등급·커스텀필드 (FR-UX-09 F2, design 리뷰 D7)
import type { JSX } from 'react'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { IssueSecurityLevelSelect } from '@/components/issue/IssueSecurityLevelSelect'
import { CustomFieldInput } from '@/components/custom-fields/CustomFieldInput'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'
import type { CustomFieldValues } from '@/api/issues'
import type { CustomField } from '@/api/custom-fields.types'
import type { Component } from '@/api/components'

/** IssueCreateExtraFields props */
export interface IssueCreateExtraFieldsProps {
  /** 현재 프로젝트 키 — 보안등급 목록 조회 키 */
  projectKey: string
  /** 프로젝트가 정해졌는가 — 프로젝트 종속 입력의 활성 조건 */
  isProjectKeyFilled: boolean
  componentOptions: Component[]
  selectedComponentIds: string[]
  onComponentsChange: (ids: string[]) => void
  securityLevelId: string | null
  onSecurityLevelChange: (id: string | null) => void
  /** 프로젝트별 커스텀 필드 정의 */
  customFieldDefs: CustomField[]
  customFieldValues: CustomFieldValues
  onCustomFieldChange: (key: string, value: unknown) => void
}

/**
 * 「추가」 필드 그룹.
 *
 * 셋 다 **프로젝트마다 정의가 다른** 값이라 프로젝트가 정해지기 전에는 비활성이다.
 * 프로젝트를 바꾸면 호출자가 값을 비운다 — 이전 프로젝트의 id 가 그대로 실리면
 * 서버가 거부하거나(422) 엉뚱한 값이 저장된다.
 */
export function IssueCreateExtraFields({
  projectKey,
  isProjectKeyFilled,
  componentOptions,
  selectedComponentIds,
  onComponentsChange,
  securityLevelId,
  onSecurityLevelChange,
  customFieldDefs,
  customFieldValues,
  onCustomFieldChange,
}: IssueCreateExtraFieldsProps): JSX.Element {
  return (
    <>
      <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {issueCreateStrings.groupExtraLabel}
      </p>

      {/* 컴포넌트 선택 — projectKey 미입력 시 disabled */}
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
          {issueDetailStrings.componentsLabel}
        </span>
        <ComponentMultiSelect
          value={selectedComponentIds}
          options={componentOptions}
          onChange={onComponentsChange}
          disabled={!isProjectKeyFilled}
        />
      </div>

      {/* 보안등급 선택 — projectKey 입력 시 등급 목록 로드 (FR-PM-06 PR-B) */}
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
          {issueDetailStrings.securityLevelCreateLabel}
        </span>
        <IssueSecurityLevelSelect
          projectKey={projectKey}
          value={securityLevelId}
          onChange={onSecurityLevelChange}
          disabled={!isProjectKeyFilled}
        />
      </div>

      {/* 커스텀 필드 섹션 — projectKey 입력 + 활성 정의가 있을 때만 렌더 (FR-IS-10) */}
      {isProjectKeyFilled && customFieldDefs.length > 0 && (
        <div data-testid="custom-fields-section" className="flex flex-col gap-3">
          {customFieldDefs.map((field) => (
            <div key={field.id} className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none">
                {field.name}
                {field.required && <span className="text-destructive ml-0.5">*</span>}
              </span>
              <CustomFieldInput
                field={field}
                value={customFieldValues[field.key]}
                onChange={(v) => onCustomFieldChange(field.key, v)}
                disabled={!isProjectKeyFilled}
              />
            </div>
          ))}
        </div>
      )}
    </>
  )
}
