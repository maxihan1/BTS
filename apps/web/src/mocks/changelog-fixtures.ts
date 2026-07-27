// 이슈 변경 이력 MSW fixture 데이터 — lifecycle/priority/assignee/components/status/securityLevel 케이스 포함 (FR-HS-02)
import { ALICE_USER_ID, BOB_USER_ID } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// UUID 상수 (RFC4122 v4 — zod-v4-uuid-fixture-strictness)
// ─────────────────────────────────────────────────────────────────────────────

/** Alice actor UUID */
export const ACTOR_ALICE_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
/** Bob actor UUID */
export const ACTOR_BOB_ID = 'b2c3d4e5-f6a7-4b8c-9d0e-f1a2b3c4d5e6'
/** Alice 사용자 UUID (assignee 필드 값) — 정본은 auth-fixtures */
export const USER_ALICE_ID = ALICE_USER_ID
/** Bob 사용자 UUID (assignee 필드 값) — 정본은 auth-fixtures */
export const USER_BOB_ID = BOB_USER_ID
/** 컴포넌트 UUID */
export const COMPONENT_UUID = 'e5f6a7b8-c9d0-4e1f-af2a-3b4c5d6e7f8a'
/** securityLevel 변경 from ID */
export const SECURITY_LEVEL_CONFIDENTIAL_ID = 'f6a7b8c9-d0e1-4f2a-8f3b-4c5d6e7f8a9b'
/** securityLevel 변경 to ID */
export const SECURITY_LEVEL_PUBLIC_ID = 'a7b8c9d0-e1f2-4a3b-8a4c-5d6e7f8a9b0c'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

export interface ChangeItemFixture {
  field: string
  fromValue: string | null
  toValue: string | null
  fromLabel: string | null
  toLabel: string | null
}

export interface ChangeGroupFixture {
  actorId: string | null
  actorName: string | null
  createdAt: string
  items: ChangeItemFixture[]
}

// ─────────────────────────────────────────────────────────────────────────────
// fixture 빌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ChangeGroupFixture 빌더. actorId/actorName 없이도 그룹을 간결하게 생성한다.
 * actorId=null이면 시스템 이벤트로 간주하고 actorName도 null이 된다.
 */
export function buildChangeGroup(params: {
  actorId: string | null
  actorName: string | null
  createdAt: string
  items: ChangeItemFixture[]
}): ChangeGroupFixture {
  return {
    actorId: params.actorId,
    actorName: params.actorName,
    createdAt: params.createdAt,
    items: params.items,
  }
}

/**
 * ChangeItemFixture 빌더. 라벨 없는 필드(label=null)를 간결하게 생성한다.
 */
export function buildChangeItem(
  field: string,
  fromValue: string | null,
  toValue: string | null,
  fromLabel: string | null = null,
  toLabel: string | null = null,
): ChangeItemFixture {
  return { field, fromValue, toValue, fromLabel, toLabel }
}

// ─────────────────────────────────────────────────────────────────────────────
// ATLAS-1 이슈 fixture 그룹 목록 (최신순 DESC)
//
// 포함 케이스:
//   - lifecycle: 이슈 생성 마커 (fromValue=null, toValue="created")
//   - priority:  숫자 변경 (label 없음)
//   - assignee:  UUID 변경 (fromLabel/toLabel 박제 — #120)
//   - components: UUID JSON 배열 변경 (label 없음)
//   - status:    state key 변경 (label 없음)
//   - securityLevel: ID 변경 (fromLabel/toLabel 박제 — #120)
//   - actorName=null: 시스템 이벤트 그룹
// ─────────────────────────────────────────────────────────────────────────────

export const atlasOneChangelogFixture: ChangeGroupFixture[] = [
  // 그룹 6 — 가장 최신: securityLevel 변경 (박제 label)
  buildChangeGroup({
    actorId: ACTOR_ALICE_ID,
    actorName: 'Alice',
    createdAt: '2026-06-11T10:00:00Z',
    items: [
      buildChangeItem(
        'securityLevel',
        SECURITY_LEVEL_CONFIDENTIAL_ID,
        SECURITY_LEVEL_PUBLIC_ID,
        'Confidential',
        'Public',
      ),
    ],
  }),
  // 그룹 5 — status 변경 (label 없음)
  buildChangeGroup({
    actorId: ACTOR_ALICE_ID,
    actorName: 'Alice',
    createdAt: '2026-06-11T09:30:00Z',
    items: [
      buildChangeItem('status', 'open', 'in_progress'),
    ],
  }),
  // 그룹 4 — components 변경 (UUID JSON 배열)
  buildChangeGroup({
    actorId: ACTOR_BOB_ID,
    actorName: 'Bob',
    createdAt: '2026-06-11T09:00:00Z',
    items: [
      buildChangeItem('components', '[]', JSON.stringify([COMPONENT_UUID])),
    ],
  }),
  // 그룹 3 — assignee 변경 (박제 label)
  buildChangeGroup({
    actorId: ACTOR_BOB_ID,
    actorName: 'Bob',
    createdAt: '2026-06-11T08:30:00Z',
    items: [
      buildChangeItem('assignee', USER_ALICE_ID, USER_BOB_ID, 'Alice', 'Bob'),
    ],
  }),
  // 그룹 2 — priority 변경 (label 없음)
  buildChangeGroup({
    actorId: ACTOR_ALICE_ID,
    actorName: 'Alice',
    createdAt: '2026-06-11T08:00:00Z',
    items: [
      buildChangeItem('priority', '1', '3'),
    ],
  }),
  // 그룹 1 — 이슈 생성 (lifecycle, 시스템 이벤트 → actorName=null)
  buildChangeGroup({
    actorId: null,
    actorName: null,
    createdAt: '2026-06-11T07:00:00Z',
    items: [
      buildChangeItem('lifecycle', null, 'created'),
    ],
  }),
]

// ─────────────────────────────────────────────────────────────────────────────
// 권한 없는 key 목록 — 이 key로 요청하면 404 반환
// ─────────────────────────────────────────────────────────────────────────────

/** changelog 조회 시 404를 반환할 이슈 key 집합 (권한 없음 / 미존재 시뮬) */
export const DENIED_CHANGELOG_KEYS: ReadonlySet<string> = new Set(['DENIED-1', 'DENIED-2'])

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 검증용 fixture — priority 변경 21건(기본 size=20 초과 → page=0에서 last=false)
// ATLAS-2 에 사전 등록되어 E2E "더 보기" 시나리오를 런타임 시드 없이 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

/** "더 보기" 페이징 검증용 — priority 변경 21그룹(최신순) */
export const paginationChangelogFixture: ChangeGroupFixture[] = Array.from(
  { length: 21 },
  (_, i) =>
    buildChangeGroup({
      actorId: ACTOR_ALICE_ID,
      actorName: 'Alice',
      createdAt: `2026-06-11T${String(20 - i).padStart(2, '0')}:00:00Z`,
      items: [buildChangeItem('priority', '1', '3')],
    }),
)
