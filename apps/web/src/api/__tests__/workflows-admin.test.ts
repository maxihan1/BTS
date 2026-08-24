// 워크플로우 관리 API client 단위 테스트 — 백엔드 DTO 계약과 1:1 대조
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  statusSchema,
  fetchStatuses,
  createStatus,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow,
  duplicateWorkflow,
  addWorkflowStatus,
  removeWorkflowStatus,
  reorderWorkflowStatuses,
  createTransition,
  updateTransition,
  deleteTransition,
  WorkflowAdminApiError,
} from '../workflows-admin'

const STATUS_ID = '11111111-1111-4111-8111-111111111111'
const TRANSITION_ID = '22222222-2222-4222-8222-222222222222'

/** 백엔드 `StatusResponse` 6필드 — id·key·name·description·category·isSystem */
const statusFixture = {
  id: STATUS_ID,
  key: 'todo',
  name: '할 일',
  description: null,
  category: 'TODO',
  isSystem: true,
}

describe('statusSchema — 백엔드 StatusResponse 계약', () => {
  it('description 이 null 이어도 통과한다 (Kotlin String?)', () => {
    expect(statusSchema.parse(statusFixture).description).toBeNull()
  })

  it('description 이 문자열이어도 통과한다', () => {
    expect(statusSchema.parse({ ...statusFixture, description: '설명' }).description).toBe('설명')
  })

  it('알 수 없는 필드는 거부한다 (.strict — 계약 봉인)', () => {
    expect(() => statusSchema.parse({ ...statusFixture, extra: 1 })).toThrow()
  })
})

describe('fetchStatuses', () => {
  it('GET /api/v1/statuses 의 data 배열을 준다', async () => {
    server.use(http.get('/api/v1/statuses', () => HttpResponse.json({ data: [statusFixture] })))
    const list = await fetchStatuses()
    expect(list).toHaveLength(1)
    expect(list[0]?.key).toBe('todo')
  })
})

describe('createStatus', () => {
  it('key·name·description·category 를 보내고 만들어진 id 를 받는다', async () => {
    let sent: unknown
    server.use(
      http.post('/api/v1/statuses', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ data: { id: STATUS_ID, key: 'review' } }, { status: 201 })
      }),
    )
    const created = await createStatus({ key: 'review', name: '검토', description: null, category: 'IN_PROGRESS' })
    expect(created.id).toBe(STATUS_ID)
    expect(sent).toEqual({ key: 'review', name: '검토', description: null, category: 'IN_PROGRESS' })
  })
})

describe('createWorkflow', () => {
  it('statuses 를 함께 보낸다 — 비면 백엔드가 400 이다', async () => {
    let sent: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/workflows', async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ data: { key: 'custom' } }, { status: 201 })
      }),
    )
    const created = await createWorkflow({
      key: 'custom',
      name: '커스텀',
      description: null,
      statuses: [{ key: 'todo', name: '할 일', category: 'TODO', displayOrder: 0 }],
    })
    expect(created.key).toBe('custom')
    expect(sent['statuses']).toHaveLength(1)
  })
})

describe('updateWorkflow — 키 불변 계약', () => {
  it('바디에 key 를 싣지 않는다 (백엔드 UpdateWorkflowRequest 에 key 가 없다)', async () => {
    let sent: Record<string, unknown> = {}
    server.use(
      http.put('/api/v1/workflows/custom', async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ data: null })
      }),
    )
    await updateWorkflow('custom', { name: '새 이름', description: null })
    expect(sent).toEqual({ name: '새 이름', description: null })
    expect(sent).not.toHaveProperty('key')
  })
})

describe('deleteWorkflow / duplicateWorkflow', () => {
  it('삭제는 204 를 본문 없이 받는다', async () => {
    server.use(http.delete('/api/v1/workflows/custom', () => new HttpResponse(null, { status: 204 })))
    await expect(deleteWorkflow('custom')).resolves.toBeUndefined()
  })

  it('복제는 새 key·name 을 보내고 새 key 를 받는다', async () => {
    server.use(
      http.post('/api/v1/workflows/custom/duplicate', () =>
        HttpResponse.json({ data: { key: 'custom-copy' } }, { status: 201 }),
      ),
    )
    expect((await duplicateWorkflow('custom', { key: 'custom-copy', name: '사본' })).key).toBe('custom-copy')
  })
})

describe('상태 편성 — 추가·제거·순서', () => {
  it('추가는 statusId·displayOrder 를 보내고 201 을 본문 없이 받는다', async () => {
    let sent: unknown
    server.use(
      http.post('/api/v1/workflows/custom/statuses', async ({ request }) => {
        sent = await request.json()
        return new HttpResponse(null, { status: 201 })
      }),
    )
    await addWorkflowStatus('custom', { statusId: STATUS_ID, displayOrder: 2 })
    expect(sent).toEqual({ statusId: STATUS_ID, displayOrder: 2 })
  })

  it('제거는 204 를 본문 없이 받는다', async () => {
    server.use(
      http.delete(`/api/v1/workflows/custom/statuses/${STATUS_ID}`, () => new HttpResponse(null, { status: 204 })),
    )
    await expect(removeWorkflowStatus('custom', STATUS_ID)).resolves.toBeUndefined()
  })

  it('순서변경은 그 워크플로우 상태 전부를 statusIds 로 보낸다', async () => {
    let sent: unknown
    server.use(
      http.put('/api/v1/workflows/custom/statuses/order', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ data: null })
      }),
    )
    await reorderWorkflowStatuses('custom', [STATUS_ID, TRANSITION_ID])
    expect(sent).toEqual({ statusIds: [STATUS_ID, TRANSITION_ID] })
  })
})

