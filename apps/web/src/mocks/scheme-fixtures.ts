// 워크플로우 스킴 MSW fixture 데이터 — 4 표준 스킴 + 2 커스텀 스킴 + 매핑 + 프로젝트 할당

/** 스킴 단건 응답 형태 */
export interface SchemeSummaryResponse {
  schemeKey: string
  name: string
  description: string
  isStandard: boolean
  usedByProjectsCount: number
  mappingsCount: number
}

/** 매핑 응답 형태 */
export interface SchemeMappingResponse {
  id: number
  issueTypeKey: string | null
  issueTypeName: string | null
  workflowKey: string
  workflowName: string
  isDefault: boolean
}

/** 스킴 상세 응답 형태 (mappings 포함) */
export interface SchemeDetailResponse extends SchemeSummaryResponse {
  mappings: SchemeMappingResponse[]
}

/** 프로젝트-스킴 할당 응답 형태 */
export interface AssignmentResponse {
  projectKey: string
  schemeKey: string
  schemeName: string
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixture helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스킴 요약 fixture를 생성하는 helper.
 * usedByProjectsCount와 mappingsCount는 데이터에서 자동 계산하거나 override로 지정한다.
 */
export const makeScheme = (overrides: Partial<SchemeSummaryResponse>): SchemeSummaryResponse => ({
  schemeKey: 'default-scheme-key',
  name: '기본 스킴 이름',
  description: '',
  isStandard: false,
  usedByProjectsCount: 0,
  mappingsCount: 0,
  ...overrides,
})

/**
 * 스킴 상세 fixture를 생성하는 helper.
 * mappingsCount는 mappings 배열 길이에서 자동 계산하여 drift를 방지한다.
 */
export const makeSchemeDetail = (
  base: Omit<SchemeSummaryResponse, 'mappingsCount'>,
  mappings: SchemeMappingResponse[],
): SchemeDetailResponse => ({
  ...base,
  mappingsCount: mappings.length,
  mappings,
})

/**
 * 매핑 fixture를 생성하는 helper.
 * isDefault=true 이면 issueTypeKey/issueTypeName이 null인 기본 매핑이다.
 */
export const makeMapping = (overrides: Partial<SchemeMappingResponse>): SchemeMappingResponse => ({
  id: 0,
  issueTypeKey: null,
  issueTypeName: null,
  workflowKey: 'software-default',
  workflowName: '소프트웨어 개발 기본 워크플로우',
  isDefault: true,
  ...overrides,
})

// ─────────────────────────────────────────────────────────────────────────────
// 표준 스킴 매핑 (각 스킴마다 이슈 타입별 매핑 + 기본 매핑 1건)
// ─────────────────────────────────────────────────────────────────────────────

/** 소프트웨어 기본 스킴 매핑 목록 */
const softwareDefaultMappings: SchemeMappingResponse[] = [
  makeMapping({ id: 10, issueTypeKey: 'bug', issueTypeName: '버그', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 11, issueTypeKey: 'story', issueTypeName: '스토리', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 12, issueTypeKey: 'task', issueTypeName: '작업', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 13, issueTypeKey: 'epic', issueTypeName: '에픽', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 14, issueTypeKey: null, issueTypeName: null, workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: true }),
]

/** 서비스 관리 스킴 매핑 목록 */
const serviceManagementMappings: SchemeMappingResponse[] = [
  makeMapping({ id: 20, issueTypeKey: 'bug', issueTypeName: '버그', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 21, issueTypeKey: 'task', issueTypeName: '작업', workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: false }),
  makeMapping({ id: 22, issueTypeKey: null, issueTypeName: null, workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: true }),
]

/** 비즈니스 프로젝트 스킴 매핑 목록 */
const businessProjectMappings: SchemeMappingResponse[] = [
  makeMapping({ id: 30, issueTypeKey: 'story', issueTypeName: '스토리', workflowKey: 'kanban-basic', workflowName: '칸반 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 31, issueTypeKey: 'epic', issueTypeName: '에픽', workflowKey: 'kanban-basic', workflowName: '칸반 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 32, issueTypeKey: null, issueTypeName: null, workflowKey: 'kanban-basic', workflowName: '칸반 기본 워크플로우', isDefault: true }),
]

/** IT 서비스 관리 스킴 매핑 목록 */
const itServiceManagementMappings: SchemeMappingResponse[] = [
  makeMapping({ id: 40, issueTypeKey: 'bug', issueTypeName: '버그', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 41, issueTypeKey: 'task', issueTypeName: '작업', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 42, issueTypeKey: 'story', issueTypeName: '스토리', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 43, issueTypeKey: 'epic', issueTypeName: '에픽', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 44, issueTypeKey: null, issueTypeName: null, workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: true }),
]

// ─────────────────────────────────────────────────────────────────────────────
// 커스텀 스킴 매핑
// ─────────────────────────────────────────────────────────────────────────────

/** Alpha 커스텀 스킴 매핑 — usedByProjectsCount > 0 이라 삭제 불가 */
const customAlphaMappings: SchemeMappingResponse[] = [
  makeMapping({ id: 50, issueTypeKey: 'bug', issueTypeName: '버그', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 51, issueTypeKey: null, issueTypeName: null, workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: true }),
]

/** Beta 커스텀 스킴 매핑 — 미사용 스킴, 삭제 가능 */
const customBetaMappings: SchemeMappingResponse[] = [
  makeMapping({ id: 60, issueTypeKey: null, issueTypeName: null, workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: true }),
]

// ─────────────────────────────────────────────────────────────────────────────
// 4 표준 스킴 (isStandard: true)
// ─────────────────────────────────────────────────────────────────────────────

/** 소프트웨어 개발 기본 스킴 — 5 매핑 (mappingsCount는 mappings.length에서 자동 계산) */
export const softwareDefaultSchemeFixture: SchemeDetailResponse = makeSchemeDetail(
  { schemeKey: 'software-default-scheme', name: '소프트웨어 개발 기본 스킴', description: '소프트웨어 개발 팀을 위한 표준 워크플로우 스킴', isStandard: true, usedByProjectsCount: 3 },
  softwareDefaultMappings,
)

/** 서비스 관리 스킴 — 3 매핑 */
export const serviceManagementSchemeFixture: SchemeDetailResponse = makeSchemeDetail(
  { schemeKey: 'service-management-scheme', name: '서비스 관리 스킴', description: 'IT 서비스 관리 팀을 위한 표준 워크플로우 스킴', isStandard: true, usedByProjectsCount: 1 },
  serviceManagementMappings,
)

/** 비즈니스 프로젝트 스킴 — 3 매핑 */
export const businessProjectSchemeFixture: SchemeDetailResponse = makeSchemeDetail(
  { schemeKey: 'business-project-scheme', name: '비즈니스 프로젝트 스킴', description: '비즈니스 프로젝트 팀을 위한 표준 워크플로우 스킴', isStandard: true, usedByProjectsCount: 2 },
  businessProjectMappings,
)

/** IT 서비스 관리 스킴 — 5 매핑 */
export const itServiceManagementSchemeFixture: SchemeDetailResponse = makeSchemeDetail(
  { schemeKey: 'it-service-management-scheme', name: 'IT 서비스 관리 스킴', description: 'ITSM 프로세스에 특화된 표준 워크플로우 스킴', isStandard: true, usedByProjectsCount: 0 },
  itServiceManagementMappings,
)

// ─────────────────────────────────────────────────────────────────────────────
// 2 커스텀 스킴 (isStandard: false)
// ─────────────────────────────────────────────────────────────────────────────

/** Alpha 커스텀 스킴 — ATLAS 프로젝트에서 사용 중 (삭제 불가) */
export const customSchemeAlphaFixture: SchemeDetailResponse = makeSchemeDetail(
  { schemeKey: 'custom-scheme-alpha', name: '사내 개발팀 커스텀 스킴', description: '내부 개발 팀 전용 커스텀 워크플로우 스킴', isStandard: false, usedByProjectsCount: 2 },
  customAlphaMappings,
)

/** Beta 커스텀 스킴 — 미사용, 삭제 가능 */
export const customSchemeBetaFixture: SchemeDetailResponse = makeSchemeDetail(
  { schemeKey: 'custom-scheme-beta', name: '파일럿 프로젝트 스킴', description: '신규 파일럿 프로젝트용 임시 스킴', isStandard: false, usedByProjectsCount: 0 },
  customBetaMappings,
)

/** 전체 스킴 목록 (상세 포함) */
export const allSchemeFixtures: SchemeDetailResponse[] = [
  softwareDefaultSchemeFixture,
  serviceManagementSchemeFixture,
  businessProjectSchemeFixture,
  itServiceManagementSchemeFixture,
  customSchemeAlphaFixture,
  customSchemeBetaFixture,
]

/** 프로젝트-스킴 할당 fixture 목록 */
export const assignmentFixtures: AssignmentResponse[] = [
  { projectKey: 'ATLAS', schemeKey: 'custom-scheme-alpha', schemeName: '사내 개발팀 커스텀 스킴' },
  { projectKey: 'BTS', schemeKey: 'software-default-scheme', schemeName: '소프트웨어 개발 기본 스킴' },
  { projectKey: 'PILOT', schemeKey: 'business-project-scheme', schemeName: '비즈니스 프로젝트 스킴' },
]
