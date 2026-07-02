// 대시보드 BC MSW 핸들러 — stateful CRUD + OCC 409 + 권한 403 (FR-DB-01 D6)
// 카탈로그 핸들러 추가 (FR-DB-02 D6/D7 Task 1)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: PATCH/DELETE 후 GET 상세에 즉시 반영
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - e2e-msw-scenario-toggle-localstorage-flag: 409 토글은 localStorage 플래그로 분기
//
import { http, HttpResponse } from 'msw'
import {
  ALICE_OWNER_ID,
  dashboardStore,
  createDashboardInStore,
  issueShareTokenInStore,
  LS_KEY_DASHBOARD_CONFLICT,
  shareTokenStore,
  type StoredDashboard,
  type StoredShareToken,
} from './dashboard-fixtures'
import { GADGET_CATALOG_FIXTURE } from './gadget-catalog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 응답 DTO 인터페이스 — 백엔드 DashboardResponse 계약과 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 단건·목록 응답 DTO 형식.
 * 백엔드 DashboardResponse + @JsonInclude(NON_NULL) 정책 재현.
 * description이 null인 경우 키 자체가 존재하지 않는다.
 */
interface DashboardResponseDto {
  id: string
  ownerId: string
  name: string
  description?: string
  visibility: string
  layout: string
  sharedUserIds: string[]
  createdAt: string
  updatedAt: string
  version: number
}

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
function toResponseDto(stored: StoredDashboard): DashboardResponseDto {
  const base: DashboardResponseDto = {
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
    return { ...base, description: stored.description }
  }

  return base
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
 * 실제 프론트 api 클라이언트(src/api/dashboards.ts)는 X-Actor-Id 헤더를 보내지 않는다.
 * JWT 쿠키 인증 방식이므로 헤더가 없거나 빈 값이면 "현재 로그인 사용자 = alice"로 폴백한다.
 *
 * - X-Actor-Id 명시 → 해당 값 사용 (비소유자 403 등 권한 시나리오 테스트에 사용)
 * - X-Actor-Id 미존재·빈 값 → ALICE_OWNER_ID 폴백 (실제 통합/E2E 호출 패턴)
 *
 * @param request fetch Request 객체
 * @returns actor ID 문자열 (빈 값이면 ALICE_OWNER_ID 폴백)
 */
function extractActorId(request: Request): string {
  return request.headers.get('X-Actor-Id') || ALICE_OWNER_ID
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
// GET /api/v1/dashboards/gadget-catalog — 가젯 카탈로그 조회 (FR-DB-02 D6/D7)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/dashboards/gadget-catalog — 가젯 카탈로그 전체 조회.
 *
 * 읽기 전용이므로 상태 없음. GADGET_CATALOG_FIXTURE를 직접 반환한다.
 * 백엔드 GadgetCatalogResponse 1:1 응답 형식 재현 — { data: { gadgets: [...] } }.
 *
 * ⚠️ 계약 drift 경고.
 *   백엔드 GadgetType.kt 변경 시 gadget-catalog-fixtures.ts도 동기화해야 한다.
 *   (memory: frontend-zod-backend-dto-contract-gap)
 *
 * 성공 → 200 { data: GadgetCatalogResponse }
 */
const getGadgetCatalogHandler = http.get('/api/v1/dashboards/gadget-catalog', () => {
  return HttpResponse.json({ data: { gadgets: GADGET_CATALOG_FIXTURE } })
})

// ─────────────────────────────────────────────────────────────────────────────
// 공유 토큰 발급/목록/취소 + 익명 공개 조회 (FR-DB-03 D6/D7 Task 2)
//
// 실제 백엔드 계약 grep 대조.
//   backend/modules/notification/.../dashboard/web/DashboardShareController.kt
//   backend/modules/notification/.../dashboard/web/PublicDashboardController.kt
//   backend/modules/notification/.../dashboard/web/dto/DashboardShareDtos.kt
//   backend/modules/notification/.../dashboard/web/dto/PublicDashboardDtos.kt
//   backend/modules/notification/.../dashboard/application/AnonymousLayoutSanitizer.kt
// ─────────────────────────────────────────────────────────────────────────────

/** 대시보드당 활성 공유 토큰 상한 — DashboardShareToken.MAX_SHARE_TOKENS 미러 */
const MAX_SHARE_TOKENS = 20

/**
 * 대시보드를 조회하고 요청 주체가 소유자인지 검증한다.
 * 공유 토큰 3개 핸들러(POST/GET/DELETE .../shares)의 공통 가드 —
 * 백엔드 DashboardService.requireOwnedDashboard() 1:1 재현.
 *
 * @returns 검증 통과 시 대시보드, 실패 시 즉시 반환할 에러 Response
 */
function requireOwnedDashboardOrError(
  id: string,
  actorId: string,
): { ok: true; dashboard: StoredDashboard } | { ok: false; response: ReturnType<typeof HttpResponse.json> } {
  const stored = dashboardStore.get(id)
  if (stored === undefined || stored.deletedAt !== null) {
    return {
      ok: false,
      response: errorResponse('NOTIF_DASHBOARD_NOT_FOUND', '대시보드를 찾을 수 없습니다.', 404),
    }
  }
  if (stored.ownerId !== actorId) {
    return {
      ok: false,
      response: errorResponse('NOTIF_DASHBOARD_FORBIDDEN', '해당 대시보드를 수정·삭제할 권한이 없습니다.', 403),
    }
  }
  return { ok: true, dashboard: stored }
}

/**
 * 공유 토큰 발급 응답 DTO 형식 — IssuedShareTokenResponse(@JsonInclude(NON_NULL)) 1:1 재현.
 * expiresAt이 null이면 키 자체가 응답에 없다.
 */
interface IssuedShareTokenDto {
  id: string
  token: string
  createdAt: string
  expiresAt?: string
}

/**
 * 공유 토큰 목록 요약 DTO 형식 — ShareTokenSummaryResponse(@JsonInclude(NON_NULL)) 1:1 재현.
 * token/tokenHash 필드 자체가 없다(EC-9 유출 차단). expiresAt이 null이면 키가 없다.
 */
interface ShareTokenSummaryDto {
  id: string
  createdAt: string
  expiresAt?: string
}

/**
 * StoredShareToken을 발급 응답 DTO로 변환한다. expiresAt이 null이면 키를 생략한다.
 */
function toIssuedShareTokenDto(entry: StoredShareToken): IssuedShareTokenDto {
  const base: IssuedShareTokenDto = { id: entry.id, token: entry.token, createdAt: entry.createdAt }
  return entry.expiresAt !== null ? { ...base, expiresAt: entry.expiresAt } : base
}

/**
 * StoredShareToken을 목록 요약 DTO로 변환한다. token 필드는 절대 포함하지 않는다.
 */
function toShareTokenSummaryDto(entry: StoredShareToken): ShareTokenSummaryDto {
  const base: ShareTokenSummaryDto = { id: entry.id, createdAt: entry.createdAt }
  return entry.expiresAt !== null ? { ...base, expiresAt: entry.expiresAt } : base
}

/**
 * 공개 대시보드 응답 DTO 형식 — PublicDashboardResponse 1:1 재현.
 *
 * ⚠️ 계약 drift 주의 — 이 DTO에는 @JsonInclude(NON_NULL)이 없다(백엔드 파일 grep 확인).
 * 따라서 description이 null이어도 키 자체는 항상 존재한다(다른 대시보드 DTO들과 다른 정책).
 */
interface PublicDashboardDto {
  name: string
  description: string | null
  layout: string
}

/** GADGET_CATALOG_FIXTURE에서 STATIC 카테고리 gadgetType 집합을 도출한다 — 카탈로그 변경 시 자동 동기화. */
function staticGadgetTypes(): Set<string> {
  return new Set(GADGET_CATALOG_FIXTURE.filter((entry) => entry.category === 'STATIC').map((entry) => entry.type))
}

/** 정화 플레이스홀더에 유지하는 위치 필드 — AnonymousLayoutSanitizer.POSITION_FIELDS 미러 */
const POSITION_FIELDS = ['i', 'x', 'y', 'w', 'h'] as const

/**
 * layout JSON 배열을 익명 공개 뷰용으로 정화한다.
 *
 * 백엔드 AnonymousLayoutSanitizer.sanitize() 1:1 재현 — fail-closed 화이트리스트.
 *   - gadgetType이 STATIC 카테고리(text_widget/link_list)면 config 포함 원본 그대로 통과.
 *   - 그 외(데이터 가젯·카탈로그 밖 미지 타입·gadgetType 없는 legacy)는 위치 필드만 남기고
 *     `requiresAuth: true`를 추가한 플레이스홀더로 치환한다(config 제거).
 *   - 파싱 실패·비배열 입력은 예외를 던지지 않고 빈 배열을 반환한다(total 함수).
 *
 * @param layoutJson 정화할 layout JSON 배열 문자열
 * @returns 정화된 layout JSON 배열 문자열. 파싱 실패 시 `"[]"`.
 */
function sanitizeLayoutForPublic(layoutJson: string): string {
  let parsed: unknown
  try {
    parsed = JSON.parse(layoutJson)
  } catch {
    return '[]'
  }
  if (!Array.isArray(parsed)) {
    return '[]'
  }

  const staticTypes = staticGadgetTypes()

  const sanitized = (parsed as unknown[]).map((item) => {
    if (typeof item !== 'object' || item === null) {
      return item
    }
    const tile = item as Record<string, unknown>
    const gadgetType = typeof tile['gadgetType'] === 'string' ? tile['gadgetType'] : undefined

    if (gadgetType !== undefined && staticTypes.has(gadgetType)) {
      // 유일한 화이트리스트 — STATIC 가젯만 config 포함 원본 통과
      return tile
    }

    // 데이터 가젯 · 카탈로그 밖 미지 타입 · gadgetType 없는 legacy — fail-closed 플레이스홀더
    const placeholder: Record<string, unknown> = {}
    for (const field of POSITION_FIELDS) {
      if (tile[field] !== undefined) {
        placeholder[field] = tile[field]
      }
    }
    if (gadgetType !== undefined) {
      placeholder['gadgetType'] = gadgetType
    }
    placeholder['requiresAuth'] = true
    return placeholder
  })

  return JSON.stringify(sanitized)
}

/**
 * POST /api/v1/dashboards/{id}/shares — 공유 토큰 발급.
 *
 * 소유자 전용. 요청 바디는 선택(`{}` 또는 `{expiresAt}`).
 * 활성 토큰 수가 MAX_SHARE_TOKENS 이상이면 400.
 *
 * 성공 → 201 { data: IssuedShareTokenDto } (원문 token 1회 노출)
 * 미존재·삭제됨 → 404 NOTIF_DASHBOARD_NOT_FOUND
 * 비소유자 → 403 NOTIF_DASHBOARD_FORBIDDEN
 * 상한 초과 → 400 NOTIF_DASHBOARD_SHARE_LIMIT_EXCEEDED
 */
const issueShareTokenHandler = http.post('/api/v1/dashboards/:id/shares', async ({ params, request }) => {
  const id = params['id'] as string
  const actorId = extractActorId(request)

  const guard = requireOwnedDashboardOrError(id, actorId)
  if (!guard.ok) {
    return guard.response
  }

  const activeCount = Array.from(shareTokenStore.values()).filter((entry) => entry.dashboardId === id).length
  if (activeCount >= MAX_SHARE_TOKENS) {
    return errorResponse(
      'NOTIF_DASHBOARD_SHARE_LIMIT_EXCEEDED',
      '발급 가능한 공유 링크 개수 상한을 초과했습니다.',
      400,
    )
  }

  let expiresAt: string | null = null
  try {
    const text = await request.text()
    if (text.trim() !== '') {
      const body = JSON.parse(text) as { expiresAt?: string | null }
      expiresAt = body.expiresAt ?? null
    }
  } catch {
    return errorResponse('NOTIF_DASHBOARD_INVALID', '요청 바디를 파싱할 수 없습니다.', 400)
  }

  const issued = issueShareTokenInStore(id, expiresAt)

  return HttpResponse.json({ data: toIssuedShareTokenDto(issued) }, { status: 201 })
})

/**
 * GET /api/v1/dashboards/{id}/shares — 발급된 공유 토큰 목록 조회.
 *
 * 소유자 전용. 응답에는 원문 token이 절대 포함되지 않는다(EC-9 회귀가드).
 *
 * 성공 → 200 { data: { items: ShareTokenSummaryDto[] } }
 * 미존재·삭제됨 → 404 NOTIF_DASHBOARD_NOT_FOUND
 * 비소유자 → 403 NOTIF_DASHBOARD_FORBIDDEN
 */
const listShareTokensHandler = http.get('/api/v1/dashboards/:id/shares', ({ params, request }) => {
  const id = params['id'] as string
  const actorId = extractActorId(request)

  const guard = requireOwnedDashboardOrError(id, actorId)
  if (!guard.ok) {
    return guard.response
  }

  const items = Array.from(shareTokenStore.values())
    .filter((entry) => entry.dashboardId === id)
    .map(toShareTokenSummaryDto)

  return HttpResponse.json({ data: { items } })
})

/**
 * DELETE /api/v1/dashboards/{id}/shares/{shareId} — 공유 토큰 취소.
 *
 * 소유자 전용. shareId가 해당 대시보드 스코프에 없으면 404.
 *
 * 성공 → 204 No Content, store에서 즉시 제거(stateful)
 * 미존재 대시보드·삭제됨 → 404 NOTIF_DASHBOARD_NOT_FOUND
 * 비소유자 → 403 NOTIF_DASHBOARD_FORBIDDEN
 * 공유 토큰 미존재·스코프 불일치 → 404 NOTIF_DASHBOARD_SHARE_NOT_FOUND
 */
const revokeShareTokenHandler = http.delete('/api/v1/dashboards/:id/shares/:shareId', ({ params, request }) => {
  const id = params['id'] as string
  const shareId = params['shareId'] as string
  const actorId = extractActorId(request)

  const guard = requireOwnedDashboardOrError(id, actorId)
  if (!guard.ok) {
    return guard.response
  }

  const entry = shareTokenStore.get(shareId)
  if (entry === undefined || entry.dashboardId !== id) {
    return errorResponse('NOTIF_DASHBOARD_SHARE_NOT_FOUND', '공유 링크를 찾을 수 없습니다.', 404)
  }

  shareTokenStore.delete(shareId)

  return new HttpResponse(null, { status: 204 })
})

/**
 * GET /api/v1/public/dashboards/{token} — 익명(비인증) 공개 대시보드 조회.
 *
 * SecurityContext(actor) 무참조 — 오직 토큰 소지로만 접근을 판정한다(실제 컨트롤러와 동일).
 * 미존재·만료·부모 대시보드 삭제는 모두 404로 수렴한다(존재 여부 열거 차단).
 *
 * 성공 → 200 { data: PublicDashboardDto } (layout은 정화된 JSON 문자열)
 * 무효·만료·부모삭제 → 404 NOTIF_DASHBOARD_NOT_FOUND
 */
const getPublicDashboardHandler = http.get('/api/v1/public/dashboards/:token', ({ params }) => {
  const token = params['token'] as string

  const entry = Array.from(shareTokenStore.values()).find((t) => t.token === token)
  if (entry === undefined) {
    return errorResponse('NOTIF_DASHBOARD_NOT_FOUND', '공유된 대시보드를 찾을 수 없습니다.', 404)
  }

  if (entry.expiresAt !== null && new Date(entry.expiresAt).getTime() <= Date.now()) {
    return errorResponse('NOTIF_DASHBOARD_NOT_FOUND', '공유된 대시보드를 찾을 수 없습니다.', 404)
  }

  const dashboard = dashboardStore.get(entry.dashboardId)
  if (dashboard === undefined || dashboard.deletedAt !== null) {
    return errorResponse('NOTIF_DASHBOARD_NOT_FOUND', '공유된 대시보드를 찾을 수 없습니다.', 404)
  }

  const dto: PublicDashboardDto = {
    name: dashboard.name,
    description: dashboard.description,
    layout: sanitizeLayoutForPublic(dashboard.layout),
  }

  return HttpResponse.json({ data: dto })
})

// ─────────────────────────────────────────────────────────────────────────────
// export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 BC MSW 핸들러 배열.
 *
 * handlers.ts에서 dashboardHandlers를 spread해 등록한다.
 * GET 목록·단건, POST 생성, PATCH 수정, DELETE 삭제 모두 포함.
 * 가젯 카탈로그 핸들러(FR-DB-02) 포함.
 *
 * ★ 핸들러 순서 주의 (MSW는 선두 매칭 방식).
 *   getGadgetCatalogHandler(GET /api/v1/dashboards/gadget-catalog)를
 *   getDashboardHandler(GET /api/v1/dashboards/:id) 보다 먼저 등록해야
 *   "gadget-catalog" 문자열이 :id 파라미터로 잘못 매칭되지 않는다.
 *   공유 토큰 핸들러(.../shares, .../shares/:shareId)는 :id 단일 세그먼트 패턴과
 *   세그먼트 수가 달라 충돌하지 않는다. 익명 공개 조회(/api/v1/public/dashboards/:token)는
 *   완전히 다른 경로 루트라 충돌 여지가 없다.
 */
export const dashboardHandlers = [
  listDashboardsHandler,
  getGadgetCatalogHandler,
  getDashboardHandler,
  createDashboardHandler,
  patchDashboardHandler,
  deleteDashboardHandler,
  issueShareTokenHandler,
  listShareTokensHandler,
  revokeShareTokenHandler,
  getPublicDashboardHandler,
]
