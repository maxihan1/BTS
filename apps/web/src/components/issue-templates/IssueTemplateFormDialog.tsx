// 이슈 템플릿 생성/수정 겸용 Dialog — ui/dialog 흡수, RHF+Zod, key prop 재마운트
import type { JSX } from 'react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import type { IssueTemplate } from '@/api/issue-templates.types'
import {
  useCreateIssueTemplate,
  useUpdateIssueTemplate,
} from '@/hooks/use-issue-templates'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { issueTemplateLabels, issueTemplateErrorMessage } from '@/i18n/issue-template-labels'
import { extractIssueTemplateErrorCode } from '@/api/issue-templates'
import { TemplateContentField } from './TemplateContentField'

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마
// ─────────────────────────────────────────────────────────────────────────────

const createFormSchema = z.object({
  // valueAsNumber: true 옵션이 빈 select를 NaN으로 변환하므로
  // .positive()가 NaN을 실패시켜 동일하게 에러 메시지를 표시한다 (CustomFieldFormDialog 선례)
  issueTypeId: z.number().int().positive('이슈 타입을 선택해주세요.'),
  name: z
    .string()
    .trim()
    .min(1, '이름은 필수입니다.')
    .max(100, '이름은 100자 이내여야 합니다.'),
  content: z.string().trim().min(1, '본문은 필수입니다.'),
})

const editFormSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, '이름은 필수입니다.')
    .max(100, '이름은 100자 이내여야 합니다.'),
  content: z.string().trim().min(1, '본문은 필수입니다.'),
})

type CreateFormValues = z.infer<typeof createFormSchema>
type EditFormValues = z.infer<typeof editFormSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueTemplateFormDialog 공통 Props */
interface IssueTemplateFormDialogBaseProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 프로젝트 키 — mutation 대상 지정에 사용 */
  readonly projectKey: string
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 저장 성공 시 상위에서 호출되는 콜백 */
  readonly onSubmitSuccess?: () => void
  /** 상위 mutation에서 전달된 서버 오류 메시지 — null이면 미표시 */
  readonly submitError?: string | null
}

/** 생성 모드 Props */
interface CreateModeProps extends IssueTemplateFormDialogBaseProps {
  readonly mode: 'create'
  readonly initial?: undefined
}

/** 수정 모드 Props */
interface EditModeProps extends IssueTemplateFormDialogBaseProps {
  readonly mode: 'edit'
  /** 수정 모드 초기값 — edit 시 필수 */
  readonly initial: IssueTemplate
}

/** IssueTemplateFormDialog Props */
type IssueTemplateFormDialogProps = CreateModeProps | EditModeProps

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 — key prop 재마운트를 위해 분리
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly mode: 'create' | 'edit'
  readonly projectKey: string
  readonly initial?: IssueTemplate
  readonly onSubmitSuccess?: () => void
  readonly onOpenChange: (open: boolean) => void
  readonly submitError?: string | null
  readonly onServerError: (message: string) => void
}

