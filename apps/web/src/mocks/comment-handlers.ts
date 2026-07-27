// 댓글 BC MSW 핸들러 — stateful in-memory store (FR-CO-01 작성·목록 / FR-CO-02 수정·삭제)
// 이슈별 댓글 목록을 Map으로 관리한다. POST·PATCH·DELETE 후 GET 이 그 결과를 반환해야
// E2E 가 "작성/수정/삭제 → 목록 반영" 을 실제로 검증할 수 있다 (stateless mock 이면 그 단정이 vacuous).
//
// ★★ 이 모크의 한계 — 다음 사람이 진짜 렌더러로 착각하지 말 것
// 이 모크는 응답 **형태만** 흉내낸다. `bodyHtml` 은 실제 `MarkdownRenderer.renderSafe` 의 출력이
// 아니라 단순 `<p>` 래핑이다(마크다운 변환도, 정화도 하지 않는다).
// 따라서 **"마크다운이 변환된다" · "스크립트가 차단된다" 를 프론트 테스트로 단언하면 안 된다** —
// 모크를 검증하는 거짓 초록이 된다. 그 두 주장은 백엔드 `CommentControllerIntegrationTest` 가 증명한다.
// 프론트가 여기서 단언해도 되는 것은 "받은 `bodyHtml` 을 그대로 렌더한다" 는 배선뿐이다.
import { http, HttpResponse } from 'msw'
import type { CommentResponse } from '@/api/comments'
import { ALICE_USER_ID, BOB_USER_ID } from './auth-fixtures'
import {
  adminPermissionsFixture,
  memberPermissionsFixture,
  viewerPermissionsFixture,
  type IssuePermissions,
} from './issue-permission-fixtures'

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

/**
 * username → userId 매핑.
 *
 * ★값을 여기에 다시 적지 않고 `auth-fixtures.ts` 의 **정본 상수**를 import 한다.
 * 어긋나면 E2E 가 whoami 사용자와 댓글 작성자를 다른 사람으로 보고 "본인 댓글" 판정이 깨진다
 * (메모리 `e2e-fixture-whoami-userid-alignment`). `worklog-handlers.ts` 와 같은 상수를 쓴다.
 */
const USER_ID_MAP: Readonly<Record<string, string>> = {
  alice: ALICE_USER_ID,
  bob: BOB_USER_ID,
}

/** 토큰 미해석 시 저작자 폴백 — 백엔드는 401 이지만 mock 은 목록 렌더를 막지 않는다 */
const FALLBACK_AUTHOR_ID = ALICE_USER_ID

/**
 * Authorization Bearer 헤더에서 현재 사용자 username을 도출한다.
 *
 * @param request MSW 요청
 * @returns username 또는 null (미인증·형식 불일치)
 */
function resolveUsernameFromRequest(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return null

  return token.slice(MOCK_TOKEN_PREFIX.length)
}

/**
 * Authorization Bearer 헤더에서 현재 사용자 userId를 도출한다.
 *
 * @param request MSW 요청
 * @returns userId 또는 null (미인증·미지 토큰)
 */
function resolveUserIdFromRequest(request: Request): string | null {
  const username = resolveUsernameFromRequest(request)
  if (username === null) return null
  return USER_ID_MAP[username] ?? null
}

/**
 * username → 이슈 권한.
 *
 * `issue-permission-handlers.ts` 와 **같은 fixture 객체**를 참조한다 — 값을 여기에 다시 적으면
 * 권한 조회 API(`/users/me/issue-permissions`)가 돌려주는 권한과 이 핸들러가 실제로 강제하는 권한이
 * 갈라져, "버튼은 보이는데 누르면 403" 같은 모크 내부 모순이 생긴다.
 */
const PERMISSIONS_BY_USERNAME: Readonly<Record<string, IssuePermissions>> = {
  alice: adminPermissionsFixture,
  bob: memberPermissionsFixture,
  // carol 은 읽기 전용 — 이슈 UPDATE 게이트의 **유일한 판별자**다.
  // alice·bob 둘 다 UPDATE=true 라, carol 이 없으면 게이트를 지워도 전량 green 이다.
  carol: viewerPermissionsFixture,
}

/**
 * 요청자가 `SOFT_DELETE`(모더레이터) 권한을 가졌는지 판정한다.
 *
 * 백엔드 `CommentApplicationService.delete` 가 **질의형** `hasPermission` 을 쓰는 것과 같은 이유로
 * 던지지 않고 boolean 만 돌려준다 — `작성자 OR 모더레이터` 의 두 변이 모두 계산될 수 있어야 한다.
 *
 * @param request MSW 요청
 * @returns SOFT_DELETE 보유 여부 (미인증·미지 사용자는 false)
 */
