// Git 웹훅 등록/목록/삭제 REST API 함수 (FR-AT-07 PR-D)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import { createGitWebhookResponseSchema, gitWebhookSummarySchema } from './automation-git-webhooks.types'
import type { CreateGitWebhookInput, CreateGitWebhookResponse, GitWebhookSummary } from './automation-git-webhooks.types'

export type { GitProvider, CreateGitWebhookInput, CreateGitWebhookResponse, GitWebhookSummary } from './automation-git-webhooks.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** Git 웹훅 목록 응답 스키마 — bare 배열(봉투 없음) */
const gitWebhookSummaryListSchema = z.array(gitWebhookSummarySchema)

/** 기본 경로 헬퍼 */
function basePath(projectKey: string): string {
  return `/api/v1/projects/${projectKey}/automation/git-webhooks`
}

/**
 * mutation 응답이 비-2xx이면 ApiError를 throw한다.
 * create/delete 2개 함수가 공유하는 에러 표면화 로직을 한 곳에 모은다(automation-rules.ts 동형).
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 Git 웹훅 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/automation/git-webhooks → bare 배열(봉투 없음) 그대로 반환.
 * 목록 항목에는 token·secret 관련 필드가 없다(backend GitWebhookSummaryResponse 계약).
 *
 * @param projectKey 프로젝트 키
 * @returns GitWebhookSummary 배열 — 등록된 웹훅이 없으면 빈 배열
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function listGitWebhooks(projectKey: string): Promise<GitWebhookSummary[]> {
  return apiGet(basePath(projectKey), gitWebhookSummaryListSchema)
}

/**
 * Git 웹훅을 등록한다.
 *
 * POST /api/v1/projects/{projectKey}/automation/git-webhooks → 201 { id, provider, webhookUrl, token } 반환.
 * `token`은 이 응답에서만 노출되는 1회성 원문 — 목록 조회에는 다시 나타나지 않는다.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * ## ★ secret 은 절대 가공하지 않는다 (BLOCKER-0)
 * `input.secret`을 trim·normalize 하지 않고 요청 본문에 그대로 싣는다 — backend가 서명(HMAC,
 * 해시 기반 메시지 인증 코드) 검증에 secret의 바이트열 원본을 그대로 쓰기 때문에, 여기서 한 글자라도
 * 손대면 실제 Git 호스팅 제공자가 보내는 웹훅 서명과 어긋나 검증이 영구히 실패한다.
 *
 * @param projectKey 프로젝트 키
 * @param input provider·secret(원문, 16~4096자·비공백)
 * @returns 등록된 웹훅 정보 + 1회성 token 원문
 * @throws ApiError(400, AUTOMATION_GIT_WEBHOOK_SECRET_INVALID) secret 검증 실패 시
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function createGitWebhook(
  projectKey: string,
  input: CreateGitWebhookInput,
): Promise<CreateGitWebhookResponse> {
  const res = await apiFetch(basePath(projectKey), {
    method: 'POST',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  await throwIfNotOk(res)
  const raw: unknown = await res.json()
  return createGitWebhookResponseSchema.parse(raw)
}

/**
 * Git 웹훅을 삭제한다.
 *
 * DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 키
 * @param id 삭제할 웹훅 UUID
 * @returns void
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function deleteGitWebhook(projectKey: string, id: string): Promise<void> {
  const res = await apiFetch(`${basePath(projectKey)}/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  await throwIfNotOk(res)
}
