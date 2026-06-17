// detectActiveMention / spliceMention 순수 함수 단위 테스트 — FR-MN-02 Task 1 RED
import { describe, it, expect } from 'vitest'
import { detectActiveMention, spliceMention } from '../mention-detect'

// ─────────────────────────────────────────────────────────────────────────────
// detectActiveMention
// ─────────────────────────────────────────────────────────────────────────────

describe('detectActiveMention', () => {
  describe('활성 멘션 — 경계 조건 충족', () => {
    it('문자열 시작의 @ 뒤 쿼리를 감지한다', () => {
      expect(detectActiveMention('@jo', 3)).toEqual({
        active: true,
        query: 'jo',
        start: 0,
        end: 3,
      })
    })

    it('공백 뒤의 @ 뒤 쿼리를 감지한다', () => {
      expect(detectActiveMention('hi @jo', 6)).toEqual({
        active: true,
        query: 'jo',
        start: 3,
        end: 6,
      })
    })

    it('@ 바로 다음이 caret인 경우 빈 쿼리를 반환한다', () => {
      expect(detectActiveMention('@', 1)).toEqual({
        active: true,
        query: '',
        start: 0,
        end: 1,
      })
    })

    it('개행 문자를 경계로 인식해 그 뒤의 @를 감지한다', () => {
      const result = detectActiveMention('a\n@jo', 5)
      expect(result.active).toBe(true)
      expect(result.query).toBe('jo')
    })
  })

  describe('비활성 멘션 — 경계 조건 불충족', () => {
    it('@ 앞 문자가 비공백이면 활성화하지 않는다 (이메일 주소 등)', () => {
      // 'mail a@b': caret=8, 역방향 스캔 → @(idx 6), 앞 문자='a'(비공백) → false
      expect(detectActiveMention('mail a@b', 8)).toEqual({ active: false })
    })

    it('쿼리 내부에 공백이 있으면 비활성화한다', () => {
      // '@jo bar': caret=7, 역방향 스캔 → 공백(idx 3)을 만나 즉시 false
      expect(detectActiveMention('@jo bar', 7)).toEqual({ active: false })
    })

    it('@ 앞 문자가 @ 이면 활성화하지 않는다', () => {
      // '@@': caret=2, 역방향 스캔 → @(idx 1), 앞 문자='@'(비공백) → false
      expect(detectActiveMention('@@', 2)).toEqual({ active: false })
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// spliceMention
// ─────────────────────────────────────────────────────────────────────────────

describe('spliceMention', () => {
  it('start..end 구간을 @<username> 으로 치환하고 새 caret 위치를 반환한다', () => {
    // 'hi @jo': start=3(@의 위치), end=6(caret), username='jdoe'
    // next = 'hi ' + '@jdoe' + ' ' = 'hi @jdoe '
    // caret = 3 + 4(jdoe) + 2(@+space) = 9
    expect(spliceMention('hi @jo', 3, 6, 'jdoe')).toEqual({
      next: 'hi @jdoe ',
      caret: 9,
    })
  })
})
