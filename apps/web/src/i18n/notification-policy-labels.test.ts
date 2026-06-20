// 알림 정책 i18n 라벨 단위 테스트 — enum 9/9/5 종 누락 0 + 미지 값 fallback + 페이지 문자열 검증

import { describe, it, expect } from 'vitest'
import { RECIPIENT_ROLES } from '@/api/notification-policies'
import {
  eventTypeLabels,
  recipientRoleLabels,
  recipientRoleDescriptions,
  channelLabels,
  notificationPolicyLabels,
  labelFor,
} from './notification-policy-labels'

// ─────────────────────────────────────────────────────────────────────────────
// eventTypeLabels — wireValue 9종
// ─────────────────────────────────────────────────────────────────────────────

describe('eventTypeLabels', () => {
  const EVENT_TYPE_WIRE_VALUES = [
    'issue.created',
    'issue.assigned',
    'issue.transitioned',
    'issue.commented',
    'issue.due_soon',
    'issue.overdue',
    'sprint.started',
    'sprint.ended',
    'automation.failed',
  ] as const

  it('wireValue 9종 전부 한국어 라벨이 존재한다 (누락 0)', () => {
    const missingKeys = EVENT_TYPE_WIRE_VALUES.filter(
      (key) => !(key in eventTypeLabels) || !eventTypeLabels[key],
    )
    expect(missingKeys).toEqual([])
  })

  it('issue.created 라벨이 존재한다', () => {
    expect(eventTypeLabels['issue.created']).toBeTruthy()
  })

  it('issue.assigned 라벨이 존재한다', () => {
    expect(eventTypeLabels['issue.assigned']).toBeTruthy()
  })

  it('issue.transitioned 라벨이 존재한다', () => {
    expect(eventTypeLabels['issue.transitioned']).toBeTruthy()
  })

  it('issue.commented 라벨이 존재한다', () => {
    expect(eventTypeLabels['issue.commented']).toBeTruthy()
  })

  it('issue.due_soon 라벨이 존재한다', () => {
    expect(eventTypeLabels['issue.due_soon']).toBeTruthy()
  })

  it('issue.overdue 라벨이 존재한다', () => {
    expect(eventTypeLabels['issue.overdue']).toBeTruthy()
  })

  it('sprint.started 라벨이 존재한다', () => {
    expect(eventTypeLabels['sprint.started']).toBeTruthy()
  })

  it('sprint.ended 라벨이 존재한다', () => {
    expect(eventTypeLabels['sprint.ended']).toBeTruthy()
  })

  it('automation.failed 라벨이 존재한다', () => {
    expect(eventTypeLabels['automation.failed']).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// recipientRoleLabels — NAME 9종
// ─────────────────────────────────────────────────────────────────────────────

describe('recipientRoleLabels', () => {
  const RECIPIENT_ROLE_NAMES = [
    'REPORTER',
    'ASSIGNEE',
    'PREVIOUS_ASSIGNEE',
    'WATCHER',
    'COMPONENT_LEAD',
    'MENTIONED',
    'PROJECT_MEMBER',
    'RULE_OWNER',
    'PROJECT_ADMIN',
  ] as const

  it('NAME 9종 전부 한국어 라벨이 존재한다 (누락 0)', () => {
    const missingKeys = RECIPIENT_ROLE_NAMES.filter(
      (key) => !(key in recipientRoleLabels) || !recipientRoleLabels[key],
    )
    expect(missingKeys).toEqual([])
  })

  it('REPORTER 라벨이 존재한다', () => {
    expect(recipientRoleLabels['REPORTER']).toBeTruthy()
  })

  it('ASSIGNEE 라벨이 존재한다', () => {
    expect(recipientRoleLabels['ASSIGNEE']).toBeTruthy()
  })

  it('PREVIOUS_ASSIGNEE 라벨이 존재한다', () => {
    expect(recipientRoleLabels['PREVIOUS_ASSIGNEE']).toBeTruthy()
  })

  it('WATCHER 라벨이 존재한다', () => {
    expect(recipientRoleLabels['WATCHER']).toBeTruthy()
  })

  it('COMPONENT_LEAD 라벨이 존재한다', () => {
    expect(recipientRoleLabels['COMPONENT_LEAD']).toBeTruthy()
  })

  it('MENTIONED 라벨이 존재한다', () => {
    expect(recipientRoleLabels['MENTIONED']).toBeTruthy()
  })

  it('PROJECT_MEMBER 라벨이 존재한다', () => {
    expect(recipientRoleLabels['PROJECT_MEMBER']).toBeTruthy()
  })

  it('RULE_OWNER 라벨이 존재한다', () => {
    expect(recipientRoleLabels['RULE_OWNER']).toBeTruthy()
  })

  it('PROJECT_ADMIN 라벨이 존재한다', () => {
    expect(recipientRoleLabels['PROJECT_ADMIN']).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// channelLabels — NAME 5종
// ─────────────────────────────────────────────────────────────────────────────

describe('channelLabels', () => {
  const CHANNEL_NAMES = ['EMAIL', 'IN_APP', 'SLACK', 'TEAMS', 'WEBHOOK'] as const

  it('NAME 5종 전부 한국어 라벨이 존재한다 (누락 0)', () => {
    const missingKeys = CHANNEL_NAMES.filter(
      (key) => !(key in channelLabels) || !channelLabels[key],
    )
    expect(missingKeys).toEqual([])
  })

  it('EMAIL 라벨이 존재한다', () => {
    expect(channelLabels['EMAIL']).toBeTruthy()
  })

  it('IN_APP 라벨이 존재한다', () => {
    expect(channelLabels['IN_APP']).toBeTruthy()
  })

  it('SLACK 라벨이 존재한다', () => {
    expect(channelLabels['SLACK']).toBeTruthy()
  })

  it('TEAMS 라벨이 존재한다', () => {
    expect(channelLabels['TEAMS']).toBeTruthy()
  })

  it('WEBHOOK 라벨이 존재한다', () => {
    expect(channelLabels['WEBHOOK']).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// labelFor — 미지 값 원문 fallback
// ─────────────────────────────────────────────────────────────────────────────

describe('labelFor', () => {
  it('알려진 키에 대해 매핑된 라벨을 반환한다', () => {
    expect(labelFor(eventTypeLabels, 'issue.created')).toBeTruthy()
    expect(labelFor(eventTypeLabels, 'issue.created')).not.toBe('issue.created')
  })

  it('미지 eventType 값은 원문 키를 그대로 반환한다 (fallback)', () => {
    expect(labelFor(eventTypeLabels, 'issue.unknown_event')).toBe('issue.unknown_event')
  })

  it('미지 recipientRole 값은 원문 키를 그대로 반환한다 (fallback)', () => {
    expect(labelFor(recipientRoleLabels, 'FUTURE_ROLE')).toBe('FUTURE_ROLE')
  })

  it('미지 channel 값은 원문 키를 그대로 반환한다 (fallback)', () => {
    expect(labelFor(channelLabels, 'FUTURE_CHANNEL')).toBe('FUTURE_CHANNEL')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// notificationPolicyLabels — 페이지/테이블/폼/에러 문자열
// ─────────────────────────────────────────────────────────────────────────────

describe('notificationPolicyLabels', () => {
  describe('page 그룹', () => {
    it('heading 키가 존재한다', () => {
      expect(notificationPolicyLabels.page.heading).toBeTruthy()
    })

    it('description 키가 존재한다', () => {
      expect(notificationPolicyLabels.page.description).toBeTruthy()
    })
  })

  describe('table 그룹', () => {
    it('eventType 헤더가 존재한다', () => {
      expect(notificationPolicyLabels.table.eventType).toBeTruthy()
    })

    it('recipientRole 헤더가 존재한다', () => {
      expect(notificationPolicyLabels.table.recipientRole).toBeTruthy()
    })

    it('channel 헤더가 존재한다', () => {
      expect(notificationPolicyLabels.table.channel).toBeTruthy()
    })

    it('enabled 헤더가 존재한다', () => {
      expect(notificationPolicyLabels.table.enabled).toBeTruthy()
    })

    it('actions 헤더가 존재한다', () => {
      expect(notificationPolicyLabels.table.actions).toBeTruthy()
    })
  })

  describe('form 그룹', () => {
    it('eventType 라벨이 존재한다', () => {
      expect(notificationPolicyLabels.form.eventType).toBeTruthy()
    })

    it('recipientRole 라벨이 존재한다', () => {
      expect(notificationPolicyLabels.form.recipientRole).toBeTruthy()
    })

    it('channel 라벨이 존재한다', () => {
      expect(notificationPolicyLabels.form.channel).toBeTruthy()
    })

    it('addButton 텍스트가 존재한다', () => {
      expect(notificationPolicyLabels.form.addButton).toBeTruthy()
    })
  })

  describe('actions 그룹', () => {
    it('deleteButton 텍스트가 존재한다', () => {
      expect(notificationPolicyLabels.actions.deleteButton).toBeTruthy()
    })

    it('confirmButton 텍스트가 존재한다', () => {
      expect(notificationPolicyLabels.actions.confirmButton).toBeTruthy()
    })

    it('cancelButton 텍스트가 존재한다', () => {
      expect(notificationPolicyLabels.actions.cancelButton).toBeTruthy()
    })

    it('toggleEnable 텍스트가 존재한다', () => {
      expect(notificationPolicyLabels.actions.toggleEnable).toBeTruthy()
    })

    it('toggleDisable 텍스트가 존재한다', () => {
      expect(notificationPolicyLabels.actions.toggleDisable).toBeTruthy()
    })
  })

  describe('empty 그룹', () => {
    it('noResults 텍스트가 존재한다', () => {
      expect(notificationPolicyLabels.empty.noResults).toBeTruthy()
    })
  })

  describe('error 그룹', () => {
    it('duplicate 에러 메시지가 존재한다', () => {
      expect(notificationPolicyLabels.error.duplicate).toBeTruthy()
    })

    it('generic 에러 메시지가 존재한다', () => {
      expect(notificationPolicyLabels.error.generic).toBeTruthy()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// recipientRoleDescriptions — NAME 9종 설명 단일출처
// ─────────────────────────────────────────────────────────────────────────────

describe('recipientRoleDescriptions', () => {
  // RECIPIENT_ROLES를 진실 출처로 사용 — 프론트 미러와 descriptions 정합 보장

  it('RECIPIENT_ROLES 9종 키를 정확히 보유한다 (누락 0)', () => {
    const missingKeys = RECIPIENT_ROLES.filter((key) => !(key in recipientRoleDescriptions))
    expect(missingKeys).toEqual([])
  })

  it('recipientRoleDescriptions에 잉여 키가 없다 (RECIPIENT_ROLES 초과 0)', () => {
    const knownSet = new Set<string>(RECIPIENT_ROLES)
    const surplusKeys = Object.keys(recipientRoleDescriptions).filter((key) => !knownSet.has(key))
    expect(surplusKeys).toEqual([])
  })

  it('각 값이 비어있지 않은 문자열이다', () => {
    const emptyValues = RECIPIENT_ROLES.filter((key) => {
      const val = recipientRoleDescriptions[key]
      return !val || val.trim() === ''
    })
    expect(emptyValues).toEqual([])
  })

  it('어떤 값도 콜론(:)으로 끝나지 않는다 (한국어 콜론 종결 금지)', () => {
    const colonTerminated = Object.entries(recipientRoleDescriptions).filter(([, val]) =>
      val.trimEnd().endsWith(':'),
    )
    expect(colonTerminated).toEqual([])
  })

  it('RULE_OWNER 설명에 "미지원" 문구가 포함된다 (무동작 안내)', () => {
    expect(recipientRoleDescriptions['RULE_OWNER']).toContain('미지원')
  })

  it('WATCHER 설명에 "구독" 문구가 포함된다 (recipientRoleLabels 용어 정합)', () => {
    expect(recipientRoleDescriptions['WATCHER']).toContain('구독')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// notificationPolicyLabels.form — 신규 키 (Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('notificationPolicyLabels.form 신규 키 (Task 3)', () => {
  it('recipientUnsupportedSuffix 키가 존재한다', () => {
    expect(notificationPolicyLabels.form.recipientUnsupportedSuffix).toBeTruthy()
  })

  it('recipientUnsupportedHint 키가 존재한다', () => {
    expect(notificationPolicyLabels.form.recipientUnsupportedHint).toBeTruthy()
  })

  it('recipientUnsupportedSuffix 값이 콜론으로 끝나지 않는다', () => {
    expect(notificationPolicyLabels.form.recipientUnsupportedSuffix.trimEnd().endsWith(':')).toBe(false)
  })

  it('recipientUnsupportedHint 값이 콜론으로 끝나지 않는다', () => {
    expect(notificationPolicyLabels.form.recipientUnsupportedHint.trimEnd().endsWith(':')).toBe(false)
  })
})
