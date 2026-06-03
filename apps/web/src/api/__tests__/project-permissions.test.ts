// projectPermissionsSchema Zod 파싱 — MANAGE_COMPONENTS/MANAGE_VERSIONS required 검증
import { describe, it, expect } from 'vitest'
import { projectPermissionsSchema } from '../project-permissions'

describe('projectPermissionsSchema', () => {
  it('CREATE 포함 완전한 응답은 parse 성공한다', () => {
    const input = {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: true,
        MANAGE_COMPONENTS: true,
        MANAGE_VERSIONS: false,
      },
    }
    expect(() => projectPermissionsSchema.parse(input)).not.toThrow()
    const result = projectPermissionsSchema.parse(input)
    expect(result.permissions.CREATE).toBe(true)
    expect(result.permissions.MANAGE_COMPONENTS).toBe(true)
    expect(result.permissions.MANAGE_VERSIONS).toBe(false)
  })

  it('MANAGE_COMPONENTS가 없으면 parse 실패한다 (required)', () => {
    const input = {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: true,
        MANAGE_VERSIONS: false,
      },
    }
    expect(() => projectPermissionsSchema.parse(input)).toThrow()
  })

  it('MANAGE_VERSIONS가 없으면 parse 실패한다 (required)', () => {
    const input = {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: true,
        MANAGE_COMPONENTS: true,
      },
    }
    expect(() => projectPermissionsSchema.parse(input)).toThrow()
  })

  it('CREATE만 있고 MANAGE_* 둘 다 없으면 parse 실패한다', () => {
    const input = {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: true,
      },
    }
    expect(() => projectPermissionsSchema.parse(input)).toThrow()
  })

  it('세 키 모두 false여도 parse 성공한다', () => {
    const input = {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: false,
        MANAGE_COMPONENTS: false,
        MANAGE_VERSIONS: false,
      },
    }
    const result = projectPermissionsSchema.parse(input)
    expect(result.permissions.MANAGE_COMPONENTS).toBe(false)
    expect(result.permissions.MANAGE_VERSIONS).toBe(false)
  })
})
