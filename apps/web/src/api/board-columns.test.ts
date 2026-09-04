// 보드 컬럼 관리 API 클라이언트 단위 테스트 — 요청 바디 계약 + Zod 응답 스키마 (부채 177 Task 3)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  columnMetaSchema,
  createColumn,
  deleteColumn,
  updateColumn,
  replaceColumnStates,
  reorderColumns,
} from './board-columns'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — Zod v4 는 RFC4122 형식 UUID 만 통과시킨다
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const COLUMN_ID = 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891'
const COLUMN_ID_2 = 'c3d4e5f6-a7b8-4012-9cde-f01234567892'

/** 백엔드 `ColumnMetaResponse` 모양의 최소 픽스처. */
const columnMeta = {
  columnId: COLUMN_ID,
  states: [{ key: 'open', name: '열림', category: 'TODO' }],
  name: '진행 중',
  category: 'IN_PROGRESS',
  displayOrder: 1,
  wipLimit: null,
}

const boardMeta = {
  boardId: BOARD_ID,
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  swimlaneField: 'NONE',
  boardType: 'KANBAN',
}

/** 요청 바디를 가로채 돌려주는 핸들러를 세우고, 캡처된 바디를 읽는 getter 를 준다. */
function captureBody(
  method: 'post' | 'patch' | 'put' | 'delete',
  path: string,
  response: unknown,
): () => unknown {
  let captured: unknown
  server.use(
    http[method](path, async ({ request }) => {
      captured = await request.json().catch(() => undefined)
      return HttpResponse.json({ data: response })
    }),
  )
  return () => captured
}

describe('columnMetaSchema — 응답 계약', () => {
  it('T-BC-1: wipLimit=null 컬럼을 파싱한다 (무제한)', () => {
    const parsed = columnMetaSchema.parse(columnMeta)
    expect(parsed.wipLimit).toBeNull()
  })

  it('T-BC-2: wipLimit 필드가 아예 없으면 파싱을 거부한다', () => {
    // 「무제한」은 명시 null 이지 필드 부재가 아니다. 부재를 통과시키면 백엔드가 필드를
    // 빠뜨려도 화면이 조용히 무제한으로 그린다.
    const withoutWipLimit: Record<string, unknown> = { ...columnMeta }
    delete withoutWipLimit.wipLimit
    expect(() => columnMetaSchema.parse(withoutWipLimit)).toThrow()
  })

  it('T-BC-3: states 가 빈 배열인 상태 0개 컬럼을 파싱한다', () => {
    const parsed = columnMetaSchema.parse({ ...columnMeta, states: [] })
    expect(parsed.states).toEqual([])
  })
})

