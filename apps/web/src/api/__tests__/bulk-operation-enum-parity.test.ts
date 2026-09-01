// 일괄작업 계약 대조 판별식 — 백엔드 enum·payload .kt 를 직접 읽어 프론트 Zod 와 차집합 0 을 단언
import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  bulkOperationTypeSchema,
  bulkOperationStatusSchema,
  bulkOperationItemStatusSchema,
  failureReasonCodeSchema,
  bulkOperationPayloadSchema,
  bulkOperationResponseSchema,
} from '../bulk-operations'
import { failureReasonLabels } from '@/i18n/bulk-operation-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 백엔드에서 값을 뽑는다 — 손으로 적지 않는다
//
// ★ 이 판별식이 있는 이유. FR-WF-07 D6 착수 실측에서 **한 파일에 어긋남이 셋** 있었다.
//   ① `bulkOperationTypeSchema` 가 2종인데 백엔드 `BulkOperationType` 은 3종
//      (`STATUS_MIGRATION` 없음)
//   ② `failureReasonCodeSchema` 가 7종인데 백엔드는 9종
//      (`STATE_NOT_IN_MAPPING`·`PROJECT_ARCHIVED` 없음)
//   ③ `bulkOperationPayloadSchema` union 에 `StatusMigration{mappings, projectKeys}` 가 없음
//
//   셋 다 상태 이관 진행률 폴링(`GET /api/v1/bulk-operations/{id}`)의 첫 응답에서 throw 한다.
//   ★특히 ③ 은 ①②를 고쳐도 남는다 — enum 만 맞추고 끝내면 여전히 죽는다.
//
//   두 목록이 서로를 검사하지 않아 생긴 일이라(MEMORY `two-lists-never-check-each-other`)
//   값만 맞추면 다음 enum 추가에서 똑같이 벌어진다. 형제 판별식
//   `hooks/__tests__/workflow-admin-error.test.ts` 가 백엔드 핸들러 .kt 를 읽는 그 방식을 따른다.
//
//   ★ enum 4종을 **전부** 건다. 지금 어긋난 둘만 걸면 나머지 둘은 계속 아무도 안 본다.
// ─────────────────────────────────────────────────────────────────────────────

/** vitest 는 `apps/web` 에서 돈다 — 거기서 두 단계 위가 저장소 루트다 */
const REPO_ROOT = resolve(process.cwd(), '../..')
const BULK_DOMAIN = `${REPO_ROOT}/backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/domain`

const TYPE_FILE = `${BULK_DOMAIN}/BulkOperationType.kt`
const STATUS_FILE = `${BULK_DOMAIN}/BulkOperationStatus.kt`
const REASON_FILE = `${BULK_DOMAIN}/FailureReasonCode.kt`
/** ★ `ItemStatus` 는 파일명이 다르다 — `BulkOperationItem.kt` 안에 산다 */
const ITEM_FILE = `${BULK_DOMAIN}/BulkOperationItem.kt`
const PAYLOAD_FILE = `${BULK_DOMAIN}/BulkOperationPayload.kt`

const ALL_FILES = [TYPE_FILE, STATUS_FILE, REASON_FILE, ITEM_FILE, PAYLOAD_FILE]

/**
 * `enum class <name> { A, B, C }` 본문의 상수 이름을 뽑는다.
 *
 * **중괄호 안만 본다.** 파일 상단 KDoc 이 `- [BULK_EDIT]:` 처럼 값을 예시로 적으므로, 파일
 * 전체를 훑으면 주석의 토큰을 값으로 오인한다. 그러면 차집합이 있지도 않은 값을 요구하며
 * red 로 굳고, 사람이 그것을 「판별식이 원래 시끄럽다」로 배운다.
 *
 * 본문 안의 주석 줄도 걷어낸다 — enum 상수마다 KDoc 이 붙는 형태가 이 저장소의 관례다.
 */
function extractEnumConstants(file: string, enumName: string): Set<string> {
  const body = readFileSync(file, 'utf8')
  const declared = body.indexOf(`enum class ${enumName}`)
  if (declared < 0) {
    return new Set()
  }
  const opened = body.indexOf('{', declared)
  const closed = body.indexOf('}', opened)
  const inner = body.slice(opened + 1, closed)
  const constants = new Set<string>()
  for (const rawLine of inner.split('\n')) {
    const line = rawLine.trim()
    if (line.startsWith('//') || line.startsWith('*') || line.startsWith('/*')) {
      continue
    }
    const matched = /^([A-Z][A-Z0-9_]*)\s*(?:,|;|$)/.exec(line)
    if (matched !== null) {
      constants.add(matched[1]!)
    }
  }
  return constants
}

