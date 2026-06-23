// 대시보드 BC MSW 핸들러 — stateful CRUD + OCC 409 + 권한 403 (FR-DB-01 D6)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: PATCH/DELETE 후 GET 상세에 즉시 반영
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - e2e-msw-scenario-toggle-localstorage-flag: 409 토글은 localStorage 플래그로 분기
//
import { http, HttpResponse } from 'msw'
import {
  dashboardStore,
  createDashboardInStore,
  LS_KEY_DASHBOARD_CONFLICT,
  type StoredDashboard,
} from './dashboard-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 타입
// ─────────────────────────────────────────────────────────────────────────────

/** ISO 8601 시각 문자열 타입 별칭 — 백엔드 Instant 직렬화 형식과 일치 */
export type Instant = string

// ─────────────────────────────────────────────────────────────────────────────
// 공통 응답 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * StoredDashboard를 응답 DTO 형식으로 변환한다.
 *
 * - description이 null이면 응답 JSON에서 제거(@JsonInclude(NON_NULL) 재현)
 * - deletedAt은 내부 전용이므로 응답에 포함하지 않는다
 *
 * @param stored store 내부 대시보드 데이터
 * @returns 응답 DTO 객체
 */
function toResponseDto(stored: StoredDashboard): Record<string, unknown> {
  const dto: Record<string, unknown> = {
    id: stored.id,
    ownerId: stored.ownerId,
    name: stored.name,
    visibility: stored.visibility,
    layout: stored.layout,
    sharedUserIds: stored.sharedUserIds,
    createdAt: stored.createdAt,
    updatedAt: stored.updatedAt,
    version: stored.version,
  }

  // @JsonInclude(NON_NULL) 재현 — null description은 직렬화 생략
  if (stored.description !== null) {
    dto['description'] = stored.description
  }

  return dto
}

/**
 * 에러 응답 JSON을 생성한다.
 *
 * 백엔드 DashboardExceptionHandler가 반환하는 RFC 7807 ProblemDetail과
 * errorCode 필드 이름을 맞춘다.
 *
 * @param errorCode BTS 에러 코드 (NOTIF_DASHBOARD_*)
 * @param message 사용자에게 보여줄 메시지
 * @param status HTTP 상태 코드
 */
function errorResponse(
  errorCode: string,
  message: string,
  status: number,
): ReturnType<typeof HttpResponse.json> {
  return HttpResponse.json({ errorCode, message }, { status })
}

/**
 * 요청 헤더 X-Actor-Id에서 현재 actor ID를 추출한다.
 *
 * 실제 백엔드는 SecurityContext에서 추출하지만, MSW 환경에서는
 * 테스트가 X-Actor-Id 헤더로 actor를 명시한다.
 *
 * @param request fetch Request 객체
 * @returns actor ID 문자열, 없으면 빈 문자열
 */
