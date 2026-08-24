// 워크플로우 관리 MSW 핸들러 — 상태 있는 쓰기(생성·편집·편성·전환) + 실패 계약
import { http, HttpResponse } from 'msw'
import type { WorkflowView } from '@/api/workflows'
import { transitionKey } from '@/components/workflow/workflow.types'
import {
  workflowStore,
  statusCatalogStore,
  findStatusById,
  nextStatusId,
  nextTransitionId,
} from './workflow-admin-fixtures'

/**
 * 백엔드와 **같은 중첩 봉투**로 실패를 돌려준다.
 *
 * ★ 종전에는 평면 `{ code, detail }` 이었다. 백엔드 핸들러 4종은 전부
 * `ErrorResponse(error = ErrorBody(code, message))` 인데 목만 평면이라, 목·테스트·클라이언트
 * 셋이 서로만 보고 **서버를 한 번도 안 보는** 상태였다. 그 어긋남은 프로덕션에서만 드러난다.
 */
// 반환 타입은 추론에 맡긴다 — msw 의 HttpResponse 는 제네릭이고 DefaultBodyType 제약이
// 있어 손으로 적으면 리졸버 시그니처와 어긋난다(실측).
function problem(status: number, code: string, message: string) {
  return HttpResponse.json({ error: { code, message } }, { status })
}

/** 출발 상태가 없는 전환(GLOBAL·INITIAL)의 key — 백엔드 계산 규칙과 1:1 */
function kindKey(kind: string, toStateKey: string): string {
  return `${kind}__${toStateKey}`
}

/** 전환 key 를 규칙대로 만든다. 정적 문자열을 쓰면 백엔드와 drift 한다. */
function keyOf(kind: string, fromStateKey: string | null, toStateKey: string): string {
  return fromStateKey === null ? kindKey(kind, toStateKey) : transitionKey(fromStateKey, toStateKey)
}

/** 저장소에서 워크플로우를 꺼낸다. 없으면 undefined. */
function findWorkflow(key: unknown): WorkflowView | undefined {
  return typeof key === 'string' ? workflowStore.get(key) : undefined
}

