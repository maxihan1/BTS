// FR-AT-07 PR-D Git 웹훅 등록 REST 3매핑 MSW fixture — store 소유 · 시나리오 플래그 상수
import type { GitProvider, GitWebhookSummary } from '@/api/automation-git-webhooks.types'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (automation-rule-fixtures.ts 동형 복제) — 신규 의존성 금지,
// crypto.randomUUID 표준 API 사용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * Zod z.string().uuid() 검증을 통과하는 형식을 보장한다(zod-v4-uuid-fixture-strictness).
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
// 시나리오 플래그 localStorage 키 (account-link-fixtures.ts / automation-rule-fixtures.ts
// SCENARIO_KEY 관례) — E2E에서 addInitScript로 localStorage에 세팅해 분기를 유발한다.
// ─────────────────────────────────────────────────────────────────────────────

export const SCENARIO_KEY = {
  /** 목록 GET을 store 내용과 무관하게 403 AUTOMATION_ACCESS_DENIED로 강제한다(FR12 error 분기 재현용) */
  FORBIDDEN: 'msw:automation-git-webhook:forbidden',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 시드 프로젝트/작성자 상수 — 다른 automation fixture와 정합
// (automation-rule-fixtures.ts DEFAULT_AUTOMATION_PROJECT_KEY/ACTOR_ID와 동일 값)
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 시드 프로젝트 키 — automation-rule-fixtures.ts와 동일 값 */
export const DEFAULT_GIT_WEBHOOK_PROJECT_KEY = 'ATLAS'

/** 시드 웹훅의 생성자 UUID — auth-fixtures.ts whoami 로그인 사용자(alice)와 동기화 */
export const DEFAULT_GIT_WEBHOOK_CREATOR_ID = '00000000-0000-4000-8000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장 타입 — GitWebhookSummary(응답 계약)에 store 격리용 projectKey를 더한다.
// token·secret은 어느 필드에도 없다 — backend GitWebhookSummaryResponse가 애초에 그런
// 필드를 만들지 않아(GitWebhookDtos.kt) 타입 상 새어 나갈 수 없다.
// ─────────────────────────────────────────────────────────────────────────────

/** store에 보관하는 웹훅 레코드 — GitWebhookSummary + 소속 프로젝트 키 */
export interface StoredGitWebhook extends GitWebhookSummary {
  projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — git-webhook-handlers.ts가 직접 참조(단일 진실 출처)
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 인메모리 웹훅 store — id → StoredGitWebhook */
export let gitWebhookStore: Map<string, StoredGitWebhook> = new Map()

/**
 * store를 빈 상태로 초기화한다.
 * 테스트 afterEach 또는 E2E 시드 전 호출해 이전 테스트 잔여 데이터를 제거한다
 * (msw-derived-behavior-shared-store-e2e). `clear()`가 아니라 재할당 방식이다 —
 * 참조를 export한 다른 모듈이 이전 Map을 계속 들고 있어도 최신 store만 신뢰하도록
 * `gitWebhookStore` 변수 자체를 새 Map으로 바꾼다(automation-rule-fixtures.ts 동형).
 */
export function resetGitWebhookStore(): void {
  gitWebhookStore = new Map()
}

/**
 * 지정한 웹훅 배열로 store를 시드한다. 기존 항목은 id 기준으로 덮어쓴다.
 * E2E addInitScript 또는 테스트 beforeEach에서 초기 상태를 구성할 때 사용한다.
 *
 * @param webhooks 시드할 웹훅 목록
 */
export function seedGitWebhooks(webhooks: StoredGitWebhook[]): void {
  for (const webhook of webhooks) {
    gitWebhookStore.set(webhook.id, { ...webhook })
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 웹훅 — dev(pnpm dev)/E2E 진입 시 빈 화면 방지용
// ─────────────────────────────────────────────────────────────────────────────

/** 시드 웹훅 UUID (RFC4122 v4 — zod-v4-uuid-fixture-strictness) */
export const SEED_GIT_WEBHOOK_IDS = {
  github: 'b1000000-0000-4000-8000-000000000001',
} as const

/** provider 화이트리스트(2종)를 시드에도 반영 — gitProviderSchema와 1:1 대응 */
const SEED_GIT_WEBHOOK_PROVIDER: GitProvider = 'GITHUB'

/** 기본 자동화 Git 웹훅 시드 — ATLAS 프로젝트, GITHUB provider 1건(token·secret 미포함). */
export const DEFAULT_GIT_WEBHOOKS: StoredGitWebhook[] = [
  {
    id: SEED_GIT_WEBHOOK_IDS.github,
    provider: SEED_GIT_WEBHOOK_PROVIDER,
    createdAt: '2026-07-01T09:00:00Z',
    createdBy: DEFAULT_GIT_WEBHOOK_CREATOR_ID,
    projectKey: DEFAULT_GIT_WEBHOOK_PROJECT_KEY,
  },
]

// 모듈 로드 시 기본 시드를 자동 적용한다 — dev(pnpm dev)/E2E 진입 시 빈 화면 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
}
