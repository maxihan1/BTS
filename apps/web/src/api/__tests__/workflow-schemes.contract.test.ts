// 백엔드가 조립에서 생성한 계약 스냅샷을 프론트 Zod 로 파싱해 계약 drift 를 양방향으로 차단하는 테스트
import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { z } from 'zod'
import {
  schemeListItemSchema,
  schemeDetailSchema,
  schemeMutationResultSchema,
  assignedSchemeSchema,
  assignmentRecordSchema,
  mappingCreatedSchema,
} from '../workflow-schemes.types'

/**
 * repo 루트를 CWD 에서 위로 올라가며 찾는다.
 *
 * 리뷰 F5 — `resolve(__dirname, '../../../../../docs/...')` 같은 5~6단계 상대경로는 파일이 한 칸만
 * 옮겨져도 조용히 빗나간다. 백엔드 스냅샷 생성기(`WorkflowSchemeContractSnapshotTest.repoRoot`)와
 * **같은 판별식**(`docs/` 디렉토리 + `CLAUDE.md` 동시 보유)을 써서 양쪽이 같은 파일을 가리키게 한다.
 */
function repoRoot(): string {
  let dir = process.cwd()
  for (;;) {
    if (existsSync(resolve(dir, 'CLAUDE.md')) && statSync(resolve(dir, 'docs'), { throwIfNoEntry: false })?.isDirectory()) {
      return dir
    }
    const parent = dirname(dir)
    if (parent === dir) throw new Error(`repo 루트를 찾지 못했다(docs/ + CLAUDE.md 기준). 시작: ${process.cwd()}`)
    dir = parent
  }
}

const SNAPSHOT = resolve(repoRoot(), 'docs/contracts/workflow-schemes.snapshot.json')

/**
 * endpoint 라벨 → 그 응답을 받는 Zod 스키마.
 *
 * `.strict()` 를 쓰는 이유. 백엔드가 필드를 **추가**해도 걸리게 하려면 strict 가 필요하다.
 * 느슨한 object 는 삭제·오타만 잡고 추가를 놓쳐, 프론트가 모르는 필드가 계약에 슬며시 들어온다
 * (spec §D-Q5). 라벨 문자열은 백엔드 `collectRawResponses()` 의 맵 키와 **정확히 같아야** 한다.
 */
const CASES: Array<[string, z.ZodTypeAny]> = [
  ['GET /api/v1/workflow-schemes', z.object({ data: z.array(schemeListItemSchema) }).strict()],
  ['GET /api/v1/workflow-schemes/{key}', z.object({ data: schemeDetailSchema }).strict()],
  ['POST /api/v1/workflow-schemes', z.object({ data: schemeMutationResultSchema }).strict()],
  ['PUT /api/v1/workflow-schemes/{key}', z.object({ data: schemeMutationResultSchema }).strict()],
  ['GET /api/v1/projects/{k}/workflow-scheme', z.object({ data: assignedSchemeSchema }).strict()],
  ['PUT /api/v1/projects/{k}/workflow-scheme', z.object({ data: assignmentRecordSchema }).strict()],
  ['POST /api/v1/workflow-schemes/{key}/mappings', z.object({ data: mappingCreatedSchema }).strict()],
  [
    'GET /api/v1/projects/{k}/assignable-workflow-schemes',
    z.object({ data: z.array(assignedSchemeSchema) }).strict(),
  ],
]

describe('워크플로우 스킴 계약 스냅샷', () => {
  it('스냅샷 파일이 존재한다', () => {
    // skip 금지 — 파일이 없으면 실패다(파일 부재로 아래 케이스가 전부 공허하게 통과하는 것을 차단).
    expect(existsSync(SNAPSHOT)).toBe(true)
  })

  const snapshot = existsSync(SNAPSHOT)
    ? (JSON.parse(readFileSync(SNAPSHOT, 'utf-8')) as Record<string, unknown>)
    : {}

  it.each(CASES)('%s 응답이 Zod 와 정합한다', (endpoint, schema) => {
    // 항목 누락도 실패 — 백엔드가 endpoint 를 스냅샷에서 빼면 그 계약이 조용히 사라진다.
    expect(Object.keys(snapshot)).toContain(endpoint)
    expect(() => schema.parse(snapshot[endpoint])).not.toThrow()
  })
})
