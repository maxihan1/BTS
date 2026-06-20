// 이슈 이동 BC MSW 핸들러 — preview/move stateful store + 옛 키 308 redirect 시뮬 (FR-MV-01)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: move 핸들러가 "이동됨" 상태를 store에 기록 →
//     이후 getIssueHandler(issue-handlers.ts)가 옛 키 GET 시 redirected URL 반환
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - e2e-fixture-whoami-userid-alignment: alice userId=00000000-...-001 (AUTH_USERS 정본)
//
// 308 시뮬 방법.
//   fetch redirect:'follow'(기본)는 same-origin 308을 자동 추적한다.
//   MSW에서 실제 HTTP 308 응답을 내보내도 fetch가 추적하므로 response.redirected=true + response.url이 새 URL이 된다.
//   단, MSW의 HttpResponse는 상태 308만으로 redirect를 완전히 시뮬하기 어렵다.
//   대신 이슈 이동 후 GET /api/v1/issues/:oldKey 핸들러가 redirected=true를 simulate:
//     → 이슈 상세 fetchIssue는 response.redirected 체크를 하므로,
//       MSW에서 실제 Response({ redirect: 'follow', ... })를 사용하거나
//       또는 별도 "이동됨" 플래그를 공유 store에서 읽어 해당 핸들러를 통해 처리.
//
// 구현 방식 (옵션 B — 공유 store + getIssueHandler 보강):
//   이 파일에서 movedIssueStore를 export → issue-handlers.ts가 import해 GET :key 시 체크.
//   (issue-handlers.ts 수정 금지 규칙에 위배되므로, 대신 issue-move-handlers.ts에서
//    GET /api/v1/issues/:key 핸들러를 별도로 등록 → MSW 우선순위상 먼저 매칭됨)
//   → issue-move-handlers가 GET /api/v1/issues/:key도 처리(moved 이슈만); 미매칭이면 passthrough
//
// MSW 핸들러 우선순위: handlers 배열에서 앞에 등록된 핸들러가 먼저 매칭된다.
// issueMoveHandlers를 handlers.ts에서 issueHandlers보다 먼저 spread하면
// moved 이슈 키에 대한 GET이 여기서 처리되고, 일반 이슈는 issue-handlers.ts로 fallthrough.
//
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — 이동된 이슈 추적
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이동된 이슈 정보 레코드.
 * oldKey → newKey 매핑 + 새 이슈의 최소 응답 데이터.
 */
interface MovedIssueEntry {
  /** 이동 전 원본 이슈 키 */
  oldKey: string
  /** 이동 후 새 이슈 키 */
  newKey: string
  /** 이동 후 새 프로젝트 키 */
  newProjectKey: string
  /** movedSubtasks 배열 */
  movedSubtasks: Array<{ previousKey: string; issueKey: string }>
}

/**
 * 이슈 이동 store — oldKey → 이동 결과 매핑.
 * move 핸들러가 기록하고, GET :key 핸들러가 읽는다.
 * 각 E2E test는 새 Playwright context → 새 MSW ServiceWorker 모듈 → 빈 상태로 시작.
 */
export let movedIssueStore: Map<string, MovedIssueEntry> = new Map()

/** store 초기화 — 단위 테스트 afterEach에서 호출 (E2E는 컨텍스트 격리로 자동) */
export function resetIssueMoveStore(): void {
  movedIssueStore = new Map()
}

/**
 * 이슈 이동을 store에 수동 시드한다 — 단위 테스트 setup에서 사용.
 *
 * @param entry 이동 결과 레코드
 */
export function seedMovedIssue(entry: MovedIssueEntry): void {
  movedIssueStore.set(entry.oldKey, entry)
}

// ─────────────────────────────────────────────────────────────────────────────
// 정적 fixture — 대상 프로젝트별 preview 응답
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 완전 호환 preview 응답 fixture — INFRA 프로젝트(subtasks 없음).
 * S1 단건 이동 (매핑 불필요) 시나리오 대응.
 */
export const compatiblePreviewFixture = {
  version: 1,
  workflow: {
    compatible: true,
    targetStates: [
      { key: 'open', name: '열림', isDone: false },
      { key: 'in_progress', name: '진행 중', isDone: false },
      { key: 'done', name: '완료', isDone: true },
    ],
    suggestedStateKey: 'open',
  },
  components: {
    current: [],
    target: [],
    autoMapping: {} as Record<string, string | null>,
  },
  affectsVersions: {
    current: [],
    target: [],
    autoMapping: {} as Record<string, string | null>,
  },
  fixVersions: {
    current: [],
    target: [],
    autoMapping: {} as Record<string, string | null>,
  },
  customFields: {
    removed: [],
    requiredMissing: [],
  },
  subtasks: [],
}

/**
 * 서브태스크 포함 preview 응답 fixture — INFRA 프로젝트(subtasks 2개).
 * S2 서브태스크 동반 이동 시나리오 대응.
 * 루트 + 자식 2개 모두 완전 호환.
 */
