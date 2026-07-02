// 대시보드 MSW 픽스처 — stateful store + 기본 시드 데이터 (FR-DB-01 D6)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: PATCH/DELETE 후 GET에 즉시 반영되도록 dashboardStore 변이
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - e2e-msw-scenario-toggle-localstorage-flag: 409 토글은 localStorage 플래그로 분기
//   - zod-v4-uuid-fixture-strictness: UUID는 RFC4122 v4 형식 (Zod v4 z.string().uuid() 통과)

// ─────────────────────────────────────────────────────────────────────────────
// Alice/Bob userId 상수 — user-fixtures.ts와 동기화
//
// board-fixtures.ts 패턴과 동일하게 인라인 상수로 정의한다.
// user-fixtures.ts를 직접 import하지 않는 이유:
//   E2E Node.js 런타임에서 import.meta.env.MODE 접근 오류가 발생할 수 있다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 사용자 ID — 소유 대시보드 ownerId 및 E2E 권한 게이팅 정합 기준.
 *
 * 백엔드 whoami API가 반환하는 alice userId와 정확히 일치해야 한다.
 * (memory: fr-db-01-dashboard-backend-done — alice userId)
 */
export const ALICE_OWNER_ID = '00000000-0000-4000-8000-000000000001'

/**
 * bob 사용자 ID — 비소유 대시보드 ownerId (S7 비소유자 읽기 전용 검증용).
 * alice가 이 대시보드를 수정·삭제하려 하면 403이 나와야 한다.
 */
export const BOB_OTHER_ID = '00000000-0000-4000-8000-000000000002'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글 — localStorage 플래그 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키.
 * 이 키가 'true'이면 PATCH 핸들러가 정상 200 대신 409 충돌 응답을 반환한다.
 *
 * board의 LS_KEY_BOARD_CONFLICT와 반드시 다른 키를 사용한다
 * (memory: e2e-msw-scenario-toggle-localstorage-flag — 고유 키 분리 필수).
 *
 * Playwright addInitScript로 goto 전에 설정하면 첫 PATCH fetch 시점부터 적용된다.
 */
export const LS_KEY_DASHBOARD_CONFLICT = '__bts_e2e_dashboard_conflict'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 store 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW store 내부에서 사용하는 대시보드 타입.
 * 백엔드 DashboardResponse DTO와 1:1 대응하되, deletedAt을 추가해 소프트 삭제를 표현한다.
 */
