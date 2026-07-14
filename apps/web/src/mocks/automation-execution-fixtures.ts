// FR-AT-05 D6/D7 자동화 룰 실행 이력 MSW fixture — 시드 데이터·공유 stateful store·시나리오 플래그 상수
//
// ⚠️ 알려진 스키마 drift (automation-executions.types.ts, 이 task 허용 파일 아님 — 수정 금지, 후속 확인 필요).
// `ruleExecutionDetailSchema` 가 `ruleExecutionSummarySchema.extend()` 로 정의돼 `actionCount`/`successCount`
// 를 상속 요구하지만, 실제 backend `RuleExecutionDetailResponse`(RuleExecutionResponses.kt)는 이 두 필드를
// 전혀 내려주지 않는다(`AutomationExecutionControllerTest.kt` jsonPath — 목록(`$[0].actionCount`)만 검증하고
// 단건 trace(`$.`)는 검증하지 않음, `RuleExecutionDetailResponse` 데이터 클래스에 필드 자체가 없음).
// `RuleExecutionDetail` 타입을 그대로 쓰려면(TS 컴파일 통과) 이 두 필드가 필요해 fixture 객체에는 outcomes와
// 일관된 값으로 채워 넣지만, 실제 wire 응답(automation-execution-handlers.ts `toWireDetail`)에서는 제거해
// backend 계약을 우선한다([[frontend-zod-backend-dto-contract-gap]] — mock 을 스키마가 아니라 실제 DTO 에
// 맞춘다). `automation-executions.ts` 의 `fetchRuleExecution`/`replayRuleExecution` 은 이 스키마로 실제
// backend 응답을 직접 parse 하므로, 이 drift 가 고쳐지지 않으면 실제 backend 대상 호출도 동일하게 깨진다 —
// Task 1 스키마(`ruleExecutionDetailSchema`) 후속 수정 필요.
import type { RuleExecutionDetail } from '@/api/automation-executions.types'
import { DEFAULT_AUTOMATION_PROJECT_KEY, SEED_AUTOMATION_RULE_IDS } from './automation-rule-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 플래그 localStorage 키 (automation-rule-fixtures.ts SCENARIO_KEY 관례 동형)
// E2E에서 addInitScript로 localStorage에 세팅해 분기를 유발한다.
// ─────────────────────────────────────────────────────────────────────────────

