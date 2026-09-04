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
 * ## 상태 코드 (백엔드 `CommentController.listComments` 실측)
 * | 코드 | 조건 |
 * |---|---|
 * | 200 | 성공 — `{ data: CommentResponse[] }` |
 * | 401 | 미인증 (apiFetch 가 refresh 후 1회 재시도) |
 * | 403 | VIEW 권한 없음 |
 * | 404 | 이슈 미존재·소프트 삭제 |
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
 * ## 상태 코드 (백엔드 `CommentController.addComment` 실측)
 * | 코드 | 조건 |
 * |---|---|
 * | 201 | 성공 — `{ data: CommentResponse }` |
 * | 400 | 본문 공백만 · 32,000자 초과 · `body` 필드 누락 |
 * | 401 | 미인증 |
 * | 403 | UPDATE 권한 없음 |
 * | 404 | 이슈 미존재·소프트 삭제 |
 * | 409 | 아카이브된 프로젝트 |
 *
 * 아카이브 프로젝트를 403 으로 적어뒀던 오기를 정정한다 — 실제 매핑은 **409** 다
 * (`ProjectArchivedExceptionHandler`, 백엔드 `CommentController` KDoc 도 같은 정정을 담고 있다).
 * 403 으로 알고 있으면 UI 가 "권한 없음" 문구를 아카이브 상황에 띄운다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param body 댓글 본문 (Markdown 원문)
 * @returns 생성된 댓글
 * @throws ApiError(400) 본문 공백 또는 32,000자 초과
 * @throws ApiError(403) UPDATE 권한 없음
 * @throws ApiError(404) 이슈 없음
 * @throws ApiError(409) 아카이브된 프로젝트
 */
export async function addComment(
  key: string,
  body: string,
  bodyHtml?: string,
): Promise<CommentResponse> {
  // ★`body`(평문/마크다운)를 **함께** 보낸다 — 검색·알림·이력이 평문 표현을 쓰기 때문이다.
  //   `bodyHtml` 은 서버가 정화해 `body_html` 에 저장한다(V039). 본문(description)과 달리
  //   둘이 배타가 아니다 — 댓글은 원문이 서식과 별개로 계속 쓰인다.
  const res = await apiFetch(commentsPath(key), {
    method: 'POST',
    body: bodyHtml === undefined ? { body } : { body, bodyHtml },
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
 * ## 저작자를 보내지 않는다 ([addComment] 와 동일)
 * 요청 본문은 `{ body }` **뿐**이다. 작성자는 서버가 인증 주체(actor)로 결정하며, 클라이언트가
 * 제안할 필드가 존재하지 않는다 — 백엔드 `UpdateCommentRequest` 에도 저작자 필드가 없다.
 * 수정 권한 판정도 "저장된 댓글의 작성자 == actor" 로 서버가 내리므로, 프론트가 보낼 수 있는
 * 저작자 관련 정보는 아무것도 없다. 필드를 두고 무시하는 것과 필드가 없는 것은 다르다.
 *
 * ## 모더레이터도 남의 댓글은 수정하지 못한다
 * 삭제는 `작성자 OR SOFT_DELETE 보유자`지만 수정은 `작성자` 뿐이다 — 술어가 다르다
 * (백엔드 `CommentApplicationService.update` KDoc). 화면에서 수정 버튼 노출 조건을 삭제 버튼과
 * 공유하면 모더레이터에게 눌러도 403 나는 버튼이 보인다.
 *
 * ## 본문이 기존과 같으면 서버가 no-op 으로 처리한다
 * 저장도 이력 기록도 건너뛰고 `updatedAt` 도 그대로 반환한다. 프론트가 `updatedAt != createdAt` 로
 * "(수정됨)" 표시를 결정하므로, 서버가 이 no-op 을 지키는 덕에 그 표시가 거짓말하지 않는다.
 *
 * ## 상태 코드 (백엔드 `CommentController.updateComment` 실측)
 * | 코드 | 조건 |
 * |---|---|
 * | 200 | 성공 — `{ data: CommentResponse }` |
 * | 400 | 본문 공백만 · 32,000자 초과 · `body` 필드 누락 · 비-UUID `commentId` · 깨진 JSON |
 * | 401 | 미인증 |
 * | 403 | UPDATE 권한 없음 · **타인 댓글** (PROJECT_ADMIN 도 거부) |
 * | 404 | 이슈·댓글 미존재 · 이슈-댓글 불일치 · 이미 삭제됨 |
 * | 409 | 아카이브된 프로젝트 |
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param commentId 수정할 댓글 UUID
 * @param body 새 댓글 본문 (Markdown 원문)
 * @returns 수정된 댓글. 본문이 기존과 같으면 갱신되지 않은 기존 댓글
 * @throws ApiError(400) 본문 공백 또는 32,000자 초과
 * @throws ApiError(403) UPDATE 권한 없음 또는 타인 댓글
 * @throws ApiError(404) 이슈·댓글 없음
 * @throws ApiError(409) 아카이브된 프로젝트
 */
export async function updateComment(
  key: string,
  commentId: string,
  body: string,
  bodyHtml?: string,
): Promise<CommentResponse> {
  // `bodyHtml` 규칙은 [addComment] 와 같다 — 평문과 함께 보낸다.
  const res = await apiFetch(commentItemPath(key, commentId), {
    method: 'PATCH',
    body: bodyHtml === undefined ? { body } : { body, bodyHtml },
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
 * ## 204 라 응답을 파싱하지 않는다
 * 성공 응답에 본문이 없으므로 `res.json()` 을 부르면 그 자체가 `SyntaxError` 다. 실패 분기에서만
 * 오류 본문을 읽는다. Zod 스키마를 태울 대상도 없다.
 *
 * ## 재삭제는 멱등 204 가 아니라 404 다
 * 이미 삭제된 댓글을 다시 지우면 404 다 (스펙 E3 — 활성 댓글만 대상). 화면에서 "이미 없어진 것이니
 * 성공으로 치자" 는 처리를 하려면 호출자가 404 를 명시적으로 흡수해야 한다.
 *
 * ## 상태 코드 (백엔드 `CommentController.deleteComment` 실측)
 * | 코드 | 조건 |
 * |---|---|
 * | 204 | 성공 — 본문 없음 |
 * | 400 | 비-UUID `commentId` |
 * | 401 | 미인증 |
 * | 403 | UPDATE 권한 없음 · 작성자도 SOFT_DELETE 보유자도 아님 |
 * | 404 | 이슈·댓글 미존재 · 이슈-댓글 불일치 · 이미 삭제됨 |
 * | 409 | 아카이브된 프로젝트 |
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
