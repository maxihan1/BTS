// 이슈 생성 폼 「기본」 그룹 — 프로젝트·유형·제목·본문 (FR-UX-09 F2, design 리뷰 D7)
import type { JSX } from 'react'
import type { Control } from 'react-hook-form'
import { FormField, FormItem, FormLabel, FormControl, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { RichTextEditor } from '@/components/editor/RichTextEditor'
import { TextLengthCounter } from '@/components/editor/TextLengthCounter'
import { DESCRIPTION_MAX_LENGTH } from '@/lib/issue-text-constraints'
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

      {/* 본문 입력 — 상세 화면과 **같은 리치 에디터**다 (J23).
          Jira Cloud 도 생성 다이얼로그의 Description 이 "code blocks, table edits, and pasted
          images" 를 받는다. 비우면 서버가 프로젝트 템플릿으로 채운다 (FR-5, FR-TM-01). */}
      <FormField
        control={control}
        name="descriptionHtml"
        render={({ field }) => (
          <FormItem>
            <FormLabel>{issueCreateStrings.descriptionLabel}</FormLabel>
            <FormControl>
              <RichTextEditor
                initialHtml={field.value}
                onChange={field.onChange}
                placeholder={issueCreateStrings.descriptionPlaceholder}
                ariaLabel={issueCreateStrings.descriptionLabel}
                // ★이슈가 아직 없어 첨부를 매달 곳이 없다. `imageIssueKey` 미전달이면
                //   RichTextEditor 가 이미지 경로를 통째로 비활성한다 — 툴바 버튼도 안 그린다.
                //
                // 다이얼로그는 상세 화면보다 좁다. 기본 높이(8rem)를 그대로 쓰면 본문이
                // 종전 `rows={4}` textarea 보다 커져 아래 필드를 밀어낸다(리뷰 D-1).
                minHeightClass="min-h-[6rem]"
              />
            </FormControl>
            {/* 상한 32,767자는 **HTML 기준**이다 — 서식이 많으면 보이는 글자가 8,000자여도
                넘길 수 있다. 상세 편집과 같은 카운터를 붙여 저장 전에 알린다. */}
            <TextLengthCounter length={field.value.length} max={DESCRIPTION_MAX_LENGTH} />
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
