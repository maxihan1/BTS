// 개인 설정 허브 페이지 — /settings 인덱스, 개인 설정 하위 라우트 10종 + PAT 바깥 링크 카드 그리드 (FR-UX-06 PR13 Task 4, PL-4)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { PageLayout } from '@/components/layout/PageLayout'
import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { PAT_ACCOUNT_LINK, SETTINGS_HUB_LINKS, type SettingsHubLink } from '@/lib/settings-hub-links'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 개인 설정 허브 — `/settings` 인덱스 페이지.
 *
 * @remarks
 * - `PageLayout` + `PageHeader`(title '설정') 위에 개인 설정 하위 라우트 10개를
 *   가리키는 카드 그리드를 렌더한다(반응형: 모바일 1열 → sm 2열 → lg 3열).
 * - 빌드 env `VITE_AUTH_ACCOUNT_URL` 이 있으면 Auth 계정 화면으로 가는 Personal Access Token 카드를 덧붙인다.
 * - requireAuthAndPasswordChanged 가드 적용 — 미인증/비밀번호 미변경 사용자는
 *   각각 /login·/settings/password로 리다이렉트된다(router.ts 등록부).
 * - 카드 전체(`<Card>` 바깥을 `<Link>`로 감쌈)가 클릭 가능한 링크다.
 *
 * @see SETTINGS_HUB_LINKS 카드 목록 단일 출처
 */
export function SettingsIndexPage(): JSX.Element {
  const authAccountUrl: string | undefined = import.meta.env['VITE_AUTH_ACCOUNT_URL'] || undefined
  return (
    <PageLayout>
      <PageHeader title="설정" description="개인 설정을 관리합니다" />
      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {SETTINGS_HUB_LINKS.map(({ to, ...card }) => (
          <li key={to}>
            <Link to={to} className={CARD_LINK_CLASS}>
              <HubCard {...card} />
            </Link>
          </li>
        ))}
        {authAccountUrl !== undefined && (
          <li>
            <a href={authAccountUrl} className={CARD_LINK_CLASS}>
              <HubCard {...PAT_ACCOUNT_LINK} />
            </a>
          </li>
        )}
      </ul>
    </PageLayout>
  )
}

const CARD_LINK_CLASS =
  'block h-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

/** 허브 카드 한 장 — 라우트 링크와 바깥 링크가 같은 모양을 쓴다 */
function HubCard({ label, description, Icon }: Omit<SettingsHubLink, 'to'>): JSX.Element {
  return (
    <Card className="h-full transition-colors hover:bg-accent/50">
      <CardHeader>
        <Icon aria-hidden="true" className="mb-1 size-5 text-muted-foreground" />
        <CardTitle>{label}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
    </Card>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * createRoute의 component 옵션에 직접 전달한다.
 */
export function SettingsIndexRouteAdapter(): JSX.Element {
  return <SettingsIndexPage />
}
