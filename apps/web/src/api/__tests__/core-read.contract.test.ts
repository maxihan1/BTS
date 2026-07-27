// 핵심 읽기 endpoint 계약 스냅샷을 프론트 Zod 로 파싱해 백엔드↔프론트 drift 를 양방향 차단
import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { z } from 'zod'
import { WhoamiResponseSchema } from '@/api/schemas'
import { projectSchema } from '@/api/projects'
import { issueTypeResponseSchema } from '@/api/issue-types'

/**
 * repo 루트를 CWD 에서 위로 올라가며 찾는다.
 *
 * 형제 `workflow-schemes.contract.test.ts` 와 **같은 판별식**(`docs/` + `CLAUDE.md` 동시 보유)을 쓴다 —
 * 여러 단계 상대경로는 파일이 한 칸만 옮겨져도 조용히 빗나간다.
 */
function repoRoot(): string {
  let dir = process.cwd()
  for (;;) {
    if (
      existsSync(resolve(dir, 'CLAUDE.md')) &&
      statSync(resolve(dir, 'docs'), { throwIfNoEntry: false })?.isDirectory()
    ) {
      return dir
    }
    const parent = dirname(dir)
    if (parent === dir) throw new Error(`repo 루트를 찾지 못했다(docs/ + CLAUDE.md 기준). 시작: ${process.cwd()}`)
    dir = parent
  }
}

const SNAPSHOT = resolve(repoRoot(), 'docs/contracts/core-read.snapshot.json')

/**
 * endpoint 라벨 → 그 응답을 받는 Zod 스키마.
 *
 * `.strict()` 는 **필드 추가**를 잡기 위한 것이다. 느슨한 object 는 삭제·오타만 잡고 추가를 놓쳐,
 * 프론트가 모르는 필드가 계약에 슬며시 들어온다.
 *
 * 라벨 문자열은 백엔드 `CoreReadContractSnapshotTest.collectRawResponses()` 의 맵 키와 **정확히 같아야** 한다.
 *
 * ★`whoami` 는 래퍼 없이 최상위 객체다 — 다른 두 endpoint 와 달리 `{ data: ... }` 로 감싸지 않는다.
 * 이 비대칭 자체가 계약의 일부이며, 스냅샷이 그것을 고정한다.
 *
 * ★★`WhoamiResponseSchema` 에 `.strict()` 를 **여기서** 건다 — 프로덕션 스키마는 느슨한 채로 둔다.
 * 두 곳의 목적이 다르기 때문이다. 런타임 파싱은 백엔드가 필드를 늘려도 화면이 죽지 않아야 하므로
 * 관대해야 하고, **계약 테스트는 그 추가를 반드시 잡아야 한다.**
 * 이 구분 없이 그냥 썼더니 뮤테이션(스냅샷에 미지 필드 주입)이 **통과**했다 — 봉인의 절반만
 * 닫혀 있었던 것이다([[seal-closes-only-half-by-default]]).
 */
const CASES: Array<[string, z.ZodTypeAny]> = [
  ['GET /api/v1/users/me/whoami', WhoamiResponseSchema.strict()],
  ['GET /api/v1/issue-types', z.object({ data: z.array(issueTypeResponseSchema) }).strict()],
  ['GET /api/v1/projects', z.object({ data: z.array(projectSchema) }).strict()],
]

describe('핵심 읽기 계약 스냅샷', () => {
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

  /**
   * 케이스 목록이 스냅샷 전량을 덮는지 확인한다.
   *
   * 이것이 없으면 백엔드가 endpoint 를 **추가**했을 때 프론트는 아무 반응도 하지 않는다 —
   * `it.each` 는 내가 적은 목록만 돌기 때문이다. 두 목록이 서로를 안 보는 그 양식 그대로다.
   */
  it('스냅샷의 모든 endpoint 가 케이스 목록에 있다', () => {
    const inSnapshot = Object.keys(snapshot).filter((k) => !k.startsWith('$'))
    const inCases = CASES.map(([endpoint]) => endpoint)

    expect(inSnapshot.slice().sort()).toEqual(inCases.slice().sort())
  })
})
