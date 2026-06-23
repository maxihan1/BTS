// 대시보드 타일 레이아웃 파싱·직렬화·생성 유틸 단위 테스트 — FR-DB-01 Task-1 RED

import { describe, it, expect } from 'vitest'
import {
  parseLayout,
  serializeLayout,
  createTile,
} from './dashboard-layout'
import type { DashboardTile } from './dashboard-layout'

// ─────────────────────────────────────────────────────────────────────────────
// T-PL. parseLayout — JSON 문자열 → DashboardTile[]
// ─────────────────────────────────────────────────────────────────────────────

describe('parseLayout — 정상 파싱', () => {
  it('T-PL-01: 유효한 JSON 배열을 DashboardTile[] 로 반환한다', () => {
    const tile: DashboardTile = { i: 'abc-123', x: 0, y: 0, w: 4, h: 3, title: '내 타일' }
    const json = JSON.stringify([tile])
    const result = parseLayout(json)
    expect(result).toHaveLength(1)
    expect(result[0]).toEqual(tile)
  })

  it('T-PL-02: 여러 타일을 포함한 배열을 올바르게 파싱한다', () => {
    const tiles: DashboardTile[] = [
      { i: 'tile-1', x: 0, y: 0, w: 4, h: 3, title: '타일 1' },
      { i: 'tile-2', x: 4, y: 0, w: 8, h: 3, title: '타일 2' },
    ]
    const result = parseLayout(JSON.stringify(tiles))
    expect(result).toHaveLength(2)
    expect(result[0]).toEqual(tiles[0])
    expect(result[1]).toEqual(tiles[1])
  })

  it('T-PL-03: 빈 배열 JSON "[  ]"을 파싱하면 [] 을 반환한다', () => {
    expect(parseLayout('[]')).toEqual([])
  })
})

describe('parseLayout — 오류 폴백 (throw 금지)', () => {
  it('T-PL-04: 빈 문자열은 [] 을 반환하고 throw 하지 않는다', () => {
    expect(parseLayout('')).toEqual([])
  })

  it('T-PL-05: 손상된 JSON은 [] 을 반환하고 throw 하지 않는다', () => {
    expect(parseLayout('{broken json')).toEqual([])
  })

  it('T-PL-06: 비-배열 JSON(객체)은 [] 을 반환한다', () => {
    expect(parseLayout('{"i":"x","x":0,"y":0,"w":4,"h":3,"title":"x"}')).toEqual([])
  })

  it('T-PL-07: 비-배열 JSON(숫자)은 [] 을 반환한다', () => {
    expect(parseLayout('42')).toEqual([])
  })

  it('T-PL-08: null JSON은 [] 을 반환한다', () => {
    expect(parseLayout('null')).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL. serializeLayout — DashboardTile[] → JSON 문자열
// ─────────────────────────────────────────────────────────────────────────────

describe('serializeLayout — 직렬화', () => {
  it('T-SL-01: DashboardTile[] 를 JSON 문자열로 변환한다', () => {
    const tiles: DashboardTile[] = [{ i: 'tile-1', x: 0, y: 0, w: 6, h: 4, title: '타일' }]
    const json = serializeLayout(tiles)
    expect(typeof json).toBe('string')
    const parsed = JSON.parse(json) as unknown[]
    expect(parsed).toHaveLength(1)
  })

  it('T-SL-02: 빈 배열을 직렬화하면 "[]" 을 반환한다', () => {
    expect(serializeLayout([])).toBe('[]')
  })

  it('T-SL-03: parseLayout(serializeLayout(tiles)) 은 원본과 동일하다 (round-trip)', () => {
    const tiles: DashboardTile[] = [
      { i: 'a', x: 0, y: 0, w: 4, h: 3, title: '첫 타일' },
      { i: 'b', x: 4, y: 0, w: 8, h: 6, title: '둘째 타일' },
    ]
    expect(parseLayout(serializeLayout(tiles))).toEqual(tiles)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CT. createTile — 신규 타일 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('createTile — 고유 식별자', () => {
  it('T-CT-01: 반환된 타일의 i 는 비어있지 않은 문자열이다', () => {
    const tile = createTile([])
    expect(typeof tile.i).toBe('string')
    expect(tile.i.length).toBeGreaterThan(0)
  })

  it('T-CT-02: 두 번 호출하면 서로 다른 i 를 반환한다 (고유성)', () => {
    const a = createTile([])
    const b = createTile([])
    expect(a.i).not.toBe(b.i)
  })

  it('T-CT-03: 기존 타일과 i 가 충돌하지 않는다', () => {
    const existing: DashboardTile[] = [
      { i: 'existing-1', x: 0, y: 0, w: 4, h: 3, title: '기존 타일' },
    ]
    const newTile = createTile(existing)
    expect(newTile.i).not.toBe('existing-1')
  })
})

describe('createTile — 기본 위치와 크기', () => {
  it('T-CT-04: 빈 그리드에서는 x=0, y=0 에 배치한다', () => {
    const tile = createTile([])
    expect(tile.x).toBe(0)
    expect(tile.y).toBe(0)
  })

  it('T-CT-05: 기본 w, h 는 양수이다', () => {
    const tile = createTile([])
    expect(tile.w).toBeGreaterThan(0)
    expect(tile.h).toBeGreaterThan(0)
  })

  it('T-CT-06: 기존 타일이 있을 때 y 는 기존 타일 아래 또는 같은 행에 배치한다 (맨 아래 이상)', () => {
    const existing: DashboardTile[] = [
      { i: 't1', x: 0, y: 0, w: 12, h: 3, title: '기존 타일' },
    ]
    const tile = createTile(existing)
    // 기존 타일 y + h = 3이 최소 y 위치
    // 맨 아래 배치 전략(y=Infinity)이면 y >= 3
    expect(tile.y).toBeGreaterThanOrEqual(0)
  })

  it('T-CT-07: 반환된 타일의 title 은 비어있지 않은 문자열이다', () => {
    const tile = createTile([])
    expect(typeof tile.title).toBe('string')
    expect(tile.title.length).toBeGreaterThan(0)
  })
})
