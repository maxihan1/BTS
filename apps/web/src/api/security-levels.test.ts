// 프로젝트 이슈 보안 등급 API fetchProjectSecurityLevels 단위 테스트 — FR-PM-06 PR-B D6
import { describe, it, expect, beforeEach } from 'vitest'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { fetchProjectSecurityLevels } from './security-levels'

const LEVELS_URL = '/api/v1/projects/ATLAS/issue-security-scheme/levels'

describe('fetchProjectSecurityLevels', () => {
  beforeEach(() => {
    server.resetHandlers()
  })

  /**
   * SL-API-1. 등급 목록이 있는 프로젝트 — levels 배열 반환.
   */
  it('SL-API-1: 등급 목록 조회 시 SecurityLevel 배열을 반환한다', async () => {
    server.use(
      http.get(LEVELS_URL, () =>
        HttpResponse.json({
          levels: [
            {
              id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
              name: '기밀',
              description: '팀 내부용',
              isDefault: false,
            },
            {
              id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
              name: '내부',
              description: null,
              isDefault: true,
            },
          ],
        }),
      ),
    )

    const result = await fetchProjectSecurityLevels('ATLAS')

    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      name: '기밀',
      description: '팀 내부용',
      isDefault: false,
    })
    expect(result[1]).toEqual({
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      name: '내부',
      description: null,
      isDefault: true,
    })
  })

  /**
   * SL-API-2. 스킴이 미적용된 프로젝트 — 빈 배열 반환 (200 + levels=[]).
   */
  it('SL-API-2: 스킴 미적용 프로젝트는 빈 배열을 반환한다', async () => {
    server.use(
      http.get(LEVELS_URL, () =>
        HttpResponse.json({ levels: [] }),
      ),
    )

    const result = await fetchProjectSecurityLevels('ATLAS')
    expect(result).toEqual([])
  })

  /**
   * SL-API-3. 프로젝트 없음 — ApiError(404) throw.
   */
  it('SL-API-3: 없는 프로젝트 조회 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get(LEVELS_URL, () =>
        HttpResponse.json({ error: 'project_not_found' }, { status: 404 }),
      ),
    )

    await expect(fetchProjectSecurityLevels('ATLAS')).rejects.toMatchObject({ status: 404 })
  })
})
