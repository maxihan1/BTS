// 관리 허브 페이지 — /admin 인덱스. J9 이후 관리 진입점의 유일한 정본(상단바 방패 아이콘이 여기로 온다)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { ADMIN_HUB_LINKS } from '@/lib/admin-hub-links'
import { PageLayout } from '@/components/layout/PageLayout'
import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리 허브 — `/admin` 인덱스 페이지.
 *
 * @remarks
 * - `PageLayout` + `PageHeader`(title '관리') 위에 {@link ADMIN_HUB_LINKS} 전량을
 *   가리키는 카드 그리드를 렌더한다(반응형: 모바일 1열 → sm 2열 → lg 3열).
 *   개수를 여기 적지 않는다 — 배열이 늘면 그 리터럴만 거짓이 된다.
 * - `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)`
 *   가드 적용 — SYSTEM_ADMIN이 아니면 `/dashboard`로 리다이렉트된다(router.ts 등록부).
 * - 카드 전체(`<Card>` 바깥을 `<Link>`로 감쌈)가 클릭 가능한 링크다.
 *
 * @see ADMIN_HUB_LINKS 카드 목록 단일 출처
 */
export function AdminIndexPage(): JSX.Element {
  return (
    <PageLayout>
      <PageHeader title="관리" description="시스템 관리 기능" />
      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {ADMIN_HUB_LINKS.map(({ to, label, description, Icon }) => {
          const descriptionId = `admin-hub-desc-${to.replaceAll('/', '-')}`
          return (
            <li key={to}>
              {/*
                🛑 `aria-label` 없이 두면 링크의 접근 이름이 **제목 + 설명 전문**이 된다
                   (예: '감사 로그 관리자 작업 이력을 조회합니다'). 그러면
                   `getByRole('link', { name: '감사 로그', exact: true })` 로 집을 수 없고,
                   J9 로 이 허브가 관리 진입점이 된 지금 그 셀렉터를 쓰는 e2e 가 6파일이다.
                   설명은 `aria-describedby` 로 남겨 스크린리더가 여전히 읽는다 —
                   이름에서 뺐다고 정보까지 버리지 않는다.
              */}
              <Link
                to={to}
                aria-label={label}
                aria-describedby={descriptionId}
                className="block h-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <Card className="h-full transition-colors hover:bg-accent/50">
                  <CardHeader>
                    <Icon aria-hidden="true" className="mb-1 size-5 text-muted-foreground" />
                    <CardTitle>{label}</CardTitle>
                    <CardDescription id={descriptionId}>{description}</CardDescription>
                  </CardHeader>
                </Card>
              </Link>
            </li>
          )
        })}
      </ul>
    </PageLayout>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * createRoute의 component 옵션에 직접 전달한다.
 */
export function AdminIndexRouteAdapter(): JSX.Element {
  return <AdminIndexPage />
}