function hasSoftDeletePermission(request: Request): boolean {
  const username = resolveUsernameFromRequest(request)
  if (username === null) return false
  return PERMISSIONS_BY_USERNAME[username]?.SOFT_DELETE ?? false
}

/**
 * 이슈 수준 `UPDATE` 게이트 — 백엔드와 **같은 순서**로 통과시킨다.
 *
 * 백엔드 `CommentApplicationService` 는 댓글을 조회하기 **전에** 이 게이트를 통과시킨다
 * (`create:150`, `update:194` → `:200`, `delete:279` → `:290`). 즉 권한 없는 사용자는
 * **댓글 존재 여부와 무관하게 403** 이다.
 *
 * 모크에 이 게이트가 없던 동안 같은 상황에서 404 가 나가, 「권한이 없다」와 「그런 댓글이 없다」가
 * 뒤바뀌어 있었다. 모크가 백엔드와 다르게 답해도 프론트 테스트는 전량 초록이라
 * 「MSW 가 MSW 와 맞는」 상태가 유지된다 — 그래서 순서까지 맞춰야 한다.
 *
 * 미인증·미지 사용자는 `null` 이라 게이트를 통과시킨다 — 백엔드라면 401 이지만 모크는 흐름을
 * 막지 않는 기존 태도(POST 의 `FALLBACK_AUTHOR_ID`)를 따른다.
 *
 * @param request MSW 요청
 * @returns 게이트 거부 응답, 통과면 `null`
 */
function issueUpdateGate(request: Request): Response | null {
  const username = resolveUsernameFromRequest(request)
  if (username === null) return null
  const allowed = PERMISSIONS_BY_USERNAME[username]?.UPDATE ?? true
  if (allowed) return null
  return HttpResponse.json({ errorCode: 'ISSUE_ACCESS_DENIED' }, { status: 403 })
}

// ─────────────────────────────────────────────────────────────────────────────
// in-memory store
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 키 → 댓글 목록 (작성 시각 오름차순 유지) */
const commentStore = new Map<string, CommentResponse[]>()

/** 백엔드 본문 상한과 같은 값 — `CommentApplicationService.MAX_BODY_LENGTH` */
const MAX_BODY_LENGTH = 32_000

/**
 * 본문 정책 위반 시 errorCode 를, 통과면 null 을 반환한다.
 *
 * 백엔드 `CommentApplicationService.validateBody` 재현. 작성과 수정이 **같은 판정**을 써야 한다 —
 * 한쪽만 검증하면 프론트의 비활성 처리·에러 토스트 경로가 경로마다 다르게 테스트된다.
 *
 * @param body 검사할 본문
 * @returns 위반 errorCode 또는 null
 */
function bodyViolation(body: string): string | null {
  if (body.trim() === '') return 'COMMENT_BODY_BLANK'
  if (body.length > MAX_BODY_LENGTH) return 'COMMENT_BODY_TOO_LONG'
  return null
}

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
 * 댓글 MSW 핸들러 4종.
 *
 * - `GET    /api/v1/issues/:key/comments` → 200 `{ data: CommentResponse[] }` (작성 시각 오름차순)
 * - `POST   /api/v1/issues/:key/comments` → 201 `{ data: CommentResponse }`
 * - `PATCH  /api/v1/issues/:key/comments/:commentId` → 200 `{ data: CommentResponse }`
 * - `DELETE /api/v1/issues/:key/comments/:commentId` → 204 (본문 없음)
 *
 * 쓰기 3종은 백엔드와 같은 본문 검증을 재현한다 — 공백만 / 상한 초과는 400 이다.
 * mock 이 검증을 생략하면 프론트의 비활성 처리·에러 토스트 경로가 테스트되지 않는다.
 * **저작자는 요청 본문이 아니라 토큰에서 도출한다** — 백엔드가 actor 로 결정하는 계약을 재현한다.
 *
 * ## ★ 수정과 삭제의 권한 술어는 다르다 (합치지 말 것)
 * 수정 = `작성자` 뿐, 삭제 = `작성자 OR SOFT_DELETE 보유자` 다
 * (`CommentApplicationService.update`/`delete` KDoc — 모더레이터가 남의 글을 **지울 수는** 있어도
 * **고칠 수는** 없다). 여기서 두 판정을 한 헬퍼로 묶으면 넓은 쪽(삭제)의 술어가 좁은 쪽(수정)에
 * 이식되어, Task 7 의 버튼 게이팅이 실제 백엔드와 어긋난 채 초록이 된다.
 */
