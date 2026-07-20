// 관리 허브 페이지 — /admin 인덱스, 관리 하위 라우트 6종 + 사용자 추가 카드 그리드 (FR-UX-06 PR13 Task 5, PL-5)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import {
  Workflow,
  ScrollText,
  ShieldCheck,
  Bell,
  Webhook,
  MessageSquare,
  UserPlus,
  type LucideIcon,
} from 'lucide-react'
import { PageLayout } from '@/components/layout/PageLayout'
import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 관리 허브 카드 1건 — 목적지 라우트 + 표시 라벨/설명/아이콘 */
interface AdminHubLink {
  readonly to: string
  readonly label: string
  readonly description: string
  readonly Icon: LucideIcon
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리 허브 카드 목록 — `/admin/*` 하위 라우트 7개로의 진입점 단일 출처(E5).
 *
 * 앞 6개 `label`은 {@link Sidebar}(`components/layout/Sidebar.tsx`, `ADMIN_NAV_LINKS`가 정본)의
 * 관리 nav 라벨을 그대로 재사용한다(워크플로우 스킴·감사 로그·전역 권한·알림 정책·Webhook·
 * Slack 연결). Sidebar 관리 nav에 없는 `/admin/users/new`(사용자 추가)는 스펙 S2 시나리오
 * 문구를 그대로 사용한다 — 해당 페이지 자체 `<h1>`은 "사용자 생성"이지만, 허브 카드
 * 라벨은 스펙이 정한 "사용자 추가"를 따른다(편집상 별개 표기이며 목적지 페이지 정정
 * 대상이 아니다, `docs/specs/2026-07-20-fr-ux-06-pr13-page-layout.md` S2).
 */
const ADMIN_HUB_LINKS: readonly AdminHubLink[] = [
  {
    to: '/admin/workflow-schemes',
    label: '워크플로우 스킴',
    description: '이슈 상태 전이 워크플로우 스킴을 생성·관리합니다',
    Icon: Workflow,
  },
  {
    to: '/admin/audit-logs',
    label: '감사 로그',
    description: '관리자 작업 이력을 조회합니다',
    Icon: ScrollText,
  },
  {
    to: '/admin/global-permissions',
    label: '전역 권한',
    description: '사용자·그룹에 전역 권한을 부여·회수합니다',
    Icon: ShieldCheck,
  },
  {
    to: '/admin/notification-policies',
    label: '알림 정책',
    description: '시스템 알림 정책을 관리합니다',
    Icon: Bell,
  },
  {
    to: '/admin/webhooks',
    label: 'Webhook',
    description: '아웃바운드 Webhook 구독을 관리합니다',
    Icon: Webhook,
  },
  {
    to: '/admin/slack',
    label: 'Slack 연결',
    description: '워크스페이스 Slack 연결을 관리합니다',
    Icon: MessageSquare,
  },
  {
    to: '/admin/users/new',
    label: '사용자 추가',
    description: '새 사용자 계정을 생성합니다',
    Icon: UserPlus,
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리 허브 — `/admin` 인덱스 페이지.
 *
 * @remarks
 * - `PageLayout` + `PageHeader`(title '관리') 위에 관리 하위 라우트 7개를
 *   가리키는 카드 그리드를 렌더한다(반응형: 모바일 1열 → sm 2열 → lg 3열).
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
        {ADMIN_HUB_LINKS.map(({ to, label, description, Icon }) => (
          <li key={to}>
            <Link
              to={to}
              className="block h-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Card className="h-full transition-colors hover:bg-accent/50">
                <CardHeader>
                  <Icon aria-hidden="true" className="mb-1 size-5 text-muted-foreground" />
                  <CardTitle>{label}</CardTitle>
                  <CardDescription>{description}</CardDescription>
                </CardHeader>
              </Card>
            </Link>
          </li>
        ))}
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
