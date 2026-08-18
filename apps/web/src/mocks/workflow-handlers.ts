// project-workflow BC MSW mock handlers (4 표준 워크플로우 fixture)
import { http, HttpResponse } from 'msw'
import { allWorkflowFixtures } from './workflow-fixtures'

/**
 * project-workflow BC MSW 핸들러 목록.
 *
 * - GET  /api/v1/workflows       — 4 표준 워크플로우 배열 반환 (`{ data: [...] }`)
 * - GET  /api/v1/workflows/:key  — key 매칭 단건 반환 (없으면 404)
 * - POST /api/v1/workflows/:key/transitions — mock TransitionPlan 반환
 *
 * 응답 형식: backend의 `DataResponse<T>` 래퍼 (`{ data: T }`)와 일치.
 */
export const workflowHandlers = [
  /** GET /api/v1/workflows — 4 표준 워크플로우 목록 */
  http.get('/api/v1/workflows', () => {
    return HttpResponse.json({ data: allWorkflowFixtures })
  }),

  /** GET /api/v1/workflows/:key — 단건 조회, 없으면 404 */
  http.get('/api/v1/workflows/:key', ({ params }) => {
    const found = allWorkflowFixtures.find((w) => w.key === params['key'])
    if (found === undefined) {
      return HttpResponse.json({ message: '워크플로우를 찾을 수 없습니다' }, { status: 404 })
    }
    return HttpResponse.json({ data: found })
  }),

  /** POST /api/v1/workflows/:key/transitions — 첫 번째 전환 기준 mock TransitionPlan 반환 */
  http.post('/api/v1/workflows/:key/transitions', ({ params }) => {
    const key = params['key'] as string
    const workflow = allWorkflowFixtures.find((w) => w.key === key)
    if (workflow === undefined) {
      return HttpResponse.json({ message: '워크플로우를 찾을 수 없습니다' }, { status: 404 })
    }
    const firstTransition = workflow.transitions[0]
    const toStateKey = firstTransition !== undefined ? firstTransition.toStateKey : 'done'

    return HttpResponse.json({
      data: {
        toStateKey,
        fieldChanges: [],
        events: [{ type: 'ISSUE_TRANSITIONED', payload: { workflowKey: key } }],
      },
    })
  }),
]
