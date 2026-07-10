// TanStack Router 라우트 트리 정의 — code-based 패턴, 51개 라우트 (공통 3 + 이슈 3 + 워크플로우 스킴 3 + 프로젝트 설정 10 + 프로젝트 보드 1 + 프로젝트 백로그 1 + 프로젝트 타임라인 1 + 스프린트 번다운 1 + 프로젝트 벨로시티 1 + 프로젝트 CFD 1 + 프로젝트 Cycle/Lead Time 1 + settings 11 + 사용자 생성 1 + 감사 로그 1 + 알림 정책 1 + workflow detail 1 + 워크로그 보고 1 + 대시보드 3 + 알림 보관함 1 + 검색 1 + Webhook 2 + Slack 연결 1 + 캘린더 1 | FR-IM-01 D6/D7: projectImportSettingsRoute /projects/$projectKey/settings/import 추가 | FR-RP-04 D6/D7: projectCycleTimeRoute /projects/$projectKey/reports/cycle-time 추가 | FR-PR-01 D6: settingsProfileRoute /settings/profile 추가 | FR-PF-01 Task 7: settingsPreferencesRoute /settings/preferences 추가 | FR-SL-01 D6/D7 Task 7: adminSlackRoute /admin/slack 추가 | FR-PF-03 Task 9: settingsKeymapRoute /settings/keymap 추가 | FR-CA-01 Task 7: calendarRoute /calendar 추가 | FR-CA-02 Task 9: settingsCalendarRoute /settings/calendar 추가 | FR-AT-01 D6 Task 8: projectAutomationSettingsRoute /projects/$projectKey/settings/automation 추가 | FR-SL-02 D6 Task 8: settingsSlackRoute /settings/slack 추가)
import { createRouter, createRoute, createRootRoute } from '@tanstack/react-router'
import { requireAuth, redirectIfAuth, requirePasswordChanged, requireMfaEnrolled, requireSystemAdmin, composeGuards } from './auth/routeGuard'

