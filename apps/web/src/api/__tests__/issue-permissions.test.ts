// 이슈 권한 조회 API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 + 누락 필드 에러 검증
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { ZodError } from 'zod'
import { server } from '@/test/server'
import {
  issuePermissionsSchema,
  fetchIssuePermissions,
} from '../issue-permissions'
import type { IssuePermissions } from '../issue-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — backend DTO 필드와 1:1
// ─────────────────────────────────────────────────────────────────────────────

const permissionsFixture = {
  issueKey: 'ATLAS-1',
  permissions: {
    UPDATE: true,
    SOFT_DELETE: false,
    TRANSITION: true,
  },
}

const ISSUE_KEY = 'ATLAS-1'
const ENDPOINT = `/api/v1/users/me/issue-permissions`

// ─────────────────────────────────────────────────────────────────────────────
// T-IP-1. issuePermissionsSchema — 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('issuePermissionsSchema', () => {
  it('T-IP-1a: 정상 응답을 파싱한다', () => {
    const result = issuePermissionsSchema.parse(permissionsFixture)

    expect(result.issueKey).toBe('ATLAS-1')
    expect(result.permissions.UPDATE).toBe(true)
    expect(result.permissions.SOFT_DELETE).toBe(false)
    expect(result.permissions.TRANSITION).toBe(true)
  })

  it('T-IP-1b: permissions 필드 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      issuePermissionsSchema.parse({ issueKey: 'ATLAS-1' }),
    ).toThrow(ZodError)
  })

  it('T-IP-1c: permissions.UPDATE 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      issuePermissionsSchema.parse({
        issueKey: 'ATLAS-1',
        permissions: { SOFT_DELETE: false, TRANSITION: true },
      }),
    ).toThrow(ZodError)
  })

  it('T-IP-1d: issueKey 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      issuePermissionsSchema.parse({
        permissions: { UPDATE: true, SOFT_DELETE: false, TRANSITION: true },
      }),
    ).toThrow(ZodError)
  })

  it('T-IP-1e: permissions 값이 boolean이 아니면 ZodError를 throw한다', () => {
    expect(() =>
      issuePermissionsSchema.parse({
        issueKey: 'ATLAS-1',
        permissions: { UPDATE: 'yes', SOFT_DELETE: false, TRANSITION: true },
      }),
    ).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IP-2. fetchIssuePermissions — GET /api/v1/users/me/issue-permissions?issueKey=...
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchIssuePermissions', () => {
  it('T-IP-2a: 올바른 URL(issueKey 쿼리 파라미터 포함)을 호출한다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get(ENDPOINT, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(permissionsFixture)
      }),
    )

    await fetchIssuePermissions(ISSUE_KEY)

    expect(capturedUrl).not.toBeNull()
    const url = new URL(capturedUrl as unknown as string)
    expect(url.pathname).toBe(ENDPOINT)
    expect(url.searchParams.get('issueKey')).toBe(ISSUE_KEY)
  })

  it('T-IP-2b: 응답을 IssuePermissions로 파싱해 반환한다', async () => {
    server.use(
      http.get(ENDPOINT, () => HttpResponse.json(permissionsFixture)),
    )

    const result = await fetchIssuePermissions(ISSUE_KEY)

    expect(result.issueKey).toBe('ATLAS-1')
    expect(result.permissions.UPDATE).toBe(true)
    expect(result.permissions.SOFT_DELETE).toBe(false)
    expect(result.permissions.TRANSITION).toBe(true)
  })

  it('T-IP-2c: 응답에서 permissions 필드 누락 시 ZodError를 throw한다', async () => {
    server.use(
      http.get(ENDPOINT, () =>
        HttpResponse.json({ issueKey: 'ATLAS-1' }),
      ),
    )

    await expect(fetchIssuePermissions(ISSUE_KEY)).rejects.toBeInstanceOf(ZodError)
  })

  it('T-IP-2d: 서버 403 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get(ENDPOINT, () =>
        HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
      ),
    )

    const err = await fetchIssuePermissions(ISSUE_KEY).catch((e: unknown) => e)
    // ApiError 또는 그 하위 에러 — status 확인
    expect(err).toBeInstanceOf(Error)
    expect((err as { status?: number }).status).toBe(403)
  })

  it('T-IP-2e: 타입 컴파일 가드 — 반환값이 IssuePermissions 타입에 할당 가능하다', async () => {
    server.use(
      http.get(ENDPOINT, () => HttpResponse.json(permissionsFixture)),
    )

    const result: IssuePermissions = await fetchIssuePermissions(ISSUE_KEY)
    expect(result.permissions.TRANSITION).toBe(true)
  })
})
