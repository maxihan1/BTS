// 알림 정책 i18n 라벨 단위 테스트 — enum 미러 대비 차집합 0 판별식 + 미지 값 fallback + 페이지 문자열 검증

import { describe, it, expect } from 'vitest'
import { NOTIFICATION_EVENT_TYPES, RECIPIENT_ROLES, CHANNELS } from '@/api/notification-policies'
import {
  eventTypeLabels,
  recipientRoleLabels,
  recipientRoleDescriptions,
  channelLabels,
  notificationPolicyLabels,
  labelFor,
} from './notification-policy-labels'

/**
 * 기준 집합(enum 미러) 대비 라벨 맵의 미커버 키를 돌려주는 차집합 판별식.
 *
 * 하드코딩 목록끼리 눈으로 대조하는 대신 api/notification-policies.ts 의 미러를
 * 유일한 기준으로 삼는다 — 백엔드 enum 이 늘면 미러가 늘고, 미러가 늘면 여기서 잡힌다.
 *
 * @param reference 기준 enum 미러 (wireValue 또는 NAME 배열)
 * @param labels    검사할 키 → 한국어 라벨 Record
 * @returns 라벨이 없거나 빈 문자열인 키 목록 (정상이면 빈 배열)
 */
function uncoveredKeys(reference: readonly string[], labels: Record<string, string>): string[] {
  return reference.filter((key) => {
    const label = labels[key]
    return !label || label.trim() === ''
  })
}

/**
 * 라벨 맵에만 있고 기준 집합에는 없는 잉여 키를 돌려주는 반대 방향 차집합 판별식.
 *
 * 커버리지(미러 ⊆ 맵)만 닫으면 봉인이 절반이다 — 오타 키나 백엔드에서 제거된
 * 죽은 라벨이 그대로 남는다. 두 방향을 함께 걸어야 두 목록이 서로를 본다.
 *
 * @param reference 기준 enum 미러 (wireValue 또는 NAME 배열)
 * @param labels    검사할 키 → 한국어 라벨 Record
 * @returns 기준 집합에 없는 키 목록 (정상이면 빈 배열)
 */
function surplusKeys(reference: readonly string[], labels: Record<string, string>): string[] {
  const known = new Set<string>(reference)
  return Object.keys(labels).filter((key) => !known.has(key))
}

// ─────────────────────────────────────────────────────────────────────────────
// uncoveredKeys — 판별식 자체의 양성 대조군
// 판별식이 항상 []를 돌려주는 고장 상태면 아래 커버리지 테스트 전부가 공허하게 통과한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('uncoveredKeys 판별식', () => {
  it('기준 집합에만 있는 키를 잡아낸다 (양성 대조군)', () => {
    expect(uncoveredKeys(['a', 'b'], { a: '가' })).toEqual(['b'])
  })

  it('값이 빈 문자열이면 미커버로 잡아낸다', () => {
    expect(uncoveredKeys(['a'], { a: '   ' })).toEqual(['a'])
  })

  it('전량 커버되면 빈 배열을 돌려준다 (음성 대조군)', () => {
    expect(uncoveredKeys(['a', 'b'], { a: '가', b: '나' })).toEqual([])
  })
})

