// 프로젝트 셸 모드 정본 — 설정 서브앱 메뉴 4그룹 10항목 + 트리/설정 판정 순수 함수 (JS-2)
import {
  Settings2,
  UserCheck,
  Workflow,
  FileText,
  ListPlus,
  ShieldCheck,
  Users,
  Zap,
  MessageSquare,
  Upload,
  type LucideIcon,
} from 'lucide-react'

/** 사이드바·탭바가 공유하는 셸 모드 — 프로젝트 트리인가 설정 서브앱인가 */
export type ProjectShellMode = 'tree' | 'settings'

/** 설정 그룹 안정 식별자 — 화면 문구가 아니라 이것이 정체성이다 */
export type ProjectSettingsNavGroupKey = 'general' | 'issue' | 'access' | 'integration'

/** 설정 메뉴 항목 1건 — 목적지 라우트 + 표시 라벨 + 아이콘 */
export interface ProjectSettingsNavItem {
  /** TanStack Router 라우트 경로 (`$projectKey` 플레이스홀더 포함) */
  readonly to: string
  /** 화면 문구 */
  readonly label: string
  /** 접힘 레일(64px)에서 라벨을 대신하는 시각 앵커 */
  readonly Icon: LucideIcon
}

/** 설정 메뉴 그룹 1건 */
export interface ProjectSettingsNavGroup {
  readonly key: ProjectSettingsNavGroupKey
  readonly label: string
  readonly items: readonly ProjectSettingsNavItem[]
}

/**
 * 프로젝트 설정 서브앱 메뉴 **단일 정본** — 4그룹 10항목.
 *
 * ### 이 목록이 곧 「설정 서브앱의 경계」다
 * 설정 사이드바(`ProjectSettingsNav`)가 그리는 대상이자 {@link resolveProjectShellMode} 의
 * 판정 근거다. 두 소비자가 **같은 배열 하나**를 읽으므로 「사이드바에 있는데 탭바가 남는」
 * 어긋남이 구조적으로 생기지 않는다.
 *
 * ### 🛑 `컴포넌트`·`버전`은 여기 없다 (편차 X-N2)
 * 두 화면은 경로가 `/projects/$projectKey/settings/...` 인데도 **정본 탭**(`PROJECT_VIEW_TABS`)
 * 으로만 유지한다. 그래서 규칙이 「설정 «경로» = 탭바 없음」이 아니라 **「설정 서브앱에 «속한»
 * 경로 = 탭바 없음」**이 된다. 넣으면 두 화면에서 탭바가 사라져 정본 탭이 스스로를 지운다.
 *
 * ### 소비자 계약 — 항목 0개인 그룹은 헤딩도 그리지 않는다 (★리뷰 D-5)
 * 지금은 권한 게이팅이 없어 항상 10항목이지만 설정은 게이팅이 붙는 표면이다(스펙 GAP-1).
 * 그날 필터가 그룹을 비우면 소비자는 **그룹 헤딩까지 함께 생략**해야 한다 — 빈 헤딩만 남은
 * 사이드바는 「권한이 없다」가 아니라 「고장났다」로 읽힌다.
 *
 * 아이콘이 필수인 이유는 접힘 레일(64px)이다 — 라벨을 `sr-only` 로 숨기면 시각 앵커가 없는
 * 항목 10개가 **구분 불가능한 빈 행**이 된다. `lib/settings-hub-links.ts` 가 개인 설정 11개에
 * 쓰는 `Icon: LucideIcon` 패턴을 그대로 재사용한다(새 추상화 아님).
 *
 * ### `settings/details` 의 라벨은 `'상세정보'` 다 — Jira 원문이 Details 다 (JI-1)
 * "Select **Details**." ([edit a space's details], Cloud · 조회 2026-09-08). 종전 `'일반'` 은
 * 그 자리의 BTS 자체 명명이었고, 이 교정은 중복 회피가 아니라 **Jira 정합 회복**이다.
 * 부수적으로 그룹 라벨 `'일반'`(`general`)과의 글자 충돌도 사라져 「일반 › 상세정보」로 읽힌다.
 * 교체 비용 0 — 이 문자열에 걸린 e2e 는 0건이다(2026-09-08 전수 실측 · `SETTINGS_LINK_CONTRACT`
 * 11개에 「일반」이 애초에 없었다. 그 누락 자체가 리뷰 E-3 드리프트의 원인 메커니즘이다).
 *
 * [edit a space's details]: https://support.atlassian.com/jira-work-management/docs/edit-a-projects-details/
 */
