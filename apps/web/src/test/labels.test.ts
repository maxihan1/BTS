// 라벨 자동완성 API + MSW 핸들러 + useLabels 훅 단위 테스트 (FR-IS-09)
import { server } from '@/test/server'
import { describe, expect, it } from 'vitest'
import { fetchLabels } from '@/api/labels'
import { labelHandlers } from '@/mocks/label-handlers'

beforeEach(() => {
  server.use(...labelHandlers)
})

const BASE_URL = '/api/v1/labels'

// ─────────────────────────────────────────────────────────────────────────────
// fetchLabels — DataResponse 언랩 + prefix 필터링
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchLabels', () => {
  it('prefix "bac"으로 검색하면 "backend"만 반환한다', async () => {
    const result = await fetchLabels('bac')
    expect(result).toContain('backend')
    result.forEach((label) => {
      expect(label.toLowerCase()).toMatch(/^bac/)
    })
  })

  it('빈 문자열이면 전체 상위 라벨(최대 10개)을 반환한다', async () => {
    const result = await fetchLabels('')
    expect(result.length).toBeGreaterThan(0)
    expect(result.length).toBeLessThanOrEqual(10)
  })

  it('매칭 0건이면 빈 배열을 반환한다', async () => {
    const result = await fetchLabels('zzznomatch')
    expect(result).toEqual([])
  })

  it('DataResponse { data: string[] } 래퍼를 언랩해 string[]을 반환한다', async () => {
    const result = await fetchLabels('b')
    expect(Array.isArray(result)).toBe(true)
    result.forEach((item) => {
      expect(typeof item).toBe('string')
    })
  })

  it('prefix "bug"으로 검색하면 "bug"가 포함된다', async () => {
    const result = await fetchLabels('bug')
    expect(result).toContain('bug')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — GET /api/v1/labels?q=<prefix> 직접 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/labels', () => {
  it('q=비어있으면 { data: string[] } 형태로 200을 반환한다', async () => {
    const res = await fetch(`${BASE_URL}?q=`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(Array.isArray(body.data)).toBe(true)
  })

  it('q=bug이면 "bug"를 포함하는 배열을 반환한다', async () => {
    const res = await fetch(`${BASE_URL}?q=bug`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: string[] }
    expect(body.data).toContain('bug')
  })

  it('q=zzznomatch이면 { data: [] }를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}?q=zzznomatch`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(body.data).toEqual([])
  })

  it('대소문자 무시 — q=BUG도 "bug"를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}?q=BUG`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: string[] }
    expect(body.data).toContain('bug')
  })

  it('결과가 최대 10개를 초과하지 않는다', async () => {
    const res = await fetch(`${BASE_URL}?q=`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(body.data.length).toBeLessThanOrEqual(10)
  })
})
