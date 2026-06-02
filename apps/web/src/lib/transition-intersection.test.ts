// 여러 이슈의 가용 전이 목록에서 공통 전이(교집합)를 구하는 순수 함수 단위 테스트
import { describe, it, expect } from 'vitest'
import { intersectTransitions } from './transition-intersection'
import type { IssueTransition } from '@/api/issues'

const t = (toStateKey: string, key = `tr-${toStateKey}`, name = `To ${toStateKey}`): IssueTransition => ({
  key,
  name,
  fromStateKey: 'OPEN',
  toStateKey,
})

describe('intersectTransitions', () => {
  it('(a) 모든 이슈에 공통으로 존재하는 toStateKey만 반환한다', () => {
    const issue1 = [t('IN_PROGRESS'), t('CLOSED')]
    const issue2 = [t('IN_PROGRESS'), t('RESOLVED')]
    const issue3 = [t('IN_PROGRESS'), t('CLOSED'), t('RESOLVED')]

    const result = intersectTransitions([issue1, issue2, issue3])

    expect(result).toHaveLength(1)
    expect(result[0]?.toStateKey).toBe('IN_PROGRESS')
  })

  it('(b) 한 이슈라도 빈 배열이면 결과는 [] 이다', () => {
    const issue1 = [t('IN_PROGRESS'), t('CLOSED')]
    const issue2: IssueTransition[] = []

    expect(intersectTransitions([issue1, issue2])).toEqual([])
  })

  it('(c) 입력이 단일 이슈면 그 이슈의 전이를 그대로(dedup) 반환한다', () => {
    const issue = [t('IN_PROGRESS'), t('CLOSED'), t('IN_PROGRESS', 'tr-dup', 'Dup')]

    const result = intersectTransitions([issue])

    expect(result).toHaveLength(2)
    expect(result.map((r) => r.toStateKey)).toEqual(['IN_PROGRESS', 'CLOSED'])
  })

  it('(d) 결과는 toStateKey 기준 dedup + 첫 등장 항목 기준으로 name/key를 보존한다', () => {
    const issue1 = [
      { key: 'tr-a', name: 'First A', fromStateKey: 'OPEN', toStateKey: 'DONE' },
      { key: 'tr-a2', name: 'Second A', fromStateKey: 'OPEN', toStateKey: 'DONE' },
    ]
    const issue2 = [
      { key: 'tr-b', name: 'B DONE', fromStateKey: 'OPEN', toStateKey: 'DONE' },
    ]

    const result = intersectTransitions([issue1, issue2])

    expect(result).toHaveLength(1)
    expect(result[0]?.key).toBe('tr-a')
    expect(result[0]?.name).toBe('First A')
  })

  it('(e) 빈 입력([])이면 [] 이다', () => {
    expect(intersectTransitions([])).toEqual([])
  })
})
