// 커스텀 필드 설정 라우트 페이지 — RouteAdapter + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { CustomFieldList } from '@/components/custom-fields/CustomFieldList'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { extractCustomFieldErrorCode } from '@/api/custom-fields'
import { customFieldLabels } from '@/i18n/custom-field-labels'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectCustomFieldsSettingsPage에 전달한다.
 */
export function ProjectCustomFieldsSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectCustomFieldsSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectCustomFieldsSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 커스텀 필드 설정 페이지.
 *
 * - useCustomFields(projectKey)로 에러 종류를 판별한다.
 *   TanStack Query 캐시 공유로 CustomFieldList 내부와 별도 네트워크 요청이 발생하지 않는다.
 * - PROJECT_NOT_FOUND(404) — 페이지 레벨에서 "접근 권한이 없습니다" 안내 화면을 렌더한다.
 *   비멤버 또는 미존재 프로젝트 모두 동일하게 처리한다.
 * - 그 외 에러 — CustomFieldList 내부 에러 분기에 위임한다.
 * - 정상 — 헤더 + CustomFieldList 렌더.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectCustomFieldsSettingsPage({
  projectKey,
}: ProjectCustomFieldsSettingsPageProps): JSX.Element {
  const { error, isError } = useCustomFields(projectKey)

  // PROJECT_NOT_FOUND(404): 비멤버 또는 미존재 프로젝트 → 접근 불가 안내.
  // errorCode는 백엔드 구현에 따라 'PROJECT_NOT_FOUND' 또는 'CUSTOM_FIELD_PROJECT_NOT_FOUND' 두 값으로 올 수 있다.
  const errorCode = isError ? extractCustomFieldErrorCode(error) : null
  if (
    errorCode === 'PROJECT_NOT_FOUND' ||
    errorCode === 'CUSTOM_FIELD_PROJECT_NOT_FOUND'
  ) {
    return <ProjectNotFoundScreen />
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">{customFieldLabels.page.heading}</h2>
        <p className="text-muted-foreground text-sm">{customFieldLabels.page.description}</p>
      </header>
      <CustomFieldList projectKey={projectKey} />
    </div>
  )
}
