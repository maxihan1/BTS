// 프로젝트 요약·활동 API MSW 핸들러 — 대시보드 차트/활동 가젯과 프로젝트 요약 화면의 데이터원
//
// ★없어서 만든 것이 아니라, **없다는 것을 이 PR 이 발견해서** 만든다.
//   `apps/web/src/routes/projects.$projectKey.tsx` 와 `apps/web/src/api/project-summary.ts` 는
//   진작 있는데 여기 핸들러가 0개였다 — 개발 서버와 e2e 에서 요약 화면이 계속 에러 상태였다는
//   뜻이다. 이 PR 의 pie/bar/activity 가젯이 같은 두 엔드포인트를 재사용하므로 함께 푼다.
import { http, HttpResponse } from 'msw'
import type { ProjectActivity, ProjectSummary } from '@/api/project-summary'

/**
 * ATLAS 프로젝트 요약 픽스처.
 *
 * ATLAS 만 값이 있고 그 외 키는 **0건 요약**을 준다 — 빈 상태를 눈으로 볼 수 있어야 하고(F-4),
 * 어떤 프로젝트를 골라도 404 로 죽지 않아야 한다.
 */
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
  const data = projectKey === 'ATLAS' ? ATLAS_SUMMARY : emptySummary(projectKey)
  return HttpResponse.json({ data })
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
    const entries = projectKey === 'ATLAS' ? ATLAS_ACTIVITY.entries.slice(0, limit) : []
    return HttpResponse.json({ data: { entries } })
  },
)

/** 프로젝트 요약·활동 핸들러 목록 */
export const projectSummaryHandlers = [getProjectSummaryHandler, getProjectActivityHandler]
