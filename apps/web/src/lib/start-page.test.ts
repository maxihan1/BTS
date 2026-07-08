// lib/start-page.ts 순수 로직 단위 테스트 — resolveStartPageNav 매핑/폴백 + preferencesSchema startPage 검증 (FR-PF-02 Task-5 RED)
import { describe, it, expect } from 'vitest'
import { resolveStartPageNav, isStartPage } from './start-page'
import { preferencesSchema } from '@/api/preferences'

describe('resolveStartPageNav', () => {
  it("'dashboards' → { to: '/dashboards' }", () => {
    expect(resolveStartPageNav('dashboards', 'u-1')).toEqual({ to: '/dashboards' })
  })

  it("'my_issues' + userId='u-1' → { to: '/issues', search: { assignee: 'u-1' } }", () => {
    expect(resolveStartPageNav('my_issues', 'u-1')).toEqual({
      to: '/issues',
      search: { assignee: 'u-1' },
    })
  })

  it("'issues' → { to: '/issues' }", () => {
    expect(resolveStartPageNav('issues', 'u-1')).toEqual({ to: '/issues' })
  })

  it("'inbox' → { to: '/inbox' }", () => {
    expect(resolveStartPageNav('inbox', 'u-1')).toEqual({ to: '/inbox' })
  })

  it('화이트리스트 밖 키는 /dashboards로 폴백한다', () => {
    expect(resolveStartPageNav('evil', 'u-1')).toEqual({ to: '/dashboards' })
  })

  it('startPage가 undefined면 /dashboards로 폴백한다', () => {
    expect(resolveStartPageNav(undefined, 'u-1')).toEqual({ to: '/dashboards' })
  })

  it("'my_issues'인데 userId가 없으면 /dashboards로 폴백한다", () => {
    expect(resolveStartPageNav('my_issues', undefined)).toEqual({ to: '/dashboards' })
  })
})

describe('isStartPage', () => {
  it("화이트리스트 4종은 true를 반환한다", () => {
    expect(isStartPage('dashboards')).toBe(true)
    expect(isStartPage('my_issues')).toBe(true)
    expect(isStartPage('issues')).toBe(true)
    expect(isStartPage('inbox')).toBe(true)
  })

  it('화이트리스트 밖 값은 false를 반환한다', () => {
    expect(isStartPage('evil')).toBe(false)
    expect(isStartPage(undefined)).toBe(false)
  })
})

describe('preferencesSchema — startPage 필드', () => {
  it('유효한 startPage를 포함한 객체를 파싱한다', () => {
    const parsed = preferencesSchema.parse({
      theme: 'system',
      locale: 'ko',
      dateFormat: 'iso',
      startPage: 'my_issues',
    })
    expect(parsed.startPage).toBe('my_issues')
  })

  it('잘못된 startPage 값은 reject한다', () => {
    expect(() =>
      preferencesSchema.parse({
        theme: 'system',
        locale: 'ko',
        dateFormat: 'iso',
        startPage: 'evil',
      }),
    ).toThrow()
  })
})