/** 대부분의 보호 라우트에 적용하는 기본 가드 체인 — 미인증 차단 + 비밀번호 변경 강제 + MFA 등록 강제 (FR-MF-04) */
const requireAuthAndPasswordChanged = composeGuards(requireAuth, requirePasswordChanged, requireMfaEnrolled)
import { RootLayout } from './routes/__root'
import { IndexPage } from './routes/index'
import { LoginPage } from './routes/login'
import { DashboardPage } from './routes/dashboard'
import { WorkflowDetailRouteAdapter } from './routes/workflows.$key'
import { IssueListRouteAdapter } from './routes/issues.index'
import { IssueCreateRouteAdapter } from './routes/issues.new'
import { IssueDetailRouteAdapter } from './routes/issues.$key'
import { AdminWorkflowSchemesRouteAdapter } from './routes/admin.workflow-schemes'
import { WorkflowSchemeNewRouteAdapter } from './routes/admin.workflow-schemes.new'
import { WorkflowSchemeDetailRouteAdapter } from './routes/admin.workflow-schemes.$schemeKey'
import { ProjectWorkflowSchemeSettingsRouteAdapter } from './routes/projects.$projectKey.settings.workflow-scheme'
import { ProjectMembersSettingsRouteAdapter } from './routes/projects.$projectKey.settings.members'
import { ProjectComponentsSettingsRouteAdapter } from './routes/projects.$projectKey.settings.components'
import { ProjectVersionsSettingsRouteAdapter } from './routes/projects.$projectKey.settings.versions'
import { ProjectCustomFieldsSettingsRouteAdapter } from './routes/projects.$projectKey.settings.custom-fields'
import { ProjectIssueTemplatesSettingsRouteAdapter } from './routes/projects.$projectKey.settings.issue-templates'
import { ProjectFieldPermissionsSettingsRouteAdapter } from './routes/projects.$projectKey.settings.field-permissions'
import { ProjectAutomationSettingsRouteAdapter } from './routes/projects.$projectKey.settings.automation'
import { ProjectLeadSettingsRouteAdapter } from './routes/projects.$projectKey.settings.project-lead'
import { ProjectImportSettingsRouteAdapter } from './routes/projects.$projectKey.settings.import'
import { AdminUsersNewRouteAdapter } from './routes/admin.users.new'
import { AdminAuditLogsRouteAdapter } from './routes/admin.audit-logs'
import { AdminNotificationPoliciesRouteAdapter } from './routes/admin.notification-policies'
import { SessionsSettingsRouteAdapter } from './routes/settings.sessions'
import { PasswordSettingsRouteAdapter } from './routes/settings.password'
import { AccountLinksSettingsRouteAdapter } from './routes/settings.account-links'
import { MfaSettingsRouteAdapter } from './routes/settings.mfa'
import { NotificationSettingsRouteAdapter } from './routes/settings.notifications'
import { SettingsPatsRouteAdapter } from './routes/settings.pats'
import { ProfileSettingsRouteAdapter } from './routes/settings.profile'
import { PreferencesSettingsRouteAdapter } from './routes/settings.preferences'
import { KeymapSettingsRouteAdapter } from './routes/settings.keymap'
import { CalendarFeedSettingsRouteAdapter } from './routes/settings.calendar'
import { SlackSettingsRouteAdapter } from './routes/settings.slack'
import { CalendarRouteAdapter } from './routes/calendar'
import { ProjectWorklogReportRouteAdapter } from './routes/projects.$projectKey.reports.worklog'
import { ProjectVelocityReportRouteAdapter } from './routes/projects.$projectKey.reports.velocity'
import { ProjectCfdReportRouteAdapter } from './routes/projects.$projectKey.reports.cfd'
import { ProjectCycleTimeReportRouteAdapter } from './routes/projects.$projectKey.reports.cycle-time'
import { BoardRouteAdapter } from './routes/projects.$projectKey.board'
import { BacklogRouteAdapter } from './routes/projects.$projectKey.backlog'
import { DashboardsRouteAdapter } from './routes/dashboards'
import { DashboardDetailRouteAdapter } from './routes/dashboards.$dashboardId'
import { SharedDashboardRouteAdapter } from './routes/dashboards.shared.$token'
import { InboxRouteAdapter } from './routes/inbox'
import { SearchRouteAdapter } from './routes/search'
import { TimelineRouteAdapter } from './routes/projects.$projectKey.timeline'
import { AdminWebhooksRouteAdapter } from './routes/admin.webhooks'
import { WebhookDeliveriesRouteAdapter } from './routes/admin.webhooks.$id.deliveries'
import { SprintBurndownRouteAdapter } from './routes/projects.$projectKey.sprints.$sprintId.burndown'
import { SlackConnectionSettingsRouteAdapter } from './routes/admin.slack'

const rootRoute = createRootRoute({
  component: RootLayout,
})

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  // T13 라우트 가드에서 dashboard / login 으로 리다이렉트 예정
  component: IndexPage,
})

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  component: LoginPage,
  staticData: { requireAuth: false },
  beforeLoad: redirectIfAuth,
})

const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboard',
  component: DashboardPage,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

const workflowsKeyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/workflows/$key',
  component: WorkflowDetailRouteAdapter,
})

/**
 * 이슈 목록 라우트 — /issues, requireAuth.
 * validateSearch로 page + status/assignee/label/component 필터 쿼리 파라미터 타입 선언.
 * 단일 문자열·배열 양쪽 허용 — 런타임 정규화는 searchToIssueFilter가 담당 (projectBoardRoute 패턴 미러).
 * N4: page 기존 타입 보존, 타 search 콜백과 충돌 없음.
 */
const issuesIndexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues',
  component: IssueListRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
  validateSearch: (search: Record<string, unknown>): {
    page?: number
    status?: string | string[]
    assignee?: string | string[]
    label?: string | string[]
    component?: string | string[]
  } => ({
    page: typeof search['page'] === 'number' ? search['page'] : undefined,
    status: Array.isArray(search['status'])
      ? (search['status'] as string[])
      : typeof search['status'] === 'string'
        ? search['status']
        : undefined,
    assignee: Array.isArray(search['assignee'])
      ? (search['assignee'] as string[])
      : typeof search['assignee'] === 'string'
        ? search['assignee']
        : undefined,
    label: Array.isArray(search['label'])
      ? (search['label'] as string[])
      : typeof search['label'] === 'string'
        ? search['label']
        : undefined,
    component: Array.isArray(search['component'])
      ? (search['component'] as string[])
      : typeof search['component'] === 'string'
        ? search['component']
        : undefined,
  }),
})

/**
 * 이슈 생성 라우트 — /issues/new, requireAuth.
 * validateSearch로 summary(선택) 쿼리 파라미터 선언 — 명령 팔레트 `/issue <제목>`(FR-UX-04 FR7)이
 * `/issues/new?summary=...`로 이동할 때 제목 프리필에 쓰인다.
 */
const issuesNewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues/new',
  component: IssueCreateRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
  validateSearch: (search: Record<string, unknown>): { summary?: string } => ({
    summary: typeof search['summary'] === 'string' ? search['summary'] : undefined,
  }),
})

/** 이슈 상세 라우트 — /issues/$key, requireAuth */
const issuesKeyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues/$key',
  component: IssueDetailRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 워크플로우 스킴 목록 라우트 — /admin/workflow-schemes, requireAuth */
const adminWorkflowSchemesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/workflow-schemes',
  component: AdminWorkflowSchemesRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 워크플로우 스킴 생성 라우트 — /admin/workflow-schemes/new, requireAuth */
const adminWorkflowSchemesNewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/workflow-schemes/new',
  component: WorkflowSchemeNewRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 워크플로우 스킴 상세 라우트 — /admin/workflow-schemes/$schemeKey, requireAuth */
const adminWorkflowSchemesDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/workflow-schemes/$schemeKey',
  component: WorkflowSchemeDetailRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 백로그·스프린트 라우트 — /projects/$projectKey/backlog, requireAuth (FR-BL-01/02) */
const projectBacklogRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/backlog',
  component: BacklogRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 타임라인(Gantt) 라우트 — /projects/$projectKey/timeline, requireAuth (FR-TL-01) */
const projectTimelineRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/timeline',
  component: TimelineRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/**
 * 프로젝트 칸반 보드 라우트 — /projects/$projectKey/board, requireAuth.
 * validateSearch로 board, assignee, label, component 쿼리 파라미터 타입 선언 (FR-BD-02).
 * 단일 문자열·배열 양쪽 허용 — 런타임 정규화는 searchToFilter가 담당.
 */
const projectBoardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/board',
  component: BoardRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
  validateSearch: (search: Record<string, unknown>): {
    board?: string
    assignee?: string | string[]
    label?: string | string[]
    component?: string | string[]
  } => ({
    board: typeof search['board'] === 'string' ? search['board'] : undefined,
    assignee: Array.isArray(search['assignee'])
      ? (search['assignee'] as string[])
      : typeof search['assignee'] === 'string'
        ? search['assignee']
        : undefined,
    label: Array.isArray(search['label'])
      ? (search['label'] as string[])
      : typeof search['label'] === 'string'
        ? search['label']
        : undefined,
    component: Array.isArray(search['component'])
      ? (search['component'] as string[])
      : typeof search['component'] === 'string'
        ? search['component']
        : undefined,
  }),
})

/**
 * 스프린트 번다운/번업 차트 라우트 — /projects/$projectKey/sprints/$sprintId/burndown, requireAuth (FR-RP-01 D6/D7).
 * validateSearch로 view(번다운/번업) 쿼리 파라미터를 선언한다 — 공유 URL 정합을 위해 토글 상태를 URL에 반영.
 * 미지정·잘못된 값은 'burndown'으로 폴백한다.
 */
const projectSprintBurndownRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/sprints/$sprintId/burndown',
  component: SprintBurndownRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
  validateSearch: (search: Record<string, unknown>): { view?: 'burndown' | 'burnup' } => ({
    view: search['view'] === 'burnup' ? 'burnup' : 'burndown',
  }),
})

/** 프로젝트 워크플로우 스킴 할당 라우트 — /projects/$projectKey/settings/workflow-scheme, requireAuth */
const projectWorkflowSchemeSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/workflow-scheme',
  component: ProjectWorkflowSchemeSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 멤버 설정 라우트 — /projects/$projectKey/settings/members, requireAuth */
const projectMembersSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/members',
  component: ProjectMembersSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 컴포넌트 설정 라우트 — /projects/$projectKey/settings/components, requireAuth */
const projectComponentsSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/components',
  component: ProjectComponentsSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 커스텀 필드 설정 라우트 — /projects/$projectKey/settings/custom-fields, requireAuth */
const projectCustomFieldsSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/custom-fields',
  component: ProjectCustomFieldsSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 이슈 템플릿 설정 라우트 — /projects/$projectKey/settings/issue-templates, requireAuth */
const projectIssueTemplatesSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/issue-templates',
  component: ProjectIssueTemplatesSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 필드 권한 규칙 설정 라우트 — /projects/$projectKey/settings/field-permissions, requireAuth */
const projectFieldPermissionsSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/field-permissions',
  component: ProjectFieldPermissionsSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

/** 프로젝트 자동화 설정 라우트 — /projects/$projectKey/settings/automation, requireAuthAndPasswordChanged (FR-AT-01 D6 Task 8) */
const projectAutomationSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/automation',
  component: ProjectAutomationSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 버전 설정 라우트 — /projects/$projectKey/settings/versions, requireAuth */
const projectVersionsSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/versions',
  component: ProjectVersionsSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 리드 설정 라우트 — /projects/$projectKey/settings/project-lead, requireAuth */
const projectLeadSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/project-lead',
  component: ProjectLeadSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 프로젝트 Import(CSV/JSON) 설정 라우트 — /projects/$projectKey/settings/import, requireAuth (FR-IM-01 D6/D7) */
const projectImportSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/import',
  component: ProjectImportSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 내 활성 세션 관리 라우트 — /settings/sessions, requireAuth */
const settingsSessionsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/sessions',
  component: SessionsSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 감사 로그 관리자 조회 라우트 — /admin/audit-logs, requireAuth + requireSystemAdmin + requirePasswordChanged */
const adminAuditLogsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/audit-logs',
  component: AdminAuditLogsRouteAdapter,
  staticData: { requireAuth: true },
  // requirePasswordChanged + requireMfaEnrolled 포함 — 강제변경 미완료 관리자가 admin 라우트로 우회 못 하게 adminUsersNewRoute 와 일관. (FR-MF-04)
  beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled),
})

/** 알림 정책 관리자 조회 라우트 — /admin/notification-policies, requireAuth + requireSystemAdmin + requirePasswordChanged */
const adminNotificationPoliciesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/notification-policies',
  component: AdminNotificationPoliciesRouteAdapter,
  staticData: { requireAuth: true },
  // requirePasswordChanged + requireMfaEnrolled 포함 — adminAuditLogsRoute 와 완전 1:1 (강제변경 미완료 관리자 우회 차단). (FR-MF-04)
  beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled),
})

/** 사용자 생성 라우트 — /admin/users/new, requireAuth + requireSystemAdmin + requirePasswordChanged */
const adminUsersNewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/new',
  component: AdminUsersNewRouteAdapter,
  staticData: { requireAuth: true },
  // requirePasswordChanged + requireMfaEnrolled 포함 (CONCERN-2) — 강제변경 미완료 관리자가 계정 생성으로 우회 못 하게 다른 보호 라우트와 일관. (FR-MF-04)
  beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled),
})

/** 비밀번호 변경 라우트 — /settings/password, requireAuth */
const settingsPasswordRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/password',
  component: PasswordSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