export const SCENARIO_KEY = {
  /** 룰별 실행 이력 목록 GET을 store 내용과 무관하게 빈 배열로 강제한다 — 빈 상태 CTA E2E용 */
  EMPTY_EXECUTIONS: 'msw:automation-execution:empty-executions',
  /** replay 요청을 항상 409(재실행 대상 룰 사용 불가)로 강제한다 — 재실행 실패 UI E2E용 */
  RULE_UNAVAILABLE: 'msw:automation-execution:rule-unavailable',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 시드 실행 이력 UUID (RFC4122 v4 — zod-v4-uuid-fixture-strictness)
// ─────────────────────────────────────────────────────────────────────────────

/** 시드 실행 이력 UUID 모음 — 테스트/E2E가 특정 레코드를 직접 참조할 때 사용한다 */
export const SEED_EXECUTION_IDS = {
  scheduledSuccess: 'b1000000-0000-4000-8000-000000000001',
  scheduledPartial: 'b1000000-0000-4000-8000-000000000002',
  scheduledFailed: 'b1000000-0000-4000-8000-000000000003',
  scheduledSkipped: 'b1000000-0000-4000-8000-000000000004',
  issueCreatedSuccess: 'b1000000-0000-4000-8000-000000000005',
} as const

/**
 * 실행 이력이 시드된 룰 id 모음 — E2E/테스트가 목록 조회 URL을 구성할 때 참조한다
 * (automation-rule-fixtures.ts `SEED_AUTOMATION_RULE_IDS` 중 실행 이력이 있는 것만 재노출).
 */
export const SEEDED_EXECUTION_RULE_IDS = {
  scheduled: SEED_AUTOMATION_RULE_IDS.scheduled,
  issueCreated: SEED_AUTOMATION_RULE_IDS.issueCreated,
} as const

/**
 * 기본 실행 이력 시드 — "매일 오전 스캔"(SCHEDULED) 룰에 4건(SUCCESS/PARTIAL/FAILED/SKIPPED),
 * "이슈 생성 알림"(ISSUE_CREATED) 룰에 1건(SUCCESS). 상태 4종·issueKey(있음/null)·outcomes 성공/실패
 * 혼재를 모두 커버해 목록/상세/replay E2E가 결정적으로 여러 케이스를 재현할 수 있게 한다.
 *
 * `rule_executions` 는 룰 테이블과 하드 FK 로 묶이지 않는다(감사 독립성, NFR-4 — backend
 * `RuleExecutionRepository` 클래스 KDoc "룰 테이블 조인 없음" 참고)는 전제와 무관하게, 이 mock 은
 * E2E 화면 전환 시 데이터가 이어지도록 실제 시드 룰 id를 그대로 참조한다.
 */
export const DEFAULT_RULE_EXECUTIONS: RuleExecutionDetail[] = [
  {
    id: SEED_EXECUTION_IDS.scheduledSuccess,
    ruleId: SEED_AUTOMATION_RULE_IDS.scheduled,
    projectKey: DEFAULT_AUTOMATION_PROJECT_KEY,
    triggerType: 'SCHEDULED',
    triggerEvent: { cron: '0 0 9 * * *', firedAt: '2026-07-10T09:00:00Z' },
    issueKey: 'ATLAS-101',
    status: 'SUCCESS',
    // actionCount/successCount — outcomes와 일관된 값(TS 컴파일용, 파일 상단 KDoc "알려진 스키마 drift" 참고.
    // 실제 wire 응답에는 handlers.ts `toWireDetail`이 이 두 필드를 제거한다).
    actionCount: 2,
    successCount: 2,
    outcomes: [
      { position: 0, actionType: 'SET_FIELD', success: true, error: null },
      { position: 1, actionType: 'ASSIGN', success: true, error: null },
    ],
    replayedFrom: null,
    startedAt: '2026-07-10T09:00:00Z',
    finishedAt: '2026-07-10T09:00:02Z',
  },
  {
    id: SEED_EXECUTION_IDS.scheduledPartial,
    ruleId: SEED_AUTOMATION_RULE_IDS.scheduled,
    projectKey: DEFAULT_AUTOMATION_PROJECT_KEY,
    triggerType: 'SCHEDULED',
    triggerEvent: { cron: '0 0 9 * * *', firedAt: '2026-07-11T09:00:00Z' },
    issueKey: 'ATLAS-102',
    status: 'PARTIAL',
    actionCount: 2,
    successCount: 1,
    outcomes: [
      { position: 0, actionType: 'SET_FIELD', success: true, error: null },
      { position: 1, actionType: 'ASSIGN', success: false, error: 'PERMISSION_DENIED' },
    ],
    replayedFrom: null,
    startedAt: '2026-07-11T09:00:00Z',
    finishedAt: '2026-07-11T09:00:02Z',
  },
  {
    id: SEED_EXECUTION_IDS.scheduledFailed,
    ruleId: SEED_AUTOMATION_RULE_IDS.scheduled,
    projectKey: DEFAULT_AUTOMATION_PROJECT_KEY,
    triggerType: 'SCHEDULED',
    triggerEvent: { cron: '0 0 9 * * *', firedAt: '2026-07-12T09:00:00Z' },
    issueKey: 'ATLAS-103',
    status: 'FAILED',
    actionCount: 2,
    successCount: 0,
    outcomes: [
      { position: 0, actionType: 'SET_FIELD', success: false, error: 'FAILED' },
      { position: 1, actionType: 'ASSIGN', success: false, error: 'FAILED' },
    ],
    replayedFrom: null,
    startedAt: '2026-07-12T09:00:00Z',
    finishedAt: '2026-07-12T09:00:02Z',
  },
  {
    id: SEED_EXECUTION_IDS.scheduledSkipped,
    ruleId: SEED_AUTOMATION_RULE_IDS.scheduled,
    projectKey: DEFAULT_AUTOMATION_PROJECT_KEY,
    triggerType: 'SCHEDULED',
    triggerEvent: { cron: '0 0 9 * * *', firedAt: '2026-07-13T09:00:00Z' },
    issueKey: null,
    status: 'SKIPPED',
    actionCount: 0,
    successCount: 0,
    outcomes: [],
    replayedFrom: null,
    startedAt: '2026-07-13T09:00:00Z',
    finishedAt: '2026-07-13T09:00:00Z',
  },
  {
    id: SEED_EXECUTION_IDS.issueCreatedSuccess,
    ruleId: SEED_AUTOMATION_RULE_IDS.issueCreated,
    projectKey: DEFAULT_AUTOMATION_PROJECT_KEY,
    triggerType: 'ISSUE_CREATED',
    triggerEvent: { issueKey: 'ATLAS-201' },
    issueKey: 'ATLAS-201',
    status: 'SUCCESS',
    actionCount: 1,
    successCount: 1,
    outcomes: [{ position: 0, actionType: 'ADD_COMMENT', success: true, error: null }],
    replayedFrom: null,
    startedAt: '2026-07-09T10:00:00Z',
    finishedAt: '2026-07-09T10:00:01Z',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — automation-execution-handlers.ts가 직접 참조(단일 진실 출처)
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 인메모리 실행 이력 store — id → RuleExecutionDetail */
export let executionStore: Map<string, RuleExecutionDetail> = new Map()

/**
 * store를 빈 상태로 초기화한다.
 * 테스트 afterEach 또는 E2E 시드 전 호출해 이전 테스트 잔여 데이터를 제거한다
 * (msw-derived-behavior-shared-store-e2e).
 */
export function resetAutomationExecutionStore(): void {
  executionStore = new Map()
}

/**
 * 지정한 실행 이력 배열로 store를 시드한다. 기존 항목은 id 기준으로 덮어쓴다.
 * E2E addInitScript 또는 테스트 beforeEach/각 it 에서 초기 상태를 구성할 때 사용한다.
 *
 * @param executions 시드할 실행 이력 목록
 */
export function seedAutomationExecutions(executions: RuleExecutionDetail[]): void {
  for (const execution of executions) {
    executionStore.set(execution.id, { ...execution })
  }
}

// 모듈 로드 시 기본 시드를 자동 적용한다 — dev(pnpm dev)/E2E 진입 시 빈 화면 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
}
