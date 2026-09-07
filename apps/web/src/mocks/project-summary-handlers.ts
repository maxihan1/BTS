// 프로젝트 요약·활동 API MSW 핸들러 — 대시보드 차트/활동 가젯과 프로젝트 요약 화면의 데이터원
//
// ★없어서 만든 것이 아니라, **없다는 것을 이 PR 이 발견해서** 만든다.
//   `apps/web/src/routes/projects.$projectKey.tsx` 와 `apps/web/src/api/project-summary.ts` 는
//   진작 있는데 여기 핸들러가 0개였다 — 개발 서버와 e2e 에서 요약 화면이 계속 에러 상태였다는
//   뜻이다. 이 PR 의 pie/bar/activity 가젯이 같은 두 엔드포인트를 재사용하므로 함께 푼다.
import { http, HttpResponse } from 'msw'
import type { ProjectActivity, ProjectSummary } from '@/api/project-summary'

/**
 * 값이 하나도 없는 프로젝트 키 — 빈 상태 눈확인 전용.
 *
 * ★「모르는 키는 전부 빈 요약」으로 두지 않는다. 그렇게 두면 **어떤 키를 줘도 200** 이라
 * 엣지 E4(없는 `projectKey` → 오류를 빈 상태로 흡수)가 e2e·개발 서버에서 **도달 불가**가 된다.
 * 실제 `ProjectSummaryController` 는 미인증 401 · BROWSE 권한 없음 403 을 내므로, mock 이
 * 더 관대하면 개발 중엔 통과하고 운영에서만 터진다 — 이 파일의 activity 핸들러가 `limit`
 * 계약을 지키며 스스로 적어 둔 기준이고, summary 만 그것을 어기고 있었다(리뷰 지적).
 *
 * 빈 상태는 이 **명시적인 키**로만 낸다.
 */
const EMPTY_PROJECT_KEY = 'EMPTY'

/**
 * ATLAS 프로젝트 요약 픽스처.
 *
 * 값이 있는 것은 ATLAS 뿐이고, 빈 상태는 [EMPTY_PROJECT_KEY], 그 밖의 키는 404 다.
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

  if (projectKey === 'ATLAS') return HttpResponse.json({ data: ATLAS_SUMMARY })
  if (projectKey === EMPTY_PROJECT_KEY) {
    return HttpResponse.json({ data: emptySummary(projectKey) })
  }
  // 모르는 키는 404 다 — 관대하게 200 을 주면 E4 를 아무도 못 재게 된다.
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
    if (projectKey === 'ATLAS') {
      return HttpResponse.json({ data: { entries: ATLAS_ACTIVITY.entries.slice(0, limit) } })
    }
    if (projectKey === EMPTY_PROJECT_KEY) return HttpResponse.json({ data: { entries: [] } })
    // summary 와 같은 기준 — 모르는 키는 404.
    return HttpResponse.json({ message: `프로젝트를 찾을 수 없습니다: ${projectKey}` }, { status: 404 })
  },
)

/** 프로젝트 요약·활동 핸들러 목록 */
export const projectSummaryHandlers = [getProjectSummaryHandler, getProjectActivityHandler]
