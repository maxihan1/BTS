// 댓글(issue-tracking BC) REST API 클라이언트 단위 테스트 — FR-CO-01 T5
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  commentResponseSchema,
  fetchComments,
  addComment,
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
    const { bodyHtml: _omitted, ...withoutBodyHtml } = commentFixture
    expect(() => commentResponseSchema.parse(withoutBodyHtml)).toThrow()
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
