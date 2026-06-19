// 변경 이력 필드명·값 표시명 해석 유틸 단위 테스트 — FR-HS-02 Task-F3 RED phase
import { describe, it, expect } from 'vitest'
import { resolveFieldLabel, resolveValueLabel } from './changelog-labels'
import type { ChangeItem, ChangelogRefs } from './changelog-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 Fixture
// ─────────────────────────────────────────────────────────────────────────────

/** 빈 refs (최소 구조) */
const emptyRefs: ChangelogRefs = {
  types: [],
  components: [],
  versions: [],
  priorityMap: {},
  impactMap: {},
  customFieldDefinitions: [],
}

/** 기본 ChangeItem 팩토리 */
function makeItem(overrides: Partial<ChangeItem> = {}): ChangeItem {
  return {
    field: 'summary',
    fromValue: null,
    toValue: null,
    fromLabel: null,
    toLabel: null,
    ...overrides,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// T-FL. resolveFieldLabel — 필드 키 → 표시명
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveFieldLabel — 표준 필드', () => {
  it('T-FL-01: lifecycle → "생명주기"를 반환한다', () => {
    expect(resolveFieldLabel('lifecycle', emptyRefs)).toBe('생명주기')
  })

  it('T-FL-02: summary → "제목"을 반환한다', () => {
    expect(resolveFieldLabel('summary', emptyRefs)).toBe('제목')
  })

  it('T-FL-03: description → "본문"을 반환한다', () => {
    expect(resolveFieldLabel('description', emptyRefs)).toBe('본문')
  })

  it('T-FL-04: priority → "우선순위"를 반환한다', () => {
    expect(resolveFieldLabel('priority', emptyRefs)).toBe('우선순위')
  })

  it('T-FL-05: labels → "라벨"을 반환한다', () => {
    expect(resolveFieldLabel('labels', emptyRefs)).toBe('라벨')
  })

  it('T-FL-06: environment → "환경"을 반환한다', () => {
    expect(resolveFieldLabel('environment', emptyRefs)).toBe('환경')
  })

  it('T-FL-07: impact → "영향도"를 반환한다', () => {
    expect(resolveFieldLabel('impact', emptyRefs)).toBe('영향도')
  })

  it('T-FL-08: type → "유형"을 반환한다', () => {
    expect(resolveFieldLabel('type', emptyRefs)).toBe('유형')
  })

  it('T-FL-09: assignee → "담당자"를 반환한다', () => {
    expect(resolveFieldLabel('assignee', emptyRefs)).toBe('담당자')
  })

  it('T-FL-10: status → "상태"를 반환한다', () => {
    expect(resolveFieldLabel('status', emptyRefs)).toBe('상태')
  })

  it('T-FL-11: resolution → "해결 결과"를 반환한다', () => {
    expect(resolveFieldLabel('resolution', emptyRefs)).toBe('해결 결과')
  })

  it('T-FL-12: components → "컴포넌트"를 반환한다', () => {
    expect(resolveFieldLabel('components', emptyRefs)).toBe('컴포넌트')
  })

  it('T-FL-13: affectsVersions → "영향 버전"을 반환한다', () => {
    expect(resolveFieldLabel('affectsVersions', emptyRefs)).toBe('영향 버전')
  })

  it('T-FL-14: fixVersions → "수정 버전"을 반환한다', () => {
    expect(resolveFieldLabel('fixVersions', emptyRefs)).toBe('수정 버전')
  })

  it('T-FL-15: securityLevel → "보안등급"을 반환한다', () => {
    expect(resolveFieldLabel('securityLevel', emptyRefs)).toBe('보안등급')
  })
})

