// 이슈 상세 우측 「더 많은 필드」 접힌 카드 — 영향도 · 환경 · 컴포넌트 · 버전 · 보안등급 · 커스텀 필드
import type { JSX } from 'react'
import type { IssueResponse, CustomFieldValues } from '@/api/issues'
import type { Component } from '@/api/components'
import type { Version } from '@/api/versions.types'
import type { CustomField } from '@/api/custom-fields.types'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { VersionMultiSelect } from '@/components/issue/VersionMultiSelect'
import { IssueSecurityLevelSelect } from '@/components/issue/IssueSecurityLevelSelect'
import { issueDetailStrings } from '@/i18n/ko'
import { IssueImpactSelect } from './IssueImpactSelect'
import { IssueEnvironmentEdit } from './IssueEnvironmentEdit'
import { IssueCustomFieldsEdit } from './IssueCustomFieldsEdit'
import { META_CARD_CLASS, META_CARD_SUMMARY_CLASS, META_LABEL_CLASS, META_ROW_CLASS } from './meta-row-class'

interface IssueMoreFieldsProps {
  issue: IssueResponse
  canEdit: boolean
  /** 환경 필드를 숨기는가 / 고칠 수 있는가 — 필드 권한 판정은 부모(`IssueMetaPanel`)가 쥔다 */
  environmentHidden: boolean
  environmentEditable: boolean
  onImpactChange: (impact: number) => void
  onEnvironmentSave: (environment: string) => void
  componentIds: string[]
  components: Component[]
  onComponentsChange: (ids: string[]) => void
  versions: Version[]
  affectsVersionIds: string[]
  onAffectsVersionsChange: (ids: string[]) => void
  fixVersionIds: string[]
  onFixVersionsChange: (ids: string[]) => void
  onSecurityLevelChange: (levelId: string | null) => void
  customFieldDefs: CustomField[]
  onCustomFieldsSave: (values: CustomFieldValues) => void
}

/**
 * 덜 쓰는 필드를 접어 두는 카드.
 *
 * 2026-10-28 Jira Cloud REA 실측(Maxi 비교 화면 2026-11-02) — Jira 오른쪽은 「세부 사항」에 늘 보는 필드만 두고
 * 나머지는 「더 많은 필드」 접힌 카드에 둔다. 종전 BTS 는 16칸을 한 카드에 펼쳐 우측이 길고 어수선했다.
 * 접기는 `<details>` — 키보드 · 화면낭독기가 그대로 쓰고 상태를 따로 들 필요가 없다.
 */
export function IssueMoreFields(props: IssueMoreFieldsProps): JSX.Element {
  const { issue, canEdit } = props
  return (
    <details className={META_CARD_CLASS} data-testid="more-fields">
      <summary className={META_CARD_SUMMARY_CLASS}>
        {issueDetailStrings.moreFieldsLabel}
        <span className="ml-2 truncate text-xs font-normal text-muted-foreground">{issueDetailStrings.moreFieldsHint}</span>
      </summary>

      <div className={META_ROW_CLASS}>
        <p className={META_LABEL_CLASS}>{issueDetailStrings.impactLabel}</p>
        <IssueImpactSelect value={issue.impact} onImpactChange={props.onImpactChange} />
      </div>

      {/* restrictedFields 에 environment 가 있으면 칸 전체를 숨긴다 (FR-PM-07) */}
      {!props.environmentHidden && (
        <div className={META_ROW_CLASS} data-testid="environment-section">
          <p className={META_LABEL_CLASS}>{issueDetailStrings.environmentLabel}</p>
          <IssueEnvironmentEdit value={issue.environment} onSave={props.onEnvironmentSave} canEdit={props.environmentEditable} />
        </div>
      )}

      <div className={META_ROW_CLASS} data-testid="components-section">
        <p className={META_LABEL_CLASS}>{issueDetailStrings.componentsLabel}</p>
        <ComponentMultiSelect value={props.componentIds} options={props.components} onChange={props.onComponentsChange} disabled={!canEdit} />
      </div>

      <div className={META_ROW_CLASS} data-testid="affects-versions-section">
        <p className={META_LABEL_CLASS}>{issueDetailStrings.affectsVersionsLabel}</p>
        <VersionMultiSelect variant="affects" value={props.affectsVersionIds} options={props.versions} onChange={props.onAffectsVersionsChange} disabled={!canEdit} />
      </div>

      <div className={META_ROW_CLASS} data-testid="fix-versions-section">
        <p className={META_LABEL_CLASS}>{issueDetailStrings.fixVersionsLabel}</p>
        <VersionMultiSelect variant="fix" value={props.fixVersionIds} options={props.versions} onChange={props.onFixVersionsChange} disabled={!canEdit} />
      </div>

      <div className={META_ROW_CLASS} data-testid="security-level-section">
        <p className={META_LABEL_CLASS}>{issueDetailStrings.securityLevelLabel}</p>
        <IssueSecurityLevelSelect projectKey={issue.projectKey} value={issue.securityLevelId ?? null} onChange={props.onSecurityLevelChange} disabled={!canEdit} />
      </div>

      {/* 커스텀 필드 — 활성 정의 기준. restrictedFields 필터는 컴포넌트 안에서 (FR-IS-10 · FR-PM-07) */}
      {props.customFieldDefs.length > 0 && (
        <IssueCustomFieldsEdit
          fieldDefs={props.customFieldDefs}
          values={issue.customFields}
          onSave={props.onCustomFieldsSave}
          canEdit={canEdit}
          restrictedFields={issue.restrictedFields}
          noneditableFields={issue.noneditableFields}
        />
      )}
    </details>
  )
}
