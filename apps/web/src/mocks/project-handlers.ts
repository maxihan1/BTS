// 프로젝트 CRUD BC MSW 핸들러 — stateful 목록/단건/생성/이름변경/아카이브(해제) + RFC 7807 ProblemDetail 에러 (FR-PJ PR-5 Task 3)
import { http, HttpResponse } from 'msw'
import type { Project, ProjectArchiveResult } from '../api/projects'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키 (FR-UX-07 S5/E2 — e2e-msw-scenario-toggle-localstorage-flag 관례)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 'true'면 `GET /api/v1/projects`가 빈 목록을 반환한다.
 * FR-UX-07 S5(접근 가능한 프로젝트 0개) 시나리오 재현용. 미설정 시 기존 시드(ATLAS·MIDDLE·ZETA)에
 * 전혀 영향을 주지 않는다(무회귀).
 */
export const LS_KEY_PROJECT_LIST_EMPTY = '__bts_e2e_project_list_empty'

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 'true'면 `GET /api/v1/projects`가 500을 반환한다.
 * FR-UX-07 E2(프로젝트 목록 조회 실패) 시나리오 재현용. 콜드 에러(캐시 없음)를 재현하려면
 * `page.addInitScript`로 최초 진입 전부터 심어야 한다 — Sidebar의 `ProjectTree`도 같은
 * queryKey(`['projects', false]`)를 공유해 먼저 실패를 캐싱한다.
 */
export const LS_KEY_PROJECT_LIST_ERROR = '__bts_e2e_project_list_error'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입
// ─────────────────────────────────────────────────────────────────────────────

