// 이슈 생성 라우트 어댑터 — IssueCreateForm(components/issue/) 을 useNavigate 에 연결
import type { JSX } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { IssueCreateForm } from '@/components/issue/IssueCreateForm'

/**
 * router.ts 에 등록되는 라우트 어댑터 컴포넌트.
 * useNavigate로 성공 후 이슈 상세 페이지로 이동한다.
 * URL summary(선택) search param을 제목 필드 기본값으로 프리필한다(FR-UX-04 FR7).
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
  // summary(선택) search param — 명령 팔레트 `/issue <제목>` 실행(FR-UX-04 FR7) 시 제목 프리필
  const search = useSearch({ strict: false }) as { summary?: string }
  const initialSummary = search.summary

  function handleSuccess(key: string): void {
    void navigate({ to: '/issues/$key', params: { key } })
  }

  // CONCERN-1(plan 리뷰): react-hook-form의 defaultValues는 mount 시 1회만 적용된다.
  // 이미 /issues/new에 머문 상태에서 URL summary만 바뀌면 같은 라우트라 컴포넌트가
  // 자연스럽게 리마운트되지 않아 프리필이 갱신되지 않는다. key={initialSummary}로
  // summary 값이 바뀔 때마다 폼을 강제 리마운트해 항상 최신 프리필을 반영한다.
  return (
    <IssueCreateForm
      key={initialSummary ?? ''}
      initialSummary={initialSummary}
      onSuccess={handleSuccess}
    />
  )
}
