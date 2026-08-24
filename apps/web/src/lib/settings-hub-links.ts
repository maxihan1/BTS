// 개인 설정 허브가 링크하는 하위 라우트 목록 — 설정 도달성 계약의 정본 (FR-UX-06 PR13)
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

/** 개인 설정 허브 카드 1건 — 목적지 라우트 + 표시 라벨/설명/아이콘 */
interface SettingsHubLink {
  readonly to: string
  readonly label: string
  readonly description: string
  readonly Icon: LucideIcon
}

/**
 * 설정 허브가 링크하는 하위 페이지 전량 — `/settings/*` 진입점 **단일 출처**(E5)이자
 * **도달성 계약의 정본**.
 *
 * `label` 은 {@link AccountMenu}(`components/layout/AccountMenu.tsx`)의 드롭다운 하위 링크
 * 라벨을 그대로 재사용한다(프로필·환경 설정·단축키·캘린더 구독·Slack 알림·2단계 인증·
 * Personal Access Token **7개**). AccountMenu 에 없는 나머지 **4개**
 * (notifications·sessions·password·account-links)는 각 라우트 페이지의 실제 `<h1>` 헤딩
 * 텍스트를 그대로 쓴다(알림 구독 설정·활성 세션 관리·비밀번호 변경·계정 연결).
 *
 * ★그 「7 대 4」가 이 파일이 분리돼 나온 이유다 — 4개는 **허브를 거쳐야만** 닿는데, 허브로
 *  가는 UI 링크가 한때 상단바 톱니 하나뿐이었다.
 *
 * 🛑 export 되어 있는 이유는 테스트 편의가 아니라 **판별식이 정본을 직접 읽게 하기 위해서**다.
 *    이 목록과 「모바일에서 실제로 도달 가능한 설정 경로」가 서로를 검사하지 않으면, 상단바
 *    톱니를 좁은 폭에서 감추는 순간 여기 있는 항목 일부가 **조용히 도달 불가**가 된다
 *    (실측 — `password`·`sessions`·`notifications`·`account-links` 4개가 그렇게 사라졌다).
 *    짝 판별식 = `components/layout/__tests__/settings-reachability.test.ts`.
 */
export const SETTINGS_HUB_LINKS: readonly SettingsHubLink[] = [
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
