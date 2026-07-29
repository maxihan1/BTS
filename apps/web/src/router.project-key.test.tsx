// /issues 라우트 validateSearch 가 projectKey 쿼리 파라미터를 파싱하는지 검증 — FR-UX-07 Task 3 (RED)
import { describe, it, expect } from 'vitest'
import { createRouter } from '@tanstack/react-router'
import { routeTree } from './router'

/** issuesIndexRoute.validateSearch 의 반환 타입 — router.ts 의 실제 선언과 1:1 미러 */
interface IssuesSearch {
  page?: number
  status?: string | string[]
  assignee?: string | string[]
  label?: string | string[]
  component?: string | string[]
  sort?: string
  selected?: string
  projectKey?: string
}

/**
 * issuesIndexRoute 는 router.ts 밖으로 export 되지 않으므로 라우터 인스턴스의
 * `routesByPath['/issues']` 를 통해 접근한다 (`router.split-search.test.tsx` 선례).
 */
function getIssuesValidateSearch(): (search: Record<string, unknown>) => IssuesSearch {
  const router = createRouter({ routeTree })
  const validateSearch = router.routesByPath['/issues'].options.validateSearch
  return validateSearch as (search: Record<string, unknown>) => IssuesSearch
}

describe('issuesIndexRoute validateSearch — projectKey 파라미터 (FR-UX-07)', () => {
  it('?projectKey=INFRA 를 문자열로 파싱한다', () => {
    const validateSearch = getIssuesValidateSearch()
    expect(validateSearch({ projectKey: 'INFRA' }).projectKey).toBe('INFRA')
  })

  it('projectKey 가 없으면 undefined 로 파싱된다', () => {
    const validateSearch = getIssuesValidateSearch()
    expect(validateSearch({}).projectKey).toBeUndefined()
  })

  it('projectKey 가 문자열이 아니면 undefined 로 접는다 (기존 7종과 동일한 가드)', () => {
    const validateSearch = getIssuesValidateSearch()
    expect(validateSearch({ projectKey: 42 }).projectKey).toBeUndefined()
    expect(validateSearch({ projectKey: ['A', 'B'] }).projectKey).toBeUndefined()
    expect(validateSearch({ projectKey: null }).projectKey).toBeUndefined()
  })

  it('기존 7종 파라미터와 함께 보존된다 (무회귀)', () => {
    const validateSearch = getIssuesValidateSearch()
    const result = validateSearch({
      projectKey: 'INFRA',
      page: 2,
      status: 'open',
      assignee: ['u1', 'u2'],
      label: 'bug',
      component: 'api',
      sort: 'priority,asc',
      selected: 'INFRA-9',
    })

    expect(result.projectKey).toBe('INFRA')
    expect(result.page).toBe(2)
    expect(result.status).toBe('open')
    expect(result.assignee).toEqual(['u1', 'u2'])
    expect(result.label).toBe('bug')
    expect(result.component).toBe('api')
    expect(result.sort).toBe('priority,asc')
    expect(result.selected).toBe('INFRA-9')
  })
})
