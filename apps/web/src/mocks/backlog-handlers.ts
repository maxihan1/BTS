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
// GET /api/v1/projects/:projectKey/backlog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/projects/{projectKey}/backlog — 백로그 전체 뷰 조회.
 *
 * store에서 해당 projectKey의 BacklogProject를 읽어
 * { data: { backlog, sprints, truncated } } 형식으로 반환한다.
 * 프로젝트가 없으면 빈 백로그·스프린트를 반환한다.
 *
 * 성공 → 200 { data: BacklogView }
 */
const getBacklogHandler = http.get(
  '/api/v1/projects/:projectKey/backlog',
  ({ params }) => {
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

    return HttpResponse.json({
      data: {
        backlog: project.backlog,
        sprints: project.sprints,
        truncated: project.truncated,
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
 * POST /api/v1/sprints/:id/start
 * POST /api/v1/sprints/:id/complete
 * 모두 포함.
 */
export const backlogHandlers = [
  getBacklogHandler,
  rerankIssueHandler,
  assignToSprintHandler,
  unassignFromSprintHandler,
  createSprintHandler,
  startSprintHandler,
  completeSprintHandler,
]
