// project-workflow BC MSW mock handlers (4 표준 워크플로우 fixture)
import { http, HttpResponse } from 'msw'
import { workflowStore } from './workflow-admin-fixtures'

/**
 * 조회 대상은 **관리 저장소**다(픽스처 배열이 아니다).
 *
 * 정적 픽스처를 그대로 돌려주면 `workflow-admin-handlers` 의 쓰기 결과가 조회에 안 보여
 * 편집기가 「저장했는데 화면이 그대로」가 된다. 저장소는 픽스처로 초기화되므로 쓰기가
 * 없는 기존 소비처의 응답은 종전과 같다.
 */
const currentWorkflows = () => [...workflowStore.values()]

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
/**
 * E2E 전용 플래그 — 프로젝트 워크플로우 목록을 403 으로 돌려준다.
 *
 * ★`page.route()` 를 쓰지 않는 이유. MSW Service Worker 가 먼저 응답해 Playwright 의 가로채기가
 * 무효임이 이 저장소에서 실측돼 있다(`board-settings-estimation.spec.ts:95`). 그래서 「응답을
 * spec 에서 덮어쓴다」는 선택지가 없고, 목 쪽에 스위치를 두는 것이 확립된 패턴이다
 * (`E2E_IS_SYSTEM_ADMIN_KEY` 선례).
 *
 * 비-어드민 멤버가 프로젝트 설정에 들어왔을 때 **빈 표가 아니라 안내 카드**가 뜨는지를 재는 데 쓴다.
 */
export const E2E_PROJECT_WORKFLOWS_FORBIDDEN_KEY = '__bts_e2e_project_workflows_forbidden'

export const workflowHandlers = [
  /** GET /api/v1/workflows — 4 표준 워크플로우 목록 */
  http.get('/api/v1/workflows', () => {
    return HttpResponse.json({ data: currentWorkflows() })
  }),

  /**
   * GET /api/v1/projects/:projectKey/workflows — 프로젝트가 쓸 수 있는 목록 (FR-WF-08).
   *
   * ★목이 전량을 그대로 주면 안 된다. 실서버는 「전역 + 그 프로젝트」로 좁히므로, 목이 안 좁히면
   * 화면 테스트가 **좁혀지지 않은 상태에서도 초록**이 된다 — 목끼리의 일치가 계약 정합으로
   * 오인되는 자리다. 그래서 여기서도 `projectId` 로 거른다.
   *
   * 픽스처 프로젝트 키는 `ATLAS` 로 고정한다 — 목 워크플로우가 전부 전역이라 소유 판정에
   * 쓸 실제 프로젝트 id 가 없다. 다른 키로 물어도 전역 목록은 같으므로 화면 검증에 충분하다.
   */
  http.get('/api/v1/projects/:projectKey/workflows', () => {
    if (globalThis.localStorage?.getItem(E2E_PROJECT_WORKFLOWS_FORBIDDEN_KEY) === 'true') {
      return HttpResponse.json({ error: { code: 'FORBIDDEN' } }, { status: 403 })
    }
    return HttpResponse.json({ data: currentWorkflows().filter((w) => w.projectId === null) })
  }),

  /** GET /api/v1/workflows/:key — 단건 조회, 없으면 404 */
  http.get('/api/v1/workflows/:key', ({ params }) => {
    const found = currentWorkflows().find((w) => w.key === params['key'])
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
    const workflow = currentWorkflows().find((w) => w.key === key)
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
