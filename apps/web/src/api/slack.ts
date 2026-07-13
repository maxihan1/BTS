// Slack 연결 상태 조회 + authorize URL 발급 + 본인 계정 연결/해제 API 클라이언트 (FR-SL-01 D6, FR-SL-02 D6)
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 view-layer 응답 계약과 1:1 정합.
// FR-SL-01 스펙 FR1/FR2 확정본(관리자 워크스페이스 설치) + FR-SL-02 스펙 FR3(본인 계정 연결).
// 봇 토큰/installedBy/scopes/appId/slack_user_id/이메일 등 민감/내부 필드는 응답에 없음(3중 미노출 관례)
// ─────────────────────────────────────────────────────────────────────────────

/** Slack 연결 상태 응답 스키마 — `GET /api/v1/slack/installation` */
export const SlackInstallationSchema = z.object({
  connected: z.boolean(),
  teamId: z.string().nullable(),
  teamName: z.string().nullable(),
  botUserId: z.string().nullable(),
  installedAt: z.string().nullable(),
  updatedAt: z.string().nullable(),
  installerName: z.string().nullable(),
})

/** Slack authorize URL 응답 스키마 — `GET /api/v1/slack/install-url` */
export const SlackInstallUrlSchema = z.object({
  url: z.string(),
})

/**
 * 본인 Slack 계정 연결 상태 응답 스키마 (FR-SL-02 D6 스펙 FR3) —
 * `GET/POST/DELETE /api/v1/slack/me/connection` 공통 응답 DTO.
 * slack_user_id/이메일/봇 토큰은 응답에 없음(3중 미노출 관례).
 */
export const SlackConnectionSchema = z.object({
  connected: z.boolean(),
  workspaceName: z.string().nullable(),
  linkedAt: z.string().nullable(),
})

/**
 * 채널↔프로젝트 매핑 응답 스키마 (FR-SL-06 D6) —
 * `POST/GET/PATCH /api/v1/slack/channel-mappings` 공통 응답 DTO.
 * 백엔드 `ChannelMappingResponse`(SlackChannelMappingController) 1:1 정합.
 * `team_id`는 응답에 없음(단일 워크스페이스 설치, 내부 해석 전용).
 */