interface StoredProject {
  id: string
  key: string
  name: string
  archivedAt: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (component-handlers.ts 동형 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 */
function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — resetProjectStore()로 테스트 격리 (component-handlers.ts 동형 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 시드 프로젝트 4건 — ATLAS·MIDDLE·ZETA(활성, 트리 다중활성 시나리오) · NOVA(아카이브됨, unarchive
 * 흐름 테스트용). ATLAS/MIDDLE/ZETA의 id·key·name은 구 `project-list-handlers.ts` fixture와 동일하게
 * 유지한다 — 이 파일이 `GET /api/v1/projects`의 유일 정본 핸들러로 통합되며, 그 파일은 이 시드를
 * 재노출하는 shim이 됐다(project MSW 핸들러 단일 정본 통합, project-tree e2e shadow 회귀 수정).
 */
const SEED_PROJECTS: StoredProject[] = [
  { id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890', key: 'ATLAS', name: 'Atlas 프로젝트', archivedAt: null },
  { id: 'c3d4e5f6-a7b8-4012-9def-123456789012', key: 'MIDDLE', name: 'Middle 프로젝트', archivedAt: null },
  { id: 'b2c3d4e5-f6a7-4901-bcde-f12345678901', key: 'ZETA', name: 'Zeta 프로젝트', archivedAt: null },
  {
    id: 'd4e5f6a7-b8c9-4123-9012-345678901234',
    key: 'NOVA',
    name: 'Nova 프로젝트',
    archivedAt: '2026-01-01T00:00:00Z',
  },
]

function cloneSeed(): Map<string, StoredProject> {
  return new Map(SEED_PROJECTS.map((p) => [p.id, { ...p }]))
}

let projectStore: Map<string, StoredProject> = cloneSeed()

/** 저장소를 시드 상태로 초기화한다 (테스트 격리용) */
export function resetProjectStore(): void {
  projectStore = cloneSeed()
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태 (backend 실 errorCode 문자열, 컨트롤러 grep 근거)
// `message` 필드 절대 금지 — 백엔드는 `detail` 필드를 사용한다
// ─────────────────────────────────────────────────────────────────────────────

interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  errorCode: string
  timestamp: string
}

function problemDetail(
  status: number,
  type: string,
  title: string,
  errorCode: string,
  detail: string,
): HttpResponse<ProblemDetail> {
  return HttpResponse.json<ProblemDetail>(
    {
      type: `https://bts.example.com/problems/${type}`,
      title,
      status,
      detail,
      errorCode,
      timestamp: new Date().toISOString(),
    },
    { status },
  )
}

function projectNotFound(idOrKey: string): HttpResponse<ProblemDetail> {
  return problemDetail(404, 'project-not-found', 'Project Not Found', 'ISSUE_PROJECT_NOT_FOUND', `프로젝트를 찾을 수 없습니다: ${idOrKey}`)
}

function projectSettingsNotFound(idOrKey: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'project-settings-not-found',
    'Project Not Found',
    'ISSUE_PROJECT_SETTINGS_NOT_FOUND',
    `프로젝트를 찾을 수 없습니다: ${idOrKey}`,
  )
}

function projectArchiveNotFound(idOrKey: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'project-archive-not-found',
    'Project Not Found',
    'ISSUE_PROJECT_ARCHIVE_NOT_FOUND',
    `프로젝트를 찾을 수 없습니다: ${idOrKey}`,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 조회/변환 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** id 또는 key로 저장된 프로젝트를 찾는다 */
function findStoredProject(idOrKey: string): StoredProject | undefined {
  return Array.from(projectStore.values()).find((p) => p.id === idOrKey || p.key === idOrKey)
}

function toProject(stored: StoredProject): Project {
  return {
    id: stored.id,
    key: stored.key,
    name: stored.name,
    archived: stored.archivedAt !== null,
  }
}

function toArchiveResult(stored: StoredProject): ProjectArchiveResult {
  return {
    projectId: stored.id,
    projectKey: stored.key,
    archivedAt: stored.archivedAt,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 목록 조회 — `archived` 쿼리 파라미터로 필터링, name 오름차순 정렬.
 * 생략 시 archived=false(활성만), `?archived=true`면 아카이브만 반환한다(백엔드 D8 지라 관례 재현).
 *
 * `GET /api/v1/projects`의 유일 정본 핸들러 — `mocks/project-list-handlers.ts`가 이 핸들러를
 * 재노출하는 shim이므로 export한다(project MSW 핸들러 단일 정본 통합).
 */
export const listProjectsHandler = http.get('/api/v1/projects', ({ request }) => {
  // FR-UX-07 E2 — 목록 조회 실패 시나리오. 저장소 상태와 무관하게 즉시 500을 반환한다.
  if (globalThis.localStorage?.getItem(LS_KEY_PROJECT_LIST_ERROR) === 'true') {
    return HttpResponse.json(
      { message: '프로젝트 목록을 불러오는 중 오류가 발생했습니다.' },
      { status: 500 },
    )
  }
  // FR-UX-07 S5 — 접근 가능한 프로젝트 0개 시나리오. archived 필터와 무관하게 빈 배열을 반환한다.
  if (globalThis.localStorage?.getItem(LS_KEY_PROJECT_LIST_EMPTY) === 'true') {
    return HttpResponse.json({ data: [] })
  }
  const url = new URL(request.url)
  const archived = url.searchParams.get('archived') === 'true'
  const items = Array.from(projectStore.values())
    .filter((p) => (p.archivedAt !== null) === archived)
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(toProject)
  return HttpResponse.json({ data: items })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 생성.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. key 중복 → 409 ISSUE_PROJECT_KEY_ALREADY_EXISTS
 * 성공 → 201 { data: Project } (archived:false)
 */
const createProjectHandler = http.post('/api/v1/projects', async ({ request }) => {
  const body = (await request.json()) as { key: string; name: string }

  const duplicate = findStoredProject(body.key)
  if (duplicate !== undefined) {
    return problemDetail(
      409,
      'project-key-already-exists',
      'Project Key Already Exists',
      'ISSUE_PROJECT_KEY_ALREADY_EXISTS',
      `이미 사용 중인 key입니다: ${body.key}`,
    )
  }

  const created: StoredProject = {
    id: generateUuidV4(),
    key: body.key,
    name: body.name,
    archivedAt: null,
  }
  projectStore.set(created.id, created)

  return HttpResponse.json({ data: toProject(created) }, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:idOrKey
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 단건 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 프로젝트 미존재 → 404 ISSUE_PROJECT_NOT_FOUND
 * 성공 → 200 { data: Project }
 */
const getProjectHandler = http.get('/api/v1/projects/:idOrKey', ({ params }) => {
  const idOrKey = params['idOrKey'] as string
  const stored = findStoredProject(idOrKey)
  if (stored === undefined) {
    return projectNotFound(idOrKey)
  }
  return HttpResponse.json({ data: toProject(stored) })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:idOrKey
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 이름 변경.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 프로젝트 미존재 → 404 ISSUE_PROJECT_SETTINGS_NOT_FOUND
 * 성공 → 204 No Content (바디 없음)
 */
const updateProjectNameHandler = http.patch('/api/v1/projects/:idOrKey', async ({ request, params }) => {
  const idOrKey = params['idOrKey'] as string
  const stored = findStoredProject(idOrKey)
  if (stored === undefined) {
    return projectSettingsNotFound(idOrKey)
  }

  const body = (await request.json()) as { name: string }
  projectStore.set(stored.id, { ...stored, name: body.name })

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:idOrKey/archive
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 아카이브 — EC-2(멱등), 이미 아카이브된 프로젝트를 다시 아카이브해도 409가 아니라 200이다.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 프로젝트 미존재 → 404 ISSUE_PROJECT_ARCHIVE_NOT_FOUND
 * 성공 → 200 { data: ProjectArchiveResult } (archivedAt non-null)
 */
const archiveProjectHandler = http.post('/api/v1/projects/:idOrKey/archive', ({ params }) => {
  const idOrKey = params['idOrKey'] as string
  const stored = findStoredProject(idOrKey)
  if (stored === undefined) {
    return projectArchiveNotFound(idOrKey)
  }

  const updated: StoredProject = { ...stored, archivedAt: stored.archivedAt ?? new Date().toISOString() }
  projectStore.set(stored.id, updated)

  return HttpResponse.json({ data: toArchiveResult(updated) })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:idOrKey/unarchive
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 아카이브 해제 — EC-2(멱등), 이미 활성 상태인 프로젝트도 다시 해제해도 200이다.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 프로젝트 미존재 → 404 ISSUE_PROJECT_ARCHIVE_NOT_FOUND
 * 성공 → 200 { data: ProjectArchiveResult } (archivedAt null)
 */
const unarchiveProjectHandler = http.post('/api/v1/projects/:idOrKey/unarchive', ({ params }) => {
  const idOrKey = params['idOrKey'] as string
  const stored = findStoredProject(idOrKey)
  if (stored === undefined) {
    return projectArchiveNotFound(idOrKey)
  }

  const updated: StoredProject = { ...stored, archivedAt: null }
  projectStore.set(stored.id, updated)

  return HttpResponse.json({ data: toArchiveResult(updated) })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 CRUD BC MSW 핸들러 배열 */
export const projectHandlers = [
  listProjectsHandler,
  createProjectHandler,
  getProjectHandler,
  updateProjectNameHandler,
  archiveProjectHandler,
  unarchiveProjectHandler,
]
