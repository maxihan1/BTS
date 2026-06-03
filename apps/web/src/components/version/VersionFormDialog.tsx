// 버전 생성/수정 겸용 Dialog — 변경 그룹 분리 호출(name·desc / startDate·releaseDate)
import type { JSX } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { versionLabels } from '@/i18n/version-labels'
import type { Version } from '@/api/versions.types'
import {
  useCreateVersion,
  useUpdateVersion,
  useChangeVersionDates,
} from '@/hooks/use-versions'
import type {
  UpdateVersionMutationInput,
  ChangeVersionDatesMutationInput,
} from '@/hooks/use-versions'
import type { CreateVersionInput } from '@/api/versions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마
// ─────────────────────────────────────────────────────────────────────────────

const formSchema = z.object({
  name: z.string().min(1, '이름은 필수입니다.'),
  description: z.string(),
  startDate: z.string(),
  releaseDate: z.string(),
})

type FormValues = z.infer<typeof formSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 변경 감지 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * date input의 빈 문자열('')을 null로 정규화한다.
 * 백엔드/타입은 null을 기대하지만 date input은 비우면 ''를 반환하기 때문이다.
 */
function normalizeDateInput(value: string): string | null {
  return value === '' ? null : value
}

/**
 * name/description 그룹의 변경 여부를 판정한다.
 */
function isMetaDirty(values: FormValues, initial: Version): boolean {
  return values.name !== initial.name || values.description !== (initial.description ?? '')
}

/**
 * startDate/releaseDate 그룹의 변경 여부를 판정한다.
 * '' → null 정규화 후 비교한다.
 */