/** `sealed class BulkOperationPayload` 의 하위 `data class` 이름을 뽑는다. */
function extractPayloadVariants(): Set<string> {
  const body = readFileSync(PAYLOAD_FILE, 'utf8')
  const variants = new Set<string>()
  for (const m of body.matchAll(/^\s{4}data class ([A-Z][A-Za-z0-9]*)\(/gm)) {
    variants.add(m[1]!)
  }
  return variants
}

/** enum 이름 → [백엔드 파일, 프론트 Zod options] 대조표. 새 enum 이 생기면 여기 한 줄만 는다. */
const ENUM_PAIRS = [
  ['BulkOperationType', TYPE_FILE, bulkOperationTypeSchema.options],
  ['BulkOperationStatus', STATUS_FILE, bulkOperationStatusSchema.options],
  ['FailureReasonCode', REASON_FILE, failureReasonCodeSchema.options],
  ['ItemStatus', ITEM_FILE, bulkOperationItemStatusSchema.options],
] as const

describe('백엔드 enum 원본 대조 — 차집합 0', () => {
  it('비-공허: 읽으려는 백엔드 파일이 전부 실재한다', () => {
    // 경로가 틀리면 아래 대조가 빈 집합끼리 비교해 조용히 통과한다.
    expect(ALL_FILES.filter((f) => !existsSync(f))).toEqual([])
  })

  it('비-공허: 네 enum 에서 뽑은 값의 합이 19건 이상이다', () => {
    const total = ENUM_PAIRS.reduce((sum, [name, file]) => sum + extractEnumConstants(file, name).size, 0)
    expect(total).toBeGreaterThanOrEqual(19)
  })

  it('비-공허(카나리): 파서가 KDoc 이 아니라 본문을 읽는다', () => {
    // 정규식이 통째로 안 물려도 위 개수 단언은 주석에서 긁어 채워질 수 있다.
    // 「지금 빠져 있는 바로 그 값들이 실제로 잡힌다」를 따로 못박는다.
    expect(extractEnumConstants(TYPE_FILE, 'BulkOperationType')).toContain('STATUS_MIGRATION')
    expect(extractEnumConstants(REASON_FILE, 'FailureReasonCode')).toContain('PROJECT_ARCHIVED')
    expect(extractEnumConstants(REASON_FILE, 'FailureReasonCode')).toContain('STATE_NOT_IN_MAPPING')
    expect(extractEnumConstants(ITEM_FILE, 'ItemStatus')).toContain('SUCCEEDED')
  })

  it.each(ENUM_PAIRS)('%s — 백엔드 값 전부가 Zod enum 에 있다', (name, file, options) => {
    const front = new Set<string>(options)
    const missing = [...extractEnumConstants(file, name)].filter((v) => !front.has(v))
    expect(missing).toEqual([])
  })

  it.each(ENUM_PAIRS)('%s — Zod 에만 있고 백엔드엔 없는 죽은 값이 없다', (name, file, options) => {
    const backend = extractEnumConstants(file, name)
    expect(options.filter((v) => !backend.has(v))).toEqual([])
  })
})

describe('payload union 대조', () => {
  it('비-공허: sealed 하위 variant 를 3건 이상 뽑는다', () => {
    expect(extractPayloadVariants().size).toBeGreaterThanOrEqual(3)
  })

  it('비-공허(카나리): StatusMigration variant 가 실제로 잡힌다', () => {
    expect(extractPayloadVariants()).toContain('StatusMigration')
  })

  it('백엔드 sealed 하위 개수와 Zod union 갈래 수가 같다', () => {
    // ★ enum 을 고쳐도 이 단언은 따로 red 다. 폴링이 죽는 세 번째 원인이 여기다.
    expect(bulkOperationPayloadSchema.options.length).toBe(extractPayloadVariants().size)
  })

  it('STATUS_MIGRATION 응답 형태를 실제로 파싱한다', () => {
    // 스키마를 늘려도 union 순서가 틀리면 여기서 잡힌다.
    // 형태의 출처는 `BulkOperationPayload.StatusMigration(mappings: Map, projectKeys: Set)`.
    const wire = {
      id: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
      operationType: 'STATUS_MIGRATION',
      status: 'PENDING',
      payload: { mappings: { done: 'in_progress' }, projectKeys: ['PROJ'] },
      // ★ 큐잉 시점에 0 이다 — 워커가 「옮기면서 센다」(materializeStatusMigrationItems KDoc).
      totalCount: 0,
      processedCount: 0,
      succeededCount: 0,
      failedCount: 0,
      items: [],
    }
    expect(() => bulkOperationResponseSchema.parse(wire)).not.toThrow()
  })
})

describe('실패 사유 라벨', () => {
  it('실패사유 전부에 한국어 라벨이 있다', () => {
    // `Record<FailureReasonCode, string>` 이라 타입이 이미 강제하지만, 타입만으로는
    // `as` 나 인덱스 시그니처 완화가 들어오면 뚫린다. 값으로도 한 번 잰다.
    const missing = failureReasonCodeSchema.options.filter((c) => failureReasonLabels[c] === undefined)
    expect(missing).toEqual([])
  })

  it('라벨이 서로 다르다 — 복사 실수로 한 문구가 두 사유를 덮지 않는다', () => {
    const values = Object.values(failureReasonLabels)
    expect(new Set(values).size).toBe(values.length)
  })
})
