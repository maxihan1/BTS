// 리포트 4종 서브내비 — 리포트 화면끼리 오가는 nav+Link (Jira 패리티 JR-2 · Radix Tabs 금지)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { PROJECT_REPORT_LINKS } from '@/components/project/project-report-links'

/**
 * 이 서브내비의 접근성 이름.
 *
 * 🛑 `navLabels` 에 **넣지 않는다.** `i18n/__tests__/nav-labels.test.ts` FR15 가 그 레지스트리의
 * 모든 값 쌍에 대해 substring 관계 0 을 단언하는데, 리포트 서브내비의 이름은 「리포트」라는
 * 낱말을 품어야 뜻이 통하고 그 낱말은 탭바의 `리포트` 탭 라벨과 겹칠 여지가 있다.
 * `i18n/project-view-labels.ts` 가 **같은 이유로** 갈라져 나온 선례다 — 면제를 적어 정본
 * 레지스트리를 오염시키는 대신, 판별식의 사정권 밖에 자리를 따로 둔다.
 *
 * 값 선정. `프로젝트`(`navLabels.projectNav`)를 substring 으로 **품지 않는다** — 품으면
 * `getByRole('navigation', { name: '프로젝트' })` 가 사이드바 트리와 이것을 함께 잡아
 * Playwright strict mode 로 죽는다.
 */
const REPORTS_NAV_LABEL = '리포트 전환'

/**
 * 서브내비 링크 스타일.
 *
 * 활성 표시는 TanStack `Link` 가 붙이는 `.active` 클래스에 건다 — `ProjectNavTabs` 의
 * `TAB_LINK_CLASS` 와 같은 관례이고, 이 화면이 탭바 **바로 아래**에 오므로 시각 언어를
 * 맞춘다. 다만 한 단계 아래 층위라 활성 배경(`bg-accent`)으로 구분을 얹는다.
 */
const REPORT_LINK_CLASS =
  'inline-flex whitespace-nowrap rounded-md px-3 py-1.5 text-sm text-muted-foreground ' +
  'hover:bg-accent hover:text-accent-foreground ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ' +
  '[&.active]:bg-accent [&.active]:font-semibold [&.active]:text-foreground'

/** {@link ProjectReportsNav} Props */
export interface ProjectReportsNavProps {
  /** 현재 프로젝트 키 — 4개 링크 전부가 `$projectKey` 를 받는다 */
  readonly projectKey: string
}

/**
 * 리포트 화면 안에서 리포트 4종을 오가는 서브내비.
 *
 * 🔴 **Radix Tabs 금지.** 이 저장소는 `role="navigation"`(nav+Link)을 e2e 5건 + 유닛 5건으로
 * 지키고 있고, Tabs 는 `role="tablist"` 를 렌더해 그 계약을 죽인다. 뷰 전환이 실제 라우트
 * 이동(URL 변경·뒤로가기 정상 동작)이므로 네비게이션이 시맨틱상으로도 정답이다 —
 * 근거 전문은 `ProjectNavTabs.tsx` KDoc 과 `docs/design/jira-parity-contract.md` §2.
 *
 * **자기 자신을 포함한 4개를 전부 그린다.** 지금 보고 있는 리포트를 목록에서 빼면 「내가 넷 중
 * 어디에 있나」가 사라진다 — 서브내비의 일은 이동만이 아니라 위치 표시다. 현재 항목에는
 * `aria-current="page"` 가 붙고(`activeProps`), 판정은 라우터가 한다(순수 함수를 따로 두지
 * 않는다 — 두 층이 생기면 서로 어긋난다).
 *
 * 🛑 `activeOptions` 는 **`exact: false`** 다. 리포트 하위에 상세 경로가 생기는 날
 * (`/reports/velocity/<스프린트>` 같은 것) 그 리포트가 계속 강조돼야 한다 — 자기 하위에
 * 들어갔다고 서브내비에서 위치 표시가 사라지면 「내가 리포트를 나갔나」가 된다. 탭바의
 * `리포트` 탭이 같은 이유로 `exact:false` 인 것과 한 층위의 같은 규칙이다.
 * 🛑 반대로 `summary` 탭의 `exact:true` 를 여기 흉내내면 안 된다. 그쪽은 `/projects/$key` 가
 *    **모든** 하위 경로의 접두사라 전 화면이 함께 강조되는 문제였고, 리포트 4경로는 서로의
 *    접두사가 아니라 그 위험이 없다.
 *
 * @param projectKey 현재 프로젝트 키
 */
export function ProjectReportsNav({ projectKey }: ProjectReportsNavProps): JSX.Element {
  return (
    <nav aria-label={REPORTS_NAV_LABEL}>
      <ul className="flex flex-wrap items-center gap-1">
        {PROJECT_REPORT_LINKS.map((link) => (
          <li key={link.to}>
            <Link
              to={link.to}
              params={{ projectKey }}
              activeOptions={{ exact: false }}
              activeProps={{ 'aria-current': 'page' }}
              className={REPORT_LINK_CLASS}
            >
              {link.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}
