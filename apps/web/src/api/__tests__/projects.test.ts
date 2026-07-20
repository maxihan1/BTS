// 프로젝트 목록 API 클라이언트 단위 테스트 — Zod 스키마 파싱 + MSW 핸들러 경유 listProjects() 검증 (FR-UX-06 PR12 Task 1)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectSchema, projectListResponseSchema, listProjects } from '../projects'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ProjectResponse 3 필드(id/key/name)만, backend DTO 1:1(게이트1 확정)
// Zod uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────
const projectFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  key: 'ATLAS',
  name: 'Atlas',
}

// ─────────────────────────────────────────────────────────────────────────────
// projectSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('projectSchema', () => {
  it('id/key/name 3필드를 파싱한다', () => {
    const result = projectSchema.parse(projectFixture)
    expect(result).toEqual(projectFixture)
  })

  it('id가 UUID 형식이 아니면 reject한다', () => {
    expect(() => projectSchema.parse({ ...projectFixture, id: 'not-a-uuid' })).toThrow()
  })

  it('id가 number면 reject한다', () => {
    expect(() => projectSchema.parse({ ...projectFixture, id: 123 })).toThrow()
  })

  it('key가 누락되면 reject한다', () => {
    const withoutKey = { id: projectFixture.id, name: projectFixture.name }
    expect(() => projectSchema.parse(withoutKey)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// projectListResponseSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('projectListResponseSchema', () => {
  it('{data:[{id,key,name}]} 를 파싱한다', () => {
    const result = projectListResponseSchema.parse({ data: [projectFixture] })
    expect(result.data).toHaveLength(1)
    expect(result.data[0]).toEqual(projectFixture)
  })

  it('빈 배열 {data:[]} 를 파싱한다', () => {
    const result = projectListResponseSchema.parse({ data: [] })
    expect(result.data).toEqual([])
  })

  it('여분 필드(leadUserId 등)는 무시하고 파싱한다', () => {
    const result = projectListResponseSchema.parse({
      data: [{ ...projectFixture, leadUserId: 'ignored', createdAt: 'ignored' }],
    })
    expect(result.data[0]).toEqual(projectFixture)
  })

  it('data 항목의 형태가 잘못되면(id가 number) reject한다', () => {
    expect(() =>
      projectListResponseSchema.parse({ data: [{ ...projectFixture, id: 42 }] }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// listProjects — MSW 핸들러 경유 HTTP 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('listProjects', () => {
  it('GET /api/v1/projects?archived=false 응답을 파싱해 Project[] 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects', ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('archived')).toBe('false')
        return HttpResponse.json({ data: [projectFixture] })
      }),
    )

    const result = await listProjects()
    expect(result).toEqual([projectFixture])
  })

  it('archived=true 를 명시하면 쿼리스트링에 반영한다', async () => {
    server.use(
      http.get('/api/v1/projects', ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('archived')).toBe('true')
        return HttpResponse.json({ data: [] })
      }),
    )

    const result = await listProjects(true)
    expect(result).toEqual([])
  })
})
