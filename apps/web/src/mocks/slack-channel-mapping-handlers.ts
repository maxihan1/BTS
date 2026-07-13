// Slack 채널↔프로젝트 매핑 MSW 핸들러 — stateful CRUD + 중복/미존재 에러 + 워크스페이스 미설치 토글 (FR-SL-06 D6 Task 6)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키 (e2e-msw-scenario-toggle-localstorage-flag 선례,
// slack-handlers.ts E2E_SLACK_CONNECTED_KEY 동형)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 이 키가 'true'이면 `POST /api/v1/slack/channel-mappings`가
 * 워크스페이스 미설치(409 WORKSPACE_NOT_INSTALLED, 백엔드 EC12)를 시뮬레이션한다.
 *
 * Playwright addInitScript로 goto 전에 플래그를 설정하면 첫 요청부터 적용된다
 * (e2e-msw-scenario-toggle-localstorage-flag 교훈 — 핸들러 임시 교체 대신 localStorage 플래그).
 */
export const SLACK_CHANNEL_MAPPING_WORKSPACE_NOT_INSTALLED_KEY =
  '__bts_e2e_slack_channel_mapping_workspace_not_installed'

/** 워크스페이스 미설치 시나리오가 켜져 있는지 읽는다 — 미설정/그 외 값이면 false(happy path). */
function isWorkspaceNotInstalled(): boolean {
  return globalThis.localStorage?.getItem(SLACK_CHANNEL_MAPPING_WORKSPACE_NOT_INSTALLED_KEY) === 'true'
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (automation-rule-fixtures.ts 동형) — 신규 의존성 금지, crypto.randomUUID 표준 API 사용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * Zod v4 z.string().uuid() 검증을 통과하는 형식을 보장한다(zod-v4-uuid-fixture-strictness).
 */
function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16)
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 저장소 — 백엔드 ChannelMappingResponse(SlackChannelMappingResponses.kt) 응답 계약과 1:1
// (frontend-zod-backend-dto-contract-gap 교훈 — 백엔드 컨트롤러/DTO 확인 완료, invent 아님)
// ─────────────────────────────────────────────────────────────────────────────

interface ChannelMapping {
  id: string
  projectKey: string
  channelId: string
  channelName: string | null
  eventTypes: string[]
  createdAt: string
  updatedAt: string
}

/** 현재 인메모리 매핑 store — id → ChannelMapping. 모듈 로드 시 빈 상태(E3 빈 상태 시나리오 기본값). */
let mappingStore: Map<string, ChannelMapping> = new Map()

/**
 * store를 빈 상태로 초기화한다.
 * 테스트 afterEach에서 호출해 이전 테스트 잔여 데이터를 제거한다(msw-derived-behavior-shared-store-e2e).
 */
export function resetSlackChannelMappingStore(): void {
  mappingStore = new Map()
}

/**
 * 지정한 매핑 배열로 store를 시드한다. 기존 항목은 id 기준으로 덮어쓴다.
 * E2E addInitScript 또는 테스트 beforeEach에서 초기 상태를 구성할 때 사용한다.
 *
 * @param mappings 시드할 매핑 목록
 */
