// 이슈 템플릿 설정 라우트 페이지 — RouteAdapter + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { IssueTemplateList } from '@/components/issue-templates/IssueTemplateList'
import { useIssueTemplates } from '@/hooks/use-issue-templates'
import { extractIssueTemplateErrorCode } from '@/api/issue-templates'
import { issueTemplateLabels } from '@/i18n/issue-template-labels'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectIssueTemplatesSettingsPage에 전달한다.
 */
export function ProjectIssueTemplatesSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectIssueTemplatesSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectIssueTemplatesSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 이슈 템플릿 설정 페이지.
 *
 * - useIssueTemplates(projectKey)로 에러 종류를 판별한다.
 *   TanStack Query 캐시 공유로 IssueTemplateList 내부와 별도 네트워크 요청이 발생하지 않는다.
 * - ISSUE_TEMPLATE_PROJECT_NOT_FOUND(404) — 페이지 레벨에서 "접근 권한이 없습니다" 안내 화면을 렌더한다.
 *   비멤버 또는 미존재 프로젝트 모두 동일하게 처리한다.
 * - 그 외 에러 — IssueTemplateList 내부 에러 분기에 위임한다.
 * - 정상 — 헤더 + IssueTemplateList 렌더.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectIssueTemplatesSettingsPage({
  projectKey,
}: ProjectIssueTemplatesSettingsPageProps): JSX.Element {
  const { error, isError } = useIssueTemplates(projectKey)

  // ISSUE_TEMPLATE_PROJECT_NOT_FOUND(404): 비멤버 또는 미존재 프로젝트 → 접근 불가 안내.
  const errorCode = isError ? extractIssueTemplateErrorCode(error) : null
  if (errorCode === 'ISSUE_TEMPLATE_PROJECT_NOT_FOUND') {
    return <ProjectNotFoundScreen />
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">{issueTemplateLabels.page.heading}</h1>
        <p className="text-muted-foreground text-sm">{issueTemplateLabels.page.description}</p>
      </header>
      <IssueTemplateList projectKey={projectKey} />
    </div>
  )
}
