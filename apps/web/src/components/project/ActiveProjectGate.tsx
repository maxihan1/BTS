// 활성 프로젝트 미해소 상태(로딩/에러/0개) 안내 — FR-UX-07 (/issues·/search 공용)
import type { JSX } from 'react'
import { EmptyState } from '@/components/ui/empty-state'
import { Button } from '@/components/ui/button'
import type { UnresolvedActiveProject } from '@/hooks/use-resolved-active-project'

/**
 * {@link ActiveProjectGate} props — 미해소 상태를 통째로 받는다.
 * `retry`가 error 멤버 **안**에 있어 호출부가 누락할 수 없다. optional prop 이면 잊어도
 * tsc 가 못 잡는다 — 실제로 `/issues`만 빠뜨린 채 CI 3종이 그린이었다(코드리뷰 CR-A).
 */
export interface ActiveProjectGateProps {
  state: UnresolvedActiveProject
}

/**
 * 활성 프로젝트가 아직 정해지지 않았을 때 대신 보여주는 안내.
 *
 * **왜 공유 컴포넌트인가.** `/issues`와 `/search`가 각자 같은 문구·구조를 들고 있으면
 * 한쪽만 자라 갈라진다(이 저장소의 지배적 결함 양식). 문구는 여기 한 곳에만 있다.
 *
 * **소비처가 배치를 정한다.** 이 컴포넌트는 "무엇을 보여줄지"만 알고 "어디에 놓을지"는
 * 모른다 — `/issues`는 split view 우측 상세 페인을 살려야 해서 **목록 영역만** 이걸로
 * 대체하고, `/search`는 살릴 영역이 없어 어댑터 최상단에서 반환한다.
 */
export function ActiveProjectGate({ state }: ActiveProjectGateProps): JSX.Element {
  if (state.status === 'loading') {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">로딩 중...</div>
    )
  }

  if (state.status === 'error') {
    // 재시도 수단이 없으면 사용자는 새로고침 말고 탈출구가 없다 — 스펙 E2 가 요구한 것이다.
    return (
      <div role="alert" className="flex flex-col items-start gap-3 p-8 text-destructive">
        <p>프로젝트 목록을 불러올 수 없습니다.</p>
        <Button type="button" variant="outline" size="sm" onClick={state.retry}>
          다시 시도
        </Button>
      </div>
    )
  }

  return (
    <EmptyState
      title="접근 가능한 프로젝트가 없습니다."
      description="프로젝트에 참여하거나 새 프로젝트를 만들면 이슈를 볼 수 있습니다."
      action={
        // plain anchor — 라우터 Link 는 라우터 컨텍스트를 요구해 단위 테스트에서 마운트할 수
        // 없다. `issues.index.tsx` 의 `새 이슈` 진입점과 동일한 기존 관례다.
        <a
          href="/projects"
          className="text-sm font-medium text-primary underline-offset-4 hover:underline"
        >
          프로젝트 목록으로 이동
        </a>
      }
    />
  )
}
