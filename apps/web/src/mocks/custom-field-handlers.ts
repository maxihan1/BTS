// 커스텀 필드 BC MSW 핸들러 — stateful CRUD + RFC 7807 ProblemDetail + 시나리오 토글 (FR-IS-10)
import { http, HttpResponse } from 'msw'
import type { CustomField, CustomFieldOption } from '../api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입
// ─────────────────────────────────────────────────────────────────────────────

interface StoredCustomField extends CustomField {
  /** 프로젝트 식별자(id 또는 key) — 저장소 격리 키 */
  projectIdOrKey: string
  /** 소프트 삭제 플래그 */
  deleted: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/** 403 강제 플래그 키 — 'true' 세팅 시 모든 write 요청이 403을 반환한다 */
const LS_KEY_CUSTOM_FIELD_403 = 'msw-custom-field-403'

// ─────────────────────────────────────────────────────────────────────────────
// 선택형 fieldType 집합 — options 0개 시 422 검증 대상
// ─────────────────────────────────────────────────────────────────────────────

const OPTION_REQUIRED_TYPES = new Set(['SINGLE_SELECT', 'MULTI_SELECT', 'RADIO'])

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼
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
// 모듈 상태 — resetCustomFieldStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let customFieldStore: Map<string, StoredCustomField> = new Map()

/** 저장소를 빈 상태로 초기화한다 (테스트 격리용) */
export function resetCustomFieldStore(): void {
  customFieldStore = new Map()
}

/**
 * 커스텀 필드 저장소에서 projectIdOrKey에 해당하는 활성 필드를 반환한다.
 * E2E 파생 동작에서 이슈 customFields 검증 시 사용하는 공유 store 읽기 헬퍼.
 *
 * @param projectIdOrKey 프로젝트 식별자
 * @returns 활성 CustomField 배열 — displayOrder 오름차순
 */
export function getActiveCustomFields(projectIdOrKey: string): CustomField[] {
  return Array.from(customFieldStore.values())
    .filter((f) => f.projectIdOrKey === projectIdOrKey && !f.deleted)
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map(toCustomField)
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

function customFieldNotFound(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'custom-field-not-found',
    'Custom Field Not Found',
    'CUSTOM_FIELD_NOT_FOUND',
    `커스텀 필드를 찾을 수 없습니다: ${id}`,
  )
}

function accessDenied(): HttpResponse<ProblemDetail> {
  return problemDetail(
    403,
    'custom-field-access-denied',
    'Custom Field Access Denied',
    'CUSTOM_FIELD_ACCESS_DENIED',
    '커스텀 필드를 관리할 권한이 없습니다.',
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// StoredCustomField → CustomField 변환 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function toCustomField(stored: StoredCustomField): CustomField {
  return {
    id: stored.id,
    projectId: stored.projectId,
    key: stored.key,
    name: stored.name,
    description: stored.description,
    fieldType: stored.fieldType,
    required: stored.required,
    displayOrder: stored.displayOrder,
    options: stored.options,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/custom-fields
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성 커스텀 필드 목록 조회 — displayOrder 오름차순 정렬.
 * 성공 → 200 { data: CustomField[] }
 *
 * E2E 시나리오 토글 — X-MSW-Seed-CustomFields 헤더(encodeURIComponent JSON 배열)가 있으면
 * 해당 프로젝트의 customFieldStore를 시드 데이터로 초기화한다.
 * 브라우저 시드 헤더는 테스트 전용이며 프로덕션 API에는 존재하지 않는다.
 */
const listCustomFieldsHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/custom-fields',
  ({ params, request }) => {
    const projectIdOrKey = params['projectIdOrKey'] as string

    // E2E seed 헤더 처리 — X-MSW-Seed-CustomFields: encodeURIComponent(JSON 배열)
    const seedHeader = request.headers.get('X-MSW-Seed-CustomFields')
    if (seedHeader !== null) {
      try {
        const seeds = JSON.parse(decodeURIComponent(seedHeader)) as Array<{
          id: string
          projectId?: string
          key: string
          name: string
          description?: string | null
          fieldType: string
          required?: boolean
          displayOrder?: number
          options?: CustomFieldOption[]
        }>
        // 기존 프로젝트 데이터 제거 후 seed 데이터로 초기화
        for (const [storeKey, field] of customFieldStore.entries()) {
          if (field.projectIdOrKey === projectIdOrKey) {
            customFieldStore.delete(storeKey)
          }
        }
        let order = 0
        for (const seed of seeds) {
          const stored: StoredCustomField = {
            id: seed.id,
            projectId: seed.projectId ?? generateUuidV4(),
            projectIdOrKey,
            key: seed.key,
            name: seed.name,
            description: seed.description ?? null,
            fieldType: seed.fieldType as CustomField['fieldType'],
            required: seed.required ?? false,
            displayOrder: seed.displayOrder ?? order++,
            options: seed.options ?? [],
            deleted: false,
          }
          customFieldStore.set(stored.id, stored)
        }
      } catch {
        // seed 파싱 실패 시 무시하고 기존 데이터 반환
        console.error('[MSW] X-MSW-Seed-CustomFields 파싱 실패 — 기존 데이터 유지')
      }
    }

    const items = Array.from(customFieldStore.values())
      .filter((f) => f.projectIdOrKey === projectIdOrKey && !f.deleted)
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map(toCustomField)

    return HttpResponse.json({ data: items })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/custom-fields/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 단건 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 미존재 또는 소프트 삭제 → 404 CUSTOM_FIELD_NOT_FOUND
 * 성공 → 200 { data: CustomField }
 */
const getCustomFieldHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/custom-fields/:id',
  ({ params }) => {
    const id = params['id'] as string
    const stored = customFieldStore.get(id)
    if (stored === undefined || stored.deleted) {
      return customFieldNotFound(id)
    }
    return HttpResponse.json({ data: toCustomField(stored) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectIdOrKey/custom-fields
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 생성.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 0. localStorage 플래그(msw-custom-field-403) → 403 CUSTOM_FIELD_ACCESS_DENIED (E2E 권한 토글)
 * 1. 같은 프로젝트 내 key 중복 → 409 CUSTOM_FIELD_KEY_DUPLICATE
 * 2. 선택형 fieldType(SINGLE_SELECT/MULTI_SELECT/RADIO)에 options 0개 → 422 CUSTOM_FIELD_INVALID_DEFINITION
 * 성공 → 201 { data: CustomField }
 */
const createCustomFieldHandler = http.post(
  '/api/v1/projects/:projectIdOrKey/custom-fields',
  async ({ request, params }) => {
    // (0) 권한 토글 — localStorage 플래그
    const accessFlag = globalThis.localStorage?.getItem(LS_KEY_CUSTOM_FIELD_403)
    if (accessFlag === 'true') {
      return accessDenied()
    }

    const projectIdOrKey = params['projectIdOrKey'] as string
    const body = (await request.json()) as {
      key: string
      name: string
      description?: string | null
      fieldType: string
      required?: boolean
      displayOrder?: number
      options?: CustomFieldOption[]
    }

    // (1) key 중복 확인
    const duplicate = Array.from(customFieldStore.values()).find(
      (f) => f.projectIdOrKey === projectIdOrKey && f.key === body.key && !f.deleted,
    )
    if (duplicate !== undefined) {
      return problemDetail(
        409,
        'custom-field-key-duplicate',
        'Custom Field Key Duplicate',
        'CUSTOM_FIELD_KEY_DUPLICATE',
        `같은 프로젝트에 동일한 키의 커스텀 필드가 이미 존재합니다: ${body.key}`,
      )
    }

    // (2) 선택형 fieldType에 options 0개 검증
    const options = body.options ?? []
    if (OPTION_REQUIRED_TYPES.has(body.fieldType) && options.length === 0) {
      return problemDetail(
        422,
        'custom-field-invalid-definition',
        'Custom Field Invalid Definition',
        'CUSTOM_FIELD_INVALID_DEFINITION',
        `선택형 필드(${body.fieldType})는 선택지(options)를 1개 이상 지정해야 합니다.`,
      )
    }

    // displayOrder 기본값: 현재 프로젝트 필드 최대값 + 1
    const existingOrders = Array.from(customFieldStore.values())
      .filter((f) => f.projectIdOrKey === projectIdOrKey && !f.deleted)
      .map((f) => f.displayOrder)
    const defaultOrder =
      existingOrders.length > 0 ? Math.max(...existingOrders) + 1 : 0

    const newField: StoredCustomField = {
      id: generateUuidV4(),
      projectId: generateUuidV4(),
      projectIdOrKey,
      key: body.key,
      name: body.name,
      description: body.description ?? null,
      fieldType: body.fieldType as CustomField['fieldType'],
      required: body.required ?? false,
      displayOrder: body.displayOrder ?? defaultOrder,
      options,
      deleted: false,
    }

    customFieldStore.set(newField.id, newField)

    return HttpResponse.json({ data: toCustomField(newField) }, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/custom-fields/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 수정 — name/description/required/displayOrder/options 변경 가능.
 * fieldType·key 는 불변이므로 변경 시도 시 422.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 미존재 또는 소프트 삭제 → 404 CUSTOM_FIELD_NOT_FOUND
 * 2. fieldType 또는 key 변경 시도 → 422 CUSTOM_FIELD_IMMUTABLE_CHANGE
 * 성공 → 200 { data: CustomField }
 */
const updateCustomFieldHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/custom-fields/:id',
  async ({ request, params }) => {
    const id = params['id'] as string
    const stored = customFieldStore.get(id)
    if (stored === undefined || stored.deleted) {
      return customFieldNotFound(id)
    }

    const body = (await request.json()) as {
      name?: string
      description?: string | null
      required?: boolean
      displayOrder?: number
      options?: CustomFieldOption[]
      // 불변 필드 — 요청에 포함되면 422
      fieldType?: string
      key?: string
    }

    // (2) 불변 필드 변경 시도 검증
    if (body.fieldType !== undefined || body.key !== undefined) {
      return problemDetail(
        422,
        'custom-field-immutable-change',
        'Custom Field Immutable Change',
        'CUSTOM_FIELD_IMMUTABLE_CHANGE',
        'fieldType과 key는 생성 후 변경할 수 없습니다.',
      )
    }

    const updated: StoredCustomField = {
      ...stored,
      name: body.name ?? stored.name,
      description: body.description !== undefined ? body.description : stored.description,
      required: body.required !== undefined ? body.required : stored.required,
      displayOrder: body.displayOrder !== undefined ? body.displayOrder : stored.displayOrder,
      options: body.options !== undefined ? body.options : stored.options,
    }
    customFieldStore.set(id, updated)

    return HttpResponse.json({ data: toCustomField(updated) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectIdOrKey/custom-fields/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 소프트 삭제 (deleted=true 마킹).
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 미존재 또는 이미 소프트 삭제됨 → 404 CUSTOM_FIELD_NOT_FOUND
 * 성공 → 204 No Content
 */
const deleteCustomFieldHandler = http.delete(
  '/api/v1/projects/:projectIdOrKey/custom-fields/:id',
  ({ params }) => {
    const id = params['id'] as string
    const stored = customFieldStore.get(id)
    if (stored === undefined || stored.deleted) {
      return customFieldNotFound(id)
    }
    customFieldStore.set(id, { ...stored, deleted: true })
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 커스텀 필드 BC MSW 핸들러 배열 */
export const customFieldHandlers = [
  listCustomFieldsHandler,
  getCustomFieldHandler,
  createCustomFieldHandler,
  updateCustomFieldHandler,
  deleteCustomFieldHandler,
]
