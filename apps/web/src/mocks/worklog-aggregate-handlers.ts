// 워크로그 집계 BC MSW 핸들러 — GET /api/v1/worklogs/aggregate (FR-TT-02 D6)
import { http, HttpResponse } from 'msw'
import type { WorklogAggregateResponse } from '@/api/worklog-aggregate'

// ─────────────────────────────────────────────────────────────────────────────
// 권한 없는 프로젝트 키 상수 — E2E 시나리오용
// ─────────────────────────────────────────────────────────────────────────────

/** 이 프로젝트 키가 요청되면 403을 반환한다 (E2E 권한 시나리오 테스트용) */
const FORBIDDEN_PROJECT_KEY = 'FORBIDDEN'

// ─────────────────────────────────────────────────────────────────────────────
// 이슈별 집계 fixture
// ─────────────────────────────────────────────────────────────────────────────

/** by=issue 응답 fixture — 버킷 3개 */
const ISSUE_AGGREGATE_FIXTURE: WorklogAggregateResponse = {
  by: 'issue',
  buckets: [
    { key: 'ATLAS-1', label: 'ATLAS-1', timeSpentSeconds: 7200, worklogCount: 2 },
    { key: 'ATLAS-2', label: 'ATLAS-2', timeSpentSeconds: 3600, worklogCount: 1 },
    { key: 'ATLAS-3', label: 'ATLAS-3', timeSpentSeconds: 1800, worklogCount: 1 },
  ],
  totalTimeSpentSeconds: 12600,
}

// ─────────────────────────────────────────────────────────────────────────────
// 사용자별 집계 fixture
// ─────────────────────────────────────────────────────────────────────────────

/**
 * by=user 응답 fixture — 빈 displayName 1개 포함.
 * 빈 displayName은 프론트 테이블에서 "(알 수 없음)"으로 대체한다.
 */
const USER_AGGREGATE_FIXTURE: WorklogAggregateResponse = {
  by: 'user',
  buckets: [
    {
      key: '00000000-0000-4000-8000-000000000001',
      label: 'alice',
      timeSpentSeconds: 9000,
      worklogCount: 3,
    },
    {
      key: '00000000-0000-4000-8000-000000000002',
      label: 'bob',
      timeSpentSeconds: 3600,
      worklogCount: 1,
    },
    {
      key: '00000000-0000-4000-8000-000000000099',
      label: '',
      timeSpentSeconds: 600,
      worklogCount: 1,
    },
  ],
  totalTimeSpentSeconds: 13200,
}

// ─────────────────────────────────────────────────────────────────────────────
// 기간별 집계 fixture
// ─────────────────────────────────────────────────────────────────────────────

/** by=period 응답 fixture — granularity=week 기준 버킷 3개 */
const PERIOD_AGGREGATE_FIXTURE: WorklogAggregateResponse = {
  by: 'period',
  granularity: 'week',
  buckets: [
    { key: '2026-W24', label: '2026-W24', timeSpentSeconds: 18000, worklogCount: 5 },
    { key: '2026-W25', label: '2026-W25', timeSpentSeconds: 14400, worklogCount: 4 },
    { key: '2026-W26', label: '2026-W26', timeSpentSeconds: 7200, worklogCount: 2 },
  ],
  totalTimeSpentSeconds: 39600,
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/worklogs/aggregate 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 집계 조회 핸들러.
 *
 * 쿼리파라미터 분기.
 * - project=FORBIDDEN → 403
 * - by=issue → 이슈별 버킷 fixture
 * - by=user → 사용자별 버킷 fixture (빈 displayName 1개 포함)
 * - by=period → 기간별 버킷 fixture (granularity=week 고정)
 * - by 미지정 또는 기타 → 이슈별 fixture 반환 (기본)
 *
 * 응답 형식은 백엔드 WorklogAggregateResponse DTO와 1:1 대응.
 */
const getWorklogAggregateHandler = http.get('/api/v1/worklogs/aggregate', ({ request }) => {
  const url = new URL(request.url)
  const project = url.searchParams.get('project') ?? ''
  const by = url.searchParams.get('by') ?? 'issue'

  // 권한 없는 프로젝트 → 403
  if (project === FORBIDDEN_PROJECT_KEY) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/access-denied',
        title: 'Access Denied',
        status: 403,
        detail: '해당 프로젝트에 접근할 권한이 없습니다.',
        errorCode: 'ACCESS_DENIED',
        timestamp: new Date().toISOString(),
      },
      { status: 403 },
    )
  }

  // 차원별 fixture 분기
  if (by === 'user') {
    return HttpResponse.json({ data: USER_AGGREGATE_FIXTURE })
  }

  if (by === 'period') {
    return HttpResponse.json({ data: PERIOD_AGGREGATE_FIXTURE })
  }

  // 기본(issue) 또는 기타
  return HttpResponse.json({ data: ISSUE_AGGREGATE_FIXTURE })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 워크로그 집계 BC MSW 핸들러 배열 */
export const worklogAggregateHandlers = [getWorklogAggregateHandler]