export const ChannelMappingSchema = z.object({
  id: z.string().uuid(),
  projectKey: z.string(),
  channelId: z.string(),
  channelName: z.string().nullable(),
  eventTypes: z.array(z.string()),
  createdAt: z.string(),
  updatedAt: z.string(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지) — 위 Zod 스키마 그룹과 동일 순서로 정리
// ─────────────────────────────────────────────────────────────────────────────

// FR-SL-01(관리자 워크스페이스 설치)
/** Slack 연결 상태 타입 — Zod 스키마에서 추론 */
export type SlackInstallation = z.infer<typeof SlackInstallationSchema>

/** Slack authorize URL 응답 타입 — Zod 스키마에서 추론 */
export type SlackInstallUrl = z.infer<typeof SlackInstallUrlSchema>

// FR-SL-02(본인 계정 연결)
/** 본인 Slack 계정 연결 상태 타입 — Zod 스키마에서 추론 */
export type SlackConnection = z.infer<typeof SlackConnectionSchema>

// FR-SL-06(채널↔프로젝트 매핑)
/** 채널↔프로젝트 매핑 타입 — Zod 스키마에서 추론 */
export type ChannelMapping = z.infer<typeof ChannelMappingSchema>

/** 채널 매핑 생성 요청 (`POST /api/v1/slack/channel-mappings` 바디) */
export interface CreateChannelMappingInput {
  projectKey: string
  channelId: string
  channelName?: string
  eventTypes: string[]
}

/** 채널 매핑 부분 수정 요청 (`PATCH /api/v1/slack/channel-mappings/{id}` 바디) — 미지정 필드는 미변경 */
export interface UpdateChannelMappingInput {
  channelId?: string
  channelName?: string
  eventTypes?: string[]
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 워크스페이스의 Slack 연결 상태를 조회한다.
 *
 * `GET /api/v1/slack/installation` → 200 {@link SlackInstallationSchema}.
 * 미연결이면 `connected: false`와 함께 teamId/teamName/botUserId/installedAt/updatedAt/installerName이 모두 null로 온다.
 *
 * @returns Slack 연결 상태
 * @throws ApiError 서버 오류 또는 미인증 시
 */
export async function getSlackInstallation(): Promise<SlackInstallation> {
  return apiGet('/api/v1/slack/installation', SlackInstallationSchema)
}

/**
 * Slack OAuth authorize URL을 조회한다.
 *
 * `GET /api/v1/slack/install-url` → 200 {@link SlackInstallUrlSchema}.
 * 반환된 url로 브라우저를 이동시켜 Slack OAuth 설치 플로우를 시작한다.
 *
 * @returns Slack authorize URL
 * @throws ApiError 서버 오류 또는 미인증 시
 */
export async function getSlackInstallUrl(): Promise<SlackInstallUrl> {
  return apiGet('/api/v1/slack/install-url', SlackInstallUrlSchema)
}

/**
 * 본인 Slack 계정 연결 상태를 조회한다 (FR-SL-02 D6 스펙 FR3).
 *
 * `GET /api/v1/slack/me/connection` → 200 {@link SlackConnectionSchema}.
 * JWT 인증 사용자 본인 기준(me-scope) — PAT는 401.
 *
 * @returns 본인 Slack 계정 연결 상태
 * @throws ApiError(401) 미인증 또는 PAT 인증 호출
 */
export async function getMyConnection(): Promise<SlackConnection> {
  return apiGet('/api/v1/slack/me/connection', SlackConnectionSchema)
}

/**
 * 본인 BTS 이메일로 Slack 계정을 자동 연결한다 (FR-SL-02 D6 스펙 FR2, 이메일 자동해석).
 *
 * `POST /api/v1/slack/me/connection`(본문 없음) → 200 {@link SlackConnectionSchema}.
 * 백엔드가 본인 이메일로 `users.lookupByEmail`을 호출해 slack_user_id/teamId를 자동 매핑한다
 * (upsert, 재호출 시에도 멱등). 인증은 Bearer(JWT)만 — OAuth 리다이렉트 없음.
 *
 * @returns 연결 후 Slack 계정 연결 상태
 * @throws ApiError(422) 본인 이메일 미설정(`EMAIL_UNAVAILABLE`)
 * @throws ApiError(409) 워크스페이스 미설치(`WORKSPACE_NOT_INSTALLED`) 또는 봇 스코프 부족(`SLACK_SCOPE_MISSING`)
 * @throws ApiError(404) Slack에서 해당 이메일 미발견(`SLACK_USER_NOT_FOUND`)
 * @throws ApiError(503) Slack 일시 오류(`SLACK_TEMPORARILY_UNAVAILABLE`)
 * @throws ApiError(401) 미인증 또는 PAT 인증 호출
 */
export async function connectSlack(): Promise<SlackConnection> {
  return apiPost('/api/v1/slack/me/connection', undefined, SlackConnectionSchema)
}

/**
 * 본인 Slack 계정 연결을 해제한다 (FR-SL-02 D6 스펙 FR4).
 *
 * `DELETE /api/v1/slack/me/connection` → 200 {@link SlackConnectionSchema}(`connected: false`).
 * 이미 미연결 상태에서 호출해도 멱등하게 200을 반환한다.
 * `apiFetch`로 직접 호출 — client.ts에 DELETE 전용 헬퍼(`apiDelete`)가 없어 응답을 직접 검사·파싱한다
 * (ooo.ts clearOoo/issue-watchers.ts removeWatcher 선례와 동일 패턴).
 *
 * @returns 해제 후 Slack 계정 연결 상태(`connected: false`)
 * @throws ApiError(401) 미인증 또는 PAT 인증 호출
 */
export async function disconnectSlack(): Promise<SlackConnection> {
  const res = await apiFetch('/api/v1/slack/me/connection', { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return SlackConnectionSchema.parse(await res.json())
}

/**
 * 채널 매핑 mutation 응답이 비-2xx이면 ApiError를 throw한다.
 * updateChannelMapping/deleteChannelMapping이 공유하는 에러 표면화 로직을 한 곳에 모은다
 * (automation-rules.ts throwIfNotOk 동형 패턴).
 */
async function throwIfChannelMappingError(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 프로젝트에 속한 Slack 채널↔프로젝트 매핑 목록을 조회한다 (FR-SL-06 D6).
 *
 * `GET /api/v1/slack/channel-mappings?projectKey=` → 200 {@link ChannelMappingSchema}[].
 *
 * @param projectKey 조회 대상 프로젝트 키
 * @returns 채널 매핑 배열 — 없으면 빈 배열
 * @throws ApiError(403, SLACK_CHANNEL_MAPPING_FORBIDDEN) 관리 권한 없음 시
 * @throws ApiError(401) 미인증 또는 PAT 인증 호출
 */
export async function listChannelMappings(projectKey: string): Promise<ChannelMapping[]> {
  return apiGet(
    `/api/v1/slack/channel-mappings?projectKey=${encodeURIComponent(projectKey)}`,
    z.array(ChannelMappingSchema),
  )
}

/**
 * Slack 채널↔프로젝트 매핑을 생성한다 (FR-SL-06 D6).
 *
 * `POST /api/v1/slack/channel-mappings` → 201 {@link ChannelMappingSchema}.
 * 권한: 대상 프로젝트 PROJECT_ADMIN(백엔드 fail-closed).
 *
 * @param input projectKey·channelId·channelName?·eventTypes(1개 이상)
 * @returns 생성된 채널 매핑
 * @throws ApiError(409, SLACK_CHANNEL_MAPPING_CONFLICT) 동일 채널 매핑 이미 존재 시
 * @throws ApiError(409, WORKSPACE_NOT_INSTALLED) Slack 워크스페이스 미설치 시
 * @throws ApiError(400) eventTypes가 비었거나 미지 값 포함, 또는 요청 형식 오류 시
 * @throws ApiError(403, SLACK_CHANNEL_MAPPING_FORBIDDEN) 관리 권한 없음 시
 * @throws ApiError(401) 미인증 또는 PAT 인증 호출
 */
export async function createChannelMapping(input: CreateChannelMappingInput): Promise<ChannelMapping> {
  return apiPost('/api/v1/slack/channel-mappings', input, ChannelMappingSchema)
}

/**
 * Slack 채널↔프로젝트 매핑을 부분 수정한다 (FR-SL-06 D6).
 *
 * `PATCH /api/v1/slack/channel-mappings/{id}` → 200 {@link ChannelMappingSchema}.
 * `null`/미지정 필드는 기존 값을 유지한다(PATCH 의미). `apiFetch`로 직접 호출 —
 * client.ts에 PATCH 전용 헬퍼가 없어 응답을 직접 검사·파싱한다(disconnectSlack 선례와 동일 패턴).
 *
 * @param id 수정 대상 매핑 UUID
 * @param input channelId?·channelName?·eventTypes?(제공 시 재검증)
 * @returns 수정된 채널 매핑
 * @throws ApiError(404, SLACK_CHANNEL_MAPPING_NOT_FOUND) 매핑 미존재 또는 권한 없음 시
 * @throws ApiError(409, SLACK_CHANNEL_MAPPING_CONFLICT) 변경 결과가 다른 매핑과 중복될 시
 * @throws ApiError(400) eventTypes가 비었거나 미지 값 포함, 또는 요청 형식 오류 시
 * @throws ApiError(401) 미인증 또는 PAT 인증 호출
 */
export async function updateChannelMapping(
  id: string,
  input: UpdateChannelMappingInput,
): Promise<ChannelMapping> {
  const res = await apiFetch(`/api/v1/slack/channel-mappings/${id}`, { method: 'PATCH', body: input })
  await throwIfChannelMappingError(res)
  return ChannelMappingSchema.parse(await res.json())
}

/**
 * Slack 채널↔프로젝트 매핑을 삭제한다 (FR-SL-06 D6).
 *
 * `DELETE /api/v1/slack/channel-mappings/{id}` → 204 No Content(본문 없음).
 *
 * @param id 삭제 대상 매핑 UUID
 * @throws ApiError(404, SLACK_CHANNEL_MAPPING_NOT_FOUND) 매핑 미존재 또는 권한 없음 시
 * @throws ApiError(401) 미인증 또는 PAT 인증 호출
 */
export async function deleteChannelMapping(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/slack/channel-mappings/${id}`, { method: 'DELETE' })
  await throwIfChannelMappingError(res)
}
