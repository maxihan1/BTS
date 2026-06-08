// 커스텀 필드 생성/수정 겸용 Dialog — RHF + Zod + 선택형 옵션 편집기, key prop 재마운트
import type { JSX } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import type {
  CustomField,
  CreateCustomFieldInput,
  UpdateCustomFieldInput,
  FieldType,
} from '@/api/custom-fields.types'
import { fieldTypeEnum } from '@/api/custom-fields.types'
import { customFieldLabels } from '@/i18n/custom-field-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 선택형 FieldType 집합 (옵션 편집기 표시 조건)
// ─────────────────────────────────────────────────────────────────────────────

const OPTION_FIELD_TYPES = new Set<FieldType>(['SINGLE_SELECT', 'MULTI_SELECT', 'RADIO'])

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마
// ─────────────────────────────────────────────────────────────────────────────

const optionRowSchema = z.object({
  value: z.string().min(1, '값은 필수입니다.'),
  label: z.string().min(1, '라벨은 필수입니다.'),
})

const formSchema = z.object({
  key: z
    .string()
    .min(1, '키는 필수입니다.')
    .max(64, '키는 64자 이내여야 합니다.')
    .regex(/^[a-z][a-z0-9_]*$/, '키는 소문자로 시작하고 소문자, 숫자, _만 사용 가능합니다.'),
  name: z.string().min(1, '이름은 필수입니다.').max(255, '이름은 255자 이내여야 합니다.'),
  description: z
    .string()
    .max(1000, '설명은 1000자 이내여야 합니다.')
    .optional(),
  fieldType: fieldTypeEnum,
  required: z.boolean(),
  displayOrder: z.number().int().min(0),
  options: z.array(optionRowSchema),
})

type FormValues = z.infer<typeof formSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface CustomFieldFormDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 생성 또는 수정 모드 */
  readonly mode: 'create' | 'edit'
  /** 수정 모드 초기값 — edit 시 필수 */
  readonly initial?: CustomField
  /** 저장 콜백 — 상위에서 mutation 호출 */
  readonly onSubmit: (input: CreateCustomFieldInput | UpdateCustomFieldInput) => void
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 상위 mutation에서 전달된 서버 오류 메시지 — null이면 미표시 */
  readonly submitError?: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// 옵션 편집기 행 컴포넌트 (분리해 200줄 제한 준수)
// ─────────────────────────────────────────────────────────────────────────────

interface OptionRowProps {
  readonly index: number
  readonly onRemove: (index: number) => void
  readonly register: ReturnType<typeof useForm<FormValues>>['register']
  readonly errors: ReturnType<typeof useForm<FormValues>>['formState']['errors']
}

