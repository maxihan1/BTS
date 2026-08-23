// project-workflow BC MSW mock handlers (4 표준 워크플로우 fixture)
import { http, HttpResponse } from 'msw'
import { allWorkflowFixtures } from './workflow-fixtures'

/**
 * project-workflow BC MSW 핸들러 목록.
 *
 * - GET  /api/v1/workflows       — 4 표준 워크플로우 배열 반환 (`{ data: [...] }`)
 * - GET  /api/v1/workflows/:key  — key 매칭 단건 반환 (없으면 404)
 * - POST /api/v1/workflows/:key/transitions/plan — mock TransitionPlan 반환
 *
 * `/transitions` 는 이 목이 다루지 않는다 — 그 경로는 전환 **정의** CRUD 다(결정 D-1).
 * 계획 요청을 그리로 되돌리면 조용히 오라우팅되므로 `/plan` 접미사를 지우지 말 것.
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

  /**
   * POST /api/v1/workflows/:key/transitions/plan — 첫 번째 NORMAL 전환 기준 mock TransitionPlan 반환.
   *
   * INITIAL 을 건너뛰는 이유. 계획 요청은 **이미 상태가 있는 이슈**가 보내므로 출발 상태가 없는
   * 전환(INITIAL·GLOBAL)은 계획의 대상이 아니다. 픽스처는 INITIAL 을 맨 앞에 두므로
   * (V207 ⑨ 의 display_order 0 과 같은 배치) 무턱대고 `[0]` 을 집으면 INITIAL 이 잡힌다.
   */
  http.post('/api/v1/workflows/:key/transitions/plan', ({ params }) => {
    const key = params['key'] as string
    const workflow = allWorkflowFixtures.find((w) => w.key === key)
    if (workflow === undefined) {
      return HttpResponse.json({ message: '워크플로우를 찾을 수 없습니다' }, { status: 404 })
    }
    const firstTransition = workflow.transitions.find((t) => t.kind === 'NORMAL')
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
