// FR-NT-01 알림 정책 MSW fixture — 시드 정책 배열 (전역 정책, projectKey 없음)

// ─────────────────────────────────────────────────────────────────────────────
// 내부 타입 — Task 1 api/notification-policies.ts와 동형 (계약 고정)
// ─────────────────────────────────────────────────────────────────────────────

/** 알림 정책 응답 DTO — NON_NULL 정책으로 projectKey 없으면 키 생략 */
export interface NotificationPolicyFixture {
  id: string
  projectKey?: string
  eventType: string
  recipientRole: string
  channel: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID 상수 (RFC4122 v4 — zod-v4-uuid-fixture-strictness)
// ─────────────────────────────────────────────────────────────────────────────

/** 시드 정책 UUID 모음 */
export const SEED_POLICY_IDS = {
  p1: 'a1b2c3d4-e5f6-4789-abcd-ef0123456701',
  p2: 'b2c3d4e5-f6a7-4890-bcde-f01234567802',
  p3: 'c3d4e5f6-a7b8-4901-8efa-012345678903',
  p4: 'd4e5f6a7-b8c9-4012-9ef0-123456789004',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 시드 정책 배열
// ─────────────────────────────────────────────────────────────────────────────

/** 알림 정책 시드 목록 — 전역 정책만(projectKey 없음, NON_NULL 미러) */
export const notificationPolicySeedData: NotificationPolicyFixture[] = [
  {
    id: SEED_POLICY_IDS.p1,
    eventType: 'issue.created',
    recipientRole: 'REPORTER',
    channel: 'EMAIL',
    enabled: true,
    createdAt: '2026-06-01T10:00:00Z',
    updatedAt: '2026-06-01T10:00:00Z',
  },
  {
    id: SEED_POLICY_IDS.p2,
    eventType: 'issue.assigned',
    recipientRole: 'ASSIGNEE',
    channel: 'IN_APP',
    enabled: true,
    createdAt: '2026-06-01T10:01:00Z',
    updatedAt: '2026-06-01T10:01:00Z',
  },
  {
    id: SEED_POLICY_IDS.p3,
    eventType: 'issue.transitioned',
    recipientRole: 'WATCHER',
    channel: 'EMAIL',
    enabled: false,
    createdAt: '2026-06-01T10:02:00Z',
    updatedAt: '2026-06-01T10:02:00Z',
  },
  {
    id: SEED_POLICY_IDS.p4,
    eventType: 'sprint.started',
    recipientRole: 'PROJECT_ADMIN',
    channel: 'SLACK',
    enabled: true,
    createdAt: '2026-06-01T10:03:00Z',
    updatedAt: '2026-06-01T10:03:00Z',
  },
]