export const commentHandlers = [
  http.get('*/api/v1/issues/:key/comments', ({ params }) => {
    const issueKey = String(params['key'])
    return HttpResponse.json({ data: commentStore.get(issueKey) ?? [] })
  }),

  http.post('*/api/v1/issues/:key/comments', async ({ params, request }) => {
    // ★ 이슈 UPDATE 게이트를 **가장 먼저** — 백엔드와 같은 순서다.
    // 리소스 조회보다 뒤에 두면 권한 없는 사용자가 404/403 차이로 댓글 실재를 열거한다.
    const denied = issueUpdateGate(request)
    if (denied !== null) return denied

    const issueKey = String(params['key'])
    const payload = (await request.json()) as { body?: unknown }
    const body = typeof payload.body === 'string' ? payload.body : ''

    const violation = bodyViolation(body)
    if (violation !== null) {
      return HttpResponse.json({ errorCode: violation }, { status: 400 })
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

  http.patch('*/api/v1/issues/:key/comments/:commentId', async ({ params, request }) => {
    // ★ 이슈 UPDATE 게이트를 **가장 먼저** — 백엔드와 같은 순서다.
    // 리소스 조회보다 뒤에 두면 권한 없는 사용자가 404/403 차이로 댓글 실재를 열거한다.
    const denied = issueUpdateGate(request)
    if (denied !== null) return denied

    const issueKey = String(params['key'])
    const commentId = String(params['commentId'])
    const payload = (await request.json()) as { body?: unknown }
    const body = typeof payload.body === 'string' ? payload.body : ''

    const violation = bodyViolation(body)
    if (violation !== null) {
      return HttpResponse.json({ errorCode: violation }, { status: 400 })
    }

    const list = commentStore.get(issueKey) ?? []
    const target = list.find((comment) => comment.id === commentId)
    if (target === undefined) {
      return HttpResponse.json({ errorCode: 'COMMENT_NOT_FOUND' }, { status: 404 })
    }

    // 수정은 작성자 한정 — 모더레이터 우회 없음. 토큰 미해석은 백엔드라면 401 이지만
    // mock 은 흐름을 막지 않는다 (POST 의 FALLBACK_AUTHOR_ID 와 같은 태도).
    const currentUserId = resolveUserIdFromRequest(request)
    if (currentUserId !== null && target.authorId !== currentUserId) {
      return HttpResponse.json({ errorCode: 'ISSUE_ACCESS_DENIED' }, { status: 403 })
    }

    // 본문이 같으면 완전 no-op — updatedAt 을 올리면 화면의 "(수정됨)" 표시가 거짓말을 한다.
    if (target.body === body) {
      return HttpResponse.json({ data: target })
    }

    const updated: CommentResponse = {
      ...target,
      body,
      bodyHtml: `<p>${body}</p>\n`,
      updatedAt: new Date().toISOString(),
    }
    commentStore.set(
      issueKey,
      list.map((comment) => (comment.id === commentId ? updated : comment)),
    )

    return HttpResponse.json({ data: updated })
  }),

  http.delete('*/api/v1/issues/:key/comments/:commentId', ({ params, request }) => {
    // ★ 이슈 UPDATE 게이트를 **가장 먼저** — 백엔드와 같은 순서다.
    // 리소스 조회보다 뒤에 두면 권한 없는 사용자가 404/403 차이로 댓글 실재를 열거한다.
    const denied = issueUpdateGate(request)
    if (denied !== null) return denied

    const issueKey = String(params['key'])
    const commentId = String(params['commentId'])

    const list = commentStore.get(issueKey) ?? []
    const target = list.find((comment) => comment.id === commentId)
    if (target === undefined) {
      // 이미 지워진 댓글의 재삭제도 404 다 — 멱등 204 가 아니다 (스펙 E3).
      return HttpResponse.json({ errorCode: 'COMMENT_NOT_FOUND' }, { status: 404 })
    }

    // 삭제는 작성자 OR 모더레이터. 두 변을 모두 계산한 뒤 최종 거부만 한 번 낸다.
    const currentUserId = resolveUserIdFromRequest(request)
    if (currentUserId !== null) {
      const isAuthor = target.authorId === currentUserId
      const isModerator = hasSoftDeletePermission(request)
      if (!isAuthor && !isModerator) {
        return HttpResponse.json({ errorCode: 'ISSUE_ACCESS_DENIED' }, { status: 403 })
      }
    }

    // 소프트 삭제 시뮬 — 목록에서 제거한다 (백엔드도 삭제분을 목록에서 제외한다).
    commentStore.set(
      issueKey,
      list.filter((comment) => comment.id !== commentId),
    )

    return new HttpResponse(null, { status: 204 })
  }),
]