describe('resolveFieldLabel — 커스텀 필드', () => {
  const refsWithCf: ChangelogRefs = {
    ...emptyRefs,
    customFieldDefinitions: [
      {
        id: 'cf-uuid-1',
        projectId: 'proj-uuid-1',
        key: 'my_field',
        name: '내 커스텀 필드',
        description: null,
        fieldType: 'SHORT_TEXT',
        required: false,
        displayOrder: 1,
        options: [],
      },
    ],
  }

  it('T-FL-CF-01: customField:<key>가 refs에 있으면 정의명을 반환한다', () => {
    expect(resolveFieldLabel('customField:my_field', refsWithCf)).toBe('내 커스텀 필드')
  })

  it('T-FL-CF-02: customField:<key>가 refs에 없으면 key 원문(prefix 제거)을 반환한다', () => {
    expect(resolveFieldLabel('customField:unknown_field', refsWithCf)).toBe('unknown_field')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-VL. resolveValueLabel — 값 → 표시명
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveValueLabel — 박제 label 우선순위', () => {
  it('T-VL-01: fromLabel이 non-null이면 그것을 반환한다', () => {
    const item = makeItem({ field: 'assignee', fromLabel: '홍길동', fromValue: 'some-uuid' })
    expect(resolveValueLabel(item, 'from', emptyRefs)).toBe('홍길동')
  })

  it('T-VL-02: toLabel이 non-null이면 그것을 반환한다', () => {
    const item = makeItem({ field: 'securityLevel', toLabel: 'CONFIDENTIAL', toValue: 'sec-uuid' })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('CONFIDENTIAL')
  })

  it('T-VL-03: fromLabel=null이어도 toLabel만 있으면 from은 refs 해석을 시도한다', () => {
    const refsWithPriority: ChangelogRefs = {
      ...emptyRefs,
      priorityMap: { 1: '가장 높음', 2: '높음', 3: '보통', 4: '낮음', 5: '가장 낮음' },
    }
    const item = makeItem({ field: 'priority', fromValue: '2', fromLabel: null, toLabel: '보통' })
    expect(resolveValueLabel(item, 'from', refsWithPriority)).toBe('높음')
  })
})

describe('resolveValueLabel — priority 해석', () => {
  const refsWithPriority: ChangelogRefs = {
    ...emptyRefs,
    priorityMap: { 1: '가장 높음', 2: '높음', 3: '보통', 4: '낮음', 5: '가장 낮음' },
  }

  it('T-VL-PR-01: priority "1" → "가장 높음"을 반환한다', () => {
    const item = makeItem({ field: 'priority', toValue: '1', toLabel: null })
    expect(resolveValueLabel(item, 'to', refsWithPriority)).toBe('가장 높음')
  })

  it('T-VL-PR-02: priority "5" → "가장 낮음"을 반환한다', () => {
    const item = makeItem({ field: 'priority', fromValue: '5', fromLabel: null })
    expect(resolveValueLabel(item, 'from', refsWithPriority)).toBe('가장 낮음')
  })

  it('T-VL-PR-03: priority가 맵에 없으면 raw 값을 반환한다', () => {
    const item = makeItem({ field: 'priority', toValue: '9', toLabel: null })
    expect(resolveValueLabel(item, 'to', refsWithPriority)).toBe('9')
  })
})

describe('resolveValueLabel — impact 해석', () => {
  const refsWithImpact: ChangelogRefs = {
    ...emptyRefs,
    impactMap: { 1: '높음', 2: '보통', 3: '낮음' },
  }

  it('T-VL-IM-01: impact "1" → "높음"을 반환한다', () => {
    const item = makeItem({ field: 'impact', toValue: '1', toLabel: null })
    expect(resolveValueLabel(item, 'to', refsWithImpact)).toBe('높음')
  })

  it('T-VL-IM-02: impact "3" → "낮음"을 반환한다', () => {
    const item = makeItem({ field: 'impact', fromValue: '3', fromLabel: null })
    expect(resolveValueLabel(item, 'from', refsWithImpact)).toBe('낮음')
  })
})

describe('resolveValueLabel — type 해석', () => {
  const refsWithTypes: ChangelogRefs = {
    ...emptyRefs,
    types: [
      { id: 1, key: 'bug', name: '버그', description: '', iconName: null },
      { id: 2, key: 'task', name: '작업', description: '', iconName: null },
    ],
  }

  it('T-VL-TY-01: typeId "1" → types 맵에서 name을 반환한다', () => {
    const item = makeItem({ field: 'type', toValue: '1', toLabel: null })
    expect(resolveValueLabel(item, 'to', refsWithTypes)).toBe('버그')
  })

  it('T-VL-TY-02: typeId가 맵에 없으면 raw 값을 반환한다', () => {
    const item = makeItem({ field: 'type', toValue: '99', toLabel: null })
    expect(resolveValueLabel(item, 'to', refsWithTypes)).toBe('99')
  })
})

describe('resolveValueLabel — components UUID 배열 해석', () => {
  const refsWithComponents: ChangelogRefs = {
    ...emptyRefs,
    components: [
      {
        id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
        projectId: 'proj-uuid-1',
        name: '프론트엔드',
        description: null,
        leadUserId: null,
      },
      {
        id: 'b2c3d4e5-f6a7-4901-bcde-f01234567891',
        projectId: 'proj-uuid-1',
        name: '백엔드',
        description: null,
        leadUserId: null,
      },
    ],
  }

  it('T-VL-CO-01: UUID 배열 JSON을 파싱해 name 배열을 반환한다', () => {
    const item = makeItem({
      field: 'components',
      toValue: '["a1b2c3d4-e5f6-4890-abcd-ef1234567890","b2c3d4e5-f6a7-4901-bcde-f01234567891"]',
      toLabel: null,
    })
    expect(resolveValueLabel(item, 'to', refsWithComponents)).toBe('프론트엔드, 백엔드')
  })

  it('T-VL-CO-02: UUID가 refs에 없으면 "(삭제됨)"으로 폴백한다', () => {
    const item = makeItem({
      field: 'components',
      toValue: '["aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee"]',
      toLabel: null,
    })
    expect(resolveValueLabel(item, 'to', refsWithComponents)).toBe('(삭제됨)')
  })

  it('T-VL-CO-03: 빈 배열 "[]"은 "(없음)"을 반환한다', () => {
    const item = makeItem({ field: 'components', toValue: '[]', toLabel: null })
    expect(resolveValueLabel(item, 'to', refsWithComponents)).toBe('(없음)')
  })
})

describe('resolveValueLabel — affectsVersions / fixVersions UUID 배열 해석', () => {
  const refsWithVersions: ChangelogRefs = {
    ...emptyRefs,
    versions: [
      {
        id: 'c3d4e5f6-a7b8-4012-cdef-012345678901',
        projectId: 'proj-uuid-1',
        name: 'v1.0.0',
        description: null,
        startDate: null,
        releaseDate: null,
        status: 'RELEASED',
      },
    ],
  }

  it('T-VL-VR-01: affectsVersions UUID 배열을 name으로 변환한다', () => {
    const item = makeItem({
      field: 'affectsVersions',
      toValue: '["c3d4e5f6-a7b8-4012-cdef-012345678901"]',
      toLabel: null,
    })
    expect(resolveValueLabel(item, 'to', refsWithVersions)).toBe('v1.0.0')
  })

  it('T-VL-VR-02: fixVersions UUID가 없으면 "(삭제됨)"을 반환한다', () => {
    const item = makeItem({
      field: 'fixVersions',
      toValue: '["dddddddd-eeee-4fff-aaaa-bbbbbbbbbbbb"]',
      toLabel: null,
    })
    expect(resolveValueLabel(item, 'to', refsWithVersions)).toBe('(삭제됨)')
  })
})

describe('resolveValueLabel — lifecycle 특수 처리', () => {
  it('T-VL-LC-01: lifecycle toValue "created" → "이슈를 생성했습니다"를 반환한다', () => {
    const item = makeItem({ field: 'lifecycle', toValue: 'created', fromValue: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('이슈를 생성했습니다')
  })

  it('T-VL-LC-02: lifecycle toValue "deleted" → "이슈를 삭제했습니다"를 반환한다', () => {
    const item = makeItem({ field: 'lifecycle', toValue: 'deleted', fromValue: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('이슈를 삭제했습니다')
  })

  it('T-VL-LC-03: lifecycle from side도 toValue를 기준으로 처리한다', () => {
    const item = makeItem({ field: 'lifecycle', toValue: 'created', fromValue: null })
    expect(resolveValueLabel(item, 'from', emptyRefs)).toBe('이슈를 생성했습니다')
  })
})

describe('resolveValueLabel — status / resolution', () => {
  it('T-VL-ST-01: status는 raw state key를 그대로 반환한다', () => {
    const item = makeItem({ field: 'status', toValue: 'IN_PROGRESS', toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('IN_PROGRESS')
  })

  it('T-VL-RE-01: resolution은 raw 값을 그대로 반환한다 (refs 없음)', () => {
    const item = makeItem({ field: 'resolution', toValue: 'FIXED', toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('FIXED')
  })
})

describe('resolveValueLabel — 텍스트 필드 raw 반환', () => {
  it('T-VL-TX-01: summary는 raw 값을 그대로 반환한다', () => {
    const item = makeItem({ field: 'summary', toValue: '새 제목', toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('새 제목')
  })

  it('T-VL-TX-02: labels는 raw 값을 그대로 반환한다', () => {
    const item = makeItem({ field: 'labels', toValue: 'bug,ui', toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('bug,ui')
  })

  it('T-VL-TX-03: environment는 raw 값을 그대로 반환한다', () => {
    const item = makeItem({ field: 'environment', toValue: 'Chrome 126', toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('Chrome 126')
  })
})

describe('resolveValueLabel — null/empty 폴백', () => {
  it('T-VL-NL-01: value와 label 모두 null이면 "(없음)"을 반환한다', () => {
    const item = makeItem({ field: 'summary', toValue: null, toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('(없음)')
  })

  it('T-VL-NL-02: from side도 모두 null이면 "(없음)"을 반환한다', () => {
    const item = makeItem({ field: 'priority', fromValue: null, fromLabel: null })
    expect(resolveValueLabel(item, 'from', emptyRefs)).toBe('(없음)')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MV. FR-MV-02 — 이동(key 변경) 이력 라벨
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveFieldLabel — key 이동 이벤트 (FR-MV-02)', () => {
  it('T-FL-MV-01: key → "프로젝트 이동"을 반환한다', () => {
    expect(resolveFieldLabel('key', emptyRefs)).toBe('프로젝트 이동')
  })
})

describe('resolveValueLabel — key 이동 raw 반환 (폴백 경로 회귀)', () => {
  it('T-VL-MV-01: field=key, from side에서 옛 이슈 키를 raw 그대로 반환한다', () => {
    const item = makeItem({
      field: 'key',
      fromValue: 'BTS-1',
      toValue: 'PROJ-42',
      fromLabel: null,
      toLabel: null,
    })
    expect(resolveValueLabel(item, 'from', emptyRefs)).toBe('BTS-1')
  })

  it('T-VL-MV-02: field=key, to side에서 새 이슈 키를 raw 그대로 반환한다', () => {
    const item = makeItem({
      field: 'key',
      fromValue: 'BTS-1',
      toValue: 'PROJ-42',
      fromLabel: null,
      toLabel: null,
    })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('PROJ-42')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PL. FR-PL-01 — 일정 필드(startDate/dueDate/targetDate) changelog 라벨
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveFieldLabel — 일정 필드 (FR-PL-01)', () => {
  it('T-FL-PL-01: startDate → "시작일"을 반환한다', () => {
    expect(resolveFieldLabel('startDate', emptyRefs)).toBe('시작일')
  })

  it('T-FL-PL-02: dueDate → "마감일"을 반환한다', () => {
    expect(resolveFieldLabel('dueDate', emptyRefs)).toBe('마감일')
  })

  it('T-FL-PL-03: targetDate → "목표일"을 반환한다', () => {
    expect(resolveFieldLabel('targetDate', emptyRefs)).toBe('목표일')
  })
})

describe('resolveValueLabel — 일정 필드 raw 반환 (FR-PL-01)', () => {
  it('T-VL-PL-01: startDate toValue는 날짜 문자열을 raw 그대로 반환한다', () => {
    const item = makeItem({ field: 'startDate', toValue: '2026-06-20', toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('2026-06-20')
  })

  it('T-VL-PL-02: dueDate fromValue는 날짜 문자열을 raw 그대로 반환한다', () => {
    const item = makeItem({ field: 'dueDate', fromValue: '2026-05-01', fromLabel: null })
    expect(resolveValueLabel(item, 'from', emptyRefs)).toBe('2026-05-01')
  })

  it('T-VL-PL-03: targetDate toValue는 날짜 문자열을 raw 그대로 반환한다', () => {
    const item = makeItem({ field: 'targetDate', toValue: '2026-12-31', toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('2026-12-31')
  })

  it('T-VL-PL-04: startDate 클리어(toValue=null) 시 "(없음)"을 반환한다', () => {
    const item = makeItem({ field: 'startDate', toValue: null, toLabel: null })
    expect(resolveValueLabel(item, 'to', emptyRefs)).toBe('(없음)')
  })

  it('T-VL-PL-05: dueDate 클리어(fromValue=null) 시 "(없음)"을 반환한다', () => {
    const item = makeItem({ field: 'dueDate', fromValue: null, fromLabel: null })
    expect(resolveValueLabel(item, 'from', emptyRefs)).toBe('(없음)')
  })
})