function OptionRow({ index, onRemove, register, errors }: OptionRowProps): JSX.Element {
  return (
    <div className="flex gap-2 items-start mb-2">
      <div className="flex-1">
        <input
          type="text"
          placeholder="값"
          aria-label={`옵션 ${index + 1} 값`}
          className="w-full rounded-md border border-input bg-transparent px-3 py-1.5 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          {...register(`options.${index}.value`)}
        />
        {errors.options?.[index]?.value !== undefined && (
          <p className="text-xs text-destructive mt-0.5" role="alert">
            {errors.options[index].value?.message}
          </p>
        )}
      </div>
      <div className="flex-1">
        <input
          type="text"
          placeholder="라벨"
          aria-label={`옵션 ${index + 1} 라벨`}
          className="w-full rounded-md border border-input bg-transparent px-3 py-1.5 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          {...register(`options.${index}.label`)}
        />
        {errors.options?.[index]?.label !== undefined && (
          <p className="text-xs text-destructive mt-0.5" role="alert">
            {errors.options[index].label?.message}
          </p>
        )}
      </div>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-label="옵션 삭제"
        onClick={() => { onRemove(index) }}
        className="px-2 text-muted-foreground hover:text-destructive"
      >
        ✕
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly mode: 'create' | 'edit'
  readonly initial?: CustomField
  readonly onSubmit: (input: CreateCustomFieldInput | UpdateCustomFieldInput) => void
  readonly onOpenChange: (open: boolean) => void
  readonly submitError?: string | null
}

function FormBody({
  mode,
  initial,
  onSubmit,
  onOpenChange,
  submitError,
}: FormBodyProps): JSX.Element {
  const { form: labels, fieldTypes } = customFieldLabels

  const {
    register,
    handleSubmit,
    watch,
    control,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      key: initial?.key ?? '',
      name: initial?.name ?? '',
      description: initial?.description ?? '',
      fieldType: initial?.fieldType ?? 'SHORT_TEXT',
      required: initial?.required ?? false,
      displayOrder: initial?.displayOrder ?? 0,
      options: initial?.options.map((o) => ({ value: o.value, label: o.label })) ?? [],
    },
  })

  const { fields, append, remove } = useFieldArray({ control, name: 'options' })

  const watchedFieldType = watch('fieldType')
  const isOptionType = OPTION_FIELD_TYPES.has(watchedFieldType)
  const isSaveDisabled = isOptionType && fields.length === 0

  /** 선택지 배열을 API 입력 형태로 변환한다. */
  function mapOptions(values: FormValues) {
    return isOptionType
      ? values.options.map((o, i) => ({ value: o.value, label: o.label, displayOrder: i }))
      : undefined
  }

  function onValid(values: FormValues): void {
    const description = values.description !== '' ? values.description : undefined
    if (mode === 'create') {
      const payload: CreateCustomFieldInput = {
        key: values.key,
        name: values.name,
        description,
        fieldType: values.fieldType,
        required: values.required,
        displayOrder: values.displayOrder,
        options: mapOptions(values),
      }
      onSubmit(payload)
    } else {
      // edit 모드: fieldType·key 제외 (백엔드 불변)
      const payload: UpdateCustomFieldInput = {
        name: values.name,
        description,
        required: values.required,
        displayOrder: values.displayOrder,
        options: mapOptions(values),
      }
      onSubmit(payload)
    }
  }

  return (
    <form onSubmit={handleSubmit(onValid)} noValidate>
      {/* 키 */}
      <div className="mb-4">
        <label htmlFor="cf-key" className="block text-sm font-medium mb-1">
          {labels.keyLabel}
        </label>
        <input
          id="cf-key"
          type="text"
          aria-label={labels.keyLabel}
          placeholder="예: priority"
          disabled={mode === 'edit'}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground disabled:opacity-50 disabled:cursor-not-allowed"
          autoComplete="off"
          {...register('key')}
        />
        {errors.key !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.key.message}
          </p>
        )}
      </div>

      {/* 이름 */}
      <div className="mb-4">
        <label htmlFor="cf-name" className="block text-sm font-medium mb-1">
          {labels.nameLabel}
        </label>
        <input
          id="cf-name"
          type="text"
          aria-label={labels.nameLabel}
          placeholder="예: 우선순위"
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

      {/* 설명 */}
      <div className="mb-4">
        <label htmlFor="cf-description" className="block text-sm font-medium mb-1">
          {labels.descriptionLabel}
        </label>
        <input
          id="cf-description"
          type="text"
          aria-label={labels.descriptionLabel}
          placeholder="선택 입력"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          autoComplete="off"
          {...register('description')}
        />
      </div>

      {/* 필드 타입 */}
      <div className="mb-4">
        <label htmlFor="cf-field-type" className="block text-sm font-medium mb-1">
          {labels.fieldTypeLabel}
        </label>
        <select
          id="cf-field-type"
          aria-label={labels.fieldTypeLabel}
          disabled={mode === 'edit'}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed"
          {...register('fieldType')}
        >
          {fieldTypeEnum.options.map((ft) => (
            <option key={ft} value={ft}>
              {fieldTypes[ft]}
            </option>
          ))}
        </select>
      </div>

      {/* 필수 여부 */}
      <div className="mb-4 flex items-center gap-2">
        <input
          id="cf-required"
          type="checkbox"
          aria-label={labels.requiredLabel}
          className="rounded border-input"
          {...register('required')}
        />
        <label htmlFor="cf-required" className="text-sm font-medium">
          {labels.requiredLabel}
        </label>
      </div>

      {/* 표시 순서 */}
      <div className="mb-4">
        <label htmlFor="cf-display-order" className="block text-sm font-medium mb-1">
          {labels.displayOrderLabel}
        </label>
        <input
          id="cf-display-order"
          type="number"
          min={0}
          aria-label={labels.displayOrderLabel}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          {...register('displayOrder', { valueAsNumber: true })}
        />
      </div>

      {/* 선택지 편집기 (선택형 타입만 표시) */}
      {isOptionType && (
        <div className="mb-4">
          <p className="text-sm font-medium mb-2">{labels.optionsLabel}</p>
          {fields.map((field, index) => (
            <OptionRow
              key={field.id}
              index={index}
              onRemove={remove}
              register={register}
              errors={errors}
            />
          ))}
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => { append({ value: '', label: '' }) }}
          >
            {labels.addOptionButton}
          </Button>
        </div>
      )}

      {/* 서버 오류 */}
      {submitError !== undefined && submitError !== null && (
        <p className="text-sm text-destructive mb-4" role="alert">
          {submitError}
        </p>
      )}

      {/* 액션 버튼 */}
      <div className="flex justify-end gap-2 mt-6">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => { onOpenChange(false) }}
        >
          취소
        </Button>
        <Button type="submit" size="sm" disabled={isSaveDisabled}>
          저장
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CustomFieldFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 생성/수정 겸용 Dialog.
 *
 * - edit 모드에서 fieldType·key 는 백엔드 불변 — disabled 표시 + 제출 시 제외.
 * - 선택형(SINGLE_SELECT/MULTI_SELECT/RADIO)일 때만 옵션 편집기 렌더.
 * - initial.id가 바뀌면 key prop으로 FormBody를 재마운트해 stale state 방지
 *   (react-usestate-stale-key-prop 교훈).
 * - submitError 는 상위 mutation 에서 내려받아 폼 내 표시 + Dialog 유지.
 */
export const CustomFieldFormDialog = ({
  open,
  mode,
  initial,
  onSubmit,
  onOpenChange,
  submitError,
}: CustomFieldFormDialogProps): JSX.Element => {
  const title = mode === 'create' ? '커스텀 필드 추가' : '커스텀 필드 수정'
  // initial.id가 바뀌면 FormBody를 재마운트해 폼 state 초기화
  const formKey = initial?.id ?? 'new'

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 overflow-y-auto max-h-[90vh]"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            {title}
          </DialogPrimitive.Title>

          <FormBody
            key={formKey}
            mode={mode}
            initial={initial}
            onSubmit={onSubmit}
            onOpenChange={onOpenChange}
            submitError={submitError}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
