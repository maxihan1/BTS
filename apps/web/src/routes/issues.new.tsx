// 이슈 생성 폼 페이지 — IssueCreateForm(라우터 비의존) + IssueCreateRouteAdapter(useNavigate 연결)
import type { JSX } from 'react'
import { useState } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { createIssue } from '@/api/issues'
import { ApiError } from '@/api/client'
import {
  Form,
  FormField,
  FormItem,
  FormLabel,
  FormControl,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { issueCreateStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 폼 스키마 — interface 중복 정의 금지
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 생성 폼 입력 Zod 스키마 */
const issueCreateSchema = z.object({
  projectKey: z.string().min(1, issueCreateStrings.projectKeyRequired),
  summary: z
    .string()
    .min(1, issueCreateStrings.summaryRequired)
    .max(500, issueCreateStrings.summaryTooLong),
})

/** Zod 스키마에서 추론한 폼 값 타입 */
type IssueCreateFormValues = z.infer<typeof issueCreateSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 사용자 메시지 매핑
// ─────────────────────────────────────────────────────────────────────────────

function resolveCreateErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    const body = err.body
    if (
      typeof body === 'object' &&
      body !== null &&
      'errorCode' in body &&
      (body as Record<string, unknown>)['errorCode'] === 'PROJECT_NOT_FOUND'
    ) {
      return issueCreateStrings.errorProjectNotFound
    }
  }
  return issueCreateStrings.errorDefault
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface IssueCreateFormProps {
  /** 이슈 생성 성공 후 호출되는 콜백 — 생성된 이슈 key를 전달 */
  onSuccess?: (key: string) => void
}

/**
 * 이슈 생성 폼 컴포넌트.
 *
 * - projectKey + summary 입력 필드
 * - 클라이언트 Zod 검증: 빈 값 제출 차단
 * - 제출 성공 시 onSuccess(key) 콜백 호출
 * - PROJECT_NOT_FOUND(404) 시 role="alert" 에러 메시지 노출
 *
 * 라우터 의존 없이 props로 onSuccess를 받아 단위 테스트가 가능하다.
 */
export function IssueCreateForm({ onSuccess }: IssueCreateFormProps = {}): JSX.Element {
  const [serverError, setServerError] = useState<string | null>(null)

  const form = useForm<IssueCreateFormValues>({
    resolver: zodResolver(issueCreateSchema),
    defaultValues: { projectKey: '', summary: '' },
  })

  const mutation = useMutation({
    mutationFn: createIssue,
    onSuccess: (data) => {
      setServerError(null)
      onSuccess?.(data.key)
    },
    onError: (err: unknown) => {
      setServerError(resolveCreateErrorMessage(err))
    },
  })

  function handleSubmit(values: IssueCreateFormValues): void {
    setServerError(null)
    mutation.mutate(values)
  }

  return (
    <Form {...form}>
      <form
        onSubmit={form.handleSubmit(handleSubmit)}
        noValidate
        className="space-y-4"
      >
        {/* 서버 에러 알림 영역 */}
        {serverError !== null && (
          <p role="alert" className="text-sm text-destructive">
            {serverError}
          </p>
        )}

        {/* 프로젝트 키 입력 */}
        <FormField
          control={form.control}
          name="projectKey"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{issueCreateStrings.projectKeyLabel}</FormLabel>
              <FormControl>
                <Input
                  id={`${field.name}-input`}
                  placeholder="예: ATLAS"
                  aria-label={issueCreateStrings.projectKeyLabel}
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* 이슈 제목 입력 */}
        <FormField
          control={form.control}
          name="summary"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{issueCreateStrings.summaryLabel}</FormLabel>
              <FormControl>
                <Input
                  id={`${field.name}-input`}
                  placeholder="이슈 제목을 입력하세요"
                  aria-label={issueCreateStrings.summaryLabel}
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <Button
          type="submit"
          disabled={mutation.isPending}
          className="w-full sm:w-auto"
        >
          {issueCreateStrings.submitButton}
        </Button>
      </form>
    </Form>
  )
}

/**
 * router.ts 에 등록되는 라우트 어댑터 컴포넌트.
 * useNavigate로 성공 후 이슈 상세 페이지로 이동한다.
 *
 * 등록 방법 (code-based 패턴 — PR #11 컨벤션):
 * ```ts
 * import { IssueCreateRouteAdapter } from './routes/issues.new'
 * const issueNewRoute = createRoute({
 *   getParentRoute: () => rootRoute,
 *   path: '/issues/new',
 *   component: IssueCreateRouteAdapter,
 * })
 * ```
 */
export function IssueCreateRouteAdapter(): JSX.Element {
  const navigate = useNavigate()

  function handleSuccess(key: string): void {
    void navigate({ to: '/issues/$key', params: { key } })
  }

  return <IssueCreateForm onSuccess={handleSuccess} />
}
