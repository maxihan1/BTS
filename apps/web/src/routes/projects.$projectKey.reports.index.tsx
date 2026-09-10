// 리포트 착지 화면 — 리포트 탭을 누르면 개별 차트가 아니라 4종 카드 목록이 뜬다 (Jira 패리티 JR-2)
import type { JSX } from 'react'
import { Link, useParams } from '@tanstack/react-router'
import { Card, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { PROJECT_REPORT_LINKS } from '@/components/project/project-report-links'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 화면 문구
//
// 리포트 4종의 라벨·질문은 `project-report-links.ts` 가 소유한다. 여기 남는 두 줄은 이 화면
// **하나만** 쓰는 문구라 `settings.index.tsx`(허브 화면 선례)와 같은 자리에 둔다.
// ─────────────────────────────────────────────────────────────────────────────

/** 착지 화면 제목 */
const REPORTS_INDEX_TITLE = '리포트'

/** 착지 화면 부제 — 「무엇을 고르는 자리인가」를 한 줄로 말한다 */
const REPORTS_INDEX_DESCRIPTION = '보고 싶은 것을 골라 여세요'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts 에 등록되는 라우트 어댑터.
 * `useParams` 로 URL 의 `$projectKey` 를 뽑아 페이지에 넘긴다 (리포트 4화면과 같은 관례).
 */
export function ProjectReportsIndexRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectReportsIndexPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

/** {@link ProjectReportsIndexPage} Props */
interface ProjectReportsIndexPageProps {
  /** URL params 에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 리포트 착지 화면.
 *
 * Jira 는 리포트 탭의 착지를 **개별 차트가 아니라 목록/인사이트 화면**으로 둔다(JR-2). 탭을
 * 누른 사람이 아직 무엇을 볼지 안 골랐기 때문이다 — 임의의 한 차트를 띄우면 그 선택이
 * 제품의 판단인 것처럼 보인다.
 *
 * 🛑 **`<h1>` 을 두지 않는다.** 프로젝트 셸의 `ProjectViewHeader` 가 h1 을 단독 소유한다
 * (e2e 의 `<h1>` 단독 계약). 리포트 4화면도 같은 이유로 `h2` 부터 쓴다.
 *
 * 상태 3종을 따로 그리지 않는 이유 — 이 화면은 **정적 목록**이다. 서버 조회가 없어
 * 로딩·에러가 존재하지 않고, 목록은 소스 상수라 빈 경우도 없다(비면 리포트 기능 자체가
 * 없어진 것이고 그것은 판별식이 잡는다).
 *
 * @param projectKey 프로젝트 키
 */
export function ProjectReportsIndexPage({ projectKey }: ProjectReportsIndexPageProps): JSX.Element {
  return (
    <div className="space-y-6 p-8">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">{REPORTS_INDEX_TITLE}</h2>
        <p className="text-muted-foreground text-sm">{REPORTS_INDEX_DESCRIPTION}</p>
      </header>
      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {PROJECT_REPORT_LINKS.map((link) => (
          <li key={link.to}>
            <Link
              to={link.to}
              params={{ projectKey }}
              className="block h-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Card className="h-full transition-colors hover:bg-accent/50">
                <CardHeader>
                  <CardTitle>{link.label}</CardTitle>
                  {/* 제목만 두면 「벨로시티」와 「사이클/리드 타임」 중 무엇을 눌러야 할지 모른다 */}
                  <CardDescription>{link.question}</CardDescription>
                </CardHeader>
              </Card>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}
