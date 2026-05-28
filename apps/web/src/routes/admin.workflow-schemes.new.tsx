// 워크플로우 스킴 생성 페이지 — react-hook-form + Zod 검증 + 한국어 에러 메시지
import type { JSX } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate } from '@tanstack/react-router'
import {
  Form,
  FormField,
  FormItem,
  FormLabel,
  FormControl,
  FormMessage,
  FormDescription,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { useCreateWorkflowScheme } from '@/hooks/use-workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 폼 스키마
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우 스킴 생성 폼 Zod 스키마 */
const createSchemeFormSchema = z.object({
  schemeKey: z
    .string()
    .regex(/^[a-z][a-z0-9-]{1,29}$/, '스킴 키는 소문자/숫자/하이픈 1~30자로 시작은 소문자'),
  name: z
    .string()
    .min(1, '이름은 필수입니다')
    .max(255, '이름은 255자 이내'),
  description: z
    .string()
    .max(1000, '설명은 1000자 이내')
    .optional(),
})

/** Zod 스키마에서 추론한 폼 값 타입 */
type CreateSchemeFormValues = z.infer<typeof createSchemeFormSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface WorkflowSchemeNewFormProps {
  /** 생성 성공 후 호출되는 콜백 — 생성된 schemeKey를 전달 */
  onSuccess?: (schemeKey: string) => void
}

/**
 * 워크플로우 스킴 생성 폼 컴포넌트.
 *
 * - schemeKey / name / description 필드
 * - Zod 클라이언트 검증 선행 (key REGEX, name 길이, description 길이)
 * - 성공 시 onSuccess(schemeKey) 콜백 호출
 * - 409 에러 시 notifySchemeError 경유 toast.error, 폼 유지
 *
 * 라우터 의존 없이 props로 onSuccess를 받아 단위 테스트가 가능하다.
 */
export function WorkflowSchemeNewForm({ onSuccess }: WorkflowSchemeNewFormProps = {}): JSX.Element {
  const form = useForm<CreateSchemeFormValues>({
    resolver: zodResolver(createSchemeFormSchema),
    defaultValues: { schemeKey: '', name: '', description: '' },
  })

  const { mutate, isPending } = useCreateWorkflowScheme()

  function handleSubmit(values: CreateSchemeFormValues): void {
    // description 빈 문자열은 undefined로 정규화 — API에 불필요한 빈 문자열 전송 방지
    const description = values.description?.trim() === '' ? undefined : values.description?.trim()

    mutate(
      { schemeKey: values.schemeKey, name: values.name, description },
      {
        onSuccess: (created) => {
          onSuccess?.(created.schemeKey)
        },
      },
    )
  }

  return (
    <Form {...form}>
      <form
        onSubmit={form.handleSubmit(handleSubmit)}
        noValidate
        className="space-y-4"
      >
        {/* 스킴 키 입력 */}
        <FormField
          control={form.control}
          name="schemeKey"
          render={({ field }) => (
            <FormItem>
              <FormLabel>스킴 키</FormLabel>
              <FormControl>
                <Input
                  placeholder="예: my-scheme-01"
                  aria-label="스킴 키"
                  {...field}
                />
              </FormControl>
              <FormDescription>
                소문자 영문자로 시작하고 소문자/숫자/하이픈 2~30자 (예: software-default)
              </FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* 이름 입력 */}
        <FormField
          control={form.control}
          name="name"
          render={({ field }) => (
            <FormItem>
              <FormLabel>이름</FormLabel>
              <FormControl>
                <Input
                  placeholder="스킴 이름을 입력하세요"
                  aria-label="이름"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* 설명 입력 (선택) */}
        <FormField
          control={form.control}
          name="description"
          render={({ field }) => (
            <FormItem>
              <FormLabel>설명 (선택)</FormLabel>
              <FormControl>
                <Input
                  placeholder="스킴 설명을 입력하세요"
                  aria-label="설명 (선택)"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <div className="flex gap-2">
          <Button
            type="submit"
            disabled={isPending}
            className="sm:w-auto"
          >
            스킴 생성
          </Button>
          <CancelButton />
        </div>
      </form>
    </Form>
  )
}

/**
 * 취소 버튼 — 스킴 목록으로 이동.
 * 라우터 hook을 격리해서 WorkflowSchemeNewForm의 테스트 용이성을 보존한다.
 */
function CancelButton(): JSX.Element {
  const navigate = useNavigate()

  function handleCancel(): void {
    void navigate({ to: '/admin/workflow-schemes' })
  }

  return (
    <Button
      type="button"
      variant="outline"
      onClick={handleCancel}
    >
      취소
    </Button>
  )
}

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useNavigate로 성공 후 스킴 상세 페이지로 이동한다.
 *
 * 등록 방법 (code-based 패턴):
 * ```ts
 * import { WorkflowSchemeNewRouteAdapter } from './routes/admin.workflow-schemes.new'
 * const adminWorkflowSchemeNewRoute = createRoute({
 *   getParentRoute: () => rootRoute,
 *   path: '/admin/workflow-schemes/new',
 *   component: WorkflowSchemeNewRouteAdapter,
 * })
 * ```
 */
export function WorkflowSchemeNewRouteAdapter(): JSX.Element {
  const navigate = useNavigate()

  function handleSuccess(schemeKey: string): void {
    void navigate({ to: '/admin/workflow-schemes/$schemeKey', params: { schemeKey } })
  }

  return <WorkflowSchemeNewForm onSuccess={handleSuccess} />
}
