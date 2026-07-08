// Slack 연결 상태 조회 + authorize URL 발급 API 클라이언트 (FR-SL-01 D6)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 view-layer 응답 계약과 1:1 정합 (스펙 FR1/FR2 확정본,
// 봇 토큰/installedBy/scopes/appId 등 민감/내부 필드는 응답에 없음)
// ─────────────────────────────────────────────────────────────────────────────

/** Slack 연결 상태 응답 스키마 — `GET /api/v1/slack/installation` */
export const SlackInstallationSchema = z.object({
  connected: z.boolean(),
  teamId: z.string().nullable(),
  teamName: z.string().nullable(),
  installedAt: z.string().nullable(),
})

/** Slack authorize URL 응답 스키마 — `GET /api/v1/slack/install-url` */
export const SlackInstallUrlSchema = z.object({
  url: z.string(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** Slack 연결 상태 타입 — Zod 스키마에서 추론 */
export type SlackInstallation = z.infer<typeof SlackInstallationSchema>

/** Slack authorize URL 응답 타입 — Zod 스키마에서 추론 */
export type SlackInstallUrl = z.infer<typeof SlackInstallUrlSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 워크스페이스의 Slack 연결 상태를 조회한다.
 *
 * `GET /api/v1/slack/installation` → 200 {@link SlackInstallationSchema}.
 * 미연결이면 `connected: false`와 함께 teamId/teamName/installedAt이 모두 null로 온다.
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