function FormBody({
  mode,
  projectKey,
  initial,
  onSubmitSuccess,
  onOpenChange,
  submitError,
  onServerError,
}: FormBodyProps): JSX.Element {
  const { form: labels } = issueTemplateLabels

  const { data: issueTypes = [], isLoading: isIssueTypesLoading } = useIssueTypes()

  const createMutation = useCreateIssueTemplate(projectKey, { silent: true })
  const updateMutation = useUpdateIssueTemplate(projectKey, { silent: true })

  // ── create 모드 폼 ──────────────────────────────────────────────────────────

  const createForm = useForm<CreateFormValues>({
    resolver: zodResolver(createFormSchema),
    defaultValues: {
      issueTypeId: undefined,
      name: '',
      content: '',
    },
  })

  // ── edit 모드 폼 ────────────────────────────────────────────────────────────

  const editForm = useForm<EditFormValues>({
    resolver: zodResolver(editFormSchema),
    defaultValues: {
      name: initial?.name ?? '',
      content: initial?.content ?? '',
    },
  })

  // ── submit 핸들러 ─────────────────────────────────────────────────────────

  /**
   * 서버 에러 코드를 추출해 사용자 메시지로 변환하고 상위에 전달한다.
   * 이 컴포넌트가 에러 메시지 매핑의 단일 출처 역할을 하지 않으며
   * issueTemplateErrorMessage(labels)를 통해 단일 출처를 유지한다.
   */
  function handleMutationError(error: unknown): void {
    const code = extractIssueTemplateErrorCode(error)
    onServerError(issueTemplateErrorMessage(code))
  }

  function onCreateValid(values: CreateFormValues): void {
    createMutation.mutate(
      {
        issueTypeId: values.issueTypeId,
        name: values.name,
        content: values.content,
      },
      {
        onSuccess: () => {
          onSubmitSuccess?.()
          onOpenChange(false)
        },
        onError: handleMutationError,
      },
    )
  }

  function onEditValid(values: EditFormValues): void {
    if (initial === undefined) return
    updateMutation.mutate(
      {
        templateId: initial.id,
        input: { name: values.name, content: values.content },
      },
      {
        onSuccess: () => {
          onSubmitSuccess?.()
          onOpenChange(false)
        },
        onError: handleMutationError,
      },
    )
  }

  const isPending = createMutation.isPending || updateMutation.isPending

  // ── create 모드 렌더 ──────────────────────────────────────────────────────

  if (mode === 'create') {
    const { register, handleSubmit, setValue, formState: { errors } } = createForm

    return (
      <form onSubmit={handleSubmit(onCreateValid)} noValidate>
        {/* 이슈 타입 */}
        <div className="mb-4">
          <label htmlFor="it-issue-type" className="block text-sm font-medium mb-1">
            {labels.issueTypeLabel}
          </label>
          <select
            id="it-issue-type"
            aria-label={labels.issueTypeLabel}
            disabled={isIssueTypesLoading}
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed"
            {...register('issueTypeId', { valueAsNumber: true })}
          >
            <option value="">{labels.issueTypePlaceholder}</option>
            {issueTypes.map((it) => (
              <option key={it.id} value={it.id}>
                {it.name}
              </option>
            ))}
          </select>
          {errors.issueTypeId !== undefined && (
            <p className="text-xs text-destructive mt-1" role="alert">
              {errors.issueTypeId.message}
            </p>
          )}
        </div>

        {/* 이름 */}
        <div className="mb-4">
          <label htmlFor="it-name" className="block text-sm font-medium mb-1">
            {labels.nameLabel}
          </label>
          <input
            id="it-name"
            type="text"
            aria-label={labels.nameLabel}
            placeholder="예: 버그 리포트 기본 템플릿"
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
            autoComplete="off"
            {...register('name')}
          />
          {errors.name !== undefined && (
            <p className="text-xs text-destructive mt-1" role="alert">
              {errors.name.message}
            </p>
          )}
        </div>

        {/* 본문 — TemplateContentField로 변수 삽입 버튼 + 도움말 포함 */}
        <TemplateContentField
          textareaId="it-content"
          label={labels.contentLabel}
          registration={register('content')}
          errorMessage={errors.content?.message}
          setFieldValue={(next) => { setValue('content', next, { shouldDirty: true, shouldValidate: true }) }}
        />

        {/* 서버 오류 */}
        {submitError !== undefined && submitError !== null && (
          <p className="text-sm text-destructive mb-4" role="alert">
            {submitError}
          </p>
        )}

        {/* 액션 버튼 */}
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => { onOpenChange(false) }}
          >
            {labels.cancelButton}
          </Button>
          <Button type="submit" size="sm" disabled={isPending}>
            {labels.submitButton}
          </Button>
        </DialogFooter>
      </form>
    )
  }

  // ── edit 모드 렌더 ────────────────────────────────────────────────────────

  const { register, handleSubmit, setValue, formState: { errors } } = editForm

  return (
    <form onSubmit={handleSubmit(onEditValid)} noValidate>
      {/* 이슈 타입 — edit 시 disabled */}
      <div className="mb-4">
        <label htmlFor="it-issue-type-edit" className="block text-sm font-medium mb-1">
          {labels.issueTypeLabel}
        </label>
        <select
          id="it-issue-type-edit"
          aria-label={labels.issueTypeLabel}
          disabled={true}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed"
          defaultValue={initial?.issueTypeId}
        >
          {issueTypes.map((it) => (
            <option key={it.id} value={it.id}>
              {it.name}
            </option>
          ))}
          {/* issueTypes 로드 전 initial.issueTypeId를 표시하기 위한 fallback */}
          {issueTypes.length === 0 && initial !== undefined && (
            <option value={initial.issueTypeId}>
              {String(initial.issueTypeId)}
            </option>
          )}
        </select>
      </div>

      {/* 이름 */}
      <div className="mb-4">
        <label htmlFor="it-name-edit" className="block text-sm font-medium mb-1">
          {labels.nameLabel}
        </label>
        <input
          id="it-name-edit"
          type="text"
          aria-label={labels.nameLabel}
          placeholder="예: 버그 리포트 기본 템플릿"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          autoComplete="off"
          {...register('name')}
        />
        {errors.name !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.name.message}
          </p>
        )}
      </div>

      {/* 본문 — TemplateContentField로 변수 삽입 버튼 + 도움말 포함 */}
      <TemplateContentField
        textareaId="it-content-edit"
        label={labels.contentLabel}
        registration={register('content')}
        errorMessage={errors.content?.message}
        setFieldValue={(next) => { setValue('content', next, { shouldDirty: true, shouldValidate: true }) }}
      />

      {/* 서버 오류 */}
      {submitError !== undefined && submitError !== null && (
        <p className="text-sm text-destructive mb-4" role="alert">
          {submitError}
        </p>
      )}

      {/* 액션 버튼 */}
      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => { onOpenChange(false) }}
        >
          {labels.cancelButton}
        </Button>
        <Button type="submit" size="sm" disabled={isPending}>
          {labels.submitButton}
        </Button>
      </DialogFooter>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueTemplateFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 생성/수정 겸용 Dialog.
 *
 * - ui/dialog 래퍼(Dialog/DialogContent/DialogHeader/DialogTitle/DialogFooter) 사용
 *   (FR-UX-06 PR5 흡수 — CustomFieldFormDialog 선례).
 * - create 모드: 이슈 타입 select + 이름 + 본문 입력 → useCreateIssueTemplate 뮤테이션.
 * - edit 모드: 이슈 타입 disabled(issueTypeId 불변) + 이름/본문 프리필 → useUpdateIssueTemplate 뮤테이션.
 * - 본문 영역은 TemplateContentField로 분리되어 create/edit 모드가 공유한다.
 *   변수 삽입 버튼({{author}}/{{date}}/{{project}})과 도움말 문구가 두 모드에서 동일하게 노출된다.
 * - submitError 우선순위: 부모 prop(externalSubmitError) > 내부 state(internalSubmitError).
 *   부모가 직접 관리하면 외부 prop을 쓰고, 관리하지 않으면 컴포넌트가 내부 state로 인라인 표시.
 *   dead-path 방지 — dialog-submiterror-ownership-dead-path 교훈.
 * - initial.id가 바뀌면 key prop으로 FormBody를 재마운트해 stale state 방지
 *   (react-usestate-stale-key-prop 교훈).
 * - 에러코드 → 메시지 매핑은 issueTemplateErrorMessage를 통해 단일 출처 유지
 *   (error-key 매핑 공유 util 교훈).
 */
export const IssueTemplateFormDialog = ({
  open,
  mode,
  projectKey,
  initial,
  onSubmitSuccess,
  onOpenChange,
  submitError: externalSubmitError,
}: IssueTemplateFormDialogProps): JSX.Element => {
  const { form: labels } = issueTemplateLabels
  const title = mode === 'create' ? labels.createTitle : labels.editTitle
  // initial.id가 바뀌면 FormBody를 재마운트해 폼 state 초기화
  const formKey = initial?.id ?? 'new'

  // 부모가 submitError를 관리하지 않는 경우를 위한 내부 상태
  const [internalSubmitError, setInternalSubmitError] = useState<string | null>(null)

  // 외부 prop이 있으면 우선 사용, 없으면 내부 state — dead-path 방지
  const effectiveSubmitError = externalSubmitError ?? internalSubmitError

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg overflow-y-auto max-h-[90vh]" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>

        <FormBody
          key={formKey}
          mode={mode}
          projectKey={projectKey}
          initial={initial}
          onSubmitSuccess={onSubmitSuccess}
          onOpenChange={onOpenChange}
          submitError={effectiveSubmitError}
          onServerError={setInternalSubmitError}
        />
      </DialogContent>
    </Dialog>
  )
}
