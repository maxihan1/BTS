// 필드 권한 규칙 BC MSW 핸들러 — stateful CRUD + RFC 7807 ProblemDetail + 시나리오 토글 (FR-PM-07)
import { http, HttpResponse } from 'msw'
import type {
  FieldPermissionResponse,
  CreateFieldPermissionInput,
} from '../api/field-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입
// ─────────────────────────────────────────────────────────────────────────────

interface StoredFieldPermission extends FieldPermissionResponse {
  /** 프로젝트 키 — 저장소 격리 키 */
  projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/** 403 강제 플래그 키 — 'true' 세팅 시 모든 write 요청이 403을 반환한다 */
const LS_KEY_FIELD_PERMISSION_403 = 'msw-field-permission-403'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

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
// 모듈 상태 — resetFieldPermissionStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let fieldPermissionStore: Map<string, StoredFieldPermission> = new Map()

/** 저장소를 빈 상태로 초기화한다 (테스트 격리용) */
export function resetFieldPermissionStore(): void {
  fieldPermissionStore = new Map()
}

/**
 * 필드 권한 저장소에서 projectKey에 해당하는 규칙을 반환한다.
 * E2E 파생 동작에서 이슈 restrictedFields/noneditableFields 계산 시 사용하는 공유 store 읽기 헬퍼.
 *
 * @param projectKey 프로젝트 키
 * @returns 활성 FieldPermissionResponse 배열
 */
export function getFieldPermissionsForProject(projectKey: string): FieldPermissionResponse[] {
  return Array.from(fieldPermissionStore.values())
    .filter((fp) => fp.projectKey === projectKey)
    .map(toFieldPermission)
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

function fieldPermissionNotFound(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'field-permission-not-found',
    'Field Permission Not Found',
    'FIELD_PERMISSION_NOT_FOUND',
    `필드 권한 규칙을 찾을 수 없습니다: ${id}`,
  )
}

function accessDenied(): HttpResponse<ProblemDetail> {
  return problemDetail(
    403,
    'field-permission-access-denied',
    'Field Permission Access Denied',
    'FIELD_PERMISSION_ACCESS_DENIED',
    '필드 권한 규칙을 관리할 권한이 없습니다.',
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// StoredFieldPermission → FieldPermissionResponse 변환 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function toFieldPermission(stored: StoredFieldPermission): FieldPermissionResponse {
  return {
    id: stored.id,
    fieldKind: stored.fieldKind,
    fieldKey: stored.fieldKey,
    groupId: stored.groupId,
    groupName: stored.groupName,
    accessLevel: stored.accessLevel,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/field-permissions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 필드 권한 규칙 목록 조회.
 * 성공 → 200 { data: FieldPermissionResponse[] }
 *
 * E2E 시나리오 토글 — X-MSW-Seed-FieldPermissions 헤더(encodeURIComponent JSON 배열)가 있으면
 * 해당 프로젝트의 fieldPermissionStore를 시드 데이터로 초기화한다.
 */
const listFieldPermissionsHandler = http.get(
  '/api/v1/projects/:projectKey/field-permissions',
  ({ params, request }) => {
    const projectKey = params['projectKey'] as string

    // E2E seed 헤더 처리 — X-MSW-Seed-FieldPermissions: encodeURIComponent(JSON 배열)
    const seedHeader = request.headers.get('X-MSW-Seed-FieldPermissions')
    if (seedHeader !== null) {
      try {
        const seeds = JSON.parse(decodeURIComponent(seedHeader)) as Array<{
          id: string
          fieldKind: string
          fieldKey: string
          groupId: string
          groupName: string
          accessLevel: string
        }>
        // 기존 프로젝트 데이터 제거 후 seed 데이터로 초기화
        for (const [storeKey, fp] of fieldPermissionStore.entries()) {
          if (fp.projectKey === projectKey) {
            fieldPermissionStore.delete(storeKey)
          }
        }
        for (const seed of seeds) {
          const stored: StoredFieldPermission = {
            id: seed.id,
            projectKey,
            fieldKind: seed.fieldKind as FieldPermissionResponse['fieldKind'],
            fieldKey: seed.fieldKey,
            groupId: seed.groupId,
            groupName: seed.groupName,
            accessLevel: seed.accessLevel as FieldPermissionResponse['accessLevel'],
          }
          fieldPermissionStore.set(stored.id, stored)
        }
      } catch {
        // seed 파싱 실패 시 무시하고 기존 데이터 반환
        console.error('[MSW] X-MSW-Seed-FieldPermissions 파싱 실패 — 기존 데이터 유지')
      }
    }

    const items = Array.from(fieldPermissionStore.values())
      .filter((fp) => fp.projectKey === projectKey)
      .map(toFieldPermission)

    return HttpResponse.json({ data: items })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectKey/field-permissions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 권한 규칙 생성.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 0. localStorage 플래그(msw-field-permission-403) → 403 FIELD_PERMISSION_ACCESS_DENIED (E2E 권한 토글)
 * 1. 같은 프로젝트 내 동일 fieldKind+fieldKey+groupId+accessLevel 중복 → 409 FIELD_PERMISSION_DUPLICATE
 * 성공 → 201 { data: FieldPermissionResponse }
 *
 * 주의: createFieldPermission API 클라이언트는 응답에서 data 래퍼 없이 직접 parse한다.
 * 따라서 201 응답 body는 FieldPermissionResponse를 직접 반환한다 (래퍼 없음).
 */
const createFieldPermissionHandler = http.post(
  '/api/v1/projects/:projectKey/field-permissions',
  async ({ request, params }) => {
    // (0) 권한 토글 — localStorage 플래그
    const accessFlag = globalThis.localStorage?.getItem(LS_KEY_FIELD_PERMISSION_403)
    if (accessFlag === 'true') {
      return accessDenied()
    }

    const projectKey = params['projectKey'] as string
    const body = (await request.json()) as CreateFieldPermissionInput

    // CSRF 헤더 확인 (존재 여부만 검증 — 실제 값 검증은 백엔드 몫)
    const csrfToken = request.headers.get('X-XSRF-TOKEN')
    if (!csrfToken) {
      return problemDetail(
        403,
        'csrf-token-missing',
        'CSRF Token Missing',
        'CSRF_TOKEN_MISSING',
        'X-XSRF-TOKEN 헤더가 누락되었습니다.',
      )
    }

    // (1) 중복 확인 — 동일 fieldKind+fieldKey+groupId+accessLevel 조합
    const duplicate = Array.from(fieldPermissionStore.values()).find(
      (fp) =>
        fp.projectKey === projectKey &&
        fp.fieldKind === body.fieldKind &&
        fp.fieldKey === body.fieldKey &&
        fp.groupId === body.groupId &&
        fp.accessLevel === body.accessLevel,
    )
    if (duplicate !== undefined) {
      return problemDetail(
        409,
        'field-permission-duplicate',
        'Field Permission Duplicate',
        'FIELD_PERMISSION_DUPLICATE',
        `동일한 필드 권한 규칙이 이미 존재합니다: ${body.fieldKind}/${body.fieldKey}`,
      )
    }

    // groupName은 시드된 그룹에서 찾거나 groupId를 fallback으로 사용
    const groupName = body.groupId

    const newFp: StoredFieldPermission = {
      id: generateUuidV4(),
      projectKey,
      fieldKind: body.fieldKind,
      fieldKey: body.fieldKey,
      groupId: body.groupId,
      groupName,
      accessLevel: body.accessLevel,
    }
    fieldPermissionStore.set(newFp.id, newFp)

    // API 클라이언트 createFieldPermission 는 data 래퍼 없이 직접 parse
    return HttpResponse.json(toFieldPermission(newFp), { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectKey/field-permissions/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 권한 규칙 삭제.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 0. localStorage 플래그(msw-field-permission-403) → 403 FIELD_PERMISSION_ACCESS_DENIED (E2E 권한 토글)
 * 1. 미존재 → 404 FIELD_PERMISSION_NOT_FOUND
 * 성공 → 204 No Content
 */
const deleteFieldPermissionHandler = http.delete(
  '/api/v1/projects/:projectKey/field-permissions/:id',
  ({ params, request }) => {
    // (0) 권한 토글 — localStorage 플래그
    const accessFlag = globalThis.localStorage?.getItem(LS_KEY_FIELD_PERMISSION_403)
    if (accessFlag === 'true') {
      return accessDenied()
    }

    // CSRF 헤더 확인
    const csrfToken = request.headers.get('X-XSRF-TOKEN')
    if (!csrfToken) {
      return problemDetail(
        403,
        'csrf-token-missing',
        'CSRF Token Missing',
        'CSRF_TOKEN_MISSING',
        'X-XSRF-TOKEN 헤더가 누락되었습니다.',
      )
    }

    const id = params['id'] as string
    const stored = fieldPermissionStore.get(id)
    if (stored === undefined) {
      return fieldPermissionNotFound(id)
    }
    fieldPermissionStore.delete(id)
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 필드 권한 규칙 BC MSW 핸들러 배열 */
export const fieldPermissionHandlers = [
  listFieldPermissionsHandler,
  createFieldPermissionHandler,
  deleteFieldPermissionHandler,
]