function isDatesDirty(values: FormValues, initial: Version): boolean {
  return (
    normalizeDateInput(values.startDate) !== initial.startDate ||
    normalizeDateInput(values.releaseDate) !== initial.releaseDate
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface VersionFormDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 생성 또는 수정 모드 */
  readonly mode: 'create' | 'edit'
  /** 프로젝트 키 (버전 API 경로에 사용) */
  readonly projectKey: string
  /** 수정 모드 초기값 — edit 시 필수 */
  readonly initial?: Version
  /** 다이얼로그 닫기 콜백 */
  readonly onClose: () => void
  /** 상위 mutation에서 전달된 서버 오류 메시지 — null이면 미표시 */
  readonly submitError?: string | null
  /**
   * 테스트 주입용 — useCreateVersion의 mutate를 override한다.
   * 프로덕션에서는 절대 사용하지 않는다.
   * @internal
   */
  readonly _testCreateMutate?: (input: CreateVersionInput) => void
  /**
   * 테스트 주입용 — useUpdateVersion의 mutate를 override한다.
   * @internal
   */
  readonly _testUpdateMutate?: (input: UpdateVersionMutationInput) => void
  /**
   * 테스트 주입용 — useChangeVersionDates의 mutate를 override한다.
   * @internal
   */
  readonly _testChangeDatesMutate?: (input: ChangeVersionDatesMutationInput) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly mode: 'create' | 'edit'
  readonly projectKey: string
  readonly initial?: Version
  readonly onClose: () => void
  readonly submitError?: string | null
  readonly _testCreateMutate?: (input: CreateVersionInput) => void
  readonly _testUpdateMutate?: (input: UpdateVersionMutationInput) => void
  readonly _testChangeDatesMutate?: (input: ChangeVersionDatesMutationInput) => void
}

function FormBody({
  mode,
  projectKey,
  initial,
  onClose,
  submitError,
  _testCreateMutate,
  _testUpdateMutate,
  _testChangeDatesMutate,
}: FormBodyProps): JSX.Element {
  const { form: formLabels, actions } = versionLabels

  const createVersion = useCreateVersion(projectKey)
  const updateVersion = useUpdateVersion(projectKey)
  const changeVersionDates = useChangeVersionDates(projectKey)

  // 테스트 주입 우선 — 없으면 실제 hook mutate 사용
  const doCreate = _testCreateMutate ?? ((input: CreateVersionInput) => { createVersion.mutate(input) })
  const doUpdate = _testUpdateMutate ?? ((input: UpdateVersionMutationInput) => { updateVersion.mutate(input) })
  const doChangeDates = _testChangeDatesMutate ?? ((input: ChangeVersionDatesMutationInput) => { changeVersionDates.mutate(input) })

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: initial?.name ?? '',
      description: initial?.description ?? '',
      startDate: initial?.startDate ?? '',
      releaseDate: initial?.releaseDate ?? '',
    },
  })

  function onValid(values: FormValues): void {
    if (mode === 'create') {
      const input: CreateVersionInput = {
        name: values.name,
        description: values.description !== '' ? values.description : undefined,
        startDate: normalizeDateInput(values.startDate) ?? undefined,
        releaseDate: normalizeDateInput(values.releaseDate) ?? undefined,
      }
      doCreate(input)
      return
    }

    // 수정 모드 — initial이 없으면 저장 불가 (타입 가드)
    if (initial === undefined) {
      return
    }

    const metaDirty = isMetaDirty(values, initial)
    const datesDirty = isDatesDirty(values, initial)

    // 변경 없으면 즉시 닫기
    if (!metaDirty && !datesDirty) {
      onClose()
      return
    }

    if (metaDirty) {
      doUpdate({
        id: initial.id,
        input: {
          name: values.name,
          description: values.description,
        },
      })
    }

    if (datesDirty) {
      doChangeDates({
        id: initial.id,
        startDate: normalizeDateInput(values.startDate),
        releaseDate: normalizeDateInput(values.releaseDate),
      })
    }
  }

  return (
    <form onSubmit={handleSubmit(onValid)} noValidate>
      {/* 이름 */}
      <div className="mb-4">
        <label htmlFor="version-name" className="block text-sm font-medium mb-1">
          {formLabels.nameLabel}
        </label>
        <input
          id="version-name"
          type="text"
          aria-label={formLabels.nameLabel}
          placeholder={formLabels.namePlaceholder}
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
        <label htmlFor="version-description" className="block text-sm font-medium mb-1">
          {formLabels.descriptionLabel}
        </label>
        <input
          id="version-description"
          type="text"
          aria-label={formLabels.descriptionLabel}
          placeholder={formLabels.descriptionPlaceholder}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          autoComplete="off"
          {...register('description')}
        />
      </div>

      {/* 시작일 */}
      <div className="mb-4">
        <label htmlFor="version-start-date" className="block text-sm font-medium mb-1">
          {formLabels.startDateLabel}
        </label>
        <input
          id="version-start-date"
          type="date"
          aria-label={formLabels.startDateLabel}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          {...register('startDate')}
        />
      </div>

      {/* 릴리즈 예정일 */}
      <div className="mb-4">
        <label htmlFor="version-release-date" className="block text-sm font-medium mb-1">
          {formLabels.releaseDateLabel}
        </label>
        <input
          id="version-release-date"
          type="date"
          aria-label={formLabels.releaseDateLabel}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          {...register('releaseDate')}
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
          onClick={onClose}
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
// VersionFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 생성/수정 겸용 Dialog.
 *
 * - 수정 대상(initial.id)이 바뀌면 key prop으로 FormBody를 재마운트해
 *   stale state를 방지한다 (react-usestate-stale-key-prop 교훈).
 * - 저장 시 변경된 그룹의 mutation만 호출한다.
 *   - name/description 변경 → useUpdateVersion
 *   - startDate/releaseDate 변경 → useChangeVersionDates
 *   - 둘 다 변경 → 둘 다 호출
 *   - 변경 없음 → 0 mutation, 즉시 닫기
 * - date input의 빈 문자열은 null로 정규화 후 초기값과 비교한다.
 */
export const VersionFormDialog = ({
  open,
  mode,
  projectKey,
  initial,
  onClose,
  submitError,
  _testCreateMutate,
  _testUpdateMutate,
  _testChangeDatesMutate,
}: VersionFormDialogProps): JSX.Element => {
  const title = mode === 'create' ? '버전 추가' : '버전 수정'
  const formKey = initial?.id ?? 'new'

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(isOpen) => { if (!isOpen) { onClose() } }}>
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
            mode={mode}
            projectKey={projectKey}
            initial={initial}
            onClose={onClose}
            submitError={submitError}
            _testCreateMutate={_testCreateMutate}
            _testUpdateMutate={_testUpdateMutate}
            _testChangeDatesMutate={_testChangeDatesMutate}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
