// 타임라인 MSW 핸들러 동작 검증 테스트 (FR-TL-01 D6 Task-4 + FR-TL-02 D6 Task-3)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import {
  timelineHandlers,
  timelineForbiddenHandler,
  timelineTruncatedHandler,
  timelineEmptyHandler,
  timelineDepsTruncatedHandler,
  timelineDepsEmptyHandler,
} from './timeline-handlers'
import { BTS_TIMELINE_ITEMS, TRUNCATED_TIMELINE_ITEMS, BTS_TIMELINE_DEPS } from './timeline-fixtures'

const server = setupServer(...timelineHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 응답 타입 — 테스트 내부 편의용 (Zod 스키마 z.infer와 동형)
// ─────────────────────────────────────────────────────────────────────────────

interface TimelineItem {
  key: string
  summary: string
  issueType: string
  currentStateKey: string
  assigneeId: string | null
  startDate: string | null
  dueDate: string | null
  targetDate: string | null
  epicKey: string | null
}

interface TimelineResponse {
  items: TimelineItem[]
  truncated: boolean
}

interface DepEdge {
  blockerKey: string
  blockedKey: string
}

interface DepsResponse {
  deps: DepEdge[]
  truncated: boolean
}

interface DataResponse<T> {
  data: T
}

interface ProblemDetail {
  errorCode: string
  status: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 fetch 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function getTimeline(projectKey: string): Promise<Response> {
  return fetch(`/api/v1/timeline?project=${encodeURIComponent(projectKey)}`)
}

async function getTimelineDeps(projectKey: string): Promise<Response> {
  return fetch(`/api/v1/timeline/deps?project=${encodeURIComponent(projectKey)}`)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline?project=BTS — 기본 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline?project=BTS', () => {
  it('200 DataResponse 봉투 — { data: { items, truncated } }', async () => {
    const res = await getTimeline('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body).toHaveProperty('data')
    expect(body.data).toHaveProperty('items')
    expect(body.data).toHaveProperty('truncated')
    expect(typeof body.data.truncated).toBe('boolean')
  })

  it('items 배열이 비어있지 않음 — Epic, Story, Task, 미분류 포함', async () => {
    const res = await getTimeline('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.items.length).toBeGreaterThan(0)

    const types = body.data.items.map((i) => i.issueType)
    expect(types).toContain('epic')
    expect(types).toContain('story')
    expect(types).toContain('task')
  })

  it('Epic 아이템 — epicKey가 null', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    const epics = body.data.items.filter((i) => i.issueType === 'epic')
    expect(epics.length).toBeGreaterThan(0)
    for (const epic of epics) {
      expect(epic.epicKey).toBeNull()
    }
  })

  it('자식 이슈(story/task) — epicKey가 Epic의 key와 매칭', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    const epics = body.data.items.filter((i) => i.issueType === 'epic')
    const epicKeys = new Set(epics.map((e) => e.key))

    const children = body.data.items.filter(
      (i) => i.issueType !== 'epic' && i.epicKey !== null,
    )
    expect(children.length).toBeGreaterThan(0)
    for (const child of children) {
      expect(epicKeys.has(child.epicKey as string)).toBe(true)
    }
  })

  it('미분류 이슈 — epicKey가 null이고 issueType이 epic이 아님', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    const unclassified = body.data.items.filter(
      (i) => i.issueType !== 'epic' && i.epicKey === null,
    )
    expect(unclassified.length).toBeGreaterThan(0)
  })

  it('targetDate 포함 아이템 존재', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    const withTargetDate = body.data.items.filter((i) => i.targetDate !== null)
    expect(withTargetDate.length).toBeGreaterThan(0)
  })

  it('start만/due만 케이스 존재 — 개방 막대 EC1 커버', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    const startOnly = body.data.items.filter(
      (i) => i.startDate !== null && i.dueDate === null,
    )
    const dueOnly = body.data.items.filter(
      (i) => i.startDate === null && i.dueDate !== null,
    )
    expect(startOnly.length + dueOnly.length).toBeGreaterThan(0)
  })

  it('기본 BTS 프로젝트 — truncated=false', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.truncated).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline?project=TRUNCATED — truncated 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline?project=TRUNCATED', () => {
  it('200 — truncated=true 반환', async () => {
    const res = await getTimeline('TRUNCATED')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.truncated).toBe(true)
  })

  it('items 배열 존재 (부분 목록)', async () => {
    const res = await getTimeline('TRUNCATED')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.items.length).toBeGreaterThan(0)
    expect(body.data.items.length).toBe(TRUNCATED_TIMELINE_ITEMS.length)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline?project=FORBIDDEN — 403 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline?project=FORBIDDEN', () => {
  it('403 — errorCode AGILE_ACCESS_DENIED', async () => {
    const res = await getTimeline('FORBIDDEN')
    expect(res.status).toBe(403)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_ACCESS_DENIED')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline?project=EMPTY — 빈 목록 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline?project=EMPTY', () => {
  it('200 — items 빈 배열, truncated=false', async () => {
    const res = await getTimeline('EMPTY')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.items).toHaveLength(0)
    expect(body.data.truncated).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 백엔드 정렬 순서 검증 — startDate ASC NULLS LAST → dueDate ASC → key ASC
// ─────────────────────────────────────────────────────────────────────────────

describe('백엔드 정렬 순서 준수 — startDate ASC NULLS LAST → dueDate ASC → key ASC', () => {
  it('BTS fixture items가 startDate ASC NULLS LAST 순서를 준수한다', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    const items = body.data.items

    // startDate 있는 것들이 null보다 앞에 온다
    let seenNull = false
    for (const item of items) {
      if (item.startDate === null) {
        seenNull = true
      } else if (seenNull) {
        // null 뒤에 non-null이 오면 정렬 위반
        expect(false).toBe(true) // 명시적 실패
      }
    }
  })

  it('startDate가 같은 아이템은 dueDate ASC 순서', async () => {
    // fixture 설계 시 same-startDate 그룹이 dueDate 순서인지 검증
    // BTS_TIMELINE_ITEMS에서 startDate 같은 그룹 추출 후 dueDate 순서 확인
    const items = BTS_TIMELINE_ITEMS
    const byStart = new Map<string, typeof items>()
    for (const item of items) {
      if (item.startDate !== null) {
        const group = byStart.get(item.startDate) ?? []
        group.push(item)
        byStart.set(item.startDate, group)
      }
    }

    for (const [, group] of byStart) {
      if (group.length < 2) continue
      for (let i = 1; i < group.length; i++) {
        const prev = group[i - 1]
        const curr = group[i]
        if (prev === undefined || curr === undefined) continue
        if (prev.dueDate !== null && curr.dueDate !== null) {
          expect(prev.dueDate <= curr.dueDate).toBe(true)
        }
      }
    }
  })

  it('날짜 필드가 ISO date 문자열 형식("YYYY-MM-DD")이거나 null', async () => {
    const res = await getTimeline('BTS')
    const body = (await res.json()) as DataResponse<TimelineResponse>
    const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/

    for (const item of body.data.items) {
      if (item.startDate !== null) {
        expect(item.startDate).toMatch(ISO_DATE_RE)
      }
      if (item.dueDate !== null) {
        expect(item.dueDate).toMatch(ISO_DATE_RE)
      }
      if (item.targetDate !== null) {
        expect(item.targetDate).toMatch(ISO_DATE_RE)
      }
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 미지정 프로젝트 — 빈 응답 폴백
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline?project=UNKNOWN — 알 수 없는 프로젝트', () => {
  it('200 — items 빈 배열 폴백', async () => {
    const res = await getTimeline('UNKNOWN_PROJECT_KEY')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.items).toHaveLength(0)
    expect(body.data.truncated).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// unit test override 핸들러 검증 — server.use(handler)로 시나리오 강제
// ─────────────────────────────────────────────────────────────────────────────

describe('unit test override 핸들러', () => {
  it('timelineForbiddenHandler → 항상 403 AGILE_ACCESS_DENIED', async () => {
    server.use(timelineForbiddenHandler)
    const res = await getTimeline('BTS')
    expect(res.status).toBe(403)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('AGILE_ACCESS_DENIED')
  })

  it('timelineTruncatedHandler → 항상 truncated=true', async () => {
    server.use(timelineTruncatedHandler)
    const res = await getTimeline('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.truncated).toBe(true)
    expect(body.data.items.length).toBeGreaterThan(0)
  })

  it('timelineEmptyHandler → 항상 items=[], truncated=false', async () => {
    server.use(timelineEmptyHandler)
    const res = await getTimeline('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.items).toHaveLength(0)
    expect(body.data.truncated).toBe(false)
  })

  it('afterEach resetHandlers 후 — 기본 BTS 응답으로 복구됨', async () => {
    // 이 테스트는 afterEach server.resetHandlers() 가 정상 작동하는지 검증한다.
    // 위 override 테스트 후 afterEach가 실행돼 기본 핸들러로 복구됨.
    const res = await getTimeline('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<TimelineResponse>
    expect(body.data.truncated).toBe(false)
    expect(body.data.items.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline/deps?project=BTS — deps 기본 시나리오 (FR-TL-02 Task-3)
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline/deps?project=BTS', () => {
  it('200 DataResponse 봉투 — { data: { deps, truncated } }', async () => {
    const res = await getTimelineDeps('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body).toHaveProperty('data')
    expect(body.data).toHaveProperty('deps')
    expect(body.data).toHaveProperty('truncated')
    expect(typeof body.data.truncated).toBe('boolean')
  })

  it('deps 배열이 BTS 픽스처와 정합 — BTS 타임라인 막대 키 쌍 포함', async () => {
    const res = await getTimelineDeps('BTS')
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.deps).toEqual(BTS_TIMELINE_DEPS)
  })

  it('truncated=false (기본)', async () => {
    const res = await getTimelineDeps('BTS')
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.truncated).toBe(false)
  })

  it('엣지 blockerKey/blockedKey 모두 BTS_TIMELINE_ITEMS 키 내 — 라인 렌더 정합 보장', async () => {
    const validKeys = new Set(BTS_TIMELINE_ITEMS.map((i) => i.key))
    const res = await getTimelineDeps('BTS')
    const body = (await res.json()) as DataResponse<DepsResponse>
    for (const dep of body.data.deps) {
      expect(validKeys.has(dep.blockerKey)).toBe(true)
      expect(validKeys.has(dep.blockedKey)).toBe(true)
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline/deps?project=TRUNCATED — deps truncated 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline/deps?project=TRUNCATED', () => {
  it('200 — truncated=true 반환', async () => {
    const res = await getTimelineDeps('TRUNCATED')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.truncated).toBe(true)
  })

  it('deps 배열 존재 (부분 목록)', async () => {
    const res = await getTimelineDeps('TRUNCATED')
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.deps.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline/deps?project=EMPTY — deps 빈 응답 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline/deps?project=EMPTY', () => {
  it('200 — deps 빈 배열, truncated=false', async () => {
    const res = await getTimelineDeps('EMPTY')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.deps).toHaveLength(0)
    expect(body.data.truncated).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline/deps?project=UNKNOWN — 알 수 없는 프로젝트 폴백
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/timeline/deps?project=UNKNOWN — 알 수 없는 프로젝트', () => {
  it('200 — deps 빈 배열 폴백', async () => {
    const res = await getTimelineDeps('UNKNOWN_PROJECT_KEY')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.deps).toHaveLength(0)
    expect(body.data.truncated).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deps unit test override 핸들러 — server.use(handler) 시나리오 강제
// ─────────────────────────────────────────────────────────────────────────────

describe('deps unit test override 핸들러', () => {
  it('timelineDepsTruncatedHandler → 항상 truncated=true', async () => {
    server.use(timelineDepsTruncatedHandler)
    const res = await getTimelineDeps('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.truncated).toBe(true)
    expect(body.data.deps.length).toBeGreaterThan(0)
  })

  it('timelineDepsEmptyHandler → 항상 deps=[], truncated=false', async () => {
    server.use(timelineDepsEmptyHandler)
    const res = await getTimelineDeps('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.deps).toHaveLength(0)
    expect(body.data.truncated).toBe(false)
  })

  it('afterEach resetHandlers 후 — 기본 BTS deps 응답으로 복구됨', async () => {
    const res = await getTimelineDeps('BTS')
    expect(res.status).toBe(200)
    const body = (await res.json()) as DataResponse<DepsResponse>
    expect(body.data.truncated).toBe(false)
    expect(body.data.deps.length).toBeGreaterThan(0)
  })
})
