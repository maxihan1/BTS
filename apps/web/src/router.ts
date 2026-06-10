// TanStack Router 라우트 트리 정의 — code-based 패턴, 21개 라우트 (이슈 7 + 워크플로우 스킴 4 + settings 4 + 멤버 1 + 컴포넌트 1 + 버전 1 + 커스텀 필드 1 + 사용자 생성 1 + 필드 권한 1)
import { createRouter, createRoute, createRootRoute } from '@tanstack/react-router'
import { requireAuth, redirectIfAuth, requirePasswordChanged, requireSystemAdmin, composeGuards } from './auth/routeGuard'

/** 대부분의 보호 라우트에 적용하는 기본 가드 체인 — 미인증 차단 + 비밀번호 변경 강제 */
const requireAuthAndPasswordChanged = composeGuards(requireAuth, requirePasswordChanged)
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
import { ProjectFieldPermissionsSettingsRouteAdapter } from './routes/projects.$projectKey.settings.field-permissions'
import { ProjectLeadSettingsRouteAdapter } from './routes/projects.$projectKey.settings.project-lead'
import { AdminUsersNewRouteAdapter } from './routes/admin.users.new'
import { SessionsSettingsRouteAdapter } from './routes/settings.sessions'
import { PasswordSettingsRouteAdapter } from './routes/settings.password'
import { AccountLinksSettingsRouteAdapter } from './routes/settings.account-links'

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

/** 이슈 목록 라우트 — /issues, requireAuth. validateSearch로 page 쿼리 파라미터 타입 선언 */
const issuesIndexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues',
  component: IssueListRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
  validateSearch: (search: Record<string, unknown>): { page?: number } => ({
    page: typeof search['page'] === 'number' ? search['page'] : undefined,
  }),
})

/** 이슈 생성 라우트 — /issues/new, requireAuth */
const issuesNewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues/new',
  component: IssueCreateRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
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

/** 프로젝트 필드 권한 규칙 설정 라우트 — /projects/$projectKey/settings/field-permissions, requireAuth */
const projectFieldPermissionsSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectKey/settings/field-permissions',
  component: ProjectFieldPermissionsSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
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

/** 내 활성 세션 관리 라우트 — /settings/sessions, requireAuth */
const settingsSessionsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/sessions',
  component: SessionsSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuthAndPasswordChanged,
})

/** 사용자 생성 라우트 — /admin/users/new, requireAuth + requireSystemAdmin + requirePasswordChanged */
const adminUsersNewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/new',
  component: AdminUsersNewRouteAdapter,
  staticData: { requireAuth: true },
  // requirePasswordChanged 포함 (CONCERN-2) — 강제변경 미완료 관리자가 계정 생성으로 우회 못 하게 다른 보호 라우트와 일관.
  beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged),
})

/** 비밀번호 변경 라우트 — /settings/password, requireAuth */
const settingsPasswordRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings/password',
  component: PasswordSettingsRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
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

/**
 * 전체 라우트 트리.
 * 21개 라우트: / · /login · /dashboard · /workflows/:key · /issues · /issues/new · /issues/:key
 *   · /admin/workflow-schemes · /admin/workflow-schemes/new · /admin/workflow-schemes/:schemeKey
 *   · /admin/users/new
 *   · /projects/:projectKey/settings/workflow-scheme · /projects/:projectKey/settings/members
 *   · /projects/:projectKey/settings/components · /projects/:projectKey/settings/versions
 *   · /projects/:projectKey/settings/custom-fields · /projects/:projectKey/settings/field-permissions
 *   · /projects/:projectKey/settings/project-lead
 *   · /settings/sessions · /settings/password · /settings/account-links
 * requireAuth 라우트: /dashboard · /issues · /issues/* · /admin/* · /projects/*\/settings/* · /settings/*
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
  // identity-access BC — 사용자 생성 (/admin/users/new)
  adminUsersNewRoute,
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
  // project-workflow BC — 필드 권한 규칙 관리
  projectFieldPermissionsSettingsRoute,
  // issue-tracking BC — 프로젝트 리드 설정 (project 서브도메인, 권한만 MANAGE_COMPONENTS 재사용)
  projectLeadSettingsRoute,
  // identity-access BC — 내 활성 세션 관리
  settingsSessionsRoute,
  // identity-access BC — 비밀번호 변경
  settingsPasswordRoute,
  // identity-access BC — 계정 연결 관리 (FR-AU-08/08b)
  settingsAccountLinksRoute,
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
