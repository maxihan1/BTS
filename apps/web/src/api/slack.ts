// Slack 연결 상태 조회 + authorize URL 발급 + 본인 계정 연결/해제 API 클라이언트 (FR-SL-01 D6, FR-SL-02 D6)
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 view-layer 응답 계약과 1:1 정합 (스펙 FR1/FR2 확정본,
// 봇 토큰/installedBy/scopes/appId 등 민감/내부 필드는 응답에 없음)
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

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** Slack 연결 상태 타입 — Zod 스키마에서 추론 */
export type SlackInstallation = z.infer<typeof SlackInstallationSchema>

/** Slack authorize URL 응답 타입 — Zod 스키마에서 추론 */
export type SlackInstallUrl = z.infer<typeof SlackInstallUrlSchema>

/** 본인 Slack 계정 연결 상태 타입 — Zod 스키마에서 추론 */
export type SlackConnection = z.infer<typeof SlackConnectionSchema>

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
