// 워크플로우 상세 페이지 (FR-WF-01 read-only 다이어그램)
import type { JSX } from 'react'
import { useQuery } from '@tanstack/react-query'
import { WorkflowDiagram } from '@/components/workflow/WorkflowDiagram'
import { fetchWorkflow } from '@/api/workflows'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 가능한 컴포넌트 — 라우터 의존 없이 단위 테스트 가능하도록 props 방식으로 분리.
// router.ts 에서 Route.useParams()로 key를 꺼낸 뒤 이 컴포넌트에 전달한다.
// ─────────────────────────────────────────────────────────────────────────────

interface WorkflowDetailPageProps {
  workflowKey: string
}

/**
 * 워크플로우 단건 상세 페이지.
 * useQuery로 fetchWorkflow를 호출하고 3 상태(로딩/에러/성공)를 분기 렌더한다.
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
      {/* 페이지 헤더 */}
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">{data.name}</h1>
        {data.description !== '' && (
          <p className="text-muted-foreground text-sm">{data.description}</p>
        )}
      </header>

      {/* 워크플로우 다이어그램 */}
      <WorkflowDiagram workflow={data} />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router code-based 라우트 어댑터.
// router.ts 에서 이 컴포넌트를 직접 import해서 라우트에 등록한다.
// 라우터에서 key param을 추출해 WorkflowDetailPage에 주입하는 wrapper.
// ─────────────────────────────────────────────────────────────────────────────

interface WorkflowDetailRouteComponentProps {
  /** TanStack Router가 URL params에서 추출한 workflowKey */
  workflowKey: string
}

/**
 * router.ts에서 createRoute의 component로 사용하는 wrapper.
 * 라우터가 params를 꺼내 이 컴포넌트에 전달한다.
 */
export function WorkflowDetailRouteComponent({
  workflowKey,
}: WorkflowDetailRouteComponentProps): JSX.Element {
  return <WorkflowDetailPage workflowKey={workflowKey} />
}
