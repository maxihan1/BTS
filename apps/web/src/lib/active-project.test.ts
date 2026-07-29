// 활성 프로젝트 4단 해소 순수 함수 테스트 — FR-UX-07 Task 1 (RED)
import { describe, it, expect } from 'vitest'
import { resolveActiveProjectKey } from './active-project'

/**
 * 테스트용 프로젝트 목록 — 백엔드가 `ORDER BY name ASC`(ProjectQueryRepository.kt:62)로
 * 정렬해 내려주는 순서를 그대로 재현한다. 프론트는 재정렬하지 않는다(C4).
 */
const PROJECTS = [
  { key: 'ALPHA', name: '가 프로젝트' },
  { key: 'INFRA', name: '나 프로젝트' },
  { key: 'ATLAS', name: '다 프로젝트' },
] as const

describe('resolveActiveProjectKey — 4단 해소', () => {
  it('S1. URL 키가 최우선 — 저장값이 달라도 URL 을 따른다', () => {
    expect(
      resolveActiveProjectKey({ urlKey: 'INFRA', storedKey: 'ATLAS', projects: PROJECTS }),
    ).toEqual({ key: 'INFRA', source: 'url' })
  })

  it('S2. URL 이 없으면 저장값 — 목록에 실재할 때만', () => {
    expect(
      resolveActiveProjectKey({ urlKey: null, storedKey: 'INFRA', projects: PROJECTS }),
    ).toEqual({ key: 'INFRA', source: 'stored' })
  })

  it('S3. URL·저장값 둘 다 없으면 목록의 첫 원소', () => {
    expect(resolveActiveProjectKey({ urlKey: null, storedKey: null, projects: PROJECTS })).toEqual({
      key: 'ALPHA',
      source: 'first',
    })
  })

  it('S6. 저장값이 접근 가능 목록에 없으면 첫 원소로 내려간다 (삭제·아카이브·권한 회수)', () => {
    expect(
      resolveActiveProjectKey({ urlKey: null, storedKey: 'GONE', projects: PROJECTS }),
    ).toEqual({ key: 'ALPHA', source: 'first' })
  })

  it('S5. 프로젝트가 0개면 null', () => {
    expect(resolveActiveProjectKey({ urlKey: null, storedKey: null, projects: [] })).toEqual({
      key: null,
      source: 'none',
    })
  })

  it('S7. 접근 불가한 URL 키도 그대로 반환한다 — 조용한 대체 금지', () => {
    // 사용자가 명시로 요청한 값이므로 다른 프로젝트로 바꾸지 않는다.
    // 권한 실패는 호출자(라우트)가 에러로 표시하고, 저장값도 갱신하지 않는다(E4).
    expect(
      resolveActiveProjectKey({ urlKey: 'NOPERM', storedKey: 'ATLAS', projects: PROJECTS }),
    ).toEqual({ key: 'NOPERM', source: 'url' })
  })

  it('S5+S7. 프로젝트 0개여도 URL 키가 있으면 URL 을 따른다', () => {
    expect(resolveActiveProjectKey({ urlKey: 'INFRA', storedKey: null, projects: [] })).toEqual({
      key: 'INFRA',
      source: 'url',
    })
  })

  it('빈 문자열 URL 키는 미지정으로 취급한다 — 빈 스코프 요청이 백엔드로 새지 않게', () => {
    expect(
      resolveActiveProjectKey({ urlKey: '', storedKey: 'INFRA', projects: PROJECTS }),
    ).toEqual({ key: 'INFRA', source: 'stored' })
  })

  it('빈 문자열 키를 가진 프로젝트는 첫 원소 폴백에서 건너뛴다 (CR6)', () => {
    expect(
      resolveActiveProjectKey({ urlKey: null, storedKey: null, projects: [{ key: '' }, { key: 'ALPHA' }] }),
    ).toEqual({ key: 'ALPHA', source: 'first' })
  })

  it('모든 키가 빈 문자열이면 none 으로 떨어진다 — CR6 가 새로 만든 fall-through', () => {
    expect(
      resolveActiveProjectKey({ urlKey: null, storedKey: null, projects: [{ key: '' }, { key: '' }] }),
    ).toEqual({ key: null, source: 'none' })
  })

  it('빈 저장값은 빈 키 프로젝트와 매칭되지 않는다 — stored 지점 nonEmpty', () => {
    expect(
      resolveActiveProjectKey({ urlKey: null, storedKey: '', projects: [{ key: '' }, { key: 'ALPHA' }] }),
    ).toEqual({ key: 'ALPHA', source: 'first' })
  })

  it('undefined 입력을 null 과 동일하게 다룬다 (useSearch·스토어 미설정 값)', () => {
    expect(
      resolveActiveProjectKey({ urlKey: undefined, storedKey: undefined, projects: PROJECTS }),
    ).toEqual({ key: 'ALPHA', source: 'first' })
  })

  it('C4. 프론트에서 재정렬하지 않는다 — 이름 역순 목록이 와도 첫 원소를 그대로 쓴다', () => {
    // 백엔드 정렬을 신뢰한다(routes/projects.index.tsx:42 "프론트 재정렬 없음").
    // 이름이 오름차순이 아니어도 클라이언트가 sort 하지 않음을 고정한다.
    const reversed = [
      { key: 'ZULU', name: '하 프로젝트' },
      { key: 'ALPHA', name: '가 프로젝트' },
    ] as const
    expect(resolveActiveProjectKey({ urlKey: null, storedKey: null, projects: reversed })).toEqual({
      key: 'ZULU',
      source: 'first',
    })
  })
})
