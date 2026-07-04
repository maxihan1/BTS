// 필드 매핑 추천 휴리스틱 단위 테스트 — suggestFieldMappings 순수 함수
import { describe, it, expect } from 'vitest'
import { suggestFieldMappings, FIELD_MAPPING_IGNORE, type TargetFieldCatalogEntry } from './field-mapping-suggest'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 대상 필드 카탈로그 (실제 카탈로그의 일부 발췌)
// ─────────────────────────────────────────────────────────────────────────────

const targetFields: TargetFieldCatalogEntry[] = [
  { key: 'summary', label: '제목', required: true, multi: false },
  { key: 'description', label: '설명', required: false, multi: false },
  { key: 'priority', label: '우선순위', required: false, multi: false },
  { key: 'status', label: '상태', required: false, multi: false },
]

// ─────────────────────────────────────────────────────────────────────────────
// suggestFieldMappings
// ─────────────────────────────────────────────────────────────────────────────

describe('suggestFieldMappings', () => {
  it('소스 헤더가 대상 필드 key와 일치하면 그 key를 추천한다', () => {
    const result = suggestFieldMappings(['Summary'], targetFields)
    expect(result['Summary']).toBe('summary')
  })

  it('소스 헤더가 대상 필드 label(한국어)과 일치하면 그 key를 추천한다', () => {
    const result = suggestFieldMappings(['제목'], targetFields)
    expect(result['제목']).toBe('summary')
  })

  it('한국어 라벨 매칭 — 우선순위 → priority', () => {
    const result = suggestFieldMappings(['우선순위'], targetFields)
    expect(result['우선순위']).toBe('priority')
  })

  it('대소문자/공백을 무시하고 매칭한다', () => {
    const result = suggestFieldMappings(['  STATUS  '], targetFields)
    expect(result['  STATUS  ']).toBe('status')
  })

  it('일치하는 대상 필드가 없으면 IGNORE 센티널을 반환한다', () => {
    const result = suggestFieldMappings(['Foo'], targetFields)
    expect(result['Foo']).toBe(FIELD_MAPPING_IGNORE)
  })

  it('sourceFields가 빈 배열이면 빈 객체를 반환한다', () => {
    const result = suggestFieldMappings([], targetFields)
    expect(result).toEqual({})
  })

  it('여러 소스 헤더를 한 번에 매핑한다', () => {
    const result = suggestFieldMappings(['Summary', '설명', 'Unknown'], targetFields)
    expect(result).toEqual({
      Summary: 'summary',
      설명: 'description',
      Unknown: FIELD_MAPPING_IGNORE,
    })
  })
})
