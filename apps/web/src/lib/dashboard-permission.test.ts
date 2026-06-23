// canEditDashboard 순수 함수 단위 테스트
import { describe, it, expect } from 'vitest'
import { canEditDashboard } from './dashboard-permission'

describe('canEditDashboard', () => {
  const dashboard = { ownerId: 'user-123' }

  it('ownerId === currentUserId 이면 true 를 반환한다', () => {
    expect(canEditDashboard(dashboard, 'user-123')).toBe(true)
  })

  it('ownerId !== currentUserId 이면 false 를 반환한다', () => {
    expect(canEditDashboard(dashboard, 'user-456')).toBe(false)
  })

  it('currentUserId 가 null 이면 false 를 반환한다', () => {
    expect(canEditDashboard(dashboard, null)).toBe(false)
  })

  it('currentUserId 가 undefined 이면 false 를 반환한다', () => {
    expect(canEditDashboard(dashboard, undefined)).toBe(false)
  })

  it('빈 문자열은 소유자가 아니므로 false 를 반환한다', () => {
    expect(canEditDashboard(dashboard, '')).toBe(false)
  })
})
