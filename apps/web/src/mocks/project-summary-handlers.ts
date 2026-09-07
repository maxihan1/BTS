// 프로젝트 요약·활동 API MSW 핸들러 — 대시보드 차트/활동 가젯과 프로젝트 요약 화면의 데이터원
//
// ★없어서 만든 것이 아니라, **없다는 것을 이 PR 이 발견해서** 만든다.
//   `apps/web/src/routes/projects.$projectKey.tsx` 와 `apps/web/src/api/project-summary.ts` 는
//   진작 있는데 여기 핸들러가 0개였다 — 개발 서버와 e2e 에서 요약 화면이 계속 에러 상태였다는
//   뜻이다. 이 PR 의 pie/bar/activity 가젯이 같은 두 엔드포인트를 재사용하므로 함께 푼다.
import { http, HttpResponse } from 'msw'
import type { ProjectActivity, ProjectSummary } from '@/api/project-summary'

/**
 * 프로젝트 시드 — `project-handlers.ts` 의 활성 프로젝트 키와 **같아야 한다**.
 *
 * ★백엔드를 그대로 미러한다. 실제 `ProjectSummaryController` 는
 *   - **존재하는** 프로젝트에 데이터가 없으면 → 빈 요약 **200**
 *   - **없는** 프로젝트면 → 404
 * 이고, 이 둘은 다른 사건이다.
 *
 * ★두 번 틀렸던 자리라 근거를 남긴다.
 *   1차 — 모르는 키에도 200(빈 요약)을 줬다. 그러면 어떤 키를 줘도 성공이라 엣지 E4 가
 *         도달 불가였다.
 *   2차 — 그것을 고치며 「ATLAS 와 EMPTY 만 200」으로 좁혔는데, `EMPTY` 는 프로젝트 시드에
 *         없는 키다. 같은 PR 이 `projectKey` 를 드롭다운으로 바꿔 목록 밖 키를 **타이핑할
 *         수단까지 없앤 뒤라**, 이번엔 반대로 **빈 상태 3종이 UI 로 도달 불가**가 됐다
 *         (「표시할 이슈가 없습니다」·「아직 활동이 없습니다」를 볼 방법이 사라졌다).
 *   지금은 존재/부재를 뭉치지 않는다 — 시드 안이면 200, 밖이면 404 다.
 */
const SEEDED_PROJECT_KEYS = ['ATLAS', 'MIDDLE', 'ZETA'] as const

/** 데이터가 실린 프로젝트. 나머지 시드 키는 빈 요약을 준다 — 빈 상태를 UI 로 볼 수 있어야 한다(F-4). */
const POPULATED_PROJECT_KEY = 'ATLAS'

/** 시드에 있는 키인가 — 있으면 200(빈 요약이라도), 없으면 404. */
function isSeeded(projectKey: string): boolean {
  return (SEEDED_PROJECT_KEYS as readonly string[]).includes(projectKey)
}

/** ATLAS 프로젝트 요약 픽스처. */
const ATLAS_SUMMARY: ProjectSummary = {
  projectKey: 'ATLAS',
  recent: {
    windowDays: 7,
    completed: { current: 12, previous: 9 },
    updated: { current: 34, previous: 41 },
    created: { current: 18, previous: 15 },
  },
  upcoming: { windowDays: 7, due: 6, overdue: 2 },
  statusOverview: [
    { statusKey: 'todo', statusName: '할 일', category: 'TODO', count: 14 },
    { statusKey: 'in_progress', statusName: '진행 중', category: 'IN_PROGRESS', count: 8 },
    { statusKey: 'in_review', statusName: '검토 중', category: 'IN_PROGRESS', count: 3 },
    { statusKey: 'done', statusName: '완료', category: 'DONE', count: 21 },
  ],
  priorityBreakdown: [
    { priority: 1, priorityName: 'Highest', count: 2 },
    { priority: 2, priorityName: 'High', count: 7 },
    { priority: 3, priorityName: 'Medium', count: 24 },
    { priority: 4, priorityName: 'Low', count: 11 },
    { priority: 5, priorityName: 'Lowest', count: 2 },
  ],
  typesOfWork: [
    { typeKey: 'story', typeName: '스토리', count: 19 },
    { typeKey: 'bug', typeName: '버그', count: 15 },
    { typeKey: 'task', typeName: '작업', count: 12 },
  ],
  teamWorkload: [
    { assigneeId: '00000000-0000-4000-8000-000000000001', assigneeName: 'Alice', count: 17 },
    { assigneeId: '00000000-0000-4000-8000-000000000002', assigneeName: 'Bob', count: 13 },
    // assigneeId 부재 = 미할당 묶음. 화면이 이 분기를 그리는지 확인하는 자리다.
    { assigneeId: null, assigneeName: null, count: 6 },
  ],
}

