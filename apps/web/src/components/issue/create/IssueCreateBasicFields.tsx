// 이슈 생성 폼 「기본」 그룹 — 프로젝트·유형·제목·본문 (FR-UX-09 F2, design 리뷰 D7)
import type { JSX } from 'react'
import type { Control } from 'react-hook-form'
import { FormField, FormItem, FormLabel, FormControl, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { IssueTypeSelect } from '@/components/issue/meta/IssueTypeSelect'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'
import type { IssueCreateFormValues } from '@/components/issue/create/issue-create-schema'
import type { Project } from '@/api/projects'
import type { IssueTypeResponse } from '@/api/issue-types'

/** IssueCreateBasicFields props */
export interface IssueCreateBasicFieldsProps {
  control: Control<IssueCreateFormValues>
  /** 접근 가능한 프로젝트 목록 */
  projects: Project[]
  isProjectsLoading: boolean
  /** 이슈 유형 목록 */
  issueTypes: IssueTypeResponse[]
  isTypesLoading: boolean
  selectedTypeId: number | null
  onTypeChange: (typeId: number) => void
}

/**
 * 「기본」 필드 그룹.
 *
 * 프로젝트는 자유 텍스트가 아니라 접근 가능한 목록에서 고른다 (FR-3).
 * 제목·본문은 zod 검증 대상이라 `FormField` 로, 유형은 셀렉터라 로컬 상태로 다룬다.
 */
export function IssueCreateBasicFields({
  control,
  projects,
  isProjectsLoading,
  issueTypes,
  isTypesLoading,
  selectedTypeId,
  onTypeChange,
}: IssueCreateBasicFieldsProps): JSX.Element {
  return (
    <>
      <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {issueCreateStrings.groupBasicLabel}
      </p>

      {/* 프로젝트 선택 — 자유 텍스트가 아니라 접근 가능한 프로젝트 목록에서 고른다 (FR-3) */}
      <FormField
        control={control}
        name="projectKey"
        render={({ field }) => (
          <FormItem>
            <FormLabel>{issueCreateStrings.projectKeyLabel}</FormLabel>
            <FormControl>
              {/* aria-label — FormLabel.htmlFor 가 wrapper div 를 가리키므로 select 자체에 aria-label 로 WCAG AA 보장 */}
              <select
                className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
                aria-label={issueCreateStrings.projectKeyLabel}
                disabled={isProjectsLoading}
                {...field}
              >
                <option value="">{issueCreateStrings.projectPlaceholder}</option>
                {projects.map((project) => (
                  <option key={project.key} value={project.key}>
                    {project.name}
                  </option>
                ))}
              </select>
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      {/* 이슈 유형 선택 — meta/IssueTypeSelect 재사용 (FR-4) */}
      <div className="flex flex-col gap-1.5">
        {/* 눈에 보이는 라벨과 접근성 이름을 같게 둔다 — IssueTypeSelect 가 자체 aria-label
            (issueDetailStrings.typeSelectLabel)을 갖고 있어, 다른 문구를 쓰면
            WCAG 2.5.3 Label in Name 이 어긋난다. */}
        <span className="text-sm font-medium leading-none">
          {issueDetailStrings.typeSelectLabel}
        </span>
        {isTypesLoading || selectedTypeId === null ? (
          // 유형 목록이 도착하기 전에는 셀렉터를 비활성으로 둔다 — IssueTypeSelect 는
          // value:number 필수라 빈 목록으로 렌더하면 선택 없는 빈 셀렉터가 된다 (design 리뷰 B-3).
          <select
            className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px]"
            aria-label={issueDetailStrings.typeSelectLabel}
            disabled
          />
        ) : (
          <IssueTypeSelect
            value={selectedTypeId}
            availableTypes={issueTypes}
            currentTypeId={selectedTypeId}
            onTypeChange={onTypeChange}
          />
        )}
      </div>

      {/* 이슈 제목 입력 */}
      <FormField
        control={control}
        name="summary"
        render={({ field }) => (
          <FormItem>
            <FormLabel>{issueCreateStrings.summaryLabel}</FormLabel>
            <FormControl>
              <Input
                placeholder={issueCreateStrings.summaryPlaceholder}
                aria-label={issueCreateStrings.summaryLabel}
                {...field}
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      {/* 본문 입력 — 비우면 서버가 프로젝트 템플릿으로 채운다 (FR-5, FR-TM-01) */}
      <FormField
        control={control}
        name="description"
        render={({ field }) => (
          <FormItem>
            <FormLabel>{issueCreateStrings.descriptionLabel}</FormLabel>
            <FormControl>
              <Textarea
                rows={4}
                placeholder={issueCreateStrings.descriptionPlaceholder}
                aria-label={issueCreateStrings.descriptionLabel}
                {...field}
              />
            </FormControl>
            <p className="text-xs text-muted-foreground">
              {issueCreateStrings.descriptionTemplateHint}
            </p>
            <FormMessage />
          </FormItem>
        )}
      />
    </>
  )
}
