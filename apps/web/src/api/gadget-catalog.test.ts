// 가젯 카탈로그 api·Zod 스키마·validateGadgetConfig 단위 테스트

import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  fetchGadgetCatalog,
  validateGadgetConfig,
  gadgetCatalogEntrySchema,
  configFieldSchema,
  type GadgetCatalogEntry,
  type ConfigField,
} from './gadget-catalog'
import { GADGET_CATALOG_FIXTURE } from '@/mocks/gadget-catalog-fixtures'

// MSW 서버 라이프사이클은 src/test/setup.ts에서 전역 관리 (beforeAll/afterAll/afterEach)
// 각 테스트에서 server.use(...)로 엔드포인트별 핸들러를 추가한다.

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 최소 GadgetCatalogEntry 생성
// ─────────────────────────────────────────────────────────────────────────────

function makeEntry(
  configFields: ConfigField[],
  requireAtLeastOne: string[][] = [],
): GadgetCatalogEntry {
  return {
    type: 'test_gadget',
    category: 'ISSUE',
    label: 'Test Gadget',
    enabled: true,
    configFields,
    requireAtLeastOne,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// configFieldSchema — Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('configFieldSchema', () => {
  it('@JsonInclude(NON_NULL) 누락 선택 필드를 허용한다', () => {
    // UUID 필드는 minLength/maxLength/min/max/enumValues/itemSchema 미포함
    const raw = { key: 'filterId', type: 'UUID', required: false }
    expect(() => configFieldSchema.parse(raw)).not.toThrow()

    const parsed = configFieldSchema.parse(raw)
    expect(parsed.minLength).toBeUndefined()
    expect(parsed.maxLength).toBeUndefined()
    expect(parsed.min).toBeUndefined()
    expect(parsed.max).toBeUndefined()
    expect(parsed.enumValues).toBeUndefined()
    expect(parsed.itemSchema).toBeUndefined()
    expect(parsed.minItems).toBeUndefined()
    expect(parsed.maxItems).toBeUndefined()
  })

  it('STRING 필드의 minLength/maxLength를 파싱한다', () => {
    const raw = { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 }
    const parsed = configFieldSchema.parse(raw)
    expect(parsed.minLength).toBe(1)
    expect(parsed.maxLength).toBe(10000)
  })

  it('INT 필드의 min/max를 파싱한다', () => {
    const raw = { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 }
    const parsed = configFieldSchema.parse(raw)
    expect(parsed.min).toBe(1)
    expect(parsed.max).toBe(50)
  })

  it('link_list itemSchema 재귀 구조를 파싱한다 (노트 A — z.lazy)', () => {
    const raw = {
      key: 'links',
      type: 'ARRAY',
      required: true,
      minItems: 1,
      maxItems: 20,
      itemSchema: [
        { key: 'label', type: 'STRING', required: true, maxLength: 100 },
        { key: 'url', type: 'URL', required: true, maxLength: 2000 },
      ],
    }
    const parsed = configFieldSchema.parse(raw)
    expect(parsed.itemSchema).toHaveLength(2)
    expect(parsed.itemSchema?.[0]?.key).toBe('label')
    expect(parsed.itemSchema?.[0]?.type).toBe('STRING')
    expect(parsed.itemSchema?.[1]?.key).toBe('url')
    expect(parsed.itemSchema?.[1]?.type).toBe('URL')
    expect(parsed.itemSchema?.[1]?.maxLength).toBe(2000)
  })

  it('ENUM 필드의 enumValues를 파싱한다', () => {
    const raw = {
      key: 'field',
      type: 'ENUM',
      required: true,
      enumValues: ['status', 'assignee', 'priority'],
    }
    const parsed = configFieldSchema.parse(raw)
    expect(parsed.enumValues).toEqual(['status', 'assignee', 'priority'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// gadgetCatalogEntrySchema — 전체 카탈로그 엔트리 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('gadgetCatalogEntrySchema', () => {
  it('requireAtLeastOne 기본값이 빈 배열이다', () => {
    const raw = {
      type: 'text_widget',
      category: 'STATIC',
      label: 'Text Widget',
      enabled: true,
      configFields: [
        { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 },
      ],
      requireAtLeastOne: [],
    }
    const parsed = gadgetCatalogEntrySchema.parse(raw)
    expect(parsed.requireAtLeastOne).toEqual([])
  })

  it('requireAtLeastOne 그룹을 파싱한다 (filter_result)', () => {
    const raw = {
      type: 'filter_result',
      category: 'ISSUE',
      label: 'Filter Result',
      enabled: true,
      configFields: [
        { key: 'filterId', type: 'UUID', required: false },
        { key: 'aql', type: 'STRING', required: false, maxLength: 2000 },
      ],
      requireAtLeastOne: [['filterId', 'aql']],
    }
    const parsed = gadgetCatalogEntrySchema.parse(raw)
    expect(parsed.requireAtLeastOne).toEqual([['filterId', 'aql']])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchGadgetCatalog — API 통합 테스트 (MSW)
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchGadgetCatalog()', () => {
  it('백엔드 {data:{gadgets:[...]}} 응답을 파싱해 GadgetCatalogEntry[] 반환한다', async () => {
    server.use(
      http.get('/api/v1/dashboards/gadget-catalog', () =>
        HttpResponse.json({ data: { gadgets: GADGET_CATALOG_FIXTURE } }),
      ),
    )

    const gadgets = await fetchGadgetCatalog()
    expect(gadgets).toHaveLength(GADGET_CATALOG_FIXTURE.length)
    expect(gadgets[0]?.type).toBeDefined()
    expect(gadgets[0]?.category).toBeDefined()
    expect(gadgets[0]?.configFields).toBeDefined()
  })

  it('12종 카탈로그 전체를 반환한다', async () => {
    server.use(
      http.get('/api/v1/dashboards/gadget-catalog', () =>
        HttpResponse.json({ data: { gadgets: GADGET_CATALOG_FIXTURE } }),
      ),
    )

    const gadgets = await fetchGadgetCatalog()
    expect(gadgets).toHaveLength(12)
  })

  it('enabled=true 6종·enabled=false 6종이 정확하다', async () => {
    server.use(
      http.get('/api/v1/dashboards/gadget-catalog', () =>
        HttpResponse.json({ data: { gadgets: GADGET_CATALOG_FIXTURE } }),
      ),
    )

    const gadgets = await fetchGadgetCatalog()
    const enabled = gadgets.filter((g) => g.enabled)
    const disabled = gadgets.filter((g) => !g.enabled)
    expect(enabled).toHaveLength(6)
    expect(disabled).toHaveLength(6)
  })

  it('link_list 가젯의 itemSchema 재귀 파싱이 성공한다', async () => {
    server.use(
      http.get('/api/v1/dashboards/gadget-catalog', () =>
        HttpResponse.json({ data: { gadgets: GADGET_CATALOG_FIXTURE } }),
      ),
    )

    const gadgets = await fetchGadgetCatalog()
    const linkList = gadgets.find((g) => g.type === 'link_list')
    expect(linkList).toBeDefined()
    const linksField = linkList?.configFields.find((f) => f.key === 'links')
    expect(linksField?.itemSchema).toHaveLength(2)
    expect(linksField?.itemSchema?.[0]?.key).toBe('label')
    expect(linksField?.itemSchema?.[1]?.key).toBe('url')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// validateGadgetConfig — 동적 클라측 config 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('validateGadgetConfig()', () => {
  // ── EC5: required 필드 누락 ───────────────────────────────────────────────

  describe('EC5 — required 필드 누락', () => {
    it('required STRING 필드가 없으면 에러를 반환한다', () => {
      const entry = makeEntry([
        { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 },
      ])
      const errors = validateGadgetConfig(entry, {})
      expect(errors.length).toBeGreaterThan(0)
      expect(errors.some((e) => e.includes('markdown'))).toBe(true)
    })

    it('required=false 필드가 없어도 에러 없음', () => {
      const entry = makeEntry([
        { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
      ])
      const errors = validateGadgetConfig(entry, {})
      expect(errors).toHaveLength(0)
    })

    it('required ARRAY 필드가 없으면 에러를 반환한다 (link_list)', () => {
      const entry = makeEntry([
        {
          key: 'links',
          type: 'ARRAY',
          required: true,
          minItems: 1,
          maxItems: 20,
          itemSchema: [
            { key: 'label', type: 'STRING', required: true, maxLength: 100 },
            { key: 'url', type: 'URL', required: true, maxLength: 2000 },
          ],
        },
      ])
      const errors = validateGadgetConfig(entry, {})
      expect(errors.length).toBeGreaterThan(0)
      expect(errors.some((e) => e.includes('links'))).toBe(true)
    })
  })

  // ── EC8: maxLength 초과 ───────────────────────────────────────────────────

  describe('EC8 — STRING maxLength 초과', () => {
    it('10001자 markdown은 에러를 반환한다', () => {
      const entry = makeEntry([
        { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 },
      ])
      const errors = validateGadgetConfig(entry, { markdown: 'a'.repeat(10001) })
      expect(errors.length).toBeGreaterThan(0)
      expect(errors.some((e) => e.includes('10000'))).toBe(true)
    })

    it('10000자 markdown은 통과한다', () => {
      const entry = makeEntry([
        { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 },
      ])
      const errors = validateGadgetConfig(entry, { markdown: 'a'.repeat(10000) })
      expect(errors).toHaveLength(0)
    })

    it('minLength 미달도 에러를 반환한다', () => {
      const entry = makeEntry([
        { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 },
      ])
      // 빈 문자열 — required 통과(값 있음), minLength 실패
      const errors = validateGadgetConfig(entry, { markdown: '' })
      expect(errors.length).toBeGreaterThan(0)
    })
  })

  // ── EC7: URL 스킴 검증 ────────────────────────────────────────────────────

  describe('EC7 — URL http/https 스킴 강제', () => {
    it('javascript: 스킴을 거부한다', () => {
      const entry = makeEntry([
        {
          key: 'links',
          type: 'ARRAY',
          required: true,
          minItems: 1,
          maxItems: 20,
          itemSchema: [
            { key: 'label', type: 'STRING', required: true, maxLength: 100 },
            { key: 'url', type: 'URL', required: true, maxLength: 2000 },
          ],
        },
      ])
      const errors = validateGadgetConfig(entry, {
        links: [{ label: '악성', url: 'javascript:alert(1)' }],
      })
      expect(errors.length).toBeGreaterThan(0)
      expect(errors.some((e) => e.toLowerCase().includes('http'))).toBe(true)
    })

    it('data: 스킴을 거부한다', () => {
      const entry = makeEntry([
        { key: 'homepage', type: 'URL', required: true, maxLength: 2000 },
      ])
      const errors = validateGadgetConfig(entry, { homepage: 'data:text/html,<h1>x</h1>' })
      expect(errors.length).toBeGreaterThan(0)
    })

    it('https:// URL을 허용한다', () => {
      const entry = makeEntry([
        { key: 'homepage', type: 'URL', required: true, maxLength: 2000 },
      ])
      const errors = validateGadgetConfig(entry, { homepage: 'https://example.com' })
      expect(errors).toHaveLength(0)
    })

    it('http:// URL을 허용한다', () => {
      const entry = makeEntry([
        { key: 'homepage', type: 'URL', required: true, maxLength: 2000 },
      ])
      const errors = validateGadgetConfig(entry, { homepage: 'http://example.com' })
      expect(errors).toHaveLength(0)
    })
  })

  // ── EC3: requireAtLeastOne ────────────────────────────────────────────────

  describe('EC3 — requireAtLeastOne', () => {
    it('filterId·aql 둘 다 없으면 에러를 반환한다 (filter_result)', () => {
      const entry = makeEntry(
        [
          { key: 'filterId', type: 'UUID', required: false },
          { key: 'aql', type: 'STRING', required: false, maxLength: 2000 },
        ],
        [['filterId', 'aql']],
      )
      const errors = validateGadgetConfig(entry, {})
      expect(errors.length).toBeGreaterThan(0)
      expect(errors.some((e) => e.includes('filterId') || e.includes('aql'))).toBe(true)
    })

    it('filterId만 있으면 requireAtLeastOne 통과한다', () => {
      const entry = makeEntry(
        [
          { key: 'filterId', type: 'UUID', required: false },
          { key: 'aql', type: 'STRING', required: false, maxLength: 2000 },
        ],
        [['filterId', 'aql']],
      )
      const errors = validateGadgetConfig(entry, {
        filterId: '00000000-0000-4000-8000-000000000001',
      })
      expect(errors).toHaveLength(0)
    })

    it('aql만 있으면 requireAtLeastOne 통과한다', () => {
      const entry = makeEntry(
        [
          { key: 'filterId', type: 'UUID', required: false },
          { key: 'aql', type: 'STRING', required: false, maxLength: 2000 },
        ],
        [['filterId', 'aql']],
      )
      const errors = validateGadgetConfig(entry, { aql: 'status = Open' })
      expect(errors).toHaveLength(0)
    })
  })

  // ── INT 검증 ──────────────────────────────────────────────────────────────

  describe('INT 검증', () => {
    it('min 미달 INT 값을 거부한다', () => {
      const entry = makeEntry([
        { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
      ])
      const errors = validateGadgetConfig(entry, { maxItems: 0 })
      expect(errors.length).toBeGreaterThan(0)
    })

    it('max 초과 INT 값을 거부한다', () => {
      const entry = makeEntry([
        { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
      ])
      const errors = validateGadgetConfig(entry, { maxItems: 51 })
      expect(errors.length).toBeGreaterThan(0)
    })

    it('범위 내 INT 값을 허용한다', () => {
      const entry = makeEntry([
        { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
      ])
      const errors = validateGadgetConfig(entry, { maxItems: 10 })
      expect(errors).toHaveLength(0)
    })

    it('소수점 값을 거부한다', () => {
      const entry = makeEntry([
        { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
      ])
      const errors = validateGadgetConfig(entry, { maxItems: 1.5 })
      expect(errors.length).toBeGreaterThan(0)
    })
  })

  // ── ENUM 검증 ─────────────────────────────────────────────────────────────

  describe('ENUM 검증', () => {
    it('허용되지 않는 enum 값을 거부한다', () => {
      const entry = makeEntry([
        {
          key: 'field',
          type: 'ENUM',
          required: true,
          enumValues: ['status', 'assignee', 'priority'],
        },
      ])
      const errors = validateGadgetConfig(entry, { field: 'unknown' })
      expect(errors.length).toBeGreaterThan(0)
    })

    it('허용된 enum 값을 통과시킨다', () => {
      const entry = makeEntry([
        {
          key: 'field',
          type: 'ENUM',
          required: true,
          enumValues: ['status', 'assignee', 'priority'],
        },
      ])
      const errors = validateGadgetConfig(entry, { field: 'status' })
      expect(errors).toHaveLength(0)
    })
  })

  // ── ARRAY 검증 ────────────────────────────────────────────────────────────

  describe('ARRAY 검증', () => {
    it('minItems 미달 배열을 거부한다', () => {
      const entry = makeEntry([
        { key: 'links', type: 'ARRAY', required: true, minItems: 1, maxItems: 20 },
      ])
      const errors = validateGadgetConfig(entry, { links: [] })
      expect(errors.length).toBeGreaterThan(0)
    })

    it('maxItems 초과 배열을 거부한다', () => {
      const entry = makeEntry([
        { key: 'links', type: 'ARRAY', required: true, minItems: 1, maxItems: 20 },
      ])
      const tooMany = Array.from({ length: 21 }, (_, i) => ({ label: `L${i}`, url: 'https://x.com' }))
      const errors = validateGadgetConfig(entry, { links: tooMany })
      expect(errors.length).toBeGreaterThan(0)
    })
  })

  // ── itemSchema 재귀 검증 (link_list) ──────────────────────────────────────

  describe('itemSchema 재귀 검증 — link_list', () => {
    const linkListEntry = makeEntry([
      {
        key: 'links',
        type: 'ARRAY',
        required: true,
        minItems: 1,
        maxItems: 20,
        itemSchema: [
          { key: 'label', type: 'STRING', required: true, maxLength: 100 },
          { key: 'url', type: 'URL', required: true, maxLength: 2000 },
        ],
      },
    ])

    it('유효한 link_list config를 통과시킨다', () => {
      const errors = validateGadgetConfig(linkListEntry, {
        links: [
          { label: 'BTS 홈', url: 'https://bts.example.com' },
          { label: 'GitHub', url: 'https://github.com' },
        ],
      })
      expect(errors).toHaveLength(0)
    })

    it('link 항목의 url이 javascript: 스킴이면 에러를 반환한다', () => {
      const errors = validateGadgetConfig(linkListEntry, {
        links: [{ label: '악성', url: 'javascript:alert(1)' }],
      })
      expect(errors.length).toBeGreaterThan(0)
      expect(errors.some((e) => e.toLowerCase().includes('http'))).toBe(true)
    })

    it('link 항목의 label이 없으면 에러를 반환한다 (배열 항목 required)', () => {
      const errors = validateGadgetConfig(linkListEntry, {
        links: [{ url: 'https://example.com' }],
      })
      expect(errors.length).toBeGreaterThan(0)
      expect(errors.some((e) => e.includes('label'))).toBe(true)
    })

    it('link 항목의 label이 100자 초과면 에러를 반환한다', () => {
      const errors = validateGadgetConfig(linkListEntry, {
        links: [{ label: 'a'.repeat(101), url: 'https://example.com' }],
      })
      expect(errors.length).toBeGreaterThan(0)
    })
  })

  // ── 유효한 config — 빈 배열 반환 ─────────────────────────────────────────

  describe('유효한 config는 빈 배열을 반환한다', () => {
    it('text_widget 유효 config', () => {
      const entry = makeEntry([
        { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 },
      ])
      const errors = validateGadgetConfig(entry, { markdown: '# 제목\n내용입니다.' })
      expect(errors).toHaveLength(0)
    })

    it('filter_result filterId 경로 유효 config (EC3 통과)', () => {
      const entry = makeEntry(
        [
          { key: 'filterId', type: 'UUID', required: false },
          { key: 'aql', type: 'STRING', required: false, maxLength: 2000 },
          { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
        ],
        [['filterId', 'aql']],
      )
      const errors = validateGadgetConfig(entry, {
        filterId: '00000000-0000-4000-8000-000000000001',
        maxItems: 20,
      })
      expect(errors).toHaveLength(0)
    })

    it('configFields 없는 가젯은 항상 통과한다', () => {
      const entry = makeEntry([])
      expect(validateGadgetConfig(entry, {})).toHaveLength(0)
    })
  })

  // ── UUID 형식 검증 ────────────────────────────────────────────────────────

  describe('UUID 형식 검증', () => {
    it('유효하지 않은 UUID를 거부한다', () => {
      const entry = makeEntry([{ key: 'filterId', type: 'UUID', required: false }])
      const errors = validateGadgetConfig(entry, { filterId: 'not-a-uuid' })
      expect(errors.length).toBeGreaterThan(0)
    })

    it('RFC4122 v4 UUID를 허용한다', () => {
      const entry = makeEntry([{ key: 'filterId', type: 'UUID', required: false }])
      const errors = validateGadgetConfig(entry, {
        filterId: '00000000-0000-4000-8000-000000000001',
      })
      expect(errors).toHaveLength(0)
    })
  })
})