function extractActorId(request: Request): string {
  return request.headers.get('X-Actor-Id') ?? ''
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/dashboards — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/dashboards?limit=&offset= — 접근 가능 대시보드 목록 조회.
 *
 * 접근 범위 = 소유(ownerId===actor) UNION TEAM(sharedUserIds 포함) UNION ORG.
 * limit 기본 50, offset 기본 0.
 *
 * 소프트 삭제된 항목(deletedAt !== null)은 목록에서 제외한다.
 *
 * 성공 → 200 { data: DashboardPageResponse }
 */
const listDashboardsHandler = http.get('/api/v1/dashboards', ({ request }) => {
  const url = new URL(request.url)
  const limit = parseInt(url.searchParams.get('limit') ?? '50', 10)
  const offset = parseInt(url.searchParams.get('offset') ?? '0', 10)
  const actorId = extractActorId(request)

  // 접근 가능 대시보드 필터링 (소프트 삭제 제외)
  const accessible = Array.from(dashboardStore.values()).filter((d) => {
    if (d.deletedAt !== null) {
      return false
    }
    if (d.ownerId === actorId) {
      return true
    }
    if (d.visibility === 'ORG') {
      return true
    }
    if (d.visibility === 'TEAM' && d.sharedUserIds.includes(actorId)) {
      return true
    }
    return false
  })

  const total = accessible.length
  const items = accessible.slice(offset, offset + limit).map(toResponseDto)

  return HttpResponse.json({
    data: { items, total, limit, offset },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/dashboards/:id — 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/dashboards/{id} — 단건 대시보드 조회.
 *
 * 존재하지 않거나 소프트 삭제된 경우 → 404 (존재 숨김 정책).
 *
 * 성공 → 200 { data: DashboardResponse }
 * 미존재·삭제됨 → 404 NOTIF_DASHBOARD_NOT_FOUND
 */
const getDashboardHandler = http.get('/api/v1/dashboards/:id', ({ params }) => {
  const id = params['id'] as string
  const stored = dashboardStore.get(id)

  if (stored === undefined || stored.deletedAt !== null) {
    return errorResponse('NOTIF_DASHBOARD_NOT_FOUND', '대시보드를 찾을 수 없습니다.', 404)
  }

  return HttpResponse.json({ data: toResponseDto(stored) })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/dashboards — 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/dashboards — 새 대시보드 생성.
 *
 * 요청 body: { name, visibility, description?, layout?, sharedUserIds? }
 * X-Actor-Id 헤더로 ownerId를 결정한다.
 *
 * 성공 → 201 { data: DashboardResponse }
 */
const createDashboardHandler = http.post('/api/v1/dashboards', async ({ request }) => {
  const actorId = extractActorId(request)

  let name = ''
  let visibility = 'PRIVATE'
  let description: string | null = null
  let layout = '[]'
  let sharedUserIds: string[] = []

  try {
    const body = (await request.json()) as {
      name?: string
      visibility?: string
      description?: string | null
      layout?: string
      sharedUserIds?: string[]
    }
    name = body.name ?? ''
    visibility = body.visibility ?? 'PRIVATE'
    description = body.description ?? null
    layout = body.layout ?? '[]'
    sharedUserIds = body.sharedUserIds ?? []
  } catch {
    return errorResponse('NOTIF_DASHBOARD_INVALID', '요청 바디를 파싱할 수 없습니다.', 400)
  }

  const stored = createDashboardInStore(actorId, name, description, visibility, layout, sharedUserIds)

  return HttpResponse.json({ data: toResponseDto(stored) }, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/dashboards/:id — 부분 수정
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PATCH /api/v1/dashboards/{id} — 대시보드 부분 수정.
 *
 * 요청 body: { name?, description?, visibility?, layout?, sharedUserIds?, version(필수) }
 *
 * OCC 409 강제 토글 — E2E 시나리오용.
 *   localStorage 플래그 LS_KEY_DASHBOARD_CONFLICT='true'이면 409 반환.
 *   단 version 기반 자연 충돌 로직이 우선 적용된다.
 *
 * stateful 동작 (msw-mutation-stateful-refetch).
 *   - store의 해당 대시보드를 변이한다.
 *   - version을 +1 증가시킨다.
 *   - 이후 GET에서 변경된 내용이 반영됨을 보장한다.
 *
 * 성공 → 200 { data: DashboardResponse } (version+1 포함)
 * 비소유자 → 403 NOTIF_DASHBOARD_FORBIDDEN
 * 미존재·삭제됨 → 404 NOTIF_DASHBOARD_NOT_FOUND
 * version 불일치 → 409 NOTIF_DASHBOARD_CONFLICT
 */
const patchDashboardHandler = http.patch('/api/v1/dashboards/:id', async ({ params, request }) => {
  const id = params['id'] as string
  const actorId = extractActorId(request)

  // 미존재·소프트 삭제 확인
  const stored = dashboardStore.get(id)
  if (stored === undefined || stored.deletedAt !== null) {
    return errorResponse('NOTIF_DASHBOARD_NOT_FOUND', '대시보드를 찾을 수 없습니다.', 404)
  }

  // 비소유자 접근 차단
  if (stored.ownerId !== actorId) {
    return errorResponse('NOTIF_DASHBOARD_FORBIDDEN', '해당 대시보드를 수정·삭제할 권한이 없습니다.', 403)
  }

  // 요청 body 파싱
  let requestedVersion = -1
  let name: string | undefined
  let description: string | undefined | null
  let visibility: string | undefined
  let layout: string | undefined
  let sharedUserIds: string[] | undefined

  try {
    const body = (await request.json()) as {
      version?: number
      name?: string
      description?: string | null
      visibility?: string
      layout?: string
      sharedUserIds?: string[]
    }
    requestedVersion = body.version ?? -1
    name = body.name
    description = body.description
    visibility = body.visibility
    layout = body.layout
    sharedUserIds = body.sharedUserIds
  } catch {
    return errorResponse('NOTIF_DASHBOARD_INVALID', '요청 바디를 파싱할 수 없습니다.', 400)
  }

  // OCC version 검증 — 자연 충돌 (version 기반이 localStorage 토글보다 우선)
  if (requestedVersion !== stored.version) {
    return errorResponse(
      'NOTIF_DASHBOARD_CONFLICT',
      '대시보드가 다른 사용자에 의해 수정되었습니다. 최신 버전으로 다시 시도하세요.',
      409,
    )
  }

  // 409 토글 — E2E 시나리오 (e2e-msw-scenario-toggle-localstorage-flag)
  const conflictFlag = globalThis.localStorage?.getItem(LS_KEY_DASHBOARD_CONFLICT) ?? ''
  if (conflictFlag === 'true') {
    return errorResponse(
      'NOTIF_DASHBOARD_CONFLICT',
      '낙관적 잠금 충돌 — 다른 사용자가 대시보드를 변경했습니다',
      409,
    )
  }

  // store 변이 — version +1, updatedAt 갱신 (msw-mutation-stateful-refetch)
  const updated: StoredDashboard = {
    ...stored,
    name: name ?? stored.name,
    description: description !== undefined ? description : stored.description,
    visibility: visibility ?? stored.visibility,
    layout: layout ?? stored.layout,
    sharedUserIds: sharedUserIds ?? stored.sharedUserIds,
    updatedAt: new Date().toISOString(),
    version: stored.version + 1,
  }

  dashboardStore.set(id, updated)

  return HttpResponse.json({ data: toResponseDto(updated) })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/dashboards/:id — 소프트 삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DELETE /api/v1/dashboards/{id} — 대시보드 소프트 삭제.
 *
 * 비소유자이면 403.
 * 존재하지 않거나 이미 삭제된 경우 404.
 *
 * stateful 동작 — deletedAt을 현재 시각으로 설정한다.
 * 이후 GET/LIST에서 이 항목은 제외된다.
 *
 * 성공 → 204 No Content
 * 비소유자 → 403 NOTIF_DASHBOARD_FORBIDDEN
 * 미존재·이미삭제 → 404 NOTIF_DASHBOARD_NOT_FOUND
 */
const deleteDashboardHandler = http.delete('/api/v1/dashboards/:id', ({ params, request }) => {
  const id = params['id'] as string
  const actorId = extractActorId(request)

  // 미존재·소프트 삭제 확인
  const stored = dashboardStore.get(id)
  if (stored === undefined || stored.deletedAt !== null) {
    return errorResponse('NOTIF_DASHBOARD_NOT_FOUND', '대시보드를 찾을 수 없습니다.', 404)
  }

  // 비소유자 접근 차단
  if (stored.ownerId !== actorId) {
    return errorResponse('NOTIF_DASHBOARD_FORBIDDEN', '해당 대시보드를 수정·삭제할 권한이 없습니다.', 403)
  }

  // 소프트 삭제 — deletedAt 설정
  dashboardStore.set(id, { ...stored, deletedAt: new Date().toISOString() })

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 BC MSW 핸들러 배열.
 *
 * handlers.ts에서 dashboardHandlers를 spread해 등록한다.
 * GET 목록·단건, POST 생성, PATCH 수정, DELETE 삭제 모두 포함.
 */
export const dashboardHandlers = [
  listDashboardsHandler,
  getDashboardHandler,
  createDashboardHandler,
  patchDashboardHandler,
  deleteDashboardHandler,
]