describe('surplusKeys 판별식', () => {
  it('라벨 맵에만 있는 키를 잡아낸다 (양성 대조군)', () => {
    expect(surplusKeys(['a'], { a: '가', b: '나' })).toEqual(['b'])
  })

  it('기준 집합과 일치하면 빈 배열을 돌려준다 (음성 대조군)', () => {
    expect(surplusKeys(['a', 'b'], { a: '가', b: '나' })).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// eventTypeLabels — NOTIFICATION_EVENT_TYPES 미러 전량 커버
// ─────────────────────────────────────────────────────────────────────────────

describe('eventTypeLabels', () => {
  it('기준 집합 NOTIFICATION_EVENT_TYPES 가 비어있지 않다 (공허한 통과 차단)', () => {
    expect(NOTIFICATION_EVENT_TYPES.length).toBeGreaterThan(0)
  })

  it('NOTIFICATION_EVENT_TYPES 전량에 한국어 라벨이 존재한다 (차집합 0)', () => {
    expect(uncoveredKeys(NOTIFICATION_EVENT_TYPES, eventTypeLabels)).toEqual([])
  })

  it('NOTIFICATION_EVENT_TYPES 밖의 잉여 라벨이 없다 (역방향 차집합 0)', () => {
    expect(surplusKeys(NOTIFICATION_EVENT_TYPES, eventTypeLabels)).toEqual([])
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

  it('issue.comment_deleted 라벨이 존재한다 (FR-CO-02)', () => {
    expect(eventTypeLabels['issue.comment_deleted']).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// recipientRoleLabels — RECIPIENT_ROLES 미러 전량 커버
// ─────────────────────────────────────────────────────────────────────────────

describe('recipientRoleLabels', () => {
  it('기준 집합 RECIPIENT_ROLES 가 비어있지 않다 (공허한 통과 차단)', () => {
    expect(RECIPIENT_ROLES.length).toBeGreaterThan(0)
  })

  it('RECIPIENT_ROLES 전량에 한국어 라벨이 존재한다 (차집합 0)', () => {
    expect(uncoveredKeys(RECIPIENT_ROLES, recipientRoleLabels)).toEqual([])
  })

  it('RECIPIENT_ROLES 밖의 잉여 라벨이 없다 (역방향 차집합 0)', () => {
    expect(surplusKeys(RECIPIENT_ROLES, recipientRoleLabels)).toEqual([])
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

  it('COMMENT_AUTHOR 라벨이 존재한다 (FR-CO-02)', () => {
    expect(recipientRoleLabels['COMMENT_AUTHOR']).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// channelLabels — CHANNELS 미러 전량 커버
// ─────────────────────────────────────────────────────────────────────────────

describe('channelLabels', () => {
  it('기준 집합 CHANNELS 가 비어있지 않다 (공허한 통과 차단)', () => {
    expect(CHANNELS.length).toBeGreaterThan(0)
  })

  it('CHANNELS 전량에 한국어 라벨이 존재한다 (차집합 0)', () => {
    expect(uncoveredKeys(CHANNELS, channelLabels)).toEqual([])
  })

  it('CHANNELS 밖의 잉여 라벨이 없다 (역방향 차집합 0)', () => {
    expect(surplusKeys(CHANNELS, channelLabels)).toEqual([])
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
  // RECIPIENT_ROLES 미러를 기준 집합으로 양방향 차집합을 건다.
  // 추가로 recipientRoleLabels 와의 lockstep 을 둬서 "라벨은 넣고 설명은 빠뜨림"도 막는다.

  it('RECIPIENT_ROLES 전량에 설명이 존재한다 (차집합 0)', () => {
    expect(uncoveredKeys(RECIPIENT_ROLES, recipientRoleDescriptions)).toEqual([])
  })

  it('RECIPIENT_ROLES 밖의 잉여 설명이 없다 (역방향 차집합 0)', () => {
    expect(surplusKeys(RECIPIENT_ROLES, recipientRoleDescriptions)).toEqual([])
  })

  it('recipientRoleLabels 와 키 집합이 정확히 일치한다 (양방향 차집합 0)', () => {
    const labelKeys = Object.keys(recipientRoleLabels).sort()
    const descriptionKeys = Object.keys(recipientRoleDescriptions).sort()
    expect(descriptionKeys).toEqual(labelKeys)
  })

  it('각 값이 비어있지 않은 문자열이다', () => {
    const emptyValues = Object.entries(recipientRoleDescriptions).filter(
      ([, val]) => !val || val.trim() === '',
    )
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

  it('COMMENT_AUTHOR 설명이 존재한다 (FR-CO-02)', () => {
    expect(recipientRoleDescriptions['COMMENT_AUTHOR']).toBeTruthy()
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
