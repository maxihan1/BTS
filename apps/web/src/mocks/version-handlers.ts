// 버전 BC MSW 핸들러 — stateful CRUD + 상태 전이 + RFC 7807 ProblemDetail 에러 (FR-VR-01, FR-VR-02)
import { http, HttpResponse } from 'msw'
import type { Version, VersionStatus } from '../api/versions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입 — 프로젝트별 버전 Map
// ─────────────────────────────────────────────────────────────────────────────

interface StoredVersion extends Version {
  projectIdOrKey: string
  deleted: boolean
  // status/releasedAt은 Version에서 상속됨 (FR-VR-02 추가)
}

// ─────────────────────────────────────────────────────────────────────────────
// 전이 그래프 — backend 전이 규칙과 1:1 미러 (FR-VR-02)
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 상태에서 전이 가능한 상태 집합 */
const ALLOWED_TRANSITIONS: Record<VersionStatus, ReadonlySet<VersionStatus>> = {
  UNRELEASED: new Set<VersionStatus>(['RELEASED', 'ARCHIVED']),
  RELEASED: new Set<VersionStatus>(['UNRELEASED', 'ARCHIVED']),
  ARCHIVED: new Set<VersionStatus>(['UNRELEASED']),
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (component-handlers.ts 동형)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * 3번째 그룹 첫 글자 '4', 4번째 그룹 첫 글자 '8'|'9'|'a'|'b' 보증.
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
// 모듈 상태 — resetVersionStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let versionStore: Map<string, StoredVersion> = new Map()

/** 저장소를 빈 상태로 초기화한다 (테스트 격리용) */
export function resetVersionStore(): void {
  versionStore = new Map()
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태
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

function versionNotFound(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'version-not-found',
    'Version Not Found',
    'VERSION_NOT_FOUND',
    `버전을 찾을 수 없습니다: ${id}`,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 목록을 name 오름차순으로 정렬해 반환하는 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * StoredVersion을 Version 응답 형태로 변환한다.
 * releasedAt은 @JsonInclude(NON_NULL) 정책 미러 — null/undefined이면 키 자체 제외.
 */
function toVersion(stored: StoredVersion): Version {
  const base: Version = {
    id: stored.id,
    projectId: stored.projectId,
    name: stored.name,
    description: stored.description,
    startDate: stored.startDate,
    releaseDate: stored.releaseDate,
    status: stored.status,
  }
  // releasedAt이 null/undefined이면 키 자체 없음 (@JsonInclude(NON_NULL) 미러)
  if (stored.releasedAt != null) {
    return { ...base, releasedAt: stored.releasedAt }
  }
  return base
}

function sortedByName(versions: StoredVersion[]): Version[] {
  return [...versions]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(toVersion)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/versions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성 버전 목록 조회 — name 오름차순 정렬.
 * C2: 전역 GET 목록은 항상 200 반환 (PROJECT_NOT_FOUND 자체발행 금지).
 * X-MSW-Reset-Versions: true 헤더 포함 시 저장소를 초기화한 뒤 목록을 반환한다 (E2E 격리용).
 * 성공 → 200 { data: Version[] }
 */
const listVersionsHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/versions',
  ({ request, params }) => {
    if (request.headers.get('X-MSW-Reset-Versions') === 'true') {
      resetVersionStore()
    }
    const projectIdOrKey = params['projectIdOrKey'] as string
    const items = Array.from(versionStore.values()).filter(
      (v) => v.projectIdOrKey === projectIdOrKey && !v.deleted,
    )
    return HttpResponse.json({ data: sortedByName(items) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectIdOrKey/versions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 생성.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 같은 프로젝트 내 활성 버전 이름 중복 → 409 VERSION_NAME_DUPLICATE
 * 성공 → 201 { data: Version }
 */
const createVersionHandler = http.post(
  '/api/v1/projects/:projectIdOrKey/versions',
  async ({ request, params }) => {
    const projectIdOrKey = params['projectIdOrKey'] as string
    const body = (await request.json()) as {
      name: string
      description?: string
      startDate?: string
      releaseDate?: string
    }

    // 같은 프로젝트 내 활성 버전 이름 중복 확인 (삭제된 버전은 제외)
    const duplicate = Array.from(versionStore.values()).find(
      (v) => v.projectIdOrKey === projectIdOrKey && v.name === body.name && !v.deleted,
    )
    if (duplicate !== undefined) {
      return problemDetail(
        409,
        'version-name-duplicate',
        'Version Name Duplicate',
        'VERSION_NAME_DUPLICATE',
        `같은 프로젝트에 동일한 이름의 활성 버전이 이미 존재합니다: ${body.name}`,
      )
    }

    const newVersion: StoredVersion = {
      id: generateUuidV4(),
      projectId: generateUuidV4(),
      name: body.name,
      description: body.description ?? null,
      startDate: body.startDate ?? null,
      releaseDate: body.releaseDate ?? null,
      status: 'UNRELEASED',
      releasedAt: undefined,
      projectIdOrKey,
      deleted: false,
    }

    versionStore.set(newVersion.id, newVersion)

    return HttpResponse.json({ data: toVersion(newVersion) }, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/versions/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 단건 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 버전 미존재 또는 삭제됨 → 404 VERSION_NOT_FOUND
 * 성공 → 200 { data: Version }
 */
const getVersionHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/versions/:id',
  ({ params }) => {
    const id = params['id'] as string
    const stored = versionStore.get(id)
    if (stored === undefined || stored.deleted) {
      return versionNotFound(id)
    }
    return HttpResponse.json({ data: toVersion(stored) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/versions/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 name/description 수정.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 버전 미존재 또는 삭제됨 → 404 VERSION_NOT_FOUND
 * 성공 → 200 { data: Version }
 */
const updateVersionHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/versions/:id',
  async ({ request, params }) => {
    const id = params['id'] as string
    const stored = versionStore.get(id)
    if (stored === undefined || stored.deleted) {
      return versionNotFound(id)
    }

    const body = (await request.json()) as { name?: string; description?: string }
    const updated: StoredVersion = {
      ...stored,
      name: body.name ?? stored.name,
      description: body.description !== undefined ? body.description : stored.description,
    }
    versionStore.set(id, updated)

    return HttpResponse.json({ data: toVersion(updated) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/versions/:id/dates
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 startDate/releaseDate 변경. null 전달 시 날짜 해제.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 버전 미존재 또는 삭제됨 → 404 VERSION_NOT_FOUND
 * 성공 → 200 { data: Version }
 */
const changeDatesHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/versions/:id/dates',
  async ({ request, params }) => {
    const id = params['id'] as string
    const stored = versionStore.get(id)
    if (stored === undefined || stored.deleted) {
      return versionNotFound(id)
    }

    const body = (await request.json()) as { startDate: string | null; releaseDate: string | null }
    const updated: StoredVersion = {
      ...stored,
      startDate: body.startDate,
      releaseDate: body.releaseDate,
    }
    versionStore.set(id, updated)

    return HttpResponse.json({ data: toVersion(updated) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectIdOrKey/versions/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 소프트 삭제 (deleted 플래그 설정).
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 버전 미존재 또는 이미 삭제됨 → 404 VERSION_NOT_FOUND
 * 성공 → 204 No Content
 */
const deleteVersionHandler = http.delete(
  '/api/v1/projects/:projectIdOrKey/versions/:id',
  ({ params }) => {
    const id = params['id'] as string
    const stored = versionStore.get(id)
    if (stored === undefined || stored.deleted) {
      return versionNotFound(id)
    }
    versionStore.set(id, { ...stored, deleted: true })
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/versions/:id/status — FR-VR-02
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 상태 전이.
 * 전이 그래프(ALLOWED_TRANSITIONS)와 self-transition을 검증하고,
 * released_at 규칙(backend FR-3 미러)을 적용한다.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 버전 미존재 또는 삭제됨 → 404 VERSION_NOT_FOUND
 * 2. self-transition 또는 그래프 외 전이 → 409 VERSION_TRANSITION_NOT_ALLOWED
 * 성공 → 200 { data: Version }
 */
const changeStatusHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/versions/:id/status',
  async ({ request, params }) => {
    const id = params['id'] as string
    const stored = versionStore.get(id)
    if (stored === undefined || stored.deleted) {
      return versionNotFound(id)
    }

    const body = (await request.json()) as { status: VersionStatus }
    const targetStatus: VersionStatus = body.status
    const currentStatus = stored.status

    // self-transition 또는 그래프 외 전이 거부
    if (!ALLOWED_TRANSITIONS[currentStatus].has(targetStatus)) {
      return problemDetail(
        409,
        'version-transition-not-allowed',
        'Version Transition Not Allowed',
        'VERSION_TRANSITION_NOT_ALLOWED',
        `${currentStatus} → ${targetStatus} 전이는 허용되지 않습니다.`,
      )
    }

    // releasedAt 규칙 (backend FR-3 미러)
    // null을 undefined로 정규화 (StoredVersion은 undefined 사용)
    let releasedAt: string | undefined = stored.releasedAt ?? undefined
    if (targetStatus === 'RELEASED') {
      releasedAt = new Date().toISOString()
    } else if (targetStatus === 'UNRELEASED') {
      releasedAt = undefined
    }
    // ARCHIVED: releasedAt 직전값 유지 (그대로 둠)

    const updated: StoredVersion = {
      ...stored,
      status: targetStatus,
      releasedAt,
    }
    versionStore.set(id, updated)

    return HttpResponse.json({ data: toVersion(updated) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 BC MSW 핸들러 배열 */
export const versionHandlers = [
  listVersionsHandler,
  createVersionHandler,
  getVersionHandler,
  updateVersionHandler,
  changeDatesHandler,
  changeStatusHandler,
  deleteVersionHandler,
]