describe('createColumn — 컬럼 생성 (R6 · J23 · X1)', () => {
  it('T-BC-4: name 과 빈 stateKeys 만 보내고 category 는 보내지 않는다', async () => {
    const body = captureBody('post', `*/api/v1/boards/${BOARD_ID}/columns`, columnMeta)

    await createColumn(BOARD_ID, '검수')

    // ★category 를 보내지 않는 것이 편차 X1 의 실체다. 이 단언을 지우면 편차가 문서에만 남는다.
    expect(body()).toEqual({ name: '검수', stateKeys: [] })
  })

  it('T-BC-5: 400 이면 ApiError 를 던진다', async () => {
    server.use(
      http.post(`*/api/v1/boards/${BOARD_ID}/columns`, () =>
        HttpResponse.json({ errorCode: 'AGILE_VALIDATION_FAILED' }, { status: 400 }),
      ),
    )
    await expect(createColumn(BOARD_ID, '   ')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('updateColumn — 부분 갱신 (R8 · R9 · J24 · J29)', () => {
  it('T-BC-6: 이름만 주면 wipLimit 키를 아예 싣지 않는다', async () => {
    const body = captureBody('patch', `*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}`, columnMeta)

    await updateColumn(BOARD_ID, COLUMN_ID, { name: '검수' })

    // ★키가 없어야 백엔드 3-state 가 「무변경」으로 읽는다. wipLimit:null 을 실으면 해제된다.
    expect(body()).toEqual({ name: '검수' })
    expect(body()).not.toHaveProperty('wipLimit')
  })

  it('T-BC-7: wipLimit=null 을 명시하면 그 키를 실어 해제를 요청한다', async () => {
    const body = captureBody('patch', `*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}`, columnMeta)

    await updateColumn(BOARD_ID, COLUMN_ID, { wipLimit: null })

    // ★T-BC-6 과 짝이다. 둘을 한 테스트로 합치면 「키를 항상 싣는다」로 고쳐도 한쪽이 통과한다.
    expect(body()).toEqual({ wipLimit: null })
  })

  it('T-BC-8: 이름과 wipLimit 을 함께 주면 둘 다 싣는다', async () => {
    const body = captureBody('patch', `*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}`, columnMeta)

    await updateColumn(BOARD_ID, COLUMN_ID, { name: '검수', wipLimit: 3 })

    expect(body()).toEqual({ name: '검수', wipLimit: 3 })
  })
})

describe('replaceColumnStates — 집합 통째 교체 (R5 · J27)', () => {
  it('T-BC-9: 상태 키 전량을 stateKeys 로 보낸다', async () => {
    const body = captureBody(
      'put',
      `*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}/states`,
      columnMeta,
    )

    await replaceColumnStates(BOARD_ID, COLUMN_ID, ['open', 'in-progress'])

    expect(body()).toEqual({ stateKeys: ['open', 'in-progress'] })
  })

  it('T-BC-10: 빈 배열도 그대로 보낸다 — 상태 0개 컬럼이 유효하다', async () => {
    const body = captureBody(
      'put',
      `*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}/states`,
      { ...columnMeta, states: [] },
    )

    await replaceColumnStates(BOARD_ID, COLUMN_ID, [])

    expect(body()).toEqual({ stateKeys: [] })
  })

  it('T-BC-11: 409 면 ApiError 로 상태 코드를 보존한다', async () => {
    server.use(
      http.put(`*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}/states`, () =>
        HttpResponse.json({ errorCode: 'AGILE_STATE_ALREADY_MAPPED' }, { status: 409 }),
      ),
    )

    // 화면이 409 만 전용 문구로 갈라내려면 상태 코드가 살아 있어야 한다(스펙 E3 · G2).
    await expect(replaceColumnStates(BOARD_ID, COLUMN_ID, ['open'])).rejects.toMatchObject({
      status: 409,
    })
  })
})

describe('reorderColumns — 순서 통째 교체 (R10 · J25)', () => {
  it('T-BC-12: columnIds 전량을 순서대로 보낸다', async () => {
    const body = captureBody('put', `*/api/v1/boards/${BOARD_ID}/columns/order`, boardMeta)

    await reorderColumns(BOARD_ID, [COLUMN_ID_2, COLUMN_ID])

    expect(body()).toEqual({ columnIds: [COLUMN_ID_2, COLUMN_ID] })
  })

  it('T-BC-13: 400(집합 불일치)이면 ApiError 를 던진다', async () => {
    server.use(
      http.put(`*/api/v1/boards/${BOARD_ID}/columns/order`, () =>
        HttpResponse.json({ errorCode: 'AGILE_VALIDATION_FAILED' }, { status: 400 }),
      ),
    )
    await expect(reorderColumns(BOARD_ID, [COLUMN_ID])).rejects.toBeInstanceOf(ApiError)
  })
})

describe('deleteColumn — 삭제와 폭발 반경 (R7 · J26)', () => {
  it('T-BC-14: removedCardCount 를 돌려준다', async () => {
    server.use(
      http.delete(`*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}`, () =>
        HttpResponse.json({ data: { removedCardCount: 2 } }),
      ),
    )

    await expect(deleteColumn(BOARD_ID, COLUMN_ID)).resolves.toEqual({ removedCardCount: 2 })
  })

  it('T-BC-15: 404 면 ApiError 를 던진다', async () => {
    server.use(
      http.delete(`*/api/v1/boards/${BOARD_ID}/columns/${COLUMN_ID}`, () =>
        HttpResponse.json({ errorCode: 'AGILE_BOARD_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(deleteColumn(BOARD_ID, COLUMN_ID)).rejects.toBeInstanceOf(ApiError)
  })
})