describe('전환 정의 CRUD', () => {
  const transitionResponse = {
    id: TRANSITION_ID,
    key: 'todo__done',
    kind: 'NORMAL',
    fromStateKey: 'todo',
    toStateKey: 'done',
    name: '완료 처리',
  }

  it('생성은 fromStatusKey·toStatusKey 로 보내고 fromStateKey·toStateKey 로 받는다', async () => {
    let sent: unknown
    server.use(
      http.post('/api/v1/workflows/custom/transitions', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ data: transitionResponse }, { status: 201 })
      }),
    )
    const created = await createTransition('custom', {
      fromStatusKey: 'todo',
      toStatusKey: 'done',
      name: '완료 처리',
      kind: 'NORMAL',
    })
    expect(sent).toEqual({ fromStatusKey: 'todo', toStatusKey: 'done', name: '완료 처리', kind: 'NORMAL' })
    expect(created.id).toBe(TRANSITION_ID)
    expect(created.fromStateKey).toBe('todo')
  })

  it('GLOBAL 은 fromStatusKey 를 아예 싣지 않는다 (실으면 백엔드 400)', async () => {
    let sent: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/workflows/custom/transitions', async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ data: { ...transitionResponse, kind: 'GLOBAL', fromStateKey: null } }, { status: 201 })
      }),
    )
    await createTransition('custom', { fromStatusKey: null, toStatusKey: 'done', name: '즉시 완료', kind: 'GLOBAL' })
    expect(sent).not.toHaveProperty('fromStatusKey')
  })

  it('수정은 표현 전체를 갈아 끼운다', async () => {
    server.use(
      http.put(`/api/v1/workflows/custom/transitions/${TRANSITION_ID}`, () =>
        HttpResponse.json({ data: { ...transitionResponse, name: '바뀐 이름' } }),
      ),
    )
    expect((await updateTransition('custom', TRANSITION_ID, {
      fromStatusKey: 'todo',
      toStatusKey: 'done',
      name: '바뀐 이름',
      kind: 'NORMAL',
    })).name).toBe('바뀐 이름')
  })

  it('삭제는 204 를 본문 없이 받는다', async () => {
    server.use(
      http.delete(`/api/v1/workflows/custom/transitions/${TRANSITION_ID}`, () => new HttpResponse(null, { status: 204 })),
    )
    await expect(deleteTransition('custom', TRANSITION_ID)).resolves.toBeUndefined()
  })
})

describe('경로 파라미터 인코딩 — confused deputy 차단', () => {
  it('워크플로우 키의 슬래시가 경로 세그먼트를 뚫지 못한다', async () => {
    // `workflowKey` 는 URL 경로에서 그대로 온다. 인코딩이 없으면 `../../users` 가
    // 그대로 박히고 fetch 가 `..` 를 정규화해 **다른 자원**으로 요청이 나간다.
    let hit = false
    server.use(
      http.put('/api/v1/workflows/:key', () => {
        hit = true
        return HttpResponse.json({ data: null })
      }),
      http.put('/api/users/:id', () => HttpResponse.json({ data: null })),
    )
    await updateWorkflow('../../users/victim', { name: 'x', description: null })
    expect(hit).toBe(true)
  })

  it('상태 id 와 전환 id 도 인코딩된다', async () => {
    let seen = ''
    server.use(
      http.delete('/api/v1/workflows/:key/statuses/:statusId', ({ params }) => {
        seen = String(params['statusId'])
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await removeWorkflowStatus('custom', '../evil')
    expect(seen).toBe('../evil')
  })
})

describe('WorkflowAdminApiError — 중첩 봉투 code 보존', () => {
  it('409 의 error.code 를 errorCode 로, error.message 를 detail 로 남긴다', async () => {
    // 백엔드 핸들러 4종은 전부 `ErrorResponse(error = ErrorBody(code, message))` 다.
    // 평면 RFC 7807 은 **스킴** 핸들러 전용이고 필드명도 `errorCode` 로 다르다.
    server.use(
      http.delete('/api/v1/workflows/custom', () =>
        HttpResponse.json({ error: { code: 'WORKFLOW_IN_USE', message: '스킴이 참조 중' } }, { status: 409 }),
      ),
    )
    await expect(deleteWorkflow('custom')).rejects.toMatchObject({
      status: 409,
      errorCode: 'WORKFLOW_IN_USE',
      detail: '스킴이 참조 중',
    })
  })

  it('평면 봉투를 주면 UNKNOWN 으로 떨어진다 — 파서가 형태를 실제로 가린다', async () => {
    // 처음에 평면 파서를 쓰다 잡혔다. `z.object` 는 모르는 키를 버리고 `.default()` 가
    // 빈 자리를 메우므로 **safeParse 가 성공한다** — 실패가 아니라 조용히 UNKNOWN 이 됐다.
    // 이 단언이 그 방향(중첩 파서에 평면을 주면 UNKNOWN)을 고정한다.
    server.use(
      http.delete('/api/v1/workflows/custom', () =>
        HttpResponse.json({ code: 'WORKFLOW_IN_USE', detail: '스킴이 참조 중' }, { status: 409 }),
      ),
    )
    await expect(deleteWorkflow('custom')).rejects.toMatchObject({ errorCode: 'UNKNOWN' })
  })

  it('본문이 아예 없는 응답도 UNKNOWN 으로 떨어진다', async () => {
    server.use(http.delete('/api/v1/workflows/custom', () => new HttpResponse(null, { status: 500 })))
    await expect(deleteWorkflow('custom')).rejects.toBeInstanceOf(WorkflowAdminApiError)
  })
})
