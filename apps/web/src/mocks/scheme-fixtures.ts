// 워크플로우 스킴 MSW fixture 데이터 — 4 표준 스킴 + 2 커스텀 스킴 + 매핑 + 프로젝트 할당
//
// ★ 타입을 여기서 새로 정의하지 않는다. 자체 interface 를 두면 백엔드가 형태를 바꿔도 목이 옛 형태로
//   계속 초록이라 "목끼리의 일치"가 계약 정합으로 오인된다. 실제로 이 파일이 그 상태였다.
//   Zod 추론 타입을 참조하면 계약 스냅샷(docs/contracts/workflow-schemes.snapshot.json)이
//   바뀌는 순간 여기서 타입 에러가 난다.
import type {
  SchemeListItem,
  SchemeDetail,
  MappingDetail,
  AssignedScheme,
} from '@/api/workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스킴 요약 fixture를 생성하는 helper.
 * usedByProjectsCount와 mappingsCount는 데이터에서 자동 계산하거나 override로 지정한다.
 */
export const makeScheme = (overrides: Partial<SchemeListItem>): SchemeListItem => ({
  id: 1,
  key: 'default-scheme-key',
  name: '기본 스킴 이름',
  description: '',
  isStandard: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  usedByProjectsCount: 0,
  mappingsCount: 0,
  mappings: [],
  ...overrides,
})

/**
 * 스킴 상세 fixture를 생성하는 helper.
 * mappingsCount는 mappings 배열 길이에서 자동 계산하여 drift를 방지한다.
 */
export const makeSchemeDetail = (
  base: Omit<SchemeListItem, 'mappingsCount' | 'mappings'>,
  mappings: MappingDetail[],
): SchemeDetail => ({
  ...base,
  mappingsCount: mappings.length,
  mappings,
})

/**
 * 매핑 fixture를 생성하는 helper.
 * isDefault=true 이면 issueTypeKey/issueTypeName이 null인 기본 매핑이다.
 */