export function seedSlackChannelMappings(mappings: ChannelMapping[]): void {
  for (const mapping of mappings) {
    mappingStore.set(mapping.id, { ...mapping })
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — 에러 응답 + 중복 판정
// (SlackChannelMappingExceptionHandler 1:1 정합 — 코드/메시지 고정 문구를 호출부마다 반복하지 않고
// 응답 종류별 헬퍼로 모아 3개 핸들러가 공유한다. automation-rule-handlers.ts problemDetail 동형 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/** 백엔드 `{code, message}` 에러 봉투 */
interface ChannelMappingErrorBody {
  code: string
  message: string
}

/** 지정한 상태코드·코드·메시지로 에러 응답을 만든다 */
function errorResponse(status: number, code: string, message: string): HttpResponse<ChannelMappingErrorBody> {
  return HttpResponse.json<ChannelMappingErrorBody>({ code, message }, { status })
}

/** 대상 매핑 id 미존재 → 404 SLACK_CHANNEL_MAPPING_NOT_FOUND (patch/delete 공유) */
function notFoundResponse(): HttpResponse<ChannelMappingErrorBody> {
  return errorResponse(404, 'SLACK_CHANNEL_MAPPING_NOT_FOUND', '채널 매핑을 찾을 수 없습니다.')
}

/** 같은 (projectKey, channelId) 매핑 이미 존재(EC2) → 409 SLACK_CHANNEL_MAPPING_CONFLICT (create/patch 공유) */
function conflictResponse(): HttpResponse<ChannelMappingErrorBody> {
  return errorResponse(409, 'SLACK_CHANNEL_MAPPING_CONFLICT', '이미 동일한 채널 매핑이 존재합니다.')
}

/** eventTypes 빈 배열(EC1) → 400 SLACK_CHANNEL_MAPPING_INVALID (create/patch 공유) */
function invalidEventTypesResponse(): HttpResponse<ChannelMappingErrorBody> {
  return errorResponse(400, 'SLACK_CHANNEL_MAPPING_INVALID', '이벤트 유형 값이 올바르지 않습니다.')
}

/**
 * store에서 같은 (projectKey, channelId) 조합의 다른 매핑을 찾는다(UNIQUE(team_id, project_key,
 * channel_id) 위반 시뮬레이션). [excludeId]를 지정하면 자기 자신은 후보에서 제외한다(PATCH용).
 */
function findDuplicate(projectKey: string, channelId: string, excludeId?: string): ChannelMapping | undefined {
  return Array.from(mappingStore.values()).find(
    (mapping) =>
      mapping.projectKey === projectKey && mapping.channelId === channelId && mapping.id !== excludeId,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/slack/channel-mappings?projectKey=
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 속한 채널 매핑 목록 조회.
 * `projectKey` 쿼리 누락 시 400(백엔드 MissingServletRequestParameterException 미러).
 * 성공 → 200 ChannelMappingResponse[](봉투 없음, bare 배열).
 */
const listHandler = http.get('/api/v1/slack/channel-mappings', ({ request }) => {
  const projectKey = new URL(request.url).searchParams.get('projectKey')
  if (projectKey === null) {
    return errorResponse(400, 'SLACK_CHANNEL_MAPPING_BAD_REQUEST', '요청 형식이 올바르지 않습니다.')
  }

  const items = Array.from(mappingStore.values()).filter((mapping) => mapping.projectKey === projectKey)
  return HttpResponse.json(items)
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/slack/channel-mappings
// ─────────────────────────────────────────────────────────────────────────────

interface CreateChannelMappingRequestBody {
  projectKey: string
  channelId: string
  channelName?: string
  eventTypes: string[]
}

/**
 * 채널 매핑 생성.
 *
 * 검증 순서(백엔드 SlackChannelMappingService.create 1:1).
 * 1. 워크스페이스 미설치 시나리오 토글 → 409 WORKSPACE_NOT_INSTALLED(EC12).
 * 2. eventTypes 빈 배열 → 400 SLACK_CHANNEL_MAPPING_INVALID(EC1).
 * 3. 같은 (projectKey, channelId) 매핑 이미 존재 → 409 SLACK_CHANNEL_MAPPING_CONFLICT(EC2).
 * 성공 → 201 + eventTypes 정렬된 매핑 뷰(백엔드 ChannelMappingResponse.from 계약).
 */
const createHandler = http.post('/api/v1/slack/channel-mappings', async ({ request }) => {
  const body = (await request.json()) as CreateChannelMappingRequestBody

  if (isWorkspaceNotInstalled()) {
    return errorResponse(409, 'WORKSPACE_NOT_INSTALLED', 'Slack 워크스페이스가 설치되어 있지 않습니다.')
  }

  if (body.eventTypes.length === 0) {
    return invalidEventTypesResponse()
  }

  if (findDuplicate(body.projectKey, body.channelId) !== undefined) {
    return conflictResponse()
  }

  const now = new Date().toISOString()
  const created: ChannelMapping = {
    id: generateUuidV4(),
    projectKey: body.projectKey,
    channelId: body.channelId,
    channelName: body.channelName ?? null,
    eventTypes: [...body.eventTypes].sort(),
    createdAt: now,
    updatedAt: now,
  }
  mappingStore.set(created.id, created)

  return HttpResponse.json(created, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/slack/channel-mappings/:id
// ─────────────────────────────────────────────────────────────────────────────

interface UpdateChannelMappingRequestBody {
  channelId?: string
  channelName?: string
  eventTypes?: string[]
}

/**
 * 채널 매핑 부분 수정 — channelId·channelName·eventTypes. 미지정 필드는 기존 값 유지(PATCH 의미).
 *
 * 에러 분기 순서(백엔드 SlackChannelMappingService.update 1:1, requireManageOrHideNotFound로 403도
 * 404에 수렴하는 정책이라 이 mock은 권한 축을 시뮬레이션하지 않고 미존재만 404로 다룬다).
 * 1. 대상 id 미존재 → 404 SLACK_CHANNEL_MAPPING_NOT_FOUND.
 * 2. eventTypes 지정 시 빈 배열 → 400 SLACK_CHANNEL_MAPPING_INVALID(EC1).
 * 3. 변경 결과가 다른 매핑과 (projectKey, channelId) 중복 → 409 SLACK_CHANNEL_MAPPING_CONFLICT(EC2).
 * 성공 → 200 + updatedAt 갱신(msw-mutation-stateful-refetch — 후속 GET에 즉시 반영).
 */
const patchHandler = http.patch(
  '/api/v1/slack/channel-mappings/:id',
  async ({ params, request }) => {
    const id = params['id'] as string
    const existing = mappingStore.get(id)
    if (existing === undefined) {
      return notFoundResponse()
    }

    const body = (await request.json()) as UpdateChannelMappingRequestBody

    if (body.eventTypes !== undefined && body.eventTypes.length === 0) {
      return invalidEventTypesResponse()
    }

    const nextChannelId = body.channelId ?? existing.channelId
    if (findDuplicate(existing.projectKey, nextChannelId, existing.id) !== undefined) {
      return conflictResponse()
    }

    const updated: ChannelMapping = {
      ...existing,
      channelId: nextChannelId,
      channelName: body.channelName ?? existing.channelName,
      eventTypes: body.eventTypes !== undefined ? [...body.eventTypes].sort() : existing.eventTypes,
      updatedAt: new Date().toISOString(),
    }
    mappingStore.set(id, updated)

    return HttpResponse.json(updated)
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/slack/channel-mappings/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 채널 매핑 삭제(하드 삭제 — mock에서는 store에서 즉시 제거).
 * 대상 id 미존재 → 404 SLACK_CHANNEL_MAPPING_NOT_FOUND.
 * 성공 → 204 No Content.
 */
const deleteHandler = http.delete('/api/v1/slack/channel-mappings/:id', ({ params }) => {
  const id = params['id'] as string
  if (!mappingStore.has(id)) {
    return notFoundResponse()
  }

  mappingStore.delete(id)
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** Slack 채널↔프로젝트 매핑 BC MSW 핸들러 배열 */
export const slackChannelMappingHandlers = [listHandler, createHandler, patchHandler, deleteHandler]
