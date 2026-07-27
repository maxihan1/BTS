// 댓글(issue-tracking BC) REST API 클라이언트 단위 테스트 — FR-CO-01 T5 / FR-CO-02 T6
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { ZodError } from 'zod'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/auth/authStore'
import { commentHandlers, resetCommentStore, seedComments } from '@/mocks/comment-handlers'
import {
  commentResponseSchema,
  fetchComments,
  addComment,
  updateComment,
  deleteComment,
  commentQueryKey,
  type CommentResponse,
} from './comments'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const commentFixture: CommentResponse = {
  id: '11111111-1111-4111-a111-111111111111',
  authorId: '22222222-2222-4222-a222-222222222222',
  body: '확인했습니다.',
  bodyHtml: '<p>확인했습니다.</p>\n',
  createdAt: '2026-07-27T09:00:00Z',
  updatedAt: '2026-07-27T09:00:00Z',
}

const ISSUE_KEY = 'ATLAS-1'
const LIST_URL = `*/api/v1/issues/${ISSUE_KEY}/comments`

describe('commentResponseSchema', () => {
  it('백엔드 CommentResponse 6필드를 파싱한다', () => {
    expect(commentResponseSchema.parse(commentFixture)).toEqual(commentFixture)
  })

  it('bodyHtml 누락 시 파싱에 실패한다 — 백엔드가 항상 채우는 필드다', () => {
    const { id, authorId, body, createdAt, updatedAt } = commentFixture
    expect(() =>
      commentResponseSchema.parse({ id, authorId, body, createdAt, updatedAt }),
    ).toThrow()
  })

  it('id 가 UUID 형식이 아니면 파싱에 실패한다', () => {
    expect(() => commentResponseSchema.parse({ ...commentFixture, id: 'not-a-uuid' })).toThrow()
  })
})

describe('commentQueryKey', () => {
  it('이슈 키를 포함한 안정적인 쿼리 키를 만든다', () => {
    expect(commentQueryKey(ISSUE_KEY)).toEqual(['issues', ISSUE_KEY, 'comments'])
  })
})

