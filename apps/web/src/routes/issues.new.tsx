// 이슈 생성 딥링크 라우트 — CreateIssueDialog 를 열린 상태로 마운트하는 얇은 어댑터
import type { JSX } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'

/**
 * router.ts 에 등록되는 라우트 어댑터 컴포넌트.
 *
 * ### 왜 라우트가 모달을 여는가 (FR-11, design 리뷰 D3)
 * `/issues/new` 는 **딥링크 계약**이라 유지해야 한다(북마크·공유 링크·`c` 단축키·커맨드 팔레트가
 * 이 주소로 이동한다). 동시에 같은 기능의 입력 화면이 두 벌 생기면 필드를 추가할 때마다
 * 두 곳을 고쳐야 하고, 한쪽만 고치면 **사용자가 들어온 경로에 따라 다른 결과**를 얻는다.
 * → 주소는 살리되 **같은 모달**을 열린 상태로 렌더한다.
 *
 * ### 이동은 여기서 정한다 (FR-16, design 리뷰 D8)
 * 모달은 URL 을 모르는 제어 컴포넌트라 이동을 스스로 하지 않는다.
 * **딥링크로 들어온 사용자는 방금 만든 이슈를 보러 온 것**이므로 상세로 보낸다.
 * (상단바에서 제자리로 연 경우는 `TopBar` 가 머무르며 토스트를 띄운다.)
 *
 * 닫으면 `/issues` 로 보낸다 — 주소로 직접 들어왔다면 뒤로 갈 히스토리가 없을 수 있다.
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

  function handleOpenChange(open: boolean): void {
    if (!open) {
      void navigate({ to: '/issues' })
    }
  }

  function handleCreated(key: string): void {
    void navigate({ to: '/issues/$key', params: { key } })
  }

  function handleCreateProject(): void {
    void navigate({ to: '/projects/new' })
  }

  // key={initialSummary} — react-hook-form 의 defaultValues 는 mount 시 1회만 적용된다.
  // 이미 /issues/new 에 머문 상태에서 URL summary 만 바뀌면 같은 라우트라 자연 리마운트가
  // 일어나지 않아 프리필이 갱신되지 않는다 (CONCERN-1, PR #13 plan 리뷰).
  return (
    <CreateIssueDialog
      key={initialSummary ?? ''}
      open
      onOpenChange={handleOpenChange}
      onCreated={handleCreated}
      onCreateProject={handleCreateProject}
      initialSummary={initialSummary}
    />
  )
}