/** 2단계 인증 설정 라우트 — /settings/mfa, requireAuth (password 라우트와 동일 가드 체인) */
const settingsMfaRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/mfa',
  component: MfaSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

/** 알림 구독 설정 라우트 — /settings/notifications, requireAuth (FR-NT-04) */
const settingsNotificationsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/notifications',
  component: NotificationSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 워크로그 집계 보고 라우트 — /projects/$projectKey/reports/worklog, requireAuth (FR-TT-02) */
const projectWorklogReportRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/reports/worklog',
  component: ProjectWorklogReportRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 벨로시티 차트 보고 라우트 — /projects/$projectKey/reports/velocity, requireAuth (FR-RP-02 D6/D7) */
const projectVelocityRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/reports/velocity',
  component: ProjectVelocityReportRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 누적 흐름도(CFD) 보고 라우트 — /projects/$projectKey/reports/cfd, requireAuth (FR-RP-03 D6/D7) */
const projectCfdRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/reports/cfd',
  component: ProjectCfdReportRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** Cycle Time / Lead Time 분포 보고 라우트 — /projects/$projectKey/reports/cycle-time, requireAuth (FR-RP-04 D6/D7) */
const projectCycleTimeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/reports/cycle-time',
  component: ProjectCycleTimeReportRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/**
 * AQL 검색 라우트 — /search, requireAuth (FR-SR-02/03).
 * `filterId` 파라미터: 저장 필터 딥링크용 UUID. adapter가 1회 해소 후 제거한다 (FR-SR-03 Task-6).
 */
const searchRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/search',
  component: SearchRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
  validateSearch: (search: Record<string, unknown>): {
    q?: string
    page?: number
    projectKey?: string
    filterId?: string
  } => ({
    q: typeof search['q'] === 'string' ? search['q'] : undefined,
    page: typeof search['page'] === 'number' ? search['page'] : undefined,
    projectKey: typeof search['projectKey'] === 'string' ? search['projectKey'] : undefined,
    filterId: typeof search['filterId'] === 'string' ? search['filterId'] : undefined,
  }),
})

/** 알림 보관함 라우트 — /inbox, requireAuth (FR-UX-03) */
const inboxRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/inbox',
  component: InboxRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 대시보드 목록 라우트 — /dashboards, requireAuth (FR-DB-01) */
const dashboardsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboards',
  component: DashboardsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 대시보드 상세 라우트 — /dashboards/$dashboardId, requireAuth (FR-DB-01) */
const dashboardDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboards/$dashboardId',
  component: DashboardDetailRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/**
 * 익명 대시보드 공유 뷰 라우트 — /dashboards/shared/$token, 공개(비인증) 라우트 (FR-DB-03 D6/D7 Task 9).
 * beforeLoad 가드 없음 — 유효/무효 토큰 판정은 페이지 컴포넌트가 raw fetch 응답(200/404)으로 직접
 * 처리한다(EC-11, 로그인 리다이렉트 금지). `?embed=1` 쿼리로 임베드(크롬 최소화) 모드를 전달한다.
 *
 * embed는 boolean으로 정규화한다 — TanStack Router 기본 parseSearch(JSON.parse 기반)는
 * `?embed=1`을 문자열 '1'이 아니라 **숫자 1**로 파싱하므로, 숫자/문자열/불리언 표현을 모두
 * 인정해야 실제 URL(ShareDashboardModal이 생성하는 iframe 스니펫 `?embed=1`)에서도 동작한다.
 */
const dashboardsSharedTokenRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboards/shared/$token',
  component: SharedDashboardRouteAdapter,
  staticData: { requireAuth: false },
  validateSearch: (search: Record<string, unknown>): { embed?: boolean } => ({
    embed:
      search['embed'] === 1 || search['embed'] === '1' || search['embed'] === true || search['embed'] === 'true'
        ? true
        : undefined,
  }),
})

