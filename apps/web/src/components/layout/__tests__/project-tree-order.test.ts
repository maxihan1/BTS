// 사이드바 스페이스 3그룹 분할 순수 함수 테스트 — 별표/최근/추가 (Jira 패리티 캠페인 PR ⑩ · J2)
import { describe, it, expect } from 'vitest'
import type { Project } from '@/api/projects'
import { partitionProjectsForTree } from '../project-tree-order'

/** 백엔드 순서(`ORDER BY name ASC`)를 그대로 재현한 목록 */
const PROJECTS: readonly Project[] = [
  { id: '00000000-0000-0000-0000-000000000001', key: 'ALPHA', name: 'Alpha' },
  { id: '00000000-0000-0000-0000-000000000002', key: 'BRAVO', name: 'Bravo' },
  { id: '00000000-0000-0000-0000-000000000003', key: 'CHARLIE', name: 'Charlie' },
  { id: '00000000-0000-0000-0000-000000000004', key: 'DELTA', name: 'Delta' },
]

const keysOf = (projects: readonly Project[]): string[] => projects.map((p) => p.key)

describe('partitionProjectsForTree', () => {
  it('별표·최근 어느 쪽도 아니면 전부 「추가」 그룹에 백엔드 순서 그대로 남는다', () => {
    const { starred, recent, more } = partitionProjectsForTree(PROJECTS, [], [])

    expect(keysOf(starred)).toEqual([])
    expect(keysOf(recent)).toEqual([])
    expect(keysOf(more)).toEqual(['ALPHA', 'BRAVO', 'CHARLIE', 'DELTA'])
  })

  it('별표 그룹은 백엔드 순서를 보존한다 — 즐겨찾기 등록 순이 아니다', () => {
    // 즐겨찾기 API 는 created_at DESC 로 주지만, 저장소 관례는 "백엔드 정렬 신뢰,
    // 프론트 재정렬 없음"(FR-UX-07 FR3)이다. 별표 그룹은 그 관례를 따른다.
    const { starred } = partitionProjectsForTree(PROJECTS, ['DELTA', 'BRAVO'], [])

    expect(keysOf(starred)).toEqual(['BRAVO', 'DELTA'])
  })

  it('최근 그룹은 MRU 순을 보존한다 — 백엔드 순서가 아니다', () => {
    const { recent } = partitionProjectsForTree(PROJECTS, [], ['DELTA', 'ALPHA'])

    expect(keysOf(recent)).toEqual(['DELTA', 'ALPHA'])
  })

  it('별표가 최근을 이긴다 — 같은 프로젝트가 두 그룹에 동시에 나오지 않는다', () => {
    // 한 프로젝트가 별표이면서 최근 방문일 수 있다. 양쪽에 렌더하면 같은 행이 두 번 보이고
    // React `key` 도 중복된다. 별표가 더 강한 의사표시이므로 별표가 가져간다.
    const { starred, recent, more } = partitionProjectsForTree(
      PROJECTS,
      ['BRAVO'],
      ['BRAVO', 'CHARLIE'],
    )

    expect(keysOf(starred)).toEqual(['BRAVO'])
    expect(keysOf(recent)).toEqual(['CHARLIE'])
    expect(keysOf(more)).toEqual(['ALPHA', 'DELTA'])
  })

  it('접근 가능 목록에 없는 키는 조용히 탈락한다 (삭제·권한 회수)', () => {
    const { starred, recent, more } = partitionProjectsForTree(
      PROJECTS,
      ['GHOST'],
      ['PHANTOM', 'ALPHA'],
    )

    expect(keysOf(starred)).toEqual([])
    expect(keysOf(recent)).toEqual(['ALPHA'])
    expect(keysOf(more)).toEqual(['BRAVO', 'CHARLIE', 'DELTA'])
  })

  it('세 그룹의 합은 언제나 원본 목록과 같다 — 어떤 프로젝트도 사라지지 않는다', () => {
    // 분할이 곧 렌더 대상 전체다. 합이 줄면 사이드바에서 프로젝트가 조용히 증발한다.
    const { starred, recent, more } = partitionProjectsForTree(
      PROJECTS,
      ['DELTA'],
      ['CHARLIE', 'DELTA'],
    )

    const all = [...keysOf(starred), ...keysOf(recent), ...keysOf(more)]
    expect([...all].sort()).toEqual([...keysOf(PROJECTS)].sort())
  })

  it('중복 키가 들어와도 그룹 안에서 한 번만 나온다', () => {
    const { starred, recent } = partitionProjectsForTree(
      PROJECTS,
      ['ALPHA', 'ALPHA'],
      ['BRAVO', 'BRAVO'],
    )

    expect(keysOf(starred)).toEqual(['ALPHA'])
    expect(keysOf(recent)).toEqual(['BRAVO'])
  })
})
