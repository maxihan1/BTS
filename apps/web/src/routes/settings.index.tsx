// 개인 설정 허브 페이지 — /settings 인덱스, 개인 설정 하위 라우트 11종 카드 그리드 (FR-UX-06 PR13 Task 4, PL-4)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import {
  User,
  Settings2,
  Keyboard,
  CalendarClock,
  MessageSquare,
  ShieldCheck,
  KeyRound,
  Bell,
  Monitor,
  Lock,
  Link2,
  type LucideIcon,
} from 'lucide-react'
import { PageLayout } from '@/components/layout/PageLayout'
import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 개인 설정 허브 카드 1건 — 목적지 라우트 + 표시 라벨/설명/아이콘 */
interface SettingsHubLink {
  readonly to: string
  readonly label: string
  readonly description: string
  readonly Icon: LucideIcon
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 개인 설정 허브 카드 목록 — `/settings/*` 하위 라우트 11개로의 진입점 단일 출처(E5).
 *
 * `label`은 {@link AccountMenu}(`components/layout/AccountMenu.tsx`, 정본)의 드롭다운
 * 하위 링크 라벨을 그대로 재사용한다(프로필·환경 설정·단축키·캘린더 구독·Slack 알림·
 * 2단계 인증·Personal Access Token 7개). AccountMenu에 없는 나머지 4개
 * (notifications·sessions·password·account-links)는 각 라우트 페이지의 실제 `<h1>`
 * 헤딩 텍스트를 그대로 사용한다(추측 대신 각 페이지 Read해 확인 — 알림 구독 설정·
 * 활성 세션 관리·비밀번호 변경·계정 연결).
 */
const SETTINGS_HUB_LINKS: readonly SettingsHubLink[] = [
  {
    to: '/settings/profile',
    label: '프로필',
    description: '표시 이름·시간대·부서·아바타를 관리합니다',
    Icon: User,
  },
  {
    to: '/settings/preferences',
    label: '환경 설정',
    description: '테마·언어·날짜 표시 형식을 설정합니다',
    Icon: Settings2,
  },
  {
    to: '/settings/keymap',
    label: '단축키',
    description: '자주 쓰는 동작에 원하는 키를 배정합니다',
    Icon: Keyboard,
  },
  {
    to: '/settings/calendar',
    label: '캘린더 구독',
    description: '담당 이슈 일정과 Worklog를 외부 캘린더 앱에서 구독합니다',
    Icon: CalendarClock,
  },
  {
    to: '/settings/slack',
    label: 'Slack 알림',
    description: '본인 Slack 계정을 연결해 담당 이슈 알림을 DM으로 받습니다',
    Icon: MessageSquare,
  },
  {
    to: '/settings/mfa',
    label: '2단계 인증',
    description: '로그인 시 추가 인증 수단을 등록·관리합니다',
    Icon: ShieldCheck,
  },
  {
    to: '/settings/pats',
    label: 'Personal Access Token',
    description: 'API 호출용 개인 접근 토큰을 발급·관리합니다',
    Icon: KeyRound,
  },
  {
    to: '/settings/notifications',
    label: '알림 구독 설정',
    description: '이벤트별 알림 수신 여부를 관리합니다',
    Icon: Bell,
  },
  {
    to: '/settings/sessions',
    label: '활성 세션 관리',
    description: '현재 로그인된 기기와 브라우저 세션을 확인하고 관리합니다',
    Icon: Monitor,
  },
  {
    to: '/settings/password',
    label: '비밀번호 변경',
    description: '현재 비밀번호를 확인한 후 새 비밀번호로 변경합니다',
    Icon: Lock,
  },
  {
    to: '/settings/account-links',
    label: '계정 연결',
    description: '외부 계정 연동을 관리합니다',
    Icon: Link2,
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 개인 설정 허브 — `/settings` 인덱스 페이지.
 *
 * @remarks
 * - `PageLayout` + `PageHeader`(title '설정') 위에 개인 설정 하위 라우트 11개를
 *   가리키는 카드 그리드를 렌더한다(반응형: 모바일 1열 → sm 2열 → lg 3열).
 * - requireAuthAndPasswordChanged 가드 적용 — 미인증/비밀번호 미변경 사용자는
 *   각각 /login·/settings/password로 리다이렉트된다(router.ts 등록부).
 * - 카드 전체(`<Card>` 바깥을 `<Link>`로 감쌈)가 클릭 가능한 링크다.
 *
 * @see SETTINGS_HUB_LINKS 카드 목록 단일 출처
 */
export function SettingsIndexPage(): JSX.Element {
  return (
    <PageLayout>
      <PageHeader title="설정" description="개인 설정을 관리합니다" />
      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {SETTINGS_HUB_LINKS.map(({ to, label, description, Icon }) => (
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
export function SettingsIndexRouteAdapter(): JSX.Element {
  return <SettingsIndexPage />
}
