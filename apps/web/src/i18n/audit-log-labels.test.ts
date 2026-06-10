// 감사 로그 UI 라벨 단위 테스트 — auditLogLabels 키 존재 + authEventTypeLabels 12종 누락 0 검증

import { describe, it, expect } from 'vitest'
import { AUTH_EVENT_TYPES } from '@/api/audit-logs'
import { auditLogLabels, authEventTypeLabels } from './audit-log-labels'

describe('auditLogLabels', () => {
  describe('page 그룹', () => {
    it('heading 키가 존재한다', () => {
      expect(auditLogLabels.page.heading).toBeTruthy()
    })

    it('description 키가 존재한다', () => {
      expect(auditLogLabels.page.description).toBeTruthy()
    })
  })

  describe('filter 그룹', () => {
    it('eventType 키가 존재한다', () => {
      expect(auditLogLabels.filter.eventType).toBeTruthy()
    })

    it('userId 키가 존재한다', () => {
      expect(auditLogLabels.filter.userId).toBeTruthy()
    })

    it('from 키가 존재한다', () => {
      expect(auditLogLabels.filter.from).toBeTruthy()
    })

    it('to 키가 존재한다', () => {
      expect(auditLogLabels.filter.to).toBeTruthy()
    })

    it('reset 키가 존재한다', () => {
      expect(auditLogLabels.filter.reset).toBeTruthy()
    })

    it('allEvents 키가 존재한다', () => {
      expect(auditLogLabels.filter.allEvents).toBeTruthy()
    })

    it('searchPlaceholder 키가 존재한다', () => {
      expect(auditLogLabels.filter.searchPlaceholder).toBeTruthy()
    })
  })

  describe('table 그룹', () => {
    it('createdAt 키가 존재한다', () => {
      expect(auditLogLabels.table.createdAt).toBeTruthy()
    })

    it('eventType 키가 존재한다', () => {
      expect(auditLogLabels.table.eventType).toBeTruthy()
    })

    it('subject 키가 존재한다', () => {
      expect(auditLogLabels.table.subject).toBeTruthy()
    })

    it('provider 키가 존재한다', () => {
      expect(auditLogLabels.table.provider).toBeTruthy()
    })

    it('ipAddress 키가 존재한다', () => {
      expect(auditLogLabels.table.ipAddress).toBeTruthy()
    })

    it('metadata 키가 존재한다', () => {
      expect(auditLogLabels.table.metadata).toBeTruthy()
    })
  })

  describe('empty 그룹', () => {
    it('noResults 키가 존재한다', () => {
      expect(auditLogLabels.empty.noResults).toBeTruthy()
    })
  })

  describe('pagination 그룹', () => {
    it('previous 키가 존재한다', () => {
      expect(auditLogLabels.pagination.previous).toBeTruthy()
    })

    it('next 키가 존재한다', () => {
      expect(auditLogLabels.pagination.next).toBeTruthy()
    })

    it('rangeOf 함수가 정확한 문자열을 반환한다', () => {
      const result = auditLogLabels.pagination.rangeOf(1, 50, 234)
      expect(result).toBe('234개 중 1–50')
    })
  })

  describe('fallback 그룹', () => {
    it('unknownSubject 키가 존재한다', () => {
      expect(auditLogLabels.fallback.unknownSubject).toBeTruthy()
    })

    it('unknownProvider 키가 존재한다', () => {
      expect(auditLogLabels.fallback.unknownProvider).toBeTruthy()
    })
  })
})

describe('authEventTypeLabels', () => {
  it('AUTH_EVENT_TYPES 12종 전부 한국어 라벨이 존재한다 (누락 0)', () => {
    const missingKeys = AUTH_EVENT_TYPES.filter(
      (key) => !(key in authEventTypeLabels) || !authEventTypeLabels[key],
    )
    expect(missingKeys).toEqual([])
  })

  it('LOGIN_SUCCESS 라벨이 존재한다', () => {
    expect(authEventTypeLabels['LOGIN_SUCCESS']).toBeTruthy()
  })

  it('LOGIN_FAILURE 라벨이 존재한다', () => {
    expect(authEventTypeLabels['LOGIN_FAILURE']).toBeTruthy()
  })

  it('LOGOUT 라벨이 존재한다', () => {
    expect(authEventTypeLabels['LOGOUT']).toBeTruthy()
  })

  it('LOGOUT_ALL_DEVICES 라벨이 존재한다', () => {
    expect(authEventTypeLabels['LOGOUT_ALL_DEVICES']).toBeTruthy()
  })

  it('TOKEN_REFRESHED 라벨이 존재한다', () => {
    expect(authEventTypeLabels['TOKEN_REFRESHED']).toBeTruthy()
  })

  it('SUSPICIOUS_REFRESH_REPLAY 라벨이 존재한다', () => {
    expect(authEventTypeLabels['SUSPICIOUS_REFRESH_REPLAY']).toBeTruthy()
  })

  it('USER_PROVISIONED 라벨이 존재한다', () => {
    expect(authEventTypeLabels['USER_PROVISIONED']).toBeTruthy()
  })

  it('PAT_USED 라벨이 존재한다', () => {
    expect(authEventTypeLabels['PAT_USED']).toBeTruthy()
  })

  it('LDAP_UNAVAILABLE 라벨이 존재한다', () => {
    expect(authEventTypeLabels['LDAP_UNAVAILABLE']).toBeTruthy()
  })

  it('PROJECT_MEMBER_ADDED 라벨이 존재한다', () => {
    expect(authEventTypeLabels['PROJECT_MEMBER_ADDED']).toBeTruthy()
  })

  it('PROJECT_ROLE_CHANGED 라벨이 존재한다', () => {
    expect(authEventTypeLabels['PROJECT_ROLE_CHANGED']).toBeTruthy()
  })

  it('PROJECT_MEMBER_REMOVED 라벨이 존재한다', () => {
    expect(authEventTypeLabels['PROJECT_MEMBER_REMOVED']).toBeTruthy()
  })
})
