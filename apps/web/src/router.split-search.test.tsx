// /issues 라우트 validateSearch가 split view용 selected 쿼리 파라미터를 보존하는지 검증하는 라우터 테스트 (FR-UX-06 Phase 5 PR20 Task 4)
import { describe, it, expect } from 'vitest'
import { createRouter } from '@tanstack/react-router'
import { routeTree } from './router'

/** issuesIndexRoute.validateSearch의 반환 타입 — router.ts의 실제 선언과 1:1 미러 */
interface IssuesSearch {
  page?: number
  status?: string | string[]
  assignee?: string | string[]
  label?: string | string[]
  component?: string | string[]
  sort?: string
  selected?: string
}

/**
 * issuesIndexRoute는 router.ts 밖으로 export되지 않으므로, 라우터 인스턴스의
 * `routesByPath['/issues']`를 통해 라우트 객체(및 그 `options.validateSearch`)에 접근한다.
 * TanStack Router의 `Constrain<TSearchValidator, ...>` 래핑 타입이 유니온이라 직접 호출이
 * 불가능해, 실제 구현이 순수 함수라는 사실에 근거해 정밀 타입으로 단언한다(`any` 아님).
 */
function getIssuesValidateSearch(): (search: Record<string, unknown>) => IssuesSearch {
  const router = createRouter({ routeTree })
  const validateSearch = router.routesByPath['/issues'].options.validateSearch
  return validateSearch as (search: Record<string, unknown>) => IssuesSearch
}

describe('issuesIndexRoute validateSearch — selected 파라미터 (split view)', () => {
  it('selected가 기존 status/sort와 함께 파싱 결과에 보존된다', () => {
    const validateSearch = getIssuesValidateSearch()
    const result = validateSearch({ selected: 'ATLAS-9', status: 'open', sort: 'priority,asc' })
    expect(result.selected).toBe('ATLAS-9')
    expect(result.status).toBe('open')
    expect(result.sort).toBe('priority,asc')
  })

  it('selected 파라미터가 없으면 selected는 undefined로 파싱된다', () => {
    const validateSearch = getIssuesValidateSearch()
    const result = validateSearch({})
    expect(result.selected).toBeUndefined()
  })
})