describe('fetchComments', () => {
  beforeEach(() => {
    server.use(
      http.get(LIST_URL, () => HttpResponse.json({ data: [commentFixture] })),
    )
  })

  it('GET 으로 댓글 목록을 조회한다', async () => {
    await expect(fetchComments(ISSUE_KEY)).resolves.toEqual([commentFixture])
  })

  it('빈 목록도 정상 반환한다', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json({ data: [] })))
    await expect(fetchComments(ISSUE_KEY)).resolves.toEqual([])
  })

  it('403 이면 ApiError 를 던진다', async () => {
    server.use(
      http.get(LIST_URL, () =>
        HttpResponse.json({ errorCode: 'ISSUE_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    await expect(fetchComments(ISSUE_KEY)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('addComment', () => {
  it('POST 로 body 만 전송한다 — 저작자 필드를 보내지 않는다', async () => {
    let sentBody: unknown
    server.use(
      http.post(LIST_URL, async ({ request }) => {
        sentBody = await request.json()
        return HttpResponse.json({ data: commentFixture }, { status: 201 })
      }),
    )

    await addComment(ISSUE_KEY, '확인했습니다.')

    // ★ 저작자는 서버가 actor 로 결정한다. 클라이언트가 제안하는 필드가 있으면 안 된다.
    expect(sentBody).toEqual({ body: '확인했습니다.' })
    expect(Object.keys(sentBody as object)).toEqual(['body'])
  })

  it('생성된 댓글을 반환한다', async () => {
    server.use(
      http.post(LIST_URL, () => HttpResponse.json({ data: commentFixture }, { status: 201 })),
    )
    await expect(addComment(ISSUE_KEY, '확인했습니다.')).resolves.toEqual(commentFixture)
  })

  it('400 이면 ApiError 를 던진다 (본문 공백·길이 초과)', async () => {
    server.use(
      http.post(LIST_URL, () =>
        HttpResponse.json({ errorCode: 'COMMENT_BODY_TOO_LONG' }, { status: 400 }),
      ),
    )
    await expect(addComment(ISSUE_KEY, 'x')).rejects.toBeInstanceOf(ApiError)
  })

  it('403 이면 ApiError 를 던진다 (UPDATE 권한 없음)', async () => {
    server.use(
      http.post(LIST_URL, () =>
        HttpResponse.json({ errorCode: 'ISSUE_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    await expect(addComment(ISSUE_KEY, 'x')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-CO-02 — updateComment / deleteComment
// ─────────────────────────────────────────────────────────────────────────────

const COMMENT_ID = '33333333-3333-4333-a333-333333333333'
const ITEM_PATH = `/api/v1/issues/${ISSUE_KEY}/comments/${COMMENT_ID}`
const ITEM_URL = `*${ITEM_PATH}`

describe('updateComment', () => {
  it('PATCH 로 올바른 경로에 요청한다', async () => {
    let method = ''
    let pathname = ''
    server.use(
      http.patch(ITEM_URL, ({ request }) => {
        method = request.method
        pathname = new URL(request.url).pathname
        return HttpResponse.json({ data: commentFixture })
      }),
    )

    await updateComment(ISSUE_KEY, COMMENT_ID, '수정된 본문')

    expect(method).toBe('PATCH')
    expect(pathname).toBe(ITEM_PATH)
  })

  it('{ body } 만 전송한다 — 저작자 필드를 보내지 않는다', async () => {
    let sentBody: unknown
    server.use(
      http.patch(ITEM_URL, async ({ request }) => {
        sentBody = await request.json()
        return HttpResponse.json({ data: commentFixture })
      }),
    )

    await updateComment(ISSUE_KEY, COMMENT_ID, '수정된 본문')

    // ★ 수정에도 저작자를 제안할 창구가 없다 — 백엔드 UpdateCommentRequest 에도 저작자 필드가 없다.
    expect(sentBody).toEqual({ body: '수정된 본문' })
    expect(Object.keys(sentBody as object)).toEqual(['body'])
  })

  it('응답을 commentResponseSchema 로 파싱해 반환한다', async () => {
    const updated: CommentResponse = {
      ...commentFixture,
      body: '수정된 본문',
      bodyHtml: '<p>수정된 본문</p>\n',
      updatedAt: '2026-07-27T10:00:00Z',
    }
    server.use(http.patch(ITEM_URL, () => HttpResponse.json({ data: updated })))

    await expect(updateComment(ISSUE_KEY, COMMENT_ID, '수정된 본문')).resolves.toEqual(updated)
  })

  it('스키마 불일치 응답에 ZodError 를 던진다 (bodyHtml 누락)', async () => {
    const { id, authorId, body, createdAt, updatedAt } = commentFixture
    server.use(
      http.patch(ITEM_URL, () =>
        HttpResponse.json({ data: { id, authorId, body, createdAt, updatedAt } }),
      ),
    )

    await expect(updateComment(ISSUE_KEY, COMMENT_ID, 'x')).rejects.toBeInstanceOf(ZodError)
  })
})

describe('deleteComment', () => {
  it('DELETE 로 올바른 경로에 요청한다', async () => {
    let method = ''
    let pathname = ''
    server.use(
      http.delete(ITEM_URL, ({ request }) => {
        method = request.method
        pathname = new URL(request.url).pathname
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await deleteComment(ISSUE_KEY, COMMENT_ID)

    expect(method).toBe('DELETE')
    expect(pathname).toBe(ITEM_PATH)
  })

  it('204 무본문 응답을 파싱하지 않는다', async () => {
    server.use(http.delete(ITEM_URL, () => new HttpResponse(null, { status: 204 })))

    // 본문 없는 응답에 res.json() 을 부르면 SyntaxError 가 난다 — 파싱을 건너뛰어야만 통과한다.
    await expect(deleteComment(ISSUE_KEY, COMMENT_ID)).resolves.toBeUndefined()
  })
})

describe('updateComment·deleteComment 오류 전파', () => {
  it('403·404 응답의 에러가 호출자에게 전파된다', async () => {
    server.use(
      http.patch(ITEM_URL, () =>
        HttpResponse.json({ errorCode: 'ISSUE_ACCESS_DENIED' }, { status: 403 }),
      ),
      http.delete(ITEM_URL, () =>
        HttpResponse.json({ errorCode: 'COMMENT_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(updateComment(ISSUE_KEY, COMMENT_ID, 'x')).rejects.toBeInstanceOf(ApiError)
    await expect(updateComment(ISSUE_KEY, COMMENT_ID, 'x')).rejects.toMatchObject({ status: 403 })
    await expect(deleteComment(ISSUE_KEY, COMMENT_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(deleteComment(ISSUE_KEY, COMMENT_ID)).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — stateful 수정·삭제 (Task 7 UI 가 의존하는 계약)
//
// 단위 테스트 서버(`src/test/server.ts`)는 `mocks/handlers.ts` 를 싣지 않으므로 `commentHandlers`
// 를 명시적으로 주입한다. **새 `setupServer` 를 만들지 않는다** — 이중 setupServer 는 E2E 에서
// 한쪽 핸들러가 가려지는 사고를 낸다(메모리 `msw-dual-setupserver-double-dispatch`).
//
// 여기서 검증하는 것은 **권한 술어 행렬**이다. 백엔드는 수정 `= UPDATE AND 작성자`,
// 삭제 `= UPDATE AND (작성자 OR SOFT_DELETE)` 로 술어가 서로 다르다
// (`CommentApplicationService.update`/`delete` KDoc). 모크가 이 차이를 뭉개면 Task 7 의
// 버튼 노출 게이팅이 실제와 어긋난 채 초록이 된다.
// ─────────────────────────────────────────────────────────────────────────────

describe('commentHandlers (MSW stateful mock)', () => {
  /** alice = ADMIN (SOFT_DELETE 보유) — `issue-permission-fixtures.adminPermissionsFixture` */
  const ALICE_ID = '00000000-0000-4000-8000-000000000001'
  /** bob = MEMBER (SOFT_DELETE 미보유) — `issue-permission-fixtures.memberPermissionsFixture` */
  const BOB_ID = '00000000-0000-4000-8000-000000000002'

  const aliceComment: CommentResponse = { ...commentFixture, authorId: ALICE_ID }
  const bobComment: CommentResponse = { ...commentFixture, authorId: BOB_ID }

  beforeEach(() => {
    resetCommentStore()
    server.use(...commentHandlers)
  })

  afterEach(() => {
    resetCommentStore()
    useAuthStore.getState().setAccessToken(null)
  })

  it('작성자의 수정이 이후 목록 조회에 반영된다', async () => {
    seedComments(ISSUE_KEY, [aliceComment])
    useAuthStore.getState().setAccessToken('mock-access-token-alice')

    const updated = await updateComment(ISSUE_KEY, aliceComment.id, '고쳤습니다.')
    expect(updated.body).toBe('고쳤습니다.')

    const list = await fetchComments(ISSUE_KEY)
    expect(list).toHaveLength(1)
    expect(list[0]?.body).toBe('고쳤습니다.')
  })

  it('본문이 같으면 updatedAt 을 갱신하지 않는다 — "(수정됨)" 표시가 거짓말하지 않도록', async () => {
    seedComments(ISSUE_KEY, [aliceComment])
    useAuthStore.getState().setAccessToken('mock-access-token-alice')

    const updated = await updateComment(ISSUE_KEY, aliceComment.id, aliceComment.body)

    expect(updated.updatedAt).toBe(aliceComment.updatedAt)
  })

  it('공백 본문 수정은 400 이다', async () => {
    seedComments(ISSUE_KEY, [aliceComment])
    useAuthStore.getState().setAccessToken('mock-access-token-alice')

    await expect(updateComment(ISSUE_KEY, aliceComment.id, '   ')).rejects.toMatchObject({
      status: 400,
    })
  })

  it('모더레이터(alice)여도 타인 댓글 수정은 403 — 삭제와 술어가 다르다', async () => {
    seedComments(ISSUE_KEY, [bobComment])
    useAuthStore.getState().setAccessToken('mock-access-token-alice')

    await expect(updateComment(ISSUE_KEY, bobComment.id, '남의 글 고치기')).rejects.toMatchObject({
      status: 403,
    })
  })

  it('작성자의 삭제가 이후 목록 조회에 반영된다', async () => {
    seedComments(ISSUE_KEY, [aliceComment])
    useAuthStore.getState().setAccessToken('mock-access-token-alice')

    await deleteComment(ISSUE_KEY, aliceComment.id)

    await expect(fetchComments(ISSUE_KEY)).resolves.toEqual([])
  })

  it('모더레이터(alice)는 타인 댓글을 삭제할 수 있다 — SOFT_DELETE 보유', async () => {
    seedComments(ISSUE_KEY, [bobComment])
    useAuthStore.getState().setAccessToken('mock-access-token-alice')

    await deleteComment(ISSUE_KEY, bobComment.id)

    await expect(fetchComments(ISSUE_KEY)).resolves.toEqual([])
  })

  it('SOFT_DELETE 미보유자(bob)의 타인 댓글 삭제는 403 — Task 7 삭제 버튼 게이팅 경로', async () => {
    seedComments(ISSUE_KEY, [aliceComment])
    useAuthStore.getState().setAccessToken('mock-access-token-bob')

    await expect(deleteComment(ISSUE_KEY, aliceComment.id)).rejects.toMatchObject({ status: 403 })
    // 거부된 삭제는 store 를 건드리지 않는다
    await expect(fetchComments(ISSUE_KEY)).resolves.toHaveLength(1)
  })

  it('존재하지 않는 댓글의 수정·삭제는 404 다', async () => {
    seedComments(ISSUE_KEY, [aliceComment])
    useAuthStore.getState().setAccessToken('mock-access-token-alice')

    await expect(updateComment(ISSUE_KEY, COMMENT_ID, 'x')).rejects.toMatchObject({ status: 404 })
    await expect(deleteComment(ISSUE_KEY, COMMENT_ID)).rejects.toMatchObject({ status: 404 })
  })
})
