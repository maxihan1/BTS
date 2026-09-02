// 관리 허브가 링크하는 하위 라우트 목록 — 관리 진입점 계약의 정본 (Jira 패리티 J9)
import {
  Workflow,
  Waypoints,
  ScrollText,
  ShieldCheck,
  Bell,
  Webhook,
  MessageSquare,
  UserPlus,
  type LucideIcon,
} from 'lucide-react'

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
 * 관리 허브 카드 목록 — `/admin/*` 진입점의 **유일한 정본**(E5).
 *
 * ★ Jira 패리티 J9 이전에는 `Sidebar.tsx` 의 `ADMIN_NAV_LINKS` 가 같은 라벨을 따로 들고
 * 있었고 두 목록은 실제로 한 칸 어긋나 있었다(`/admin/users/new` 가 여기에만 있었다).
 * 그 nav 를 상단바 허브 링크로 옮기면서 사본을 없앴다 — **여기 말고 다른 곳에 목록을
 * 다시 만들지 마라.**
 *
 * `/admin/users/new`(사용자 추가) 라벨은 스펙 S2 시나리오 문구를 따른다 — 해당 페이지 자체
 * `<h1>`은 "사용자 생성"이지만 허브 카드 라벨은 "사용자 추가"다(편집상 별개 표기이며 목적지
 * 페이지 정정 대상이 아니다, `docs/specs/2026-07-20-fr-ux-06-pr13-page-layout.md` S2).
 *
 * 🔒 **export 한다.** `Sidebar.test.tsx` 가 「이 라벨들이 사이드바에 하나도 없다」를 재는 데
 * 쓴다 — 손 사본을 두면 9번째 링크를 허브에 넣으면서 사이드바에도 되살리는 조합을 아무도
 * 못 본다(저장소 지배 결함 `two-lists-never-check-each-other`).
 */
export const ADMIN_HUB_LINKS: readonly AdminHubLink[] = [
  {
    to: '/admin/workflows',
    // ★ '워크플로우' 로 줄이면 아래 '워크플로우 스킴' 의 substring 이다 — Sidebar 와 같은 이유
    label: '워크플로우 관리',
    description: '워크플로우를 만들고 상태와 전환을 편집합니다',
    Icon: Waypoints,
  },
  {
    to: '/admin/workflow-schemes',
    label: '워크플로우 스킴',
    description: '이슈 상태 전환 워크플로우 스킴을 생성·관리합니다',
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