export const makeMapping = (overrides: Partial<MappingDetail>): MappingDetail => ({
  id: 1,
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
const softwareDefaultMappings: MappingDetail[] = [
  makeMapping({ id: 10, issueTypeKey: 'bug', issueTypeName: '버그', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 11, issueTypeKey: 'story', issueTypeName: '스토리', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 12, issueTypeKey: 'task', issueTypeName: '작업', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 13, issueTypeKey: 'epic', issueTypeName: '에픽', workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 14, issueTypeKey: null, issueTypeName: null, workflowKey: 'software-default', workflowName: '소프트웨어 개발 기본 워크플로우', isDefault: true }),
]

/** 서비스 관리 스킴 매핑 목록 */
const serviceManagementMappings: MappingDetail[] = [
  makeMapping({ id: 20, issueTypeKey: 'bug', issueTypeName: '버그', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 21, issueTypeKey: 'task', issueTypeName: '작업', workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: false }),
  makeMapping({ id: 22, issueTypeKey: null, issueTypeName: null, workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: true }),
]

/** 비즈니스 프로젝트 스킴 매핑 목록 */
const businessProjectMappings: MappingDetail[] = [
  makeMapping({ id: 30, issueTypeKey: 'story', issueTypeName: '스토리', workflowKey: 'kanban-basic', workflowName: '칸반 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 31, issueTypeKey: 'epic', issueTypeName: '에픽', workflowKey: 'kanban-basic', workflowName: '칸반 기본 워크플로우', isDefault: false }),
  makeMapping({ id: 32, issueTypeKey: null, issueTypeName: null, workflowKey: 'kanban-basic', workflowName: '칸반 기본 워크플로우', isDefault: true }),
]

/** IT 서비스 관리 스킴 매핑 목록 */
const itServiceManagementMappings: MappingDetail[] = [
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
const customAlphaMappings: MappingDetail[] = [
  makeMapping({ id: 50, issueTypeKey: 'bug', issueTypeName: '버그', workflowKey: 'bug-tracking', workflowName: '버그 추적 워크플로우', isDefault: false }),
  makeMapping({ id: 51, issueTypeKey: null, issueTypeName: null, workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: true }),
]

/** Beta 커스텀 스킴 매핑 — 미사용 스킴, 삭제 가능 */
const customBetaMappings: MappingDetail[] = [
  makeMapping({ id: 60, issueTypeKey: null, issueTypeName: null, workflowKey: 'simple', workflowName: '단순 워크플로우 (TODO/DOING/DONE)', isDefault: true }),
]

// ─────────────────────────────────────────────────────────────────────────────
// 4 표준 스킴 (isStandard: true)
// ─────────────────────────────────────────────────────────────────────────────

/** 소프트웨어 개발 기본 스킴 — 5 매핑 (mappingsCount는 mappings.length에서 자동 계산) */
export const softwareDefaultSchemeFixture: SchemeDetail = makeSchemeDetail(
  { id: 1, key: 'software-default-scheme', name: '소프트웨어 개발 기본 스킴', description: '소프트웨어 개발 팀을 위한 표준 워크플로우 스킴', isStandard: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', usedByProjectsCount: 3 },
  softwareDefaultMappings,
)

/** 서비스 관리 스킴 — 3 매핑 */
export const serviceManagementSchemeFixture: SchemeDetail = makeSchemeDetail(
  { id: 2, key: 'service-management-scheme', name: '서비스 관리 스킴', description: 'IT 서비스 관리 팀을 위한 표준 워크플로우 스킴', isStandard: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', usedByProjectsCount: 1 },
  serviceManagementMappings,
)

/** 비즈니스 프로젝트 스킴 — 3 매핑 */
export const businessProjectSchemeFixture: SchemeDetail = makeSchemeDetail(
  { id: 3, key: 'business-project-scheme', name: '비즈니스 프로젝트 스킴', description: '비즈니스 프로젝트 팀을 위한 표준 워크플로우 스킴', isStandard: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', usedByProjectsCount: 2 },
  businessProjectMappings,
)

/** IT 서비스 관리 스킴 — 5 매핑 */
export const itServiceManagementSchemeFixture: SchemeDetail = makeSchemeDetail(
  { id: 4, key: 'it-service-management-scheme', name: 'IT 서비스 관리 스킴', description: 'ITSM 프로세스에 특화된 표준 워크플로우 스킴', isStandard: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', usedByProjectsCount: 0 },
  itServiceManagementMappings,
)

// ─────────────────────────────────────────────────────────────────────────────
// 2 커스텀 스킴 (isStandard: false)
// ─────────────────────────────────────────────────────────────────────────────

/** Alpha 커스텀 스킴 — ATLAS 프로젝트에서 사용 중 (삭제 불가) */
export const customSchemeAlphaFixture: SchemeDetail = makeSchemeDetail(
  { id: 5, key: 'custom-scheme-alpha', name: '사내 개발팀 커스텀 스킴', description: '내부 개발 팀 전용 커스텀 워크플로우 스킴', isStandard: false, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', usedByProjectsCount: 2 },
  customAlphaMappings,
)

/** Beta 커스텀 스킴 — 미사용, 삭제 가능 */
export const customSchemeBetaFixture: SchemeDetail = makeSchemeDetail(
  { id: 6, key: 'custom-scheme-beta', name: '파일럿 프로젝트 스킴', description: '신규 파일럿 프로젝트용 임시 스킴', isStandard: false, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', usedByProjectsCount: 0 },
  customBetaMappings,
)

/** 전체 스킴 목록 (상세 포함) */
export const allSchemeFixtures: SchemeDetail[] = [
  softwareDefaultSchemeFixture,
  serviceManagementSchemeFixture,
  businessProjectSchemeFixture,
  itServiceManagementSchemeFixture,
  customSchemeAlphaFixture,
  customSchemeBetaFixture,
]

/**
 * 프로젝트-스킴 할당 fixture 목록.
 *
 * `projectKey` 는 목 라우팅용 색인일 뿐 **응답 본문이 아니다** — 백엔드 GET 응답은 스킴 객체
 * (`SchemeResponse`) 하나이고 프로젝트 키는 URL 에만 있다. 예전 픽스처가 projectKey/schemeName 을
 * 본문에 싣고 있어 프론트 Zod 도 그 형태를 믿고 있었다(계약 파손 지점 중 하나).
 */
export const assignmentFixtures: Array<{ projectKey: string; scheme: AssignedScheme }> = [
  {
    projectKey: 'ATLAS',
    scheme: { id: 5, key: 'custom-scheme-alpha', name: '사내 개발팀 커스텀 스킴', description: '내부 개발 팀 전용 커스텀 워크플로우 스킴', isStandard: false },
  },
  {
    projectKey: 'BTS',
    scheme: { id: 1, key: 'software-default-scheme', name: '소프트웨어 개발 기본 스킴', description: '소프트웨어 개발 팀을 위한 표준 워크플로우 스킴', isStandard: true },
  },
  {
    projectKey: 'PILOT',
    scheme: { id: 3, key: 'business-project-scheme', name: '비즈니스 프로젝트 스킴', description: '비즈니스 프로젝트 팀을 위한 표준 워크플로우 스킴', isStandard: true },
  },
]
