// 백로그·스프린트 BC MSW 핸들러 — stateful CRUD + 이슈 이동 + rank 변경 (FR-BL-01/02 D6/D7)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: mutation 후 GET 재조회 시 변경 반영 (backlogStore 변이)
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - fr-bd-01: 신규 store 모듈 로드 시 자동 시드 (backlog-fixtures.ts에서 처리)
//
import { http, HttpResponse } from 'msw'
import {
  backlogStore,
  findSprintInStore,
  removeIssueFromProject,
  createSprintInStore,
  computeRank,
  findIssueInProject,
} from './backlog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 조회 실패 시나리오 강제 플래그. 'true' | {@link BACKLOG_FAIL_ONCE} | null.
 *
 * 'true'이면 `GET /api/v1/projects/:projectKey/backlog`가 **매번** 500을 반환한다
 * (FR-UX-13 F5 — "조회 실패 → 안내 + [다시 시도]" E2E 재현용).
 * {@link BACKLOG_FAIL_ONCE}이면 **첫 요청만** 500이고 그 뒤로는 정상 응답이다.
 * 다른 엔드포인트(rank·스프린트)는 영향을 받지 않는다.
 *
 * 회귀 학습 e2e-msw-scenario-toggle-localstorage-flag 근거 — Playwright의 `page.route()`
 * 가로채기는 MSW Service Worker가 요청을 먼저 가로채 응답해버려 무효다(import.spec.ts S3
 * 실측). 시나리오 분기는 localStorage 플래그를 addInitScript로 goto 이전에 심어 만든다.
 * 명명·정리 관례는 import-handlers.ts LS_KEY_IMPORT_FAIL,
 * board-fixtures.ts LS_KEY_BOARD_CONFLICT를 그대로 미러한다.
 */
export const LS_KEY_BACKLOG_FAIL = '__bts_e2e_backlog_fail'

/**
 * {@link LS_KEY_BACKLOG_FAIL}의 **1회성 실패** 값.
 *
 * 이 값이면 첫 `GET backlog`만 500을 주고 **플래그를 스스로 지운다** — 다음 요청부터 정상이다.
 *
 * 왜 필요한가. 'true'(계속 실패)로는 "[다시 시도] 클릭 → 정상 복귀"(FR-UX-13 F5 스펙 S7)를
 * **구조적으로 잴 수 없다**. 재시도가 성공하는 경로가 자동 커버리지 없이 사람 눈확인에만
 * 남아 있던 것을 이 값으로 닫는다. 'true'는 "재시도도 실패"(엣지 E9)용으로 그대로 유지된다.
 */
export const BACKLOG_FAIL_ONCE = 'once'

/**
 * 백로그 `truncated=true` 강제 플래그 (FR-UX-13 F15 FR-18 · E15).
 *
 * 'true'이면 GET backlog 가 `truncated: true` 를 반환한다. 완료 다이얼로그의 제출 차단
 * (「일부 이슈만 표시되어 안전하게 완료할 수 없습니다.」)을 재현하기 위한 것이며,
 * 이 토글이 없으면 픽스처의 `truncated` 가 **false 하드코딩**이라 그 경로를 만들 수단이 아예 없다.
 *
 * 선례. `timeline-handlers.ts` 의 `'truncated'` 시나리오 토글.
 */
export const LS_KEY_BACKLOG_TRUNCATED = '__bts_e2e_backlog_truncated'

/**
 * 스프린트 메타 수정(`PATCH /sprints/:id`) 실패 강제 플래그.
 *
 * 'true'이면 500 을 반환한다 — FR-4 의 「`PATCH` 실패(비-409)」 갈래 재현용.
 * 409(낙관적 잠금 충돌)는 토글 없이도 만들 수 있다 — 어긋난 `version` 을 보내면 된다.
 */
export const LS_KEY_SPRINT_PATCH_FAIL = '__bts_e2e_sprint_patch_fail'