export const workflowAdminHandlers = [
  // ── 전역 상태 카탈로그 ──────────────────────────────────────────────────
  http.get('/api/v1/statuses', () => HttpResponse.json({ data: [...statusCatalogStore.values()] })),

  http.post('/api/v1/statuses', async ({ request }) => {
    const body = (await request.json()) as { key: string; name: string; description: string | null; category: string }
    if (statusCatalogStore.has(body.key)) {
      return problem(409, 'STATUS_KEY_CONFLICT', `이미 있는 상태 키: ${body.key}`)
    }
    if ([...statusCatalogStore.values()].some((s) => s.name === body.name)) {
      return problem(409, 'STATUS_NAME_CONFLICT', `이미 있는 상태 이름: ${body.name}`)
    }
    const created = {
      id: nextStatusId(),
      key: body.key,
      name: body.name,
      description: body.description,
      category: body.category as 'TODO' | 'IN_PROGRESS' | 'DONE',
      isSystem: false,
    }
    statusCatalogStore.set(created.key, created)
    return HttpResponse.json({ data: { id: created.id, key: created.key } }, { status: 201 })
  }),

  // ── 워크플로우 ──────────────────────────────────────────────────────────
  http.post('/api/v1/workflows', async ({ request }) => {
    const body = (await request.json()) as {
      key: string
      name: string
      description: string | null
      statuses: { key: string; name: string; category: string; displayOrder: number }[]
    }
    if (workflowStore.has(body.key)) {
      return problem(409, 'WORKFLOW_KEY_CONFLICT', `이미 있는 워크플로우 키: ${body.key}`)
    }
    if (body.statuses.length === 0) {
      // 백엔드 `Workflow.of()` invariant — 상태 없는 워크플로우는 만들자마자 조회가 죽는다.
      return problem(400, 'WORKFLOW_INVALID_REQUEST', '상태를 하나 이상 넣어야 합니다')
    }
    const first = body.statuses[0]!
    workflowStore.set(body.key, {
      key: body.key,
      name: body.name,
      description: body.description ?? '',
      states: body.statuses.map((s) => ({
        key: s.key,
        name: s.name,
        category: s.category as 'TODO' | 'IN_PROGRESS' | 'DONE',
        displayOrder: s.displayOrder,
      })),
      // V207 ⑨ 백필과 같다 — 워크플로우마다 INITIAL 1건이 반드시 있다.
      transitions: [
        {
          key: kindKey('INITIAL', first.key),
          name: '이슈 생성',
          fromStateKey: null,
          toStateKey: first.key,
          id: nextTransitionId(),
          kind: 'INITIAL',
        },
      ],
    })
    return HttpResponse.json({ data: { key: body.key } }, { status: 201 })
  }),

  http.put('/api/v1/workflows/:key', async ({ params, request }) => {
    const workflow = findWorkflow(params['key'])
    if (workflow === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 워크플로우: ${String(params['key'])}`)
    }
    const body = (await request.json()) as { name: string; description: string | null }
    workflow.name = body.name
    workflow.description = body.description ?? ''
    return HttpResponse.json({ data: null })
  }),

  http.delete('/api/v1/workflows/:key', ({ params }) => {
    const key = String(params['key'])
    if (!workflowStore.has(key)) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 워크플로우: ${key}`)
    }
    workflowStore.delete(key)
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/workflows/:key/duplicate', async ({ params, request }) => {
    const source = findWorkflow(params['key'])
    if (source === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 워크플로우: ${String(params['key'])}`)
    }
    const body = (await request.json()) as { key: string; name: string }
    if (workflowStore.has(body.key)) {
      return problem(409, 'WORKFLOW_KEY_CONFLICT', `이미 있는 워크플로우 키: ${body.key}`)
    }
    workflowStore.set(body.key, {
      ...structuredClone(source),
      key: body.key,
      name: body.name,
      // 전환 id 는 새로 받는다 — 복제본이 원본과 같은 식별자를 쓰면 「어느 워크플로우의
      // 전환인가」가 id 만으로 갈리지 않는다.
      transitions: source.transitions.map((t) => ({ ...t, id: nextTransitionId() })),
    })
    return HttpResponse.json({ data: { key: body.key } }, { status: 201 })
  }),

  // ── 상태 편성 ───────────────────────────────────────────────────────────
  http.post('/api/v1/workflows/:key/statuses', async ({ params, request }) => {
    const workflow = findWorkflow(params['key'])
    if (workflow === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 워크플로우: ${String(params['key'])}`)
    }
    const body = (await request.json()) as { statusId: string; displayOrder: number }
    const status = findStatusById(body.statusId)
    if (status === undefined) {
      return problem(404, 'STATUS_NOT_FOUND', `없는 상태: ${body.statusId}`)
    }
    const existing = workflow.states.find((s) => s.key === status.key)
    if (existing !== undefined) {
      existing.displayOrder = body.displayOrder
    } else {
      workflow.states.push({
        key: status.key,
        name: status.name,
        category: status.category,
        displayOrder: body.displayOrder,
      })
    }
    return new HttpResponse(null, { status: 201 })
  }),

  http.delete('/api/v1/workflows/:key/statuses/:statusId', ({ params }) => {
    const workflow = findWorkflow(params['key'])
    if (workflow === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 워크플로우: ${String(params['key'])}`)
    }
    const status = findStatusById(String(params['statusId']))
    if (status === undefined || !workflow.states.some((s) => s.key === status.key)) {
      return problem(404, 'STATUS_NOT_FOUND', `편성되지 않은 상태: ${String(params['statusId'])}`)
    }
    if (workflow.states.length === 1) {
      return problem(400, 'WORKFLOW_STATUS_COMPOSITION_INVALID', '마지막 상태는 뺄 수 없습니다')
    }
    // 전환의 상태 FK 가 ON DELETE CASCADE(V207 ③)라 편성을 떼면 그 전환이 하드 삭제된다.
    // 백엔드가 그래서 먼저 막는다 — 목도 같은 자리에서 막아야 화면이 같은 길을 탄다.
    if (workflow.transitions.some((t) => t.fromStateKey === status.key || t.toStateKey === status.key)) {
      return problem(409, 'WORKFLOW_STATUS_REFERENCED_BY_TRANSITION', '전환이 가리키는 상태입니다')
    }
    workflow.states = workflow.states.filter((s) => s.key !== status.key)
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/workflows/:key/statuses/order', async ({ params, request }) => {
    const workflow = findWorkflow(params['key'])
    if (workflow === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 워크플로우: ${String(params['key'])}`)
    }
    const body = (await request.json()) as { statusIds: string[] }
    const orderedKeys = body.statusIds.map((id) => findStatusById(id)?.key)
    if (orderedKeys.some((k) => k === undefined) || orderedKeys.length !== workflow.states.length) {
      // 부분 목록이면 빠진 상태의 순서가 어긋난다 — 백엔드가 전부를 요구하는 이유다.
      return problem(400, 'WORKFLOW_STATUS_COMPOSITION_INVALID', '그 워크플로우의 상태 전부를 보내야 합니다')
    }
    orderedKeys.forEach((key, index) => {
      const state = workflow.states.find((s) => s.key === key)
      if (state !== undefined) {
        state.displayOrder = index + 1
      }
    })
    return HttpResponse.json({ data: null })
  }),

  // ── 전환 정의 ───────────────────────────────────────────────────────────
  http.post('/api/v1/workflows/:key/transitions', async ({ params, request }) => {
    const workflow = findWorkflow(params['key'])
    if (workflow === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 워크플로우: ${String(params['key'])}`)
    }
    const body = (await request.json()) as {
      toStatusKey: string
      name: string
      fromStatusKey?: string
      kind?: string
    }
    const kind = body.kind ?? 'NORMAL'
    const fromStateKey = body.fromStatusKey ?? null
    if (kind === 'NORMAL' && fromStateKey === null) {
      return problem(400, 'WORKFLOW_INVALID_REQUEST', 'NORMAL 전환은 출발 상태가 필요합니다')
    }
    if (kind !== 'NORMAL' && fromStateKey !== null) {
      return problem(400, 'WORKFLOW_INVALID_REQUEST', `${kind} 전환에는 출발 상태를 실을 수 없습니다`)
    }
    if (kind === 'INITIAL' && workflow.transitions.some((t) => t.kind === 'INITIAL')) {
      return problem(409, 'WORKFLOW_TRANSITION_CONFLICT', '최초 전환은 하나뿐입니다')
    }
    const created = {
      key: keyOf(kind, fromStateKey, body.toStatusKey),
      name: body.name,
      fromStateKey,
      toStateKey: body.toStatusKey,
      id: nextTransitionId(),
      kind: kind as 'NORMAL' | 'GLOBAL' | 'INITIAL',
    }
    workflow.transitions.push(created)
    return HttpResponse.json({ data: created }, { status: 201 })
  }),

  http.put('/api/v1/workflows/:key/transitions/:transitionId', async ({ params, request }) => {
    const workflow = findWorkflow(params['key'])
    const transitionId = String(params['transitionId'])
    const target = workflow?.transitions.find((t) => t.id === transitionId)
    if (workflow === undefined || target === undefined) {
      // 그 워크플로우의 전환이 아니면 404 다(spec E9) — 남의 전환을 경로만 바꿔 못 고친다.
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 전환: ${transitionId}`)
    }
    const body = (await request.json()) as { toStatusKey: string; name: string; fromStatusKey?: string; kind?: string }
    const kind = body.kind ?? 'NORMAL'
    target.name = body.name
    target.kind = kind as 'NORMAL' | 'GLOBAL' | 'INITIAL'
    target.fromStateKey = body.fromStatusKey ?? null
    target.toStateKey = body.toStatusKey
    target.key = keyOf(kind, target.fromStateKey, target.toStateKey)
    return HttpResponse.json({ data: target })
  }),

  http.delete('/api/v1/workflows/:key/transitions/:transitionId', ({ params }) => {
    const workflow = findWorkflow(params['key'])
    const transitionId = String(params['transitionId'])
    const target = workflow?.transitions.find((t) => t.id === transitionId)
    if (workflow === undefined || target === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', `없는 전환: ${transitionId}`)
    }
    if (target.kind === 'INITIAL') {
      return problem(409, 'WORKFLOW_TRANSITION_CONFLICT', '최초 전환은 지울 수 없습니다')
    }
    workflow.transitions = workflow.transitions.filter((t) => t.id !== transitionId)
    return new HttpResponse(null, { status: 204 })
  }),
]