export const subtaskPreviewFixture = {
  version: 2,
  workflow: {
    compatible: true,
    targetStates: [
      { key: 'open', name: '열림', isDone: false },
      { key: 'in_progress', name: '진행 중', isDone: false },
      { key: 'done', name: '완료', isDone: true },
    ],
    suggestedStateKey: 'open',
  },
  components: {
    current: [],
    target: [],
    autoMapping: {} as Record<string, string | null>,
  },
  affectsVersions: {
    current: [],
    target: [],
    autoMapping: {} as Record<string, string | null>,
  },
  fixVersions: {
    current: [],
    target: [],
    autoMapping: {} as Record<string, string | null>,
  },
  customFields: {
    removed: [],
    requiredMissing: [],
  },
  subtasks: [
    {
      issueKey: 'ATLAS-13',
      issueTypeKey: 'task',
      version: 0,
      workflow: {
        compatible: true,
        targetStates: [
          { key: 'open', name: '열림', isDone: false },
          { key: 'done', name: '완료', isDone: true },
        ],
        suggestedStateKey: 'open',
      },
      components: {
        current: [],
        target: [],
        autoMapping: {} as Record<string, string | null>,
      },
      affectsVersions: {
        current: [],
        target: [],
        autoMapping: {} as Record<string, string | null>,
      },
      fixVersions: {
        current: [],
        target: [],
        autoMapping: {} as Record<string, string | null>,
      },
      customFields: {
        removed: [],
        requiredMissing: [],
      },
    },
    {
      issueKey: 'ATLAS-14',
      issueTypeKey: 'task',
      version: 0,
      workflow: {
        compatible: true,
        targetStates: [
          { key: 'open', name: '열림', isDone: false },
          { key: 'done', name: '완료', isDone: true },
        ],
        suggestedStateKey: 'open',
      },
      components: {
        current: [],
        target: [],
        autoMapping: {} as Record<string, string | null>,
      },
      affectsVersions: {
        current: [],
        target: [],
        autoMapping: {} as Record<string, string | null>,
      },
      fixVersions: {
        current: [],
        target: [],
        autoMapping: {} as Record<string, string | null>,
      },
      customFields: {
        removed: [],
        requiredMissing: [],
      },
    },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// localStorage 플래그 — E2E 시나리오 분기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키.
 * 'subtask'이면 subtaskPreviewFixture를 반환 (S2 서브태스크 시나리오).
 * 미설정(기본)이면 compatiblePreviewFixture를 반환 (S1 단건 시나리오).
 *
 * Playwright addInitScript로 goto 전에 플래그를 설정하면
 * 첫 preview fetch 시점부터 적용된다.
 */
export const LS_KEY_MOVE_SCENARIO = '__bts_e2e_move_scenario'

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/issues/:key/move/preview — 이슈 이동 preview.
 *
 * localStorage 플래그에 따라 시나리오를 분기한다.
 * - '__bts_e2e_move_scenario' = 'subtask' → subtaskPreviewFixture
 * - 미설정/기타 → compatiblePreviewFixture
 */
const movePreviewHandler = http.post('/api/v1/issues/:key/move/preview', () => {
  const scenario = globalThis.localStorage?.getItem(LS_KEY_MOVE_SCENARIO) ?? ''
  const fixture = scenario === 'subtask' ? subtaskPreviewFixture : compatiblePreviewFixture
  return HttpResponse.json({ data: fixture })
})

/**
 * POST /api/v1/issues/:key/move — 이슈 이동 실행.
 *
 * 단건 이동(subtasks=[] 또는 미포함): ATLAS-12 → INFRA-5
 * 서브태스크 포함 이동: ATLAS-12 → INFRA-5, subtasks [ATLAS-13→INFRA-6, ATLAS-14→INFRA-7]
 *
 * move 성공 시 movedIssueStore에 oldKey→newKey 매핑을 기록한다.
 * 이후 GET /api/v1/issues/:oldKey 핸들러가 이 store를 읽어 redirect 시뮬한다.
 */
const moveIssueHandler = http.post('/api/v1/issues/:key/move', async ({ params, request }) => {
  const oldKey = params['key'] as string

  let body: { subtasks?: Array<{ issueKey: string }> } = {}
  try {
    body = (await request.json()) as { subtasks?: Array<{ issueKey: string }> }
  } catch {
    // body 파싱 실패 시 단건으로 처리
  }

  const subtasks = body.subtasks ?? []
  const newKey = 'INFRA-5'
  const newProjectKey = 'INFRA'

  // 서브태스크 이동 결과 생성
  const subtaskKeyMap: Record<string, string> = {
    'ATLAS-13': 'INFRA-6',
    'ATLAS-14': 'INFRA-7',
  }
  const movedSubtasks = subtasks.map((st) => ({
    previousKey: st.issueKey,
    issueKey: subtaskKeyMap[st.issueKey] ?? `INFRA-${st.issueKey.split('-')[1]}`,
  }))

  // store에 이동 결과 기록 — 이후 GET :oldKey 요청이 redirect 시뮬
  const entry: MovedIssueEntry = {
    oldKey,
    newKey,
    newProjectKey,
    movedSubtasks,
  }
  movedIssueStore.set(oldKey, entry)

  // 서브태스크도 store에 기록 (각 자식 oldKey → newKey)
  for (const ms of movedSubtasks) {
    movedIssueStore.set(ms.previousKey, {
      oldKey: ms.previousKey,
      newKey: ms.issueKey,
      newProjectKey,
      movedSubtasks: [],
    })
  }

  return HttpResponse.json(
    {
      data: {
        issueKey: newKey,
        previousKey: oldKey,
        movedSubtasks,
      },
    },
    { status: 200 },
  )
})

/**
 * GET /api/v1/issues/:key — 이동된 이슈 308 redirect 시뮬.
 *
 * movedIssueStore에 oldKey가 있으면 308 + Location 응답.
 * 없으면 핸들러를 통과(passthrough)하여 issue-handlers.ts의 일반 핸들러가 처리한다.
 *
 * MSW에서 308 상태코드 + Location 헤더를 내보내면:
 * - fetch redirect:'follow'(기본)는 301/302/303/307/308 모두 자동 추적한다.
 * - response.redirected === true, response.url === 추적된 최종 URL이 된다.
 * - fetchIssue는 response.redirected 체크 → response.url에서 newKey 추출 → IssueRedirectError throw.
 *
 * 주의: MSW의 HttpResponse로 내보낸 308 + Location을 브라우저 fetch가 정말로 자동 추적하는지는
 * 브라우저/ServiceWorker 환경마다 다를 수 있다. 실제로 MSW는 네트워크 요청을 가로채
 * ServiceWorker에서 응답을 반환하는데, fetch가 SW 응답의 redirect를 따르는 동작은
 * same-origin 308 + Location이 /api/v1/... 경로인 경우 정상 동작한다.
 *
 * 이 핸들러는 issueMoveHandlers 배열의 맨 앞에 두어야 issue-handlers.ts보다 먼저 매칭된다.
 * handlers.ts에서 issueMoveHandlers를 issueHandlers보다 앞에 spread해야 한다.
 */
const movedIssueRedirectHandler = http.get('/api/v1/issues/:key', ({ params }) => {
  const key = params['key'] as string
  const entry = movedIssueStore.get(key)
  if (entry === undefined) {
    // 이동되지 않은 이슈 — 다음 핸들러(issue-handlers.ts)로 passthrough
    return undefined
  }
  // 308 Permanent Redirect — Location: 새 이슈 API 경로
  return new Response(null, {
    status: 308,
    headers: {
      Location: `/api/v1/issues/${entry.newKey}`,
    },
  })
})

/**
 * GET /api/v1/issues/:key — 이동 후 새 키 이슈 응답.
 * movedIssueStore에서 newKey로 진입 시 최소 응답 반환.
 * (fetch가 308을 자동 추적하면 이 핸들러로 도달)
 */
const newKeyIssueHandler = http.get('/api/v1/issues/:key', ({ params }) => {
  const key = params['key'] as string
  // 새 키(이동 결과 newKey)로 조회 → 이슈 상세 응답 반환
  const isNewKey = Array.from(movedIssueStore.values()).some((e) => e.newKey === key)
  if (!isNewKey) {
    return undefined
  }
  // 새 키 이슈 응답 — 최소 fixture
  return HttpResponse.json({
    data: {
      key,
      id: '99999999-9999-4999-8999-999999999999',
      projectKey: 'INFRA',
      summary: '이동된 이슈',
      currentStateKey: 'open',
      reporterId: '00000000-0000-4000-8000-000000000001',
      assigneeId: null,
      componentIds: [],
      affectsVersionIds: [],
      fixVersionIds: [],
      version: 0,
      createdAt: '2026-06-16T00:00:00Z',
      updatedAt: null,
      typeId: 1,
      typeKey: 'bug',
      typeName: '버그',
      description: null,
      descriptionHtml: null,
      priority: 3,
      priorityName: 'Medium',
      labels: [],
      environment: null,
      impact: null,
      impactName: null,
      customFields: {},
      restrictedFields: [],
      noneditableFields: [],
      securityLevelId: null,
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 이동 MSW 핸들러 배열.
 *
 * handlers.ts에서 issueHandlers보다 **앞에** spread해야 한다.
 * (movedIssueRedirectHandler가 moved 이슈 GET을 먼저 가로채야 함)
 */
export const issueMoveHandlers = [
  movedIssueRedirectHandler,
  newKeyIssueHandler,
  movePreviewHandler,
  moveIssueHandler,
]