/** 아웃바운드 Webhook 구독 관리 라우트 — /admin/webhooks, requireAuth + requireSystemAdmin + requirePasswordChanged (FR-API-03 PR4) */
const adminWebhooksRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/webhooks',
  component: AdminWebhooksRouteAdapter,
  staticData: { requireAuth: true },
  // requirePasswordChanged + requireMfaEnrolled 포함 — adminAuditLogsRoute 와 동일 (FR-MF-04)
  beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled),
})

/** 아웃바운드 Webhook 구독 발송 이력 라우트 — /admin/webhooks/$id/deliveries, requireAuth + requireSystemAdmin + requirePasswordChanged (FR-API-03 PR4) */
const adminWebhooksDeliveriesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/webhooks/$id/deliveries',
  component: WebhookDeliveriesRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled),
})

/**
 * Slack 연결 관리자 라우트 — /admin/slack, requireAuth + requireSystemAdmin (FR-SL-01 D6/D7 Task 7).
 * `?installed=`/`?error=` OAuth 콜백 쿼리 파라미터 타입 선언 — 값은 페이지가 마운트 시 캡처 후
 * navigate로 제거한다(새로고침 재표시 방지).
 */
const adminSlackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/slack',
  component: SlackConnectionSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: composeGuards(requireAuth, requireSystemAdmin),
  validateSearch: (search: Record<string, unknown>): { installed?: string; error?: string } => ({
    installed: typeof search['installed'] === 'string' ? search['installed'] : undefined,
    error: typeof search['error'] === 'string' ? search['error'] : undefined,
  }),
})

/** 계정 연결 설정 라우트 — /settings/account-links, requireAuth + mustChangePassword 차단 */
const settingsAccountLinksRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/account-links',
  component: AccountLinksSettingsRouteAdapter,
  staticData: { requireAuth: true },
  // settings.sessions 동급 가드 — mustChangePassword 사용자 선차단 (settings.password의 requireAuth 단독 아님)
  beforeLoad: requireAuthAndPasswordChanged,
  // ?link= / ?reauth= 콜백 쿼리 파라미터 타입 선언 — window.location.search 직접 파싱 대신 (issuesIndexRoute 선례)
  validateSearch: (search: Record<string, unknown>): { link?: string; reauth?: string } => ({
    link: typeof search['link'] === 'string' ? search['link'] : undefined,
    reauth: typeof search['reauth'] === 'string' ? search['reauth'] : undefined,
  }),
})

/** Personal Access Token 셀프서비스 관리 라우트 — /settings/pats, requireAuth (settings.sessions/notifications/account-links 동급 가드) (FR-API-04) */
const settingsPatsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/pats',
  component: SettingsPatsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 사용자 프로필 설정 라우트 — /settings/profile, requireAuth (settings.password/mfa와 동일 단독 가드) (FR-PR-01 D6) */
const settingsProfileRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/profile',
  component: ProfileSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

/** 사용자 환경설정(테마/언어/날짜포맷) 라우트 — /settings/preferences, requireAuth (settings.profile과 동일 단독 가드) (FR-PF-01 Task 7) */
const settingsPreferencesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/preferences',
  component: PreferencesSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

/** 단축키 커스터마이즈 설정 라우트 — /settings/keymap, requireAuthAndPasswordChanged (settings.sessions/notifications/pats와 동일 가드) (FR-PF-03 Task 9) */
const settingsKeymapRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/keymap',
  component: KeymapSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 캘린더 iCal 구독(Export) 설정 라우트 — /settings/calendar, requireAuthAndPasswordChanged (settings.keymap과 동일 가드) (FR-CA-02 Task 9) */
const settingsCalendarRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/calendar',
  component: CalendarFeedSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 본인 Slack 계정 연결 설정 라우트 — /settings/slack, requireAuthAndPasswordChanged (settings.calendar와 동일 가드) (FR-SL-02 D6 Task 8) */
const settingsSlackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/slack',
  component: SlackSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 개인 캘린더 월/주 뷰 라우트 — /calendar, requireAuth (identity-access BC, FR-CA-01 Task 7) */
const calendarRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/calendar',
  component: CalendarRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/**
 * 전체 라우트 트리.
 * 50개 라우트: / · /login · /dashboard · /workflows/:key · /issues · /issues/new · /issues/:key
 *   · /admin/workflow-schemes · /admin/workflow-schemes/new · /admin/workflow-schemes/:schemeKey
 *   · /admin/users/new · /admin/audit-logs · /admin/notification-policies
 *   · /admin/webhooks · /admin/webhooks/:id/deliveries · /admin/slack
 *   · /inbox · /dashboards · /dashboards/:dashboardId · /dashboards/shared/:token · /search
 *   · /projects/:projectKey/backlog · /projects/:projectKey/board · /projects/:projectKey/timeline
 *   · /projects/:projectKey/sprints/:sprintId/burndown
 *   · /projects/:projectKey/settings/workflow-scheme · /projects/:projectKey/settings/members
 *   · /projects/:projectKey/settings/components · /projects/:projectKey/settings/versions
 *   · /projects/:projectKey/settings/custom-fields · /projects/:projectKey/settings/issue-templates
 *   · /projects/:projectKey/settings/field-permissions · /projects/:projectKey/settings/automation
 *   · /projects/:projectKey/settings/project-lead
 *   · /projects/:projectKey/settings/import
 *   · /projects/:projectKey/reports/worklog · /projects/:projectKey/reports/velocity · /projects/:projectKey/reports/cfd
 *   · /projects/:projectKey/reports/cycle-time
 *   · /settings/sessions · /settings/password · /settings/account-links · /settings/mfa
 *   · /settings/notifications · /settings/pats · /settings/profile · /settings/preferences · /settings/keymap
 *   · /settings/calendar · /settings/slack
 *   · /calendar
 * requireAuth 라우트: /dashboard · /inbox · /dashboards · /dashboards/* · /search · /issues · /issues/* · /admin/* · /projects/* · /settings/* · /calendar
 */
