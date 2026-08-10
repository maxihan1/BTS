// required 빈값 판정의 전수 표 테스트 — 유형 10종 × 입력 9종, 두 화면이 같은 규칙을 쓰는지
import { describe, it, expect } from 'vitest'
import { fieldTypeEnum } from '@/api/custom-fields.types'
import type { FieldType } from '@/api/custom-fields.types'
import { isRequiredFieldEmpty } from './required-empty'

/**
 * ## 이 파일이 막는 것
 *
 * 이 함수는 **글자 단위로 같은 사본 2개**로 존재했고(생성 폼 · 편집 화면), 생성 폼만 고치자
 * 두 화면의 계약이 갈라졌다. 사본을 없앤 뒤에도 **표를 전수로 고정하지 않으면** 다음 수정이
 * 한 분기만 건드리고 지나간다.
 *
 * ★`toBe(true)` 와 `toBe(false)` 를 **양쪽 다** 명시한다. 「빈값인 것」만 단언하면
 * 함수가 항상 `true` 를 돌려줘도 통과한다(그러면 모든 required 필드가 영구 차단된다).
 *
 * ★`fieldTypeEnum` 에서 유형 목록을 **파생**시킨다. 여기 손으로 적으면 새 유형이
 * 추가돼도 이 표가 모르고 지나간다 — 아래 「유형 전수」 단언이 그것을 차단한다.
 */

/** 판정 입력 9종. 이름은 실패 메시지에 그대로 실린다. */
const INPUTS = [
  ['undefined', undefined],
  ['null', null],
  ['빈 문자열', ''],
  ['빈 배열', []],
  ['값 있는 배열', ['a']],
  ['숫자 0', 0],
  ['NaN', Number.NaN],
  ['false', false],
  ['true', true],
] as const

/**
 * 기대표 — `true` 면 빈값.
 *
 * 텍스트류 7종은 규칙이 같으므로 한 줄로 묶고 아래에서 펼친다.
 */
const TEXT_LIKE: readonly FieldType[] = [
  'SHORT_TEXT',
  'LONG_TEXT',
  'URL',
  'DATE',
  'DATETIME',
  'SINGLE_SELECT',
  'RADIO',
]

/** 입력 이름 → 빈값 여부. */
type Row = Record<(typeof INPUTS)[number][0], boolean>

const TEXT_ROW: Row = {
  undefined: true,
  null: true,
  '빈 문자열': true,
  '빈 배열': false, // 텍스트 필드에 배열이 오는 것은 비정상 입력 — 「빈값」으로 보지 않는다
  '값 있는 배열': false,
  '숫자 0': false,
  NaN: false,
  false: false,
  true: false,
}

const EXPECTED: Record<FieldType, Row> = {
  SHORT_TEXT: TEXT_ROW,
  LONG_TEXT: TEXT_ROW,
  URL: TEXT_ROW,
  DATE: TEXT_ROW,
  DATETIME: TEXT_ROW,
  SINGLE_SELECT: TEXT_ROW,
  RADIO: TEXT_ROW,
  NUMBER: {
    undefined: true,
    null: true,
    '빈 문자열': false, // 숫자 필드의 '' 는 위젯이 만들지 않는다 — NaN 으로 온다
    '빈 배열': false,
    '값 있는 배열': false,
    '숫자 0': false, // ★0 은 유효값. falsy 검사를 쓰면 여기서 깨진다
    NaN: true,
    false: false,
    true: false,
  },
  MULTI_SELECT: {
    undefined: true, // ★옛 편집 화면 사본이 여기서 뚫려 있었다
    null: true,
    '빈 문자열': false,
    '빈 배열': true,
    '값 있는 배열': false,
    '숫자 0': false,
    NaN: false,
    false: false,
    true: false,
  },
  CHECKBOX: {
    undefined: true,
    null: true,
    '빈 문자열': true, // 체크되지 않음 — raw !== true
    '빈 배열': true,
    '값 있는 배열': true,
    '숫자 0': true,
    NaN: true,
    false: true, // ★옛 편집 화면 사본은 여기서 false(=유효)를 돌려줬다
    true: false, // 체크됨 — 유일한 유효값
  },
}

describe('isRequiredFieldEmpty — 유형 10종 × 입력 9종 전수', () => {
  it('★유형 전수를 덮는다 (새 FieldType 이 표를 우회하지 못한다)', () => {
    const declared = [...fieldTypeEnum.options].sort()
    const covered = Object.keys(EXPECTED).sort()
    expect(covered).toEqual(declared)
    // 비-공허 짝 — 유형이 0건이면 아래 표 단언이 통째로 공허하다.
    expect(declared.length).toBe(10)
  })

  it('★입력 전수를 덮는다 (판정이 갈리는 지점을 빠뜨리지 않는다)', () => {
    for (const type of fieldTypeEnum.options) {
      const row = EXPECTED[type]
      expect(Object.keys(row).sort()).toEqual(INPUTS.map(([name]) => name).sort())
    }
  })

  for (const type of fieldTypeEnum.options) {
    for (const [name, value] of INPUTS) {
      const expected = EXPECTED[type][name]
      it(`${type} × ${name} → ${expected ? '빈값' : '유효'}`, () => {
        expect(isRequiredFieldEmpty(type, value)).toBe(expected)
      })
    }
  }

  it('텍스트류 7종의 규칙이 서로 완전히 같다', () => {
    // 한 유형만 슬쩍 바꾸는 변경을 차단한다.
    for (const [, value] of INPUTS) {
      const results = TEXT_LIKE.map((t) => isRequiredFieldEmpty(t, value))
      expect(new Set(results).size).toBe(1)
    }
  })

  it('판정이 한쪽으로 굳어 있지 않다 (비-공허 짝)', () => {
    // 함수가 항상 true 를 돌려주면 모든 required 필드가 영구 차단되고,
    // 항상 false 면 검증이 통째로 사라진다. 둘 다 「표를 절반만 읽는」 단언은 못 잡는다.
    const all = fieldTypeEnum.options.flatMap((t) =>
      INPUTS.map(([, v]) => isRequiredFieldEmpty(t, v)),
    )
    expect(all).toContain(true)
    expect(all).toContain(false)
  })
})
