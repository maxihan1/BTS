// 워크플로우 상세 페이지 (FR-WF-01 read-only 다이어그램 + FR-WF-06 전환 규칙 + FR-NT-05 post-action 설정)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { WorkflowDiagram } from '@/components/workflow/WorkflowDiagram'
import { fetchWorkflow } from '@/api/workflows'
import { PostActionConfigSection } from '@/components/workflow/PostActionConfigSection'
import { ValidatorConfigSection } from '@/components/workflow/ValidatorConfigSection'

// ─────────────────────────────────────────────────────────────────────────────
// router.ts 등록 방법 (code-based 패턴 — PR #11 컨벤션).
//
//   import { WorkflowDetailRouteAdapter } from './routes/workflows.$key'
//
//   const workflowsKeyRoute = createRoute({
//     getParentRoute: () => rootRoute,
//     path: '/workflows/$key',
//     component: WorkflowDetailRouteAdapter,
//   })
//
// WorkflowDetailRouteAdapter는 useParams({ strict: false })로 key를 추출해
// WorkflowDetailPage에 전달한다. 라우터 등록은 router.ts 담당.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts 에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $key param을 추출하여 WorkflowDetailPage에 전달한다.
 */
export function WorkflowDetailRouteAdapter(): JSX.Element {
  // strict: false — 라우트 트리 어느 위치에서나 param을 추출 가능
  const { key } = useParams({ strict: false })
  return <WorkflowDetailPage workflowKey={key ?? ''} />
}

interface WorkflowDetailPageProps {
  /** URL params에서 추출한 워크플로우 식별 키 */
  workflowKey: string
}

/**
 * 워크플로우 단건 상세 페이지 컴포넌트.
 *
 * - useQuery로 fetchWorkflow(workflowKey)를 호출한다.
 * - 3 상태 분기: 로딩 → 에러/미존재 → 성공(헤더 + WorkflowDiagram).
 * - 에러 시 role="alert" 폴백으로 스크린 리더 접근성 보장.
 *
 * 라우터 의존 없이 props로 workflowKey를 받아 단위 테스트가 가능하다.
 */
export function WorkflowDetailPage({ workflowKey }: WorkflowDetailPageProps): JSX.Element {
  const { data, isLoading, error } = useQuery({
    queryKey: ['workflow', workflowKey],
    queryFn: () => fetchWorkflow(workflowKey),
    retry: false,
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        로딩 중...
      </div>
    )
  }

  if (error !== null || data === undefined) {
    return (
      <div role="alert" className="p-8 text-destructive">
        워크플로우를 찾을 수 없습니다
      </div>
    )
  }

  return (
    <div className="p-8 space-y-6">
      {/* 페이지 헤더 — 워크플로우 이름 + 설명 */}
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">{data.name}</h1>
        {/* backend toDto 가 description 미제공 시 빈 문자열 반환 (PR #13 Task 9 fallback) — .length > 0 으로 빈 문자열 분기 명시 */}
        {data.description.length > 0 && (
          <p className="text-muted-foreground text-sm">{data.description}</p>
        )}
      </header>

      {/* 워크플로우 FSM 다이어그램 (FR-WF-01 read-only, spec S1) */}
      <WorkflowDiagram workflow={data} />

      {/*
        전환 규칙(validator) 설정 섹션 (FR-WF-06) — isSystemAdmin 게이팅은 ValidatorConfigSection 내부에서 수행.

        post-action 섹션보다 **앞에** 둔다. 검증기는 전환을 막거나 후보에서 감추는 것이고
        post-action 은 전환이 끝난 뒤 실행되는 것이라, 위에서 아래로 읽는 순서가 곧 전환이
        일어나는 시간 순서가 된다.
      */}
      <ValidatorConfigSection workflowKey={workflowKey} transitions={data.transitions} />

      {/* post-action 설정 섹션 (FR-NT-05) — isSystemAdmin 게이팅은 PostActionConfigSection 내부에서 수행 */}
      <PostActionConfigSection workflowKey={workflowKey} transitions={data.transitions} />
    </div>
  )
}