export const routeTree = rootRoute.addChildren([
  // 공통 — 인증/진입점
  indexRoute,
  loginRoute,
  dashboardRoute,
  // issue-tracking BC
  issuesIndexRoute,
  issuesNewRoute,
  issuesKeyRoute,
  // project-workflow BC — 관리자 스킴 관리 (/admin/workflow-schemes/new은 /:schemeKey보다 먼저 등록)
  adminWorkflowSchemesRoute,
  adminWorkflowSchemesNewRoute,
  adminWorkflowSchemesDetailRoute,
  // identity-access BC — 감사 로그 관리자 조회
  adminAuditLogsRoute,
  // notification BC — 알림 정책 관리자 조회 (FR-NT-01)
  adminNotificationPoliciesRoute,
  // identity-access BC — 사용자 생성 (/admin/users/new)
  adminUsersNewRoute,
  // search-export-import BC — 아웃바운드 Webhook 구독 관리 + 발송 이력 (FR-API-03 PR4)
  adminWebhooksRoute,
  adminWebhooksDeliveriesRoute,
  // slack-integration BC — Slack 연결 관리 (FR-SL-01 D6/D7 Task 7)
  adminSlackRoute,
  // search-export-import BC — AQL 검색 (FR-SR-02)
  searchRoute,
  // notification BC — 알림 보관함 (FR-UX-03)
  inboxRoute,
  // notification BC — 대시보드 목록/상세 (FR-DB-01, /dashboards/$dashboardId는 /dashboards보다 뒤에 등록해 충돌 없음)
  dashboardsRoute,
  dashboardDetailRoute,
  // notification BC — 익명 대시보드 공유 뷰, 공개 라우트 (FR-DB-03 D6/D7 Task 9)
  dashboardsSharedTokenRoute,
  // agile-planning BC — 프로젝트 백로그·스프린트 (FR-BL-01/02)
  projectBacklogRoute,
  // agile-planning BC — 프로젝트 칸반 보드 (FR-BD-01)
  projectBoardRoute,
  // agile-planning BC — 프로젝트 타임라인(Gantt) (FR-TL-01)
  projectTimelineRoute,
  // agile-planning BC — 스프린트 번다운/번업 차트 (FR-RP-01 D6/D7)
  projectSprintBurndownRoute,
  // project-workflow BC — 프로젝트별 스킴 할당
  projectWorkflowSchemeSettingsRoute,
  // project-membership BC — 프로젝트 멤버 관리
  projectMembersSettingsRoute,
  // component-management BC — 프로젝트 컴포넌트 관리
  projectComponentsSettingsRoute,
  // version-release BC — 프로젝트 버전 관리
  projectVersionsSettingsRoute,
  // issue-tracking BC — 커스텀 필드 관리
  projectCustomFieldsSettingsRoute,
  // issue-tracking BC — 이슈 템플릿 관리 (FR-TM-01)
  projectIssueTemplatesSettingsRoute,
  // project-workflow BC — 필드 권한 규칙 관리
  projectFieldPermissionsSettingsRoute,
  // automation BC — 프로젝트 자동화 트리거 규칙 관리 (FR-AT-01 D6 Task 8)
  projectAutomationSettingsRoute,
  // issue-tracking BC — 프로젝트 리드 설정 (project 서브도메인, 권한만 MANAGE_COMPONENTS 재사용)
  projectLeadSettingsRoute,
  // search-export-import BC — 프로젝트 Import(CSV/JSON) 설정 (FR-IM-01 D6/D7)
  projectImportSettingsRoute,
  // issue-tracking BC — 워크로그 집계 보고 (FR-TT-02)
  projectWorklogReportRoute,
  // agile-planning BC — 벨로시티 차트 보고 (FR-RP-02 D6/D7)
  projectVelocityRoute,
  // agile-planning BC — 누적 흐름도(CFD) 보고 (FR-RP-03 D6/D7)
  projectCfdRoute,
  // agile-planning BC — Cycle Time / Lead Time 분포 보고 (FR-RP-04 D6/D7)
  projectCycleTimeRoute,
  // identity-access BC — 내 활성 세션 관리
  settingsSessionsRoute,
  // identity-access BC — 비밀번호 변경
  settingsPasswordRoute,
  // identity-access BC — 계정 연결 관리 (FR-AU-08/08b)
  settingsAccountLinksRoute,
  // identity-access BC — 2단계 인증 설정 (FR-MF-01)
  settingsMfaRoute,
  // notification BC — 사용자 알림 구독 설정 (FR-NT-04)
  settingsNotificationsRoute,
  // identity-access BC — Personal Access Token 셀프서비스 관리 (FR-API-04)
  settingsPatsRoute,
  // identity-access BC — 사용자 프로필(이름/아바타/타임존/부서) 편집 (FR-PR-01 D6)
  settingsProfileRoute,
  // identity-access BC — 사용자 환경설정(테마/언어/날짜포맷) (FR-PF-01 Task 7)
  settingsPreferencesRoute,
  // identity-access BC — 단축키 커스터마이즈 설정 (FR-PF-03 Task 9)
  settingsKeymapRoute,
  // identity-access BC — 캘린더 iCal 구독(Export) 설정 (FR-CA-02 Task 9)
  settingsCalendarRoute,
  // slack-integration BC — 본인 Slack 계정 연결 설정 (FR-SL-02 D6 Task 8)
  settingsSlackRoute,
  // identity-access BC — 개인 캘린더 월/주 뷰 (FR-CA-01 Task 7)
  calendarRoute,
  // workflows (레거시 workflow 상세 — 향후 마이그레이션 예정)
  workflowsKeyRoute,
])

/** 앱 전역 라우터 인스턴스 — Register 모듈 증강으로 전체 타입 안전 navigate 보장 */
export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
  // 라우트별 인증 필요 여부를 staticData 로 선언.
  // 실제 가드 로직은 T13 beforeLoad 에서 구현.
  interface StaticDataRouteOption {
    requireAuth?: boolean
  }
}
