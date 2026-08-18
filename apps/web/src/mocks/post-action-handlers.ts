// 워크플로우 전환 post-action CRUD MSW stateful 핸들러 (GET/POST/PUT/DELETE + reset)
import { http, HttpResponse } from 'msw'
import type { PostActionResponse } from '@/api/post-actions'

// ─────────────────────────────────────────────────────────────────────────────
// Stateful store — transition별 post-action 배열
//
// 키: `${workflowKey}::${transitionKey}` (두 파라미터 복합 키)
// msw-mutation-stateful-refetch: POST/PUT/DELETE 결과가 다음 GET에 반영되어야 함
// msw-derived-behavior-shared-store-e2e: 핸들러간 공유 단일 store
// ─────────────────────────────────────────────────────────────────────────────

const postActionStore = new Map<string, PostActionResponse[]>()

/**
 * store 키 생성 — workflowKey + transitionKey 복합
 */
function storeKey(workflowKey: string, transitionKey: string): string {
  return `${workflowKey}::${transitionKey}`
}

/**
 * store를 완전 초기화한다.
 * E2E 테스트 beforeEach에서 전용 reset 라우트(DELETE /api/v1/__e2e__/post-actions/reset)로 호출해 격리.
 */
export function resetPostActionStore(): void {
  postActionStore.clear()
}

/**
 * RFC4122 v4 UUID 생성 — Zod v4 uuid() 검증을 통과해야 함
 * (메모리 zod-v4-uuid-fixture-strictness)
 */
function generateUuidV4(): string {
  const hex = () => Math.floor(Math.random() * 16).toString(16)
  const hex4 = () => `${hex()}${hex()}${hex()}${hex()}`
  const variant = (Math.floor(Math.random() * 4) + 8).toString(16) // 8, 9, a, b
  return `${hex4()}${hex4()}-${hex4()}-4${hex()}${hex()}${hex()}-${variant}${hex()}${hex()}${hex()}-${hex4()}${hex4()}${hex4()}`
}

/**
 * 에러 봉투 헬퍼 — `{ error: { code, message } }` 중첩 구조
 * (post-actions.ts nestedErrorBodySchema 와 1:1 대응)
 */
function errorBody(code: string, message: string): { error: { code: string; message: string } } {
  return { error: { code, message } }
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — 경로 파라미터: workflowKey + transitionKey + (선택) id
// 경로: /api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions(/:id)
// ─────────────────────────────────────────────────────────────────────────────

export const postActionHandlers = [
  /**
   * GET /api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions
   * → 200 { data: PostActionResponse[] }
   */
  http.get(
    '/api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions',
    ({ params }) => {
      const wKey = params['workflowKey'] as string
      const tKey = params['transitionKey'] as string

      const key = storeKey(wKey, tKey)
      const items = postActionStore.get(key) ?? []
      return HttpResponse.json({ data: items })
    },
  ),

  /**
   * POST /api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions
   * → 201 { data: PostActionResponse }
   */
  http.post(
    '/api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions',
    async ({ params, request }) => {
      const wKey = params['workflowKey'] as string
      const tKey = params['transitionKey'] as string

      const body = await request.json() as {
        type?: string
        config?: Record<string, unknown>
        displayOrder?: number
      }

      if (typeof body.type !== 'string' || body.type.length === 0) {
        return HttpResponse.json(
          errorBody('WORKFLOW_POST_ACTION_INVALID', 'type은 필수입니다.'),
          { status: 400 },
        )
      }

      const key = storeKey(wKey, tKey)
      const existing = postActionStore.get(key) ?? []

      const newItem: PostActionResponse = {
        id: generateUuidV4(),
        type: body.type,
        config: body.config ?? {},
        displayOrder: typeof body.displayOrder === 'number' ? body.displayOrder : existing.length,
      }

      postActionStore.set(key, [...existing, newItem])
      return HttpResponse.json({ data: newItem }, { status: 201 })
    },
  ),

  /**
   * PUT /api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions/:id
   * → 200 { data: PostActionResponse }
   */
  http.put(
    '/api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions/:id',
    async ({ params, request }) => {
      const wKey = params['workflowKey'] as string
      const tKey = params['transitionKey'] as string
      const id = params['id'] as string

      const key = storeKey(wKey, tKey)
      const items = postActionStore.get(key) ?? []
      const idx = items.findIndex((item) => item.id === id)

      if (idx === -1) {
        return HttpResponse.json(
          errorBody('WORKFLOW_POST_ACTION_NOT_FOUND', 'post-action을 찾을 수 없습니다.'),
          { status: 404 },
        )
      }

      const body = await request.json() as {
        type?: string
        config?: Record<string, unknown>
        displayOrder?: number
      }

      if (typeof body.type !== 'string' || body.type.length === 0) {
        return HttpResponse.json(
          errorBody('WORKFLOW_POST_ACTION_INVALID', 'type은 필수입니다.'),
          { status: 400 },
        )
      }

      const existing = items[idx]!
      const updated: PostActionResponse = {
        id,
        type: body.type,
        config: body.config ?? existing.config,
        displayOrder: typeof body.displayOrder === 'number' ? body.displayOrder : existing.displayOrder,
      }

      const newItems = items.map((item) => (item.id === id ? updated : item))
      postActionStore.set(key, newItems)
      return HttpResponse.json({ data: updated })
    },
  ),

  /**
   * DELETE /api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions/:id
   * → 204 (빈 바디)
   */
  http.delete(
    '/api/v1/workflows/:workflowKey/transitions/:transitionKey/post-actions/:id',
    ({ params }) => {
      const wKey = params['workflowKey'] as string
      const tKey = params['transitionKey'] as string
      const id = params['id'] as string

      const key = storeKey(wKey, tKey)
      const items = postActionStore.get(key) ?? []
      const idx = items.findIndex((item) => item.id === id)

      if (idx === -1) {
        return HttpResponse.json(
          errorBody('WORKFLOW_POST_ACTION_NOT_FOUND', 'post-action을 찾을 수 없습니다.'),
          { status: 404 },
        )
      }

      postActionStore.set(key, items.filter((item) => item.id !== id))
      return new HttpResponse(null, { status: 204 })
    },
  ),

  /**
   * DELETE /api/v1/__e2e__/post-actions/reset
   * E2E 전용 store 완전 초기화 라우트 — GET 헤더 방식보다 명시적.
   * beforeEach 에서 호출해 테스트 간 격리를 보장한다.
   * 프로덕션 MSW 핸들러 배열에 포함되지 않으면 실서버에 영향 없음.
   */
  http.delete('/api/v1/__e2e__/post-actions/reset', () => {
    resetPostActionStore()
    return new HttpResponse(null, { status: 204 })
  }),
]