export const PROJECT_SETTINGS_NAV: readonly ProjectSettingsNavGroup[] = [
  {
    key: 'general',
    label: '일반',
    items: [
      { to: '/projects/$projectKey/settings/details', label: '상세정보', Icon: Settings2 },
      { to: '/projects/$projectKey/settings/project-lead', label: '프로젝트 리드', Icon: UserCheck },
    ],
  },
  {
    key: 'issue',
    label: '이슈',
    items: [
      // FR-WF-08 — 스킴보다 먼저 둔다. 워크플로우를 만들고 그것을 스킴에 매핑하는 순서다.
      { to: '/projects/$projectKey/settings/workflows', label: '워크플로우', Icon: Workflow },
      {
        to: '/projects/$projectKey/settings/workflow-scheme',
        label: '워크플로우 스킴',
        Icon: Workflow,
      },
      { to: '/projects/$projectKey/settings/issue-templates', label: '이슈 템플릿', Icon: FileText },
      { to: '/projects/$projectKey/settings/custom-fields', label: '커스텀 필드', Icon: ListPlus },
      {
        to: '/projects/$projectKey/settings/field-permissions',
        label: '필드 권한',
        Icon: ShieldCheck,
      },
    ],
  },
  {
    key: 'access',
    label: '액세스',
    items: [{ to: '/projects/$projectKey/settings/members', label: '멤버', Icon: Users }],
  },
  {
    key: 'integration',
    label: '연동',
    items: [
      { to: '/projects/$projectKey/settings/automation', label: '자동화', Icon: Zap },
      {
        to: '/projects/$projectKey/settings/slack-channels',
        label: 'Slack 채널',
        Icon: MessageSquare,
      },
      { to: '/projects/$projectKey/settings/import', label: '가져오기', Icon: Upload },
    ],
  },
]

/**
 * 현재 경로가 설정 서브앱에 속하는지 판정한다 — 사이드바와 탭바가 **이 함수 하나를 공유**한다.
 *
 * ### 왜 문자열이 아니라 목록에서 유도하는가
 * 🛑 `pathname.includes('/settings/')` 로 판정하면 `컴포넌트`·`버전`(편차 X-N2)을 되살리려고
 * **예외 분기**를 따로 써야 한다. 그 분기는 {@link PROJECT_SETTINGS_NAV} 와 서로를 검사하지
 * 않는 **두 번째 목록**이다 — 메뉴에 항목을 하나 더하는 사람은 분기를 모르고, 분기를 고치는
 * 사람은 메뉴를 안 본다. 이 저장소가 `two-lists-never-check-each-other` 로 이름 붙인 지배
 * 결함 양식이고, 실물 증거가 이미 있다(`SETTINGS_LINK_CONTRACT` 11 vs 소스 12 · 리뷰 E-3).
 * 목록에서 유도하면 판정은 **정의상** 메뉴와 같다.
 *
 * ### 경계 2개
 * - `projectKey` 부재 → 무조건 `'tree'`. `Sidebar` 는 `/issues`·`/dashboards`·`/calendar`
 *   에서도 렌더되고 그때 키가 `undefined` 다(★리뷰 E-4). 치환을 그대로 두면
 *   `/projects/undefined/settings/details` 와 비교하며 **우연히** 동작한다.
 * - 접두사가 아니라 **세그먼트**로 비교한다. `startsWith(href)` 만 쓰면
 *   `/settings/details` 가 `/settings/details-v2` 를 먹는다.
 *
 * @param pathname 라우터가 만든 현재 pathname
 * @param projectKey 현재 프로젝트 키 — 프로젝트 밖에서는 `undefined`
 * @returns 설정 서브앱이면 `'settings'`, 그 밖에는 `'tree'`
 */
export function resolveProjectShellMode(
  pathname: string,
  projectKey: string | undefined,
): ProjectShellMode {
  if (projectKey === undefined || projectKey === '') return 'tree'

  for (const group of PROJECT_SETTINGS_NAV) {
    for (const item of group.items) {
      const href = item.to.replace('$projectKey', projectKey)
      if (pathname === href || pathname.startsWith(`${href}/`)) return 'settings'
    }
  }
  return 'tree'
}