/**
 * 스프린트 시작(`POST /sprints/:id/start`) 실패 강제 플래그.
 *
 * - `'true'` → 500. S5(수정은 됐는데 시작이 실패) 재현용.
 * - `'409'` → 409 `SPRINT_INVALID_TRANSITION`. E10(남이 이미 시작함) 재현용.
 *
 * 값이 없으면 정상 전이다 — 실패 토글이 스프린트를 영구히 못 쓰게 만들면 안 된다.
 */
export const LS_KEY_SPRINT_START_FAIL = '__bts_e2e_sprint_start_fail'

/**
 * 스프린트 해제(`DELETE /sprints/:id/issues/:issueKey`) 실패 강제 플래그.
 *
 * 값은 **실패시킬 이슈 키의 쉼표 구분 목록**이다. 목록에 든 키만 500 이고 나머지는 정상 204 다.
 * S7·S18(이관 **부분** 실패 → `complete` 미발사)을 재현하려면 「일부만 실패」가 필요한데,
 * 전건 실패 토글로는 그 상태를 만들 수 없다.
 */
export const LS_KEY_SPRINT_UNASSIGN_FAIL = '__bts_e2e_sprint_unassign_fail'

// ─────────────────────────────────────────────────────────────────────────────
// 토글 조회 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** localStorage 토글 값을 읽는다. 없으면 null */
function toggle(key: string): string | null {
  return globalThis.localStorage?.getItem(key) ?? null
}

