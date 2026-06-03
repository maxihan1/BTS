// 컴포넌트 생성/수정 겸용 Dialog — React Hook Form + Zod 검증, ComponentLeadSelect props 주입
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import type { Component, CreateComponentInput } from '@/api/components.types'
import { componentLabels } from '@/i18n/component-labels'
import { ComponentLeadSelect } from './ComponentLeadSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마
// ─────────────────────────────────────────────────────────────────────────────

const formSchema = z.object({
  name: z.string().min(1, '이름은 필수입니다.'),
  description: z.string().optional(),
})

type FormValues = z.infer<typeof formSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface ComponentFormDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 생성 또는 수정 모드 */
  readonly mode: 'create' | 'edit'
  /** 수정 모드 초기값 — edit 시 필수 */
  readonly initial?: Component
  /** 저장 콜백 — 상위에서 mutation 호출 */
  readonly onSubmit: (input: CreateComponentInput) => void
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 상위 mutation에서 전달된 서버 오류 메시지 — null이면 미표시 */
  readonly submitError?: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly initial?: Component
  readonly onSubmit: (input: CreateComponentInput) => void
  readonly onOpenChange: (open: boolean) => void
  readonly submitError?: string | null
}

function FormBody({ initial, onSubmit, onOpenChange, submitError }: FormBodyProps): JSX.Element {
  const { form: labels, actions } = componentLabels

  // 리드 검색어 (debounce 이 컴포넌트가 담당)
  const [searchQuery, setSearchQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [leadUserId, setLeadUserId] = useState<string | null>(initial?.leadUserId ?? null)

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(searchQuery)
    }, 300)
    return () => { clearTimeout(timer) }
  }, [searchQuery])

  const { data: searchUsers = [] } = useUsers(debouncedQuery)
  const leadIds = leadUserId !== null ? [leadUserId] : []
  const { data: leadUsers = [] } = useUsersByIds(leadIds)
  const currentLead = leadUsers.find((u) => u.id === leadUserId) ?? null

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: initial?.name ?? '',
      // null → '' 변환 (description은 서버에서 null로 올 수 있음)
      description: initial?.description ?? '',
    },
  })

  function onValid(values: FormValues): void {
    const isEdit = initial != null
    const payload: CreateComponentInput = {
      name: values.name,
      // 수정 모드: 빈 문자열도 그대로 전송해 기존 설명 비우기를 허용한다
      //   (백엔드 update는 null=무변경, ""=changeDescription("")로 실제 비움).
      // 생성 모드: 빈 값은 undefined로 — 서버에 불필요한 빈 description 전송 방지.
      description: isEdit
        ? values.description
        : values.description !== ''
          ? values.description
          : undefined,
      leadUserId: leadUserId ?? undefined,
    }
    onSubmit(payload)
  }

  return (
    <form onSubmit={handleSubmit(onValid)} noValidate>
      {/* 이름 */}
      <div className="mb-4">
        <label
          htmlFor="component-name"
          className="block text-sm font-medium mb-1"
        >
          {labels.nameLabel}
        </label>
        <input
          id="component-name"
          type="text"
          aria-label={labels.nameLabel}
          placeholder={labels.namePlaceholder}
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
        <label
          htmlFor="component-description"
          className="block text-sm font-medium mb-1"
        >
          {labels.descriptionLabel}
        </label>
        <input
          id="component-description"
          type="text"
          aria-label={labels.descriptionLabel}
          placeholder={labels.descriptionPlaceholder}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          autoComplete="off"
          {...register('description')}
        />
      </div>

      {/* 리드 */}
      <div className="mb-4">
        <p className="text-sm font-medium mb-1">{labels.leadLabel}</p>
        <ComponentLeadSelect
          users={searchUsers}
          currentLead={currentLead}
          onSearch={setSearchQuery}
          onChange={setLeadUserId}
        />
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
          {actions.cancelButton}
        </Button>
        <Button type="submit" size="sm">
          {actions.saveButton}
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ComponentFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 생성/수정 겸용 Dialog.
 *
 * - 수정 대상(initial.id)이 바뀌면 key prop으로 FormBody를 재마운트해
 *   stale state를 방지한다 (react-usestate-stale-key-prop 교훈).
 * - 리드 검색 debounce는 이 Dialog가 담당하고 ComponentLeadSelect에 props로 주입한다.
 * - submitError는 상위 mutation에서 내려받아 폼 내 표시 + Dialog 유지.
 */
export const ComponentFormDialog = ({
  open,
  mode,
  initial,
  onSubmit,
  onOpenChange,
  submitError,
}: ComponentFormDialogProps): JSX.Element => {
  const title = mode === 'create' ? '컴포넌트 추가' : '컴포넌트 수정'
  // initial.id가 바뀌면 FormBody를 재마운트해 폼 state 초기화
  const formKey = initial?.id ?? 'new'

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            {title}
          </DialogPrimitive.Title>

          <FormBody
            key={formKey}
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
