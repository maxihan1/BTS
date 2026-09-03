// 프로젝트 요약·활동 API 클라이언트 단위 테스트 — Zod 계약 · fetch · 델타 순수 함수 (Jira 패리티 J4)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  fetchProjectActivity,
  fetchProjectSummary,
  isProjectSummaryEmpty,
  projectActivitySchema,
  projectSummarySchema,
  resolveWindowDelta,
  type ProjectSummary,
} from './project-summary'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const projectKey = 'BTS'

/** 백엔드가 실제로 내주는 형태 — nullable 필드는 키 자체가 빠진다(@JsonInclude NON_NULL) */
const summaryFixture: ProjectSummary = {
  projectKey,
  recent: {
    windowDays: 7,
    completed: { current: 12, previous: 8 },
    updated: { current: 34, previous: 34 },
    created: { current: 18, previous: 12 },
  },
  upcoming: { windowDays: 7, due: 5, overdue: 2 },
  statusOverview: [
    { statusKey: 'IN_PROGRESS', statusName: '진행 중', category: 'IN_PROGRESS', count: 9 },
  ],
  priorityBreakdown: [{ priority: 3, priorityName: '보통', count: 12 }],
  typesOfWork: [{ typeKey: 'story', typeName: '스토리', count: 22 }],
  teamWorkload: [{ assigneeId: 'u-1', assigneeName: '성민제', count: 7 }],
}

const emptySummaryFixture: ProjectSummary = {
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

// ─────────────────────────────────────────────────────────────────────────────
// Zod 계약
// ─────────────────────────────────────────────────────────────────────────────

describe('projectSummarySchema — 백엔드 DTO 계약', () => {
  it('전 필드가 채워진 응답을 파싱한다', () => {
    expect(projectSummarySchema.parse(summaryFixture)).toEqual(summaryFixture)
  })

  it('NON_NULL 로 빠진 키를 허용한다 — statusName · priorityName · assigneeId · assigneeName', () => {
    const withMissingKeys = {
      ...summaryFixture,
      statusOverview: [{ statusKey: 'CUSTOM', category: 'TODO', count: 1 }],
      priorityBreakdown: [{ priority: 9, count: 1 }],
      teamWorkload: [{ count: 4 }],
    }
    const parsed = projectSummarySchema.parse(withMissingKeys)
    expect(parsed.statusOverview[0]?.statusName).toBeUndefined()
    expect(parsed.priorityBreakdown[0]?.priorityName).toBeUndefined()
    expect(parsed.teamWorkload[0]?.assigneeId).toBeUndefined()
  })

  it('백엔드가 StatusCategory 를 늘려도 파싱이 죽지 않는다 — 화면이 안 쓰는 필드가 전체를 막지 않는다', () => {
    const widened = {
      ...summaryFixture,
      statusOverview: [{ statusKey: 'X', statusName: 'X', category: 'BLOCKED', count: 1 }],
    }
    expect(projectSummarySchema.parse(widened).statusOverview[0]?.category).toBe('BLOCKED')
  })

  it('typesOfWork 의 typeName 은 non-null 이라 누락을 거부한다', () => {
    const bogus = { ...summaryFixture, typesOfWork: [{ typeKey: 'story', count: 1 }] }
    expect(() => projectSummarySchema.parse(bogus)).toThrow()
  })
})

describe('projectActivitySchema — 백엔드 DTO 계약', () => {
  it('changeItemSchema 를 재사용해 items 를 파싱한다', () => {
    const parsed = projectActivitySchema.parse({
      entries: [
        {
          issueKey: 'BTS-401',
          actorId: 'u-1',
          actorName: '성민제',
          createdAt: '2026-09-03T10:00:00Z',
          items: [
            { field: 'status', fromValue: 'TODO', toValue: 'DONE', fromLabel: null, toLabel: '완료' },
          ],
        },
      ],
    })
    expect(parsed.entries[0]?.items[0]?.toLabel).toBe('완료')
  })

  it('시스템 자동 변경은 actorId · actorName 키가 빠진 채로 온다', () => {
    const parsed = projectActivitySchema.parse({
      entries: [{ issueKey: 'BTS-1', createdAt: '2026-09-03T10:00:00Z', items: [] }],
    })
    expect(parsed.entries[0]?.actorName).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetch
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchProjectSummary', () => {
  it('200 이면 data 를 벗겨 반환한다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/summary`, () =>
        HttpResponse.json({ data: summaryFixture }),
      ),
    )
    await expect(fetchProjectSummary(projectKey)).resolves.toEqual(summaryFixture)
  })

  it('403 이면 ApiError(403) 을 던진다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/summary`, () =>
        HttpResponse.json({ message: 'forbidden' }, { status: 403 }),
      ),
    )
    await expect(fetchProjectSummary(projectKey)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('fetchProjectActivity', () => {
  it('limit 을 쿼리 파라미터로 싣는다 — 기본 20', async () => {
    let seen: string | null = null
    server.use(
      http.get(`/api/v1/projects/${projectKey}/activity`, ({ request }) => {
        seen = new URL(request.url).searchParams.get('limit')
        return HttpResponse.json({ data: { entries: [] } })
      }),
    )
    await fetchProjectActivity(projectKey)
    expect(seen).toBe('20')

    await fetchProjectActivity(projectKey, 5)
    expect(seen).toBe('5')
  })

  it('400(limit 범위 밖)이면 ApiError 를 던진다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/activity`, () =>
        HttpResponse.json({ message: 'bad limit' }, { status: 400 }),
      ),
    )
    await expect(fetchProjectActivity(projectKey, 999)).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveWindowDelta', () => {
  it('증가 · 감소 · 변동 없음을 방향으로 가른다', () => {
    expect(resolveWindowDelta({ current: 12, previous: 8 })).toEqual({ diff: 4, direction: 'up' })
    expect(resolveWindowDelta({ current: 8, previous: 12 })).toEqual({ diff: -4, direction: 'down' })
    expect(resolveWindowDelta({ current: 34, previous: 34 })).toEqual({ diff: 0, direction: 'flat' })
  })

  it('직전 창이 0 이어도 나눗셈 없이 차이만 낸다 — 0 나누기 NaN 차단', () => {
    expect(resolveWindowDelta({ current: 5, previous: 0 })).toEqual({ diff: 5, direction: 'up' })
  })
})

describe('isProjectSummaryEmpty', () => {
  it('분포 4종이 모두 비면 true', () => {
    expect(isProjectSummaryEmpty(emptySummaryFixture)).toBe(true)
  })

  it('분포가 하나라도 있으면 false', () => {
    expect(isProjectSummaryEmpty(summaryFixture)).toBe(false)
  })

  it('카드 값이 0 이어도 분포가 있으면 빈 화면이 아니다 — 0 은 정보다', () => {
    const zeroCardsWithSlices: ProjectSummary = {
      ...emptySummaryFixture,
      statusOverview: summaryFixture.statusOverview,
    }
    expect(isProjectSummaryEmpty(zeroCardsWithSlices)).toBe(false)
  })
})
