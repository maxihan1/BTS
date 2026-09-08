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

/** 프로젝트 설정 서브앱 메뉴 정본 — 4그룹 10항목 */
export const PROJECT_SETTINGS_NAV: readonly ProjectSettingsNavGroup[] = [
  {
    key: 'general',
    label: '일반',
    items: [
      { to: '/projects/$projectKey/settings/details', label: '일반', Icon: Settings2 },
      { to: '/projects/$projectKey/settings/project-lead', label: '프로젝트 리드', Icon: UserCheck },
    ],
  },
  {
    key: 'issue',
    label: '이슈',
    items: [
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
 * 현재 경로가 설정 서브앱에 속하는지 판정한다.
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
