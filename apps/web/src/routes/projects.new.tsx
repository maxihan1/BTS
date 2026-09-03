// 프로젝트 생성 폼 페이지 — ProjectCreatePage(라우터 비의존) + ProjectCreateRouteAdapter(useNavigate 연결) — FR-PJ PR-5 Task 5 (FE-2)
import type { JSX } from 'react'
import { useState } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate } from '@tanstack/react-router'
import { PageLayout } from '@/components/layout/PageLayout'
import { PageHeader } from '@/components/layout/PageHeader'
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
import { useCreateProject } from '@/hooks/use-project-mutations'
import { isValidProjectKey } from '@/lib/project-key'
import { projectKeyFormatMessage } from '@/i18n/ko'
import { extractProjectErrorCode, ProjectErrorCodes } from '@/api/projects'
import { useAuthUser } from '@/auth/authStore'

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 — 로컬 상수. 단 `keyInvalid` 만 공유 i18n 참조 (2026-08-12 · PR #367)
//
// ★이전 주석은 「공유 i18n 미접촉, 스펙 §라벨 지시」였는데 **그 인용이 부정확했다.**
//   실측 — 그런 §라벨 절은 스펙(`2026-07-20-fr-pj-pr-5-project-crud-ui.md`)에 없다.
//   근거는 **plan** `2026-07-20-fr-pj-pr-5-project-crud-ui.md` W2 이고, 내용은
//   「T4/5/6 **병렬 구현 시** 공용 라벨 파일 동시편집 충돌 가능」이라는 **그 PR 한정
//   작업 스케줄 완화책**이다. 게다가 대안으로 **「T3/T7 중앙화」를 명시적으로 허용**한다.
//
//   그래서 `keyInvalid` 는 중앙화했다 — 같은 형식 규칙을 이슈 이동 화면도 설명하는데
//   문장을 두 벌 두면 한쪽만 고쳐졌을 때 화면마다 다른 설명이 나온다.
//   나머지 라벨은 공유 대상이 없어 로컬로 남긴다(범위를 넓히지 않는다).
// ─────────────────────────────────────────────────────────────────────────────

const projectCreateLabels = {
  pageTitle: '새 프로젝트',
  breadcrumbProjects: '프로젝트',
  keyLabel: '프로젝트 키',
  keyPlaceholder: '예: ATLAS',
  keyRequired: '프로젝트 키를 입력하세요.',
  // 이슈 이동 화면과 **같은 문장**을 쓴다 — 같은 규칙을 화면마다 다르게 설명하지 않기 위해.
  keyInvalid: projectKeyFormatMessage,
  nameLabel: '프로젝트 이름',
  nameRequired: '프로젝트 이름을 입력하세요.',
  submitButton: '프로젝트 생성',
  errorKeyDuplicate: '이미 사용 중인 키입니다.',
  errorValidation: '입력값을 확인해주세요.',
  errorForbidden: '프로젝트를 생성할 권한이 없습니다.',
  errorDefault: '프로젝트 생성에 실패했습니다.',
  permissionDeniedNotice: '새 프로젝트를 생성할 권한이 없습니다. 관리자에게 문의하세요.',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Zod 폼 스키마 — backend CreateProjectRequest(PROJECT_KEY_REGEX) 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 생성 폼 입력 Zod 스키마.
 *
 * key는 `.refine`으로 "빈 값이면 형식 검증을 건너뛴다"를 명시해 빈 문자열 제출 시
 * required 메시지 하나만 노출되도록 한다(min+regex 동시 실패로 메시지가 흔들리는 것 방지).
 */
const projectCreateSchema = z.object({
  key: z
    .string()
    .min(1, projectCreateLabels.keyRequired)
    .refine((val) => val.length === 0 || isValidProjectKey(val), {
      message: projectCreateLabels.keyInvalid,
    }),
  name: z.string().min(1, projectCreateLabels.nameRequired),
})

/** Zod 스키마에서 추론한 폼 값 타입 */
type ProjectCreateFormValues = z.infer<typeof projectCreateSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 사용자 메시지 매핑 — 공유 util(extractProjectErrorCode) 경유
// ─────────────────────────────────────────────────────────────────────────────

/**
 * mutation 에러에서 프로젝트 BC errorCode를 추출해 사용자 노출 메시지로 변환한다.
 *
 * @param err mutation onError로 전달된 임의 에러
 * @returns 사용자 노출 한국어 에러 메시지
 */
function resolveCreateErrorMessage(err: unknown): string {
  const code = extractProjectErrorCode(err)
  if (code === ProjectErrorCodes.KEY_ALREADY_EXISTS) return projectCreateLabels.errorKeyDuplicate
  if (code === ProjectErrorCodes.VALIDATION_FAILED) return projectCreateLabels.errorValidation
  if (code === ProjectErrorCodes.FORBIDDEN) return projectCreateLabels.errorForbidden
  return projectCreateLabels.errorDefault
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `role="alert"` 스타일 문단 — 권한 안내/서버 에러 메시지 표면에 공용으로 쓰는 로컬 헬퍼.
 * 두 지점(권한 없음 안내·mutation 실패 메시지)에서 동일한 마크업이 중복되던 것을 추출했다.
 */
function FormAlert({ message }: { message: string }): JSX.Element {
  return (
    <p role="alert" className="text-sm text-destructive">
      {message}
    </p>
  )
}

interface ProjectCreatePageProps {
  /** 프로젝트 생성 성공 후 호출되는 콜백 — 생성된 프로젝트 key를 전달 */
  onSuccess?: (key: string) => void
}

/**
 * 프로젝트 생성 페이지 (`/projects/new`, 스펙 FE-2).
 *
 * - PageLayout + PageHeader(h1 "새 프로젝트" · Breadcrumb "프로젝트 > 새 프로젝트").
 * - `whoami.canCreateProject`가 true가 아니면(EC-5·EC-6, 키 부재도 false 취급) 폼 대신
 *   권한 안내를 노출한다. 최종 방어는 백엔드 POST(fail-closed) — 이 게이팅은 UX 편의다.
 * - 폼: key(영문 대문자+숫자 2~10자)·name(required) — 클라이언트 Zod 검증.
 * - 제출 성공 시 onSuccess(key) 콜백 호출. 409(중복 key)·400(형식 위반)·403(권한 없음)을
 *   role="alert"로 표면한다.
 *
 * 라우터 의존 없이 props로 onSuccess를 받아 단위 테스트가 가능하다.
 */
export function ProjectCreatePage({ onSuccess }: ProjectCreatePageProps = {}): JSX.Element {
  const [serverError, setServerError] = useState<string | null>(null)
  const user = useAuthUser()
  const canCreateProject = user?.canCreateProject === true

  const form = useForm<ProjectCreateFormValues>({
    resolver: zodResolver(projectCreateSchema),
    defaultValues: { key: '', name: '' },
  })

  const mutation = useCreateProject()

  function handleSubmit(values: ProjectCreateFormValues): void {
    setServerError(null)
    mutation.mutate(values, {
      onSuccess: (project) => {
        onSuccess?.(project.key)
      },
      onError: (err: unknown) => {
        setServerError(resolveCreateErrorMessage(err))
      },
    })
  }

  return (
    <PageLayout maxWidth="2xl">
      <PageHeader
        title={projectCreateLabels.pageTitle}
        breadcrumbs={[
          { label: projectCreateLabels.breadcrumbProjects, to: '/projects' },
          { label: projectCreateLabels.pageTitle },
        ]}
      />

      {!canCreateProject && <FormAlert message={projectCreateLabels.permissionDeniedNotice} />}

      {canCreateProject && (
        <Form {...form}>
          <form onSubmit={form.handleSubmit(handleSubmit)} noValidate className="space-y-4">
            {serverError !== null && <FormAlert message={serverError} />}

            <FormField
              control={form.control}
              name="key"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{projectCreateLabels.keyLabel}</FormLabel>
                  <FormControl>
                    {/* aria-label — FormLabel.htmlFor 가 wrapper div 를 가리키므로 input 자체에 aria-label 로 WCAG AA 보장 */}
                    <Input
                      placeholder={projectCreateLabels.keyPlaceholder}
                      aria-label={projectCreateLabels.keyLabel}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{projectCreateLabels.nameLabel}</FormLabel>
                  <FormControl>
                    <Input aria-label={projectCreateLabels.nameLabel} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Button type="submit" disabled={mutation.isPending} className="w-full sm:w-auto">
              {projectCreateLabels.submitButton}
            </Button>
          </form>
        </Form>
      )}
    </PageLayout>
  )
}

/**
 * router.ts 에 등록되는 라우트 어댑터 컴포넌트 (T7에서 `/projects/new`로 등록 예정).
 * useNavigate로 생성 성공 후 새 프로젝트의 보드 페이지로 이동한다(스펙 S3·EC-4).
 *
 * 등록 방법 (code-based 패턴 — issues.new.tsx 컨벤션):
 * ```ts
 * import { ProjectCreateRouteAdapter } from './routes/projects.new'
 * const projectNewRoute = createRoute({
 *   getParentRoute: () => shellRoute,
 *   path: '/projects/new',
 *   component: ProjectCreateRouteAdapter,
 * })
 * ```
 */
export function ProjectCreateRouteAdapter(): JSX.Element {
  const navigate = useNavigate()

  function handleSuccess(key: string): void {
    void navigate({ to: '/projects/$projectKey', params: { projectKey: key } })
  }

  return <ProjectCreatePage onSuccess={handleSuccess} />
}
