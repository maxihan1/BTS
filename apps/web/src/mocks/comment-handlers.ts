// 댓글 BC MSW 핸들러 — stateful in-memory store (FR-CO-01)
// 이슈별 댓글 목록을 Map으로 관리한다. POST 후 GET 이 방금 만든 댓글을 반환해야
// E2E 가 "작성 → 목록 반영" 을 실제로 검증할 수 있다 (stateless mock 이면 그 단정이 vacuous).
import { http, HttpResponse } from 'msw'
import type { CommentResponse } from '@/api/comments'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (worklog-handlers 패턴 동일)
// ─────────────────────────────────────────────────────────────────────────────

/** RFC4122 v4 UUID를 생성한다. */
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
// 인증 토큰 → 사용자 도출 (worklog-handlers 패턴 동일)
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/** username → userId 매핑 (auth-fixtures AUTH_USERS 정본과 일치, RFC4122 v4 형식) */
const USER_ID_MAP: Readonly<Record<string, string>> = {
  alice: 'a0000000-0000-4000-a000-000000000001',
  bob: 'b0000000-0000-4000-a000-000000000002',
}

/** 토큰 미해석 시 저작자 폴백 — 백엔드는 401 이지만 mock 은 목록 렌더를 막지 않는다 */
const FALLBACK_AUTHOR_ID = 'a0000000-0000-4000-a000-000000000001'

/**
 * Authorization Bearer 헤더에서 현재 사용자 userId를 도출한다.
 *
 * @param request MSW 요청
 * @returns userId 또는 null (미인증·미지 토큰)
 */
function resolveUserIdFromRequest(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return null

  const username = token.slice(MOCK_TOKEN_PREFIX.length)
  return USER_ID_MAP[username] ?? null
}

// ─────────────────────────────────────────────────────────────────────────────
// in-memory store
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 키 → 댓글 목록 (작성 시각 오름차순 유지) */
const commentStore = new Map<string, CommentResponse[]>()

/** 백엔드 본문 상한과 같은 값 — `CommentApplicationService.MAX_BODY_LENGTH` */
const MAX_BODY_LENGTH = 32_000

/**
 * 댓글 store 를 초기화한다 (테스트 간 격리용).
 *
 * E2E 시나리오 토글이 상태를 물려받지 않게 하려면 각 테스트가 명시적으로 호출한다.
 */
export function resetCommentStore(): void {
  commentStore.clear()
}

/**
 * 특정 이슈의 댓글을 미리 심는다 (목록 렌더 시나리오용).
 *
 * @param issueKey 이슈 키
 * @param comments 심을 댓글 목록
 */
export function seedComments(issueKey: string, comments: CommentResponse[]): void {
  commentStore.set(issueKey, [...comments])
}

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 댓글 MSW 핸들러 2종.
 *
 * - `GET /api/v1/issues/:key/comments` → 200 `{ data: CommentResponse[] }` (작성 시각 오름차순)
 * - `POST /api/v1/issues/:key/comments` → 201 `{ data: CommentResponse }`
 *
 * POST 는 백엔드와 같은 본문 검증을 재현한다 — 공백만 / 상한 초과는 400 이다.
 * mock 이 검증을 생략하면 프론트의 비활성 처리·에러 토스트 경로가 테스트되지 않는다.
 * **저작자는 요청 본문이 아니라 토큰에서 도출한다** — 백엔드가 actor 로 결정하는 계약을 재현한다.
 */
export const commentHandlers = [
  http.get('*/api/v1/issues/:key/comments', ({ params }) => {
    const issueKey = String(params['key'])
    return HttpResponse.json({ data: commentStore.get(issueKey) ?? [] })
  }),

  http.post('*/api/v1/issues/:key/comments', async ({ params, request }) => {
    const issueKey = String(params['key'])
    const payload = (await request.json()) as { body?: unknown }
    const body = typeof payload.body === 'string' ? payload.body : ''

    if (body.trim() === '') {
      return HttpResponse.json({ errorCode: 'COMMENT_BODY_BLANK' }, { status: 400 })
    }
    if (body.length > MAX_BODY_LENGTH) {
      return HttpResponse.json({ errorCode: 'COMMENT_BODY_TOO_LONG' }, { status: 400 })
    }

    const now = new Date().toISOString()
    const created: CommentResponse = {
      id: generateUuidV4(),
      // 요청의 어떤 필드도 저작자로 쓰지 않는다 — 토큰(=actor)에서만 도출한다
      authorId: resolveUserIdFromRequest(request) ?? FALLBACK_AUTHOR_ID,
      body,
      bodyHtml: `<p>${body}</p>\n`,
      createdAt: now,
      updatedAt: now,
    }

    const existing = commentStore.get(issueKey) ?? []
    commentStore.set(issueKey, [...existing, created])

    return HttpResponse.json({ data: created }, { status: 201 })
  }),
]
