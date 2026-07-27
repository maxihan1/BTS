// 댓글(issue-tracking BC) REST API 클라이언트 — 목록 조회·작성·수정·삭제 4함수 + Zod 스키마
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 댓글 단건 응답 Zod 스키마.
 * 백엔드 `CommentResponse` DTO와 1:1 대응.
 * - id: 댓글 UUID
 * - authorId: 작성자 UUID (서버가 actor 로 결정 — 클라이언트가 제안할 수 없다)
 * - body: 원문 Markdown
 * - bodyHtml: `MarkdownRenderer.renderSafe` 로 정화된 HTML
 * - createdAt / updatedAt: ISO 8601
 *
 * `bodyHtml` 은 백엔드가 항상 채우므로 `.optional()` 을 붙이지 않는다 — 누락은 회귀로 잡아야 한다
 * (worklogSummarySchema 가 같은 이유로 optional 을 피한 선례).
 */
export const commentResponseSchema = z.object({
  id: z.string().uuid(),
  authorId: z.string().uuid(),
  body: z.string(),
  bodyHtml: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

/** 댓글 단건 응답 타입 — Zod 스키마에서 추론 */
export type CommentResponse = z.infer<typeof commentResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

/** 이슈별 댓글 엔드포인트 경로 */
const commentsPath = (key: string): string => `/api/v1/issues/${key}/comments`

/** 댓글 단건 엔드포인트 경로 (수정·삭제) */
const commentItemPath = (key: string, commentId: string): string =>
  `${commentsPath(key)}/${commentId}`

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 댓글 목록 TanStack Query 키.
 *
 * 매직 문자열 대신 이 함수를 쓴다 — 무효화(invalidate) 지점과 조회 지점이 같은 키를 참조해야
 * 갱신이 누락되지 않는다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns 안정적인 쿼리 키 배열
 */
export const commentQueryKey = (key: string): readonly string[] => ['issues', key, 'comments']

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈의 댓글 목록을 조회한다.
 *
 * GET /api/v1/issues/{key}/comments → 200 { data: CommentResponse[] }
 *
 * 작성 시각 오름차순 전량 반환이며 페이지네이션이 없다 (FR-CO-01 D6 — Worklog 동형).
 * 소프트 삭제된 댓글은 백엔드에서 제외된다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns 댓글 목록 (작성 시각 오름차순). 없으면 빈 배열
 * @throws ApiError(403) VIEW 권한 없음
 * @throws ApiError(404) 이슈 없음
 */
export async function fetchComments(key: string): Promise<CommentResponse[]> {
  const res = await apiFetch(commentsPath(key), { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(z.array(commentResponseSchema)).parse(raw).data
}

/**
 * 이슈에 댓글을 작성한다.
 *
 * POST /api/v1/issues/{key}/comments → 201 { data: CommentResponse }
 *
 * ## 저작자를 보내지 않는다
 * 요청 본문은 `{ body }` **뿐**이다. 작성자는 서버가 인증 주체(actor)로 결정하며,
 * 클라이언트가 제안할 필드가 존재하지 않는다 — 백엔드 `AddCommentRequest` 에도 저작자 필드가 없다.
 * 계약 양쪽에서 같은 방식으로 못 박아 저작자 위조 표면을 만들지 않는다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param body 댓글 본문 (Markdown 원문)
 * @returns 생성된 댓글
 * @throws ApiError(400) 본문 공백 또는 32,000자 초과
 * @throws ApiError(403) UPDATE 권한 없음 · 아카이브된 프로젝트
 * @throws ApiError(404) 이슈 없음
 */
export async function addComment(key: string, body: string): Promise<CommentResponse> {
  const res = await apiFetch(commentsPath(key), {
    method: 'POST',
    body: { body },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(commentResponseSchema).parse(raw).data
}

/**
 * 댓글 본문을 수정한다 — 작성자 본인만 가능하다.
 *
 * PATCH /api/v1/issues/{key}/comments/{commentId} → 200 { data: CommentResponse }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param commentId 수정할 댓글 UUID
 * @param body 새 댓글 본문 (Markdown 원문)
 * @returns 수정된 댓글
 * @throws ApiError(400) 본문 공백 또는 32,000자 초과
 * @throws ApiError(403) UPDATE 권한 없음 또는 타인 댓글
 * @throws ApiError(404) 이슈·댓글 없음
 * @throws ApiError(409) 아카이브된 프로젝트
 */
export async function updateComment(
  key: string,
  commentId: string,
  body: string,
): Promise<CommentResponse> {
  const res = await apiFetch(commentItemPath(key, commentId), {
    method: 'PATCH',
    body: { body },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(commentResponseSchema).parse(raw).data
}

/**
 * 댓글을 소프트 삭제한다 — 작성자 본인 또는 SOFT_DELETE 보유자(모더레이터).
 *
 * DELETE /api/v1/issues/{key}/comments/{commentId} → 204 (본문 없음)
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param commentId 삭제할 댓글 UUID
 * @throws ApiError(403) UPDATE 권한 없음, 또는 작성자도 모더레이터도 아님
 * @throws ApiError(404) 이슈·댓글 없음 (이미 삭제된 댓글 포함)
 * @throws ApiError(409) 아카이브된 프로젝트
 */
export async function deleteComment(key: string, commentId: string): Promise<void> {
  const res = await apiFetch(commentItemPath(key, commentId), { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