/** 값이 하나도 없는 프로젝트 — 가젯 빈 상태를 재는 자리 */
function emptySummary(projectKey: string): ProjectSummary {
  return {
    projectKey,
    recent: {
      windowDays: 7,
      completed: { current: 0, previous: 0 },
      updated: { current: 0, previous: 0 },
      created: { current: 0, previous: 0 },
    },
    upcoming: { windowDays: 7, due: 0, overdue: 0 },
    statusOverview: [],
    priorityBreakdown: [],
    typesOfWork: [],
    teamWorkload: [],
  }
}

/** 활동 피드 픽스처 — ATLAS 만 항목이 있다 */
const ATLAS_ACTIVITY: ProjectActivity = {
  entries: [
    {
      issueKey: 'ATLAS-101',
      actorId: '00000000-0000-4000-8000-000000000001',
      actorName: 'Alice',
      createdAt: '2026-09-06T09:15:00Z',
      items: [
        {
          field: 'status',
          fromValue: 'todo',
          toValue: 'in_progress',
          fromLabel: null,
          toLabel: null,
        },
      ],
    },
    {
      issueKey: 'ATLAS-98',
      actorId: '00000000-0000-4000-8000-000000000002',
      actorName: 'Bob',
      createdAt: '2026-09-06T08:40:00Z',
      items: [
        {
          field: 'assignee',
          fromValue: null,
          toValue: '00000000-0000-4000-8000-000000000001',
          fromLabel: null,
          toLabel: 'Alice',
        },
      ],
    },
    {
      issueKey: 'ATLAS-95',
      actorId: null,
      actorName: null,
      createdAt: '2026-09-05T17:02:00Z',
      items: [
        {
          field: 'summary',
          fromValue: '초안',
          toValue: '요약 정리',
          fromLabel: null,
          toLabel: null,
        },
      ],
    },
  ],
}

/**
 * GET /api/v1/projects/:projectKey/summary → 200 { data: ProjectSummary }
 *
 * 창은 백엔드 고정(최근 7일 · 상태 개요 DONE 만 최근 2주)이라 쿼리 파라미터가 없다.
 */
const getProjectSummaryHandler = http.get('/api/v1/projects/:projectKey/summary', ({ params }) => {
  const projectKey = String(params['projectKey'])

  if (projectKey === POPULATED_PROJECT_KEY) return HttpResponse.json({ data: ATLAS_SUMMARY })
  // 존재하지만 데이터가 없는 프로젝트 — 빈 상태를 UI 로 볼 수 있는 유일한 경로다.
  if (isSeeded(projectKey)) return HttpResponse.json({ data: emptySummary(projectKey) })
  // 시드 밖 키만 404 — E4(없는 키를 빈 상태로 흡수)를 재는 자리.
  return HttpResponse.json({ message: `프로젝트를 찾을 수 없습니다: ${projectKey}` }, { status: 404 })
})

/**
 * GET /api/v1/projects/:projectKey/activity?limit=N → 200 { data: ProjectActivity }
 *
 * 백엔드가 limit 을 1~50 으로 강제하고 범위 밖은 400 이다. 그 계약을 여기서도 지킨다 —
 * mock 이 더 관대하면 프론트의 잘못된 limit 이 개발 중엔 통과하고 운영에서만 터진다.
 */
const getProjectActivityHandler = http.get(
  '/api/v1/projects/:projectKey/activity',
  ({ params, request }) => {
    const limitRaw = new URL(request.url).searchParams.get('limit')
    const limit = limitRaw === null ? 20 : Number(limitRaw)
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) {
      return HttpResponse.json({ message: 'limit 은 1~50 이어야 합니다.' }, { status: 400 })
    }

    const projectKey = String(params['projectKey'])
    if (projectKey === POPULATED_PROJECT_KEY) {
      return HttpResponse.json({ data: { entries: ATLAS_ACTIVITY.entries.slice(0, limit) } })
    }
    // summary 와 같은 기준 — 시드 안이면 빈 목록 200, 밖이면 404.
    if (isSeeded(projectKey)) return HttpResponse.json({ data: { entries: [] } })
    return HttpResponse.json({ message: `프로젝트를 찾을 수 없습니다: ${projectKey}` }, { status: 404 })
  },
)

/** 프로젝트 요약·활동 핸들러 목록 */
export const projectSummaryHandlers = [getProjectSummaryHandler, getProjectActivityHandler]