export interface StoredDashboard {
  /** 대시보드 식별자 (UUID) */
  id: string
  /** 소유자 사용자 ID */
  ownerId: string
  /** 대시보드 이름 */
  name: string
  /** 설명 (null 가능) */
  description: string | null
  /** 공개 범위 — PRIVATE / TEAM / ORG */
  visibility: string
  /** 위젯 배치 JSONB 문자열 */
  layout: string
  /** TEAM 공유 대상 사용자 ID 목록 */
  sharedUserIds: string[]
  /** 생성 시각 (ISO 8601) */
  createdAt: string
  /** 최종 수정 시각 (ISO 8601) */
  updatedAt: string
  /** OCC 낙관적 잠금 버전 */
  version: number
  /** 소프트 삭제 표시 — null이면 미삭제 */
  deletedAt: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID 생성 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * Zod v4 z.string().uuid() 검증을 통과하는 형식을 보장한다
 * (memory: zod-v4-uuid-fixture-strictness).
 */
export function generateUUID(): string {
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
// 공유 stateful store
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 store — dashboardId → StoredDashboard.
 * PATCH/DELETE 핸들러가 변이하고, GET 핸들러가 읽는다.
 * 소프트 삭제된 항목(deletedAt !== null)은 GET/LIST에서 제외된다
 * (memory: msw-mutation-stateful-refetch).
 */
export let dashboardStore: Map<string, StoredDashboard> = new Map()

// ─────────────────────────────────────────────────────────────────────────────
// store 관리 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * dashboardStore를 초기 상태(빈 Map)로 리셋한다.
 * 각 테스트 beforeEach에서 호출해 테스트 간 격리를 보장한다.
 */
export function resetDashboardStore(): void {
  dashboardStore = new Map()
}

/**
 * StoredDashboard를 store에 시드한다.
 * 동일 id가 이미 있으면 덮어쓴다.
 *
 * @param dashboard 시드할 대시보드 데이터
 */
export function seedDashboard(dashboard: StoredDashboard): void {
  dashboardStore.set(dashboard.id, dashboard)
}

/**
 * 새 대시보드를 store에 추가하고 StoredDashboard를 반환한다.
 * POST /api/v1/dashboards 핸들러가 내부적으로 호출한다.
 *
 * @param ownerId 소유자 사용자 ID
 * @param name 대시보드 이름
 * @param description 설명 (선택)
 * @param visibility 공개 범위
 * @param layout 위젯 배치 JSON
 * @param sharedUserIds TEAM 공유 대상 사용자 ID 목록
 * @returns 생성된 StoredDashboard
 */
export function createDashboardInStore(
  ownerId: string,
  name: string,
  description: string | null,
  visibility: string,
  layout: string,
  sharedUserIds: string[],
): StoredDashboard {
  const id = generateUUID()
  const now = new Date().toISOString()

  const stored: StoredDashboard = {
    id,
    ownerId,
    name,
    description,
    visibility,
    layout,
    sharedUserIds,
    createdAt: now,
    updatedAt: now,
    version: 0,
    deletedAt: null,
  }

  dashboardStore.set(id, stored)
  return stored
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 토큰 stateful store (FR-DB-03 D6/D7 Task 2)
//
// 백엔드 계약 grep 대조 (DashboardShareController/PublicDashboardController/
// DashboardShareDtos.kt/ShareTokenMinter.kt).
//   - id·token·createdAt·expiresAt만 보관. tokenHash가 아니라 원문 token을 그대로 들고
//     있는 이유는 MSW가 SHA-256 해싱을 재현할 필요 없이 공개 GET에서 원문 대조만 하면
//     되기 때문(순수 목업, 실제 보안 경계 없음).
//   - dashboardStore와 별개 Map. dashboardId로 스코프해 조회한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW store 내부에서 사용하는 공유 토큰 타입.
 * 백엔드 DashboardShareToken 엔티티와 대응하되, tokenHash 대신 원문 token을 보관한다
 * (MSW는 해싱 없이 원문 대조로 조회한다).
 */
export interface StoredShareToken {
  /** 공유 토큰 식별자 (UUID) */
  id: string
  /** 대상 대시보드 식별자 */
  dashboardId: string
  /** 원문 공유 토큰 — 목록 응답(GET .../shares)에는 절대 포함하지 않는다(EC-9) */
  token: string
  /** 발급 시각 (ISO 8601) */
  createdAt: string
  /** 만료 시각 (ISO 8601). null이면 무기한 */
  expiresAt: string | null
}

/**
 * 공유 토큰 store — shareId → StoredShareToken.
 * POST(발급)/DELETE(취소) 핸들러가 변이하고, GET(목록)/공개 GET 핸들러가 읽는다.
 */
export let shareTokenStore: Map<string, StoredShareToken> = new Map()

/**
 * shareTokenStore를 초기 상태(빈 Map)로 리셋한다.
 * 공유 토큰 관련 테스트의 beforeEach에서 호출해 테스트 간 격리를 보장한다.
 */
export function resetShareTokenStore(): void {
  shareTokenStore = new Map()
}

/**
 * StoredShareToken을 store에 시드한다. 동일 id가 이미 있으면 덮어쓴다.
 *
 * @param entry 시드할 공유 토큰 데이터
 */
export function seedShareToken(entry: StoredShareToken): void {
  shareTokenStore.set(entry.id, entry)
}

/**
 * 실제 발급기(ShareTokenMinter)와 유사한 형태의 불투명 원문 토큰 문자열을 생성한다.
 *
 * 실제 백엔드는 32바이트 SecureRandom 값을 base64url(패딩 없음)로 인코딩하지만,
 * MSW 목업은 정확한 엔트로피·인코딩 재현이 불필요하므로 UUID 두 개를 이어붙인
 * 43자 opaque 문자열로 근사한다(형식 재현이 목적이 아니라 "해석 불가능한 문자열"이라는
 * 속성만 필요).
 */
export function generateShareToken(): string {
  return `${generateUUID().replace(/-/g, '')}${generateUUID().replace(/-/g, '')}`.slice(0, 43)
}

/**
 * 새 공유 토큰을 store에 발급하고 StoredShareToken을 반환한다.
 * POST /api/v1/dashboards/{id}/shares 핸들러가 내부적으로 호출한다.
 *
 * @param dashboardId 대상 대시보드 식별자
 * @param expiresAt 만료 시각 (선택, null이면 무기한)
 * @returns 발급된 StoredShareToken (원문 token 포함)
 */
export function issueShareTokenInStore(dashboardId: string, expiresAt: string | null): StoredShareToken {
  const entry: StoredShareToken = {
    id: generateUUID(),
    dashboardId,
    token: generateShareToken(),
    createdAt: new Date().toISOString(),
    expiresAt,
  }
  shareTokenStore.set(entry.id, entry)
  return entry
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 소유 기본 대시보드 픽스처.
 *
 * ownerId = ALICE_OWNER_ID (00000000-0000-4000-8000-000000000001) — E2E 권한 게이팅 정합 필수.
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 */
export const DEFAULT_DASHBOARD: StoredDashboard = {
  id: 'a0000000-0000-4000-8000-000000000001',
  ownerId: ALICE_OWNER_ID,
  name: '내 첫 대시보드',
  description: '기본 대시보드입니다',
  visibility: 'PRIVATE',
  layout: '[]',
  sharedUserIds: [],
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  version: 0,
  deletedAt: null,
}

/**
 * bob 소유 비소유 대시보드 픽스처 — S7 비소유자 읽기 전용 검증용.
 *
 * alice가 이 대시보드에 PATCH/DELETE 시도하면 403 NOTIF_DASHBOARD_FORBIDDEN이 나와야 한다.
 * visibility = 'ORG'으로 alice도 GET으로 조회할 수 있다.
 */
export const OTHER_DASHBOARD: StoredDashboard = {
  id: 'b0000000-0000-4000-8000-000000000001',
  ownerId: BOB_OTHER_ID,
  name: 'Bob의 팀 대시보드',
  description: null,
  visibility: 'ORG',
  layout: '[]',
  sharedUserIds: [],
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  version: 0,
  deletedAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 데모 대시보드 픽스처 — 정적+데이터 가젯 혼합 layout (FR-DB-03 D6/D7 Task 2)
//
// DEFAULT_DASHBOARD(layout='[]')는 기존 E2E(dashboard.spec.ts/dashboard-gadgets.spec.ts)가
// "빈 그리드"를 전제하므로 건드리지 않는다. 익명 공유 뷰 E2E(정적 가젯 렌더 + 데이터 가젯
// 플레이스홀더 검증)를 위한 별도 픽스처를 새로 둔다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정적(STATIC)+데이터(ISSUE) 가젯 혼합 layout — 익명 공유 뷰 검증용.
 *
 * - text_widget/link_list(STATIC 카테고리): 익명 뷰에서 config 그대로 렌더된다.
 * - issue_count(ISSUE 카테고리, 데이터 가젯): 익명 뷰에서 config가 제거되고
 *   `requiresAuth: true` 플레이스홀더로 치환된다(AnonymousLayoutSanitizer 재현).
 */
export const MIXED_GADGET_LAYOUT: string = JSON.stringify([
  {
    i: 'tile-text-1',
    x: 0,
    y: 0,
    w: 6,
    h: 4,
    gadgetType: 'text_widget',
    config: { markdown: '이 대시보드는 팀 공지사항을 정리합니다.' },
  },
  {
    i: 'tile-link-1',
    x: 6,
    y: 0,
    w: 6,
    h: 4,
    gadgetType: 'link_list',
    config: { links: [{ label: 'BTS 문서', url: 'https://example.com/docs' }] },
  },
  {
    i: 'tile-count-1',
    x: 0,
    y: 4,
    w: 6,
    h: 4,
    gadgetType: 'issue_count',
    config: { aql: 'status = "Open"' },
  },
])

/**
 * 공유 링크 발급 대상 대시보드 픽스처 — alice 소유, 정적+데이터 가젯 혼합 layout 보유.
 * 공유 모달·익명 뷰 E2E(FR-DB-03 D6/D7)가 사용할 전용 대시보드.
 */
export const SHARE_DEMO_DASHBOARD: StoredDashboard = {
  id: 'c0000000-0000-4000-8000-000000000001',
  ownerId: ALICE_OWNER_ID,
  name: '공유 데모 대시보드',
  description: null,
  visibility: 'PRIVATE',
  layout: MIXED_GADGET_LAYOUT,
  sharedUserIds: [],
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  version: 0,
  deletedAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 로드 시 자동 시드 — dev/E2E 환경 전용
// ─────────────────────────────────────────────────────────────────────────────

// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
// board-fixtures.ts 동일 패턴 (memory: msw-derived-behavior-shared-store-e2e).
if (import.meta.env.MODE !== 'test') {
  seedDashboard(DEFAULT_DASHBOARD)
  seedDashboard(OTHER_DASHBOARD)
  seedDashboard(SHARE_DEMO_DASHBOARD)
}

