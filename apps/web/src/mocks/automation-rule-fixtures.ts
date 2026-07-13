// FR-AT-01 D6 자동화 룰 MSW fixture — 시드 데이터·공유 stateful store·시나리오 플래그 상수
import type { AutomationRule } from '@/api/automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (board-fixtures.ts 동형) — 신규 의존성 금지, crypto.randomUUID 표준 API 사용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * Zod v4 z.string().uuid() 검증을 통과하는 형식을 보장한다(zod-v4-uuid-fixture-strictness).
 */
export function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16)
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 플래그 localStorage 키 (account-link-fixtures.ts SCENARIO_KEY 관례)
// E2E에서 addInitScript로 localStorage에 세팅해 분기를 유발한다.
// ─────────────────────────────────────────────────────────────────────────────

export const SCENARIO_KEY = {
  /** 목록 GET을 store 내용과 무관하게 빈 배열로 강제한다 — 빈 상태 CTA E2E용 */
  EMPTY_LIST: 'msw:automation-rule:empty-list',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 시드 프로젝트/사용자 상수 — 다른 BC fixture와 정합(E2E 화면 이동 시 데이터 이어짐)
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 시드 프로젝트 키 — board-fixtures/cfd-handlers 등과 동일 값 */
export const DEFAULT_AUTOMATION_PROJECT_KEY = 'ATLAS'

/** 시드/생성 룰의 작성자 UUID — auth-fixtures.ts whoami 로그인 사용자(alice)와 동기화 */
export const DEFAULT_AUTOMATION_ACTOR_ID = '00000000-0000-4000-8000-000000000001'

/** 시드 룰 UUID 모음 (RFC4122 v4 — zod-v4-uuid-fixture-strictness) */
export const SEED_AUTOMATION_RULE_IDS = {
  issueCreated: 'a1000000-0000-4000-8000-000000000001',
  scheduled: 'a1000000-0000-4000-8000-000000000002',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 룰 목록 — 응답 스키마 required 전 필드 채움(zod-schema-strengthen-inline-mock-fanout).
// FR-AT-02(actions·actorUserId) 확장 후에도 두 필드 모두 채워 계약 drift를 막는다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 기본 자동화 룰 시드 — ATLAS 프로젝트, 트리거 2종(ISSUE_CREATED·SCHEDULED).
 * dev(pnpm dev)/E2E 진입 시 빈 화면 방지 + "시드된 목록" 시나리오 기본값.
 *
 * SCHEDULED 시드는 액션 2건(SET_FIELD·ASSIGN)을 채워 "액션 있는 룰" 계약도 커버하고,
 * condition도 채워 "조건 있는 룰"(FR-AT-03) 계약을 커버한다
 * (config는 응답 규약대로 객체 — 요청 config=JSON 문자열과 비대칭, EC1).
 * ISSUE_CREATED 시드는 actions:[]·condition:null로 "트리거만 있는 룰"(EC5) 계약을 커버한다.
 */
export const DEFAULT_AUTOMATION_RULES: AutomationRule[] = [
  {
    id: SEED_AUTOMATION_RULE_IDS.issueCreated,
    projectKey: DEFAULT_AUTOMATION_PROJECT_KEY,
    name: '이슈 생성 알림',
    enabled: true,
    triggerType: 'ISSUE_CREATED',
    triggerConfig: '{}',
    condition: null,
    actions: [],
    actorUserId: DEFAULT_AUTOMATION_ACTOR_ID,
    hasWebhookToken: false,
    nextFireAt: null,
    createdBy: DEFAULT_AUTOMATION_ACTOR_ID,
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    version: 1,
  },
  {
    id: SEED_AUTOMATION_RULE_IDS.scheduled,
    projectKey: DEFAULT_AUTOMATION_PROJECT_KEY,
    name: '매일 오전 스캔',
    enabled: true,
    triggerType: 'SCHEDULED',
    triggerConfig: '{"cron":"0 0 9 * * *"}',
    condition: '{"and":[{">":[{"var":"issue.priority"},3]}]}',
    // 액션 있는 픽스처(FR-AT-02) — SET_FIELD(priority=3)·ASSIGN(해제) 2건, config는 응답 규약대로 객체.
    actions: [
      { type: 'SET_FIELD', config: { field: 'priority', value: 3 } },
      { type: 'ASSIGN', config: { assigneeId: null } },
    ],
    actorUserId: DEFAULT_AUTOMATION_ACTOR_ID,
    hasWebhookToken: false,
    nextFireAt: '2026-07-11T09:00:00Z',
    createdBy: DEFAULT_AUTOMATION_ACTOR_ID,
    createdAt: '2026-07-01T09:05:00Z',
    updatedAt: '2026-07-01T09:05:00Z',
    version: 1,
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — automation-rule-handlers.ts가 직접 참조(단일 진실 출처)
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 인메모리 룰 store — id → AutomationRule */
export let ruleStore: Map<string, AutomationRule> = new Map()

/**
 * store를 빈 상태로 초기화한다.
 * 테스트 afterEach 또는 E2E 시드 전 호출해 이전 테스트 잔여 데이터를 제거한다
 * (msw-derived-behavior-shared-store-e2e).
 */
export function resetAutomationRuleStore(): void {
  ruleStore = new Map()
}

/**
 * 지정한 룰 배열로 store를 시드한다. 기존 항목은 id 기준으로 덮어쓴다.
 * E2E addInitScript 또는 테스트 beforeEach에서 초기 상태를 구성할 때 사용한다.
 *
 * @param rules 시드할 룰 목록
 */
export function seedAutomationRules(rules: AutomationRule[]): void {
  for (const rule of rules) {
    ruleStore.set(rule.id, { ...rule })
  }
}

// 모듈 로드 시 기본 시드를 자동 적용한다 — dev(pnpm dev)/E2E 진입 시 빈 화면 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedAutomationRules(DEFAULT_AUTOMATION_RULES)
}
