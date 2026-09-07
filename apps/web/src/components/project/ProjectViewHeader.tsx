// 프로젝트 화면 공통 제목 헤더 — 탭바 **위**에 서는 스페이스 정체성 줄 (Jira 패리티 J5-8·J5-9·J5-10)
import type { JSX } from 'react'
import { Breadcrumb } from '@/components/layout/Breadcrumb'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'
import { navLabels } from '@/i18n/nav-labels'
import { useProject } from '@/hooks/use-project'

/** {@link ProjectViewHeader} Props */
export interface ProjectViewHeaderProps {
  /** 현재 프로젝트 키 */
  readonly projectKey: string
  /**
   * 액션 영역 DOM 노드를 셸에 돌려주는 콜백 ref.
   *
   * 🛑 **콜백이어야 한다.** `useRef` 는 첫 렌더에 `null` 이고 채워져도 재렌더가 없어
   *    `ProjectHeaderActions` 의 포털이 영영 붙지 않는다(`ProjectNavTabs.portalHost` 선례).
   */
  readonly onActionHost: (node: HTMLElement | null) => void
}

/**
 * 프로젝트 이름 줄. **탭바보다 위**에 있고 탭을 바꿔도 남는다 (J5-8·J5-10).
 *
 * ### 왜 페이지가 아니라 셸이 그리는가
 * Jira 는 스페이스 이름을 뷰가 아니라 스페이스가 소유한다 — 목록 탭을 눌러도 `Deeps Kanban`
 * 이 그대로다(J5-10, Maxi 첨부 실물 2026-09-07). 페이지마다 그리면 프로젝트 하위 20여 화면에
 * 같은 줄을 심어야 하고 하나 빠뜨린 화면만 정체성이 사라진다 — `ProjectViewChrome` 이 탭바를
 * 셸로 올린 것과 정확히 같은 이유다.
 *
 * ### h1 은 여기 하나뿐이다
 * 이 컴포넌트가 프로젝트 하위 전 화면의 문서 `<h1>` 을 단독 소유한다. 그래서
 * ① 9탭 뷰는 제목행을 아예 두지 않고(J5-11) ② 설정·보고서 같은 하위 화면은 `<h2>` 로 내린다
 * (편차 X-J5-14). 두 규칙 중 하나만 지키면 문서에 h1 이 2개가 된다.
 *
 * 🛑 **아래 테두리를 그리지 않는다.** 바로 밑의 탭바(`ProjectNavTabs`)가 이미 `border-b` 를
 *    갖고 있어 두 줄이 겹쳐 보인다. Jira 는 탭바 아래 한 줄만 있다.
 */
export function ProjectViewHeader({ projectKey, onActionHost }: ProjectViewHeaderProps): JSX.Element {
  const { data: project } = useProject(projectKey)
  const projectName = project?.name ?? projectKey

  return (
    <header className="px-6 pt-4">
      <Breadcrumb
        items={[
          { label: navLabels.projectNav, to: '/projects' },
          { label: projectName },
        ]}
      />
      <div className="mt-1 flex items-center justify-between gap-4 pb-3">
        <div className="flex min-w-0 items-center gap-2">
          <h1 className="truncate text-xl font-semibold">{projectName}</h1>
          <FavoriteButton targetType="PROJECT" targetId={projectKey} />
        </div>
        {/* 뷰가 `ProjectHeaderActions` 로 밀어 넣는 버튼들이 여기 붙는다 (J5-9).
            비어 있어도 노드는 항상 있어야 한다 — 포털 목적지가 조건부면 첫 액션이 유실된다. */}
        <div ref={onActionHost} className="flex shrink-0 items-center gap-2" />
      </div>
    </header>
  )
}