/** 쉼표 구분 토글 값을 키 목록으로 파싱한다 */
function toggleKeys(key: string): string[] {
  return (toggle(key) ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '')
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/backlog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/projects/{projectKey}/backlog — 백로그 전체 뷰 조회.
 *
 * store에서 해당 projectKey의 BacklogProject를 읽어
 * { data: { backlog, sprints, truncated } } 형식으로 반환한다.
 * 프로젝트가 없으면 빈 백로그·스프린트를 반환한다.
 *
 * 조회 실패 토글 — E2E 시나리오용:
 *   localStorage 플래그 LS_KEY_BACKLOG_FAIL='true'이면 projectKey와 무관하게 매번 500 반환.
 *   플래그가 BACKLOG_FAIL_ONCE이면 이번 요청만 500이고 플래그를 지운다(다음 요청은 정상).
 *
 * 성공 → 200 { data: BacklogView }
 * 실패 토글 시 → 500 ProblemDetail
 */
const getBacklogHandler = http.get(
  '/api/v1/projects/:projectKey/backlog',
  ({ params }) => {
    // 조회 실패 토글 — E2E 시나리오 (e2e-msw-scenario-toggle-localstorage-flag)
    const failFlag = globalThis.localStorage?.getItem(LS_KEY_BACKLOG_FAIL)
    if (failFlag === 'true' || failFlag === BACKLOG_FAIL_ONCE) {
      // 1회성 토글은 응답 전에 스스로 해제한다 — 뒤이은 재조회가 성공해야
      // "[다시 시도] → 정상 복귀"를 잴 수 있다. 'true'는 지우지 않으므로 계속 실패한다.
      if (failFlag === BACKLOG_FAIL_ONCE) {
        globalThis.localStorage?.removeItem(LS_KEY_BACKLOG_FAIL)
      }
      return HttpResponse.json(
        { title: 'Internal Server Error', status: 500 },
        { status: 500 },
      )
    }

    const projectKey = params['projectKey'] as string
    const project = backlogStore.get(projectKey)

    if (project === undefined) {
      return HttpResponse.json({
        data: {
          backlog: [],
          sprints: [],
          truncated: false,
        },
      })
    }

    // rank 순 정렬 — rerankIssueHandler가 rank를 갱신하지만 배열 순서는 그대로 유지하므로
    // GET 응답에서 매번 rank 기준으로 정렬해 반환한다. null rank는 맨 뒤(NULLS LAST).
    const byRankNullsLast = (a: { rank: string | null }, b: { rank: string | null }): number => {
      if (a.rank === null) return b.rank === null ? 0 : 1
      if (b.rank === null) return -1
      return a.rank < b.rank ? -1 : a.rank > b.rank ? 1 : 0
    }
    const sortedBacklog = [...project.backlog].sort(byRankNullsLast)
    const sortedSprints = project.sprints.map((sw) => ({
      sprint: sw.sprint,
      issues: [...sw.issues].sort(byRankNullsLast),
    }))

    return HttpResponse.json({
      data: {
        backlog: sortedBacklog,
        sprints: sortedSprints,
        // truncated 토글은 픽스처 값을 **덮어쓰지 않고 올리기만** 한다 — 시나리오가 끝나도
        // 원래 true 였던 프로젝트가 false 로 뒤집히면 안 된다.
        truncated: project.truncated || toggle(LS_KEY_BACKLOG_TRUNCATED) === 'true',
      },
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/issues/:key/rank
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PATCH /api/v1/issues/{key}/rank — 이슈 rank 변경.
 *
 * 요청 body: { previousIssueKey?: string, nextIssueKey?: string }
 *
 * stateful 동작.
 *   - store에서 이슈를 찾아 previousIssueKey·nextIssueKey 이웃의 rank 사이 값을 계산한다.
 *   - 이슈 rank를 갱신하고 version을 +1 증가한다.
 *   - 이후 GET backlog 재조회에 즉시 반영된다.
 *
 * 성공 → 200 { data: IssueRankResult }
 * 이슈 미존재 → 404
 */
const rerankIssueHandler = http.patch(
  '/api/v1/issues/:key/rank',
  async ({ params, request }) => {
    const issueKey = params['key'] as string

    // body 파싱
    let previousIssueKey: string | undefined
    let nextIssueKey: string | undefined

    try {
      const body = (await request.json()) as {
        previousIssueKey?: string
        nextIssueKey?: string
      }
      previousIssueKey = body.previousIssueKey
      nextIssueKey = body.nextIssueKey
    } catch {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
        { status: 400 },
      )
    }

    // store 전체에서 issueKey 탐색
    let foundIssue = undefined
    let foundProject = undefined

    for (const project of backlogStore.values()) {
      const issue = findIssueInProject(project, issueKey)
      if (issue !== undefined) {
        foundIssue = issue
        foundProject = project
        break
      }
    }

    if (foundIssue === undefined || foundProject === undefined) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_NOT_FOUND', message: `이슈를 찾을 수 없습니다: ${issueKey}` },
        { status: 404 },
      )
    }

    // 이웃 이슈의 rank를 찾아 중간값 계산
    const previousIssue = previousIssueKey !== undefined
      ? findIssueInProject(foundProject, previousIssueKey)
      : undefined
    const nextIssue = nextIssueKey !== undefined
      ? findIssueInProject(foundProject, nextIssueKey)
      : undefined

    const newRank = computeRank(
      previousIssue?.rank ?? null,
      nextIssue?.rank ?? null,
    )

    // store 변이 — version +1, rank 갱신
    foundIssue.rank = newRank
    foundIssue.version = foundIssue.version + 1

    return HttpResponse.json({
      data: {
        key: foundIssue.key,
        rank: newRank,
        version: foundIssue.version,
      },
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints/:id/issues (이슈 → 스프린트 할당)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/sprints/{id}/issues — 이슈를 스프린트에 할당.
 *
 * 요청 body: { issueKey: string }
 *
 * stateful 동작.
 *   - store에서 issueKey를 찾아 기존 위치(backlog 또는 다른 스프린트)에서 제거한다.
 *   - 대상 스프린트 issues 배열에 추가한다.
 *   - 이후 GET backlog 재조회에 즉시 반영된다.
 *
 * 성공 → 201 (body 없음)
 * 스프린트 미존재 → 404
 * body 파싱 실패 → 400
 */
const assignToSprintHandler = http.post(
  '/api/v1/sprints/:id/issues',
  async ({ params, request }) => {
    const sprintId = params['id'] as string

    // 스프린트 존재 확인
    const entry = findSprintInStore(sprintId)
    if (entry === undefined) {
      return HttpResponse.json(
        { errorCode: 'SPRINT_NOT_FOUND', message: `스프린트를 찾을 수 없습니다: ${sprintId}` },
        { status: 404 },
      )
    }

    // body 파싱
    let issueKey: string

    try {
      const body = (await request.json()) as { issueKey?: string }
      issueKey = body.issueKey ?? ''
    } catch {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
        { status: 400 },
      )
    }

    if (issueKey === '') {
      return HttpResponse.json(
        { errorCode: 'INVALID_REQUEST', message: 'issueKey는 필수입니다' },
        { status: 400 },
      )
    }

    // 이슈를 현재 위치(백로그 또는 다른 스프린트)에서 제거
    // 같은 프로젝트에서 먼저 찾고, 없으면 전체 store에서 찾음
    const { project: targetProject, storedSprint } = entry

    const removed = removeIssueFromProject(targetProject, issueKey)

    if (removed !== undefined) {
      // 동일 프로젝트 내 이동
      storedSprint.issues.push(removed)
    } else {
      // 다른 프로젝트에서 이슈 탐색 (cross-project 지원)
      let crossIssue: ReturnType<typeof removeIssueFromProject> = undefined
      for (const project of backlogStore.values()) {
        if (project.projectKey === targetProject.projectKey) continue
        crossIssue = removeIssueFromProject(project, issueKey)
        if (crossIssue !== undefined) break
      }

      if (crossIssue !== undefined) {
        storedSprint.issues.push(crossIssue)
      }
      // issueKey 자체가 없는 경우 — 201로 멱등 처리 (백엔드 동일 동작)
    }

    return new HttpResponse(null, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/sprints/:id/issues/:issueKey (스프린트 → 백로그)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DELETE /api/v1/sprints/{id}/issues/{issueKey} — 이슈를 스프린트에서 제거 (백로그 복귀).
 *
 * stateful 동작.
 *   - store에서 스프린트를 찾아 issueKey를 issues 배열에서 제거한다.
 *   - 해당 프로젝트의 backlog 배열에 추가한다.
 *   - 이후 GET backlog 재조회에 즉시 반영된다.
 *
 * 성공 → 204 (body 없음)
 * 스프린트 미존재 → 404
 */
const unassignFromSprintHandler = http.delete(
  '/api/v1/sprints/:id/issues/:issueKey',
  ({ params }) => {
    const sprintId = params['id'] as string
    const issueKey = params['issueKey'] as string

    // 스프린트 존재 확인
    const entry = findSprintInStore(sprintId)
    if (entry === undefined) {
      return HttpResponse.json(
        { errorCode: 'SPRINT_NOT_FOUND', message: `스프린트를 찾을 수 없습니다: ${sprintId}` },
        { status: 404 },
      )
    }

    // 부분 실패 토글 — 목록에 든 이슈 키만 500. 나머지는 정상 204 (S7·S18)
    if (toggleKeys(LS_KEY_SPRINT_UNASSIGN_FAIL).includes(issueKey)) {
      return HttpResponse.json(
        { title: 'Internal Server Error', status: 500 },
        { status: 500 },
      )
    }

    const { project, storedSprint } = entry

    // 스프린트 issues 배열에서 제거
    const idx = storedSprint.issues.findIndex((i) => i.key === issueKey)
    if (idx !== -1) {
      const [removed] = storedSprint.issues.splice(idx, 1)
      if (removed !== undefined) {
        // 백로그 맨 뒤에 추가
        project.backlog.push(removed)
      }
    }
    // 이슈가 없어도 204로 멱등 처리 (백엔드 동일 동작)

    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints (스프린트 생성)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/sprints — 새 스프린트 생성.
 *
 * 요청 body: { projectKey: string, name: string, goal?: string, startDate?: string, endDate?: string }
 *
 * stateful 동작.
 *   - 새 스프린트를 store에 추가한다.
 *   - 이후 GET backlog 재조회 시 sprints 목록에 등장한다.
 *
 * 성공 → 201 { data: SprintMeta }
 * body 파싱 실패 → 400
 */
const createSprintHandler = http.post('/api/v1/sprints', async ({ request }) => {
  let projectKey = ''
  let name = ''
  let goal: string | undefined
  let startDate: string | undefined
  let endDate: string | undefined

  try {
    const body = (await request.json()) as {
      projectKey?: string
      name?: string
      goal?: string
      startDate?: string
      endDate?: string
    }
    projectKey = body.projectKey ?? ''
    name = body.name ?? ''
    goal = body.goal
    startDate = body.startDate
    endDate = body.endDate
  } catch {
    return HttpResponse.json(
      { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
      { status: 400 },
    )
  }

  if (projectKey === '' || name === '') {
    return HttpResponse.json(
      { errorCode: 'INVALID_REQUEST', message: 'projectKey와 name은 필수입니다' },
      { status: 400 },
    )
  }

  const sprint = createSprintInStore(projectKey, name, goal, startDate, endDate)

  return HttpResponse.json({ data: sprint }, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/sprints/:id (스프린트 메타 수정 — 3-state partial)
// ─────────────────────────────────────────────────────────────────────────────

/** {@link patchSprintHandler} 가 3-state 로 반영하는 필드 전수 */
const PATCHABLE_SPRINT_FIELDS = ['name', 'goal', 'startDate', 'endDate'] as const

/**
 * PATCH /api/v1/sprints/{id} — 스프린트 이름·목표·기간 수정 (FR-UX-13 F15 FR-14).
 *
 * 요청 body: `{ name?, goal?, startDate?, endDate?, version }` — **3-state partial**.
 * 키가 없으면 무변경, 명시 `null` 이면 값 삭제다 (백엔드 `JsonNullable` 계약).
 *
 * stateful 동작.
 *   - `version` 이 store 의 값과 어긋나면 **409** 를 반환하고 아무것도 바꾸지 않는다.
 *   - 전송된 필드만 반영하고 `version` 을 +1 한다.
 *   - 이후 GET backlog 재조회에 즉시 반영된다.
 *
 * 성공 → 200 `{ data: SprintMeta }`
 * 스프린트 미존재 → 404 · version 누락/body 파싱 실패 → 400 · version 불일치 → 409
 */
const patchSprintHandler = http.patch('/api/v1/sprints/:id', async ({ params, request }) => {
  if (toggle(LS_KEY_SPRINT_PATCH_FAIL) === 'true') {
    return HttpResponse.json({ title: 'Internal Server Error', status: 500 }, { status: 500 })
  }

  const sprintId = params['id'] as string

  const entry = findSprintInStore(sprintId)
  if (entry === undefined) {
    return HttpResponse.json(
      { errorCode: 'SPRINT_NOT_FOUND', message: `스프린트를 찾을 수 없습니다: ${sprintId}` },
      { status: 404 },
    )
  }

  let body: Record<string, unknown>
  try {
    body = (await request.json()) as Record<string, unknown>
  } catch {
    return HttpResponse.json(
      { errorCode: 'INVALID_REQUEST', message: '요청 body를 파싱할 수 없습니다' },
      { status: 400 },
    )
  }

  const version = body['version']
  if (typeof version !== 'number') {
    return HttpResponse.json(
      { errorCode: 'INVALID_REQUEST', message: 'version은 필수입니다' },
      { status: 400 },
    )
  }

  const { storedSprint } = entry
  if (version !== storedSprint.sprint.version) {
    return HttpResponse.json(
      {
        errorCode: 'SPRINT_VERSION_CONFLICT',
        message: '다른 사용자가 먼저 수정했습니다',
        status: 409,
      },
      { status: 409 },
    )
  }

  // 3-state 반영 — 키가 있을 때만 건드린다. `undefined` 인지가 아니라 **키가 있는지**로 판단해야
  // 「미전송(무변경)」과 「명시 null(삭제)」이 갈린다.
  for (const field of PATCHABLE_SPRINT_FIELDS) {
    if (!Object.prototype.hasOwnProperty.call(body, field)) continue
    const value = body[field]
    if (field === 'name') {
      // 백엔드는 name 에 null 을 허용하지 않는다 (JsonNullable<String>)
      if (typeof value === 'string') storedSprint.sprint.name = value
      continue
    }
    storedSprint.sprint[field] = typeof value === 'string' ? value : null
  }
  storedSprint.sprint.version = storedSprint.sprint.version + 1

  return HttpResponse.json({ data: storedSprint.sprint })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints/:id/start
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/sprints/{id}/start — 스프린트 시작 (PLANNED → ACTIVE).
 *
 * stateful 동작.
 *   - store에서 스프린트를 찾아 status를 'ACTIVE'로 변경한다.
 *   - version을 +1 증가한다.
 *
 * 성공 → 200 { data: SprintMeta }
 * 스프린트 미존재 → 404
 */
const startSprintHandler = http.post('/api/v1/sprints/:id/start', ({ params }) => {
  const sprintId = params['id'] as string

  // 실패 토글 — 'true'는 500, '409'는 상태 전이 충돌 (S5 · E10)
  const startFail = toggle(LS_KEY_SPRINT_START_FAIL)
  if (startFail === 'true') {
    return HttpResponse.json({ title: 'Internal Server Error', status: 500 }, { status: 500 })
  }
  if (startFail === '409') {
    return HttpResponse.json(
      {
        errorCode: 'SPRINT_INVALID_TRANSITION',
        message: '이미 시작된 스프린트입니다',
        status: 409,
      },
      { status: 409 },
    )
  }

  const entry = findSprintInStore(sprintId)
  if (entry === undefined) {
    return HttpResponse.json(
      { errorCode: 'SPRINT_NOT_FOUND', message: `스프린트를 찾을 수 없습니다: ${sprintId}` },
      { status: 404 },
    )
  }

  const { storedSprint } = entry
  storedSprint.sprint.status = 'ACTIVE'
  storedSprint.sprint.version = storedSprint.sprint.version + 1

  return HttpResponse.json({ data: storedSprint.sprint })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/sprints/:id/complete
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/sprints/{id}/complete — 스프린트 완료 (ACTIVE → COMPLETED).
 *
 * stateful 동작.
 *   - store에서 스프린트를 찾아 status를 'COMPLETED'로 변경한다.
 *   - version을 +1 증가한다.
 *
 * 성공 → 200 { data: SprintMeta }
 * 스프린트 미존재 → 404
 */
const completeSprintHandler = http.post('/api/v1/sprints/:id/complete', ({ params }) => {
  const sprintId = params['id'] as string

  const entry = findSprintInStore(sprintId)
  if (entry === undefined) {
    return HttpResponse.json(
      { errorCode: 'SPRINT_NOT_FOUND', message: `스프린트를 찾을 수 없습니다: ${sprintId}` },
      { status: 404 },
    )
  }

  const { storedSprint } = entry
  storedSprint.sprint.status = 'COMPLETED'
  storedSprint.sprint.version = storedSprint.sprint.version + 1

  return HttpResponse.json({ data: storedSprint.sprint })
})

// ─────────────────────────────────────────────────────────────────────────────
// export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 BC MSW 핸들러 배열.
 *
 * handlers.ts에서 backlogHandlers를 spread해 등록한다.
 * GET /api/v1/projects/:projectKey/backlog
 * PATCH /api/v1/issues/:key/rank
 * POST /api/v1/sprints/:id/issues
 * DELETE /api/v1/sprints/:id/issues/:issueKey
 * POST /api/v1/sprints
 * PATCH /api/v1/sprints/:id
 * POST /api/v1/sprints/:id/start
 * POST /api/v1/sprints/:id/complete
 * 모두 포함.
 *
 * ★핸들러를 만들고 **이 배열에 넣지 않으면** MSW 는 그 요청을 미처리로 흘린다. 등록 누락은
 * 「핸들러 부재」와 완전히 같은 증상이라 파일 안에 코드가 있는 것만으로는 아무 보증이 없다.
 */
export const backlogHandlers = [
  getBacklogHandler,
  rerankIssueHandler,
  assignToSprintHandler,
  unassignFromSprintHandler,
  createSprintHandler,
  patchSprintHandler,
  startSprintHandler,
  completeSprintHandler,
]
