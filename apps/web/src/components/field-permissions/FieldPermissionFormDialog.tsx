// 필드 권한 규칙 생성 Dialog — RHF + Zod + CORE/CUSTOM fieldKind 분기 + 그룹 드롭다운 (FR-PM-07)
import type { JSX } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { useGroups } from '@/hooks/use-groups'
import { fieldKindEnum, accessLevelEnum } from '@/api/field-permissions.types'
import type { CreateFieldPermissionInput } from '@/api/field-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// CORE 필드 화이트리스트 (백엔드 FieldKind.CORE 대상 필드 키 7종)
// ─────────────────────────────────────────────────────────────────────────────

const CORE_FIELD_KEYS: readonly { value: string; label: string }[] = [
  { value: 'summary', label: '제목' },
  { value: 'description', label: '설명' },
  { value: 'priority', label: '우선순위' },
  { value: 'labels', label: '레이블' },
  { value: 'environment', label: '환경' },
  { value: 'impact', label: '영향도' },
  { value: 'assigneeId', label: '담당자' },
]

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마
// ─────────────────────────────────────────────────────────────────────────────

const formSchema = z.object({
  fieldKind: fieldKindEnum,
  fieldKey: z.string().min(1, '필드 키를 선택해주세요.'),
  groupId: z.string().uuid('그룹을 선택해주세요.'),
  accessLevel: accessLevelEnum,
})

type FormValues = z.infer<typeof formSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface FieldPermissionFormDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 저장 콜백 — 상위에서 mutation 호출 */
  readonly onSubmit: (input: CreateFieldPermissionInput) => void
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 상위 mutation에서 전달된 서버 오류 메시지 — null이면 미표시 */
  readonly submitError?: string | null
  /** 프로젝트 식별 키 — CUSTOM fieldKind 선택 시 커스텀 필드 목록 조회에 사용 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly onSubmit: (input: CreateFieldPermissionInput) => void
  readonly onOpenChange: (open: boolean) => void
  readonly submitError?: string | null
  readonly projectKey: string
}

function FormBody({
  onSubmit,
  onOpenChange,
  submitError,
  projectKey,
}: FormBodyProps): JSX.Element {
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      fieldKind: 'CORE',
      fieldKey: CORE_FIELD_KEYS[0]?.value ?? '',
      groupId: '',
      accessLevel: 'VIEW',
    },
  })

  const watchedFieldKind = watch('fieldKind')
  const isCustomKind = watchedFieldKind === 'CUSTOM'

  // 그룹 목록 조회
  const { data: groups, isLoading: isGroupsLoading } = useGroups({ enabled: true })

  // CUSTOM fieldKind 선택 시 커스텀 필드 목록 조회 (lazy)
  const { data: customFields, isLoading: isCustomFieldsLoading } = useCustomFields(projectKey, {
    enabled: isCustomKind,
  })

  function onValid(values: FormValues): void {
    onSubmit({
      fieldKind: values.fieldKind,
      fieldKey: values.fieldKey,
      groupId: values.groupId,
      accessLevel: values.accessLevel,
    })
  }

  return (
    <form onSubmit={handleSubmit(onValid)} noValidate>
      {/* 필드 종류 */}
      <div className="mb-4">
        <label htmlFor="fp-field-kind" className="block text-sm font-medium mb-1">
          필드 종류
        </label>
        <select
          id="fp-field-kind"
          aria-label="필드 종류"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          {...register('fieldKind')}
        >
          {fieldKindEnum.options.map((kind) => (
            <option key={kind} value={kind}>
              {kind === 'CORE' ? '기본 필드 (CORE)' : '커스텀 필드 (CUSTOM)'}
            </option>
          ))}
        </select>
        {errors.fieldKind !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.fieldKind.message}
          </p>
        )}
      </div>

      {/* 필드 키 */}
      <div className="mb-4">
        <label htmlFor="fp-field-key" className="block text-sm font-medium mb-1">
          필드 키
        </label>
        {isCustomKind ? (
          // CUSTOM: 커스텀 필드 목록 select
          <select
            id="fp-field-key"
            aria-label="필드 키"
            disabled={isCustomFieldsLoading}
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed"
            {...register('fieldKey')}
          >
            <option value="">필드를 선택하세요</option>
            {(customFields ?? []).map((cf) => (
              <option key={cf.id} value={cf.key}>
                {cf.name} ({cf.key})
              </option>
            ))}
          </select>
        ) : (
          // CORE: 화이트리스트 7종 select
          <select
            id="fp-field-key"
            aria-label="필드 키"
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            {...register('fieldKey')}
          >
            {CORE_FIELD_KEYS.map((ck) => (
              <option key={ck.value} value={ck.value}>
                {ck.label} ({ck.value})
              </option>
            ))}
          </select>
        )}
        {errors.fieldKey !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.fieldKey.message}
          </p>
        )}
      </div>

      {/* 그룹 */}
      <div className="mb-4">
        <label htmlFor="fp-group-id" className="block text-sm font-medium mb-1">
          그룹
        </label>
        <select
          id="fp-group-id"
          aria-label="그룹"
          disabled={isGroupsLoading}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed"
          {...register('groupId')}
        >
          <option value="">그룹을 선택하세요</option>
          {(groups ?? []).map((group) => (
            <option key={group.id} value={group.id}>
              {group.name}
            </option>
          ))}
        </select>
        {errors.groupId !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.groupId.message}
          </p>
        )}
      </div>

      {/* 접근 수준 */}
      <div className="mb-4">
        <label htmlFor="fp-access-level" className="block text-sm font-medium mb-1">
          접근 수준
        </label>
        <select
          id="fp-access-level"
          aria-label="접근 수준"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          {...register('accessLevel')}
        >
          {accessLevelEnum.options.map((level) => (
            <option key={level} value={level}>
              {level === 'VIEW' ? '보기 (VIEW)' : '편집 (EDIT)'}
            </option>
          ))}
        </select>
        {errors.accessLevel !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.accessLevel.message}
          </p>
        )}
      </div>

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
        <Button type="submit" size="sm">
          저장
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FieldPermissionFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 권한 규칙 생성 Dialog.
 *
 * - fieldKind CORE/CUSTOM 선택에 따라 fieldKey 입력 방식이 분기된다.
 *   - CORE: 화이트리스트 7종 select(summary/description/priority/labels/environment/impact/assigneeId)
 *   - CUSTOM: useCustomFields(projectKey) 목록 select
 * - groupId: useGroups() 드롭다운
 * - accessLevel: VIEW/EDIT
 * - submitError는 상위 mutation에서 내려받아 폼 내 표시 + Dialog 유지.
 *   (dialog-submiterror-ownership-dead-path 교훈: 부모에서 전달 필수)
 */
export const FieldPermissionFormDialog = ({
  open,
  onSubmit,
  onOpenChange,
  submitError,
  projectKey,
}: FieldPermissionFormDialogProps): JSX.Element => {
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
            필드 권한 규칙 추가
          </DialogPrimitive.Title>

          <FormBody
            key={open ? 'open' : 'closed'}
            onSubmit={onSubmit}
            onOpenChange={onOpenChange}
            submitError={submitError}
            projectKey={projectKey}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
