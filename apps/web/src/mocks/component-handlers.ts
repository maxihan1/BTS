// 컴포넌트 BC MSW 핸들러 — stateful CRUD + RFC 7807 ProblemDetail 에러 (FR-CM-01)
import { http, HttpResponse } from 'msw'
import type { Component } from '../api/components.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입 — 프로젝트별 컴포넌트 Map
// ─────────────────────────────────────────────────────────────────────────────

interface StoredComponent extends Component {
  projectIdOrKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/** 422 강제 플래그 키 — 'true' 세팅 시 PATCH /lead가 422를 반환한다 */
const LS_KEY_COMPONENT_LEAD_422 = 'msw-component-lead-422'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼
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
// 모듈 상태 — resetComponentStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let componentStore: Map<string, StoredComponent> = new Map()

/** 저장소를 빈 상태로 초기화한다 (테스트 격리용) */
export function resetComponentStore(): void {
  componentStore = new Map()
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

function componentNotFound(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'component-not-found',
    'Component Not Found',
    'COMPONENT_NOT_FOUND',
    `컴포넌트를 찾을 수 없습니다: ${id}`,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 목록을 name 오름차순으로 정렬해 반환하는 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function toComponent(stored: StoredComponent): Component {
  return {
    id: stored.id,
    projectId: stored.projectId,
    name: stored.name,
    description: stored.description,
    leadUserId: stored.leadUserId,
  }
}

function sortedByName(components: StoredComponent[]): Component[] {
  return [...components]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(toComponent)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/components
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성 컴포넌트 목록 조회 — name 오름차순 정렬.
 * 성공 → 200 { data: Component[] }
 */
const listComponentsHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/components',
  ({ params }) => {
    const projectIdOrKey = params['projectIdOrKey'] as string
    const items = Array.from(componentStore.values()).filter(
      (c) => c.projectIdOrKey === projectIdOrKey,
    )
    return HttpResponse.json({ data: sortedByName(items) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectIdOrKey/components
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 생성.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 같은 프로젝트 내 이름 중복 → 409 COMPONENT_NAME_DUPLICATE
 * 2. leadUserId 지정 + localStorage 플래그(msw-component-lead-422) → 422 COMPONENT_LEAD_NOT_FOUND (E2E 토글)
 * 성공 → 201 { data: Component }
 */
const createComponentHandler = http.post(
  '/api/v1/projects/:projectIdOrKey/components',
  async ({ request, params }) => {
    const projectIdOrKey = params['projectIdOrKey'] as string
    const body = (await request.json()) as {
      name: string
      description?: string
      leadUserId?: string
    }

    // 같은 프로젝트 내 이름 중복 확인
    const duplicate = Array.from(componentStore.values()).find(
      (c) => c.projectIdOrKey === projectIdOrKey && c.name === body.name,
    )
    if (duplicate !== undefined) {
      return problemDetail(
        409,
        'component-name-duplicate',
        'Component Name Duplicate',
        'COMPONENT_NAME_DUPLICATE',
        `같은 프로젝트에 동일한 이름의 컴포넌트가 이미 존재합니다: ${body.name}`,
      )
    }

    // 백엔드 create는 leadUserId 실재를 검증한다(미존재 → 422).
    // PATCH /lead와 동일한 localStorage 토글로 E2E에서 시뮬레이션한다.
    const leadFlag = globalThis.localStorage?.getItem(LS_KEY_COMPONENT_LEAD_422)
    if (body.leadUserId != null && leadFlag === 'true') {
      return problemDetail(
        422,
        'component-lead-not-found',
        'Component Lead Not Found',
        'COMPONENT_LEAD_NOT_FOUND',
        '리드로 지정한 사용자를 찾을 수 없습니다.',
      )
    }

    const newComponent: StoredComponent = {
      id: generateUuidV4(),
      projectId: generateUuidV4(),
      name: body.name,
      description: body.description ?? null,
      leadUserId: body.leadUserId ?? null,
      projectIdOrKey,
    }

    componentStore.set(newComponent.id, newComponent)

    return HttpResponse.json({ data: toComponent(newComponent) }, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/components/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 단건 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 컴포넌트 미존재 → 404 COMPONENT_NOT_FOUND
 * 성공 → 200 { data: Component }
 */
const getComponentHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/components/:id',
  ({ params }) => {
    const id = params['id'] as string
    const stored = componentStore.get(id)
    if (stored === undefined) {
      return componentNotFound(id)
    }
    return HttpResponse.json({ data: toComponent(stored) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/components/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 name/description 수정.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 컴포넌트 미존재 → 404 COMPONENT_NOT_FOUND
 * 성공 → 200 { data: Component }
 */
const updateComponentHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/components/:id',
  async ({ request, params }) => {
    const id = params['id'] as string
    const stored = componentStore.get(id)
    if (stored === undefined) {
      return componentNotFound(id)
    }

    const body = (await request.json()) as { name?: string; description?: string }
    const updated: StoredComponent = {
      ...stored,
      name: body.name ?? stored.name,
      description: body.description !== undefined ? body.description : stored.description,
    }
    componentStore.set(id, updated)

    return HttpResponse.json({ data: toComponent(updated) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/components/:id/lead
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 리드 사용자 변경.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 컴포넌트 미존재 → 404 COMPONENT_NOT_FOUND
 * 2. localStorage 플래그(msw-component-lead-422) → 422 COMPONENT_LEAD_NOT_FOUND (E2E 토글)
 * 성공 → 200 { data: Component }
 */
const changeLeadHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/components/:id/lead',
  async ({ request, params }) => {
    const id = params['id'] as string
    const stored = componentStore.get(id)
    if (stored === undefined) {
      return componentNotFound(id)
    }

    // E2E 시나리오 토글 — localStorage 플래그로 422 강제
    const leadFlag = globalThis.localStorage?.getItem(LS_KEY_COMPONENT_LEAD_422)
    if (leadFlag === 'true') {
      return problemDetail(
        422,
        'component-lead-not-found',
        'Component Lead Not Found',
        'COMPONENT_LEAD_NOT_FOUND',
        '리드로 지정한 사용자를 찾을 수 없습니다.',
      )
    }

    const body = (await request.json()) as { leadUserId: string | null }
    const updated: StoredComponent = {
      ...stored,
      leadUserId: body.leadUserId,
    }
    componentStore.set(id, updated)

    return HttpResponse.json({ data: toComponent(updated) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectIdOrKey/components/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 소프트 삭제 (map에서 제외).
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 컴포넌트 미존재 → 404 COMPONENT_NOT_FOUND
 * 성공 → 204 No Content
 */
const deleteComponentHandler = http.delete(
  '/api/v1/projects/:projectIdOrKey/components/:id',
  ({ params }) => {
    const id = params['id'] as string
    if (!componentStore.has(id)) {
      return componentNotFound(id)
    }
    componentStore.delete(id)
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 컴포넌트 BC MSW 핸들러 배열 */
export const componentHandlers = [
  listComponentsHandler,
  createComponentHandler,
  getComponentHandler,
  updateComponentHandler,
  changeLeadHandler,
  deleteComponentHandler,
]
