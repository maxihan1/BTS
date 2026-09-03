// 오버플로 판정 순수 함수 경계값 전수 — 활성 탭 핀 고정 · 최소 1개 · 레이아웃 부재 (Jira 패리티 J5)
import { describe, it, expect } from 'vitest'
import { computeVisibleTabIndexes } from '@/hooks/use-tab-overflow'

/** 탭 9개가 각각 100px(gap 포함), 「더 보기」가 80px 인 기준 판. 계산을 암산으로 검산할 수 있게 균일하게 둔다 */
const WIDTHS = [100, 100, 100, 100, 100, 100, 100, 100, 100]
const MORE = 80

/** 기준 판에 폭·활성만 갈아끼우는 헬퍼 */
function visible(containerWidth: number, activeIndex = 0, itemWidths = WIDTHS): readonly number[] {
  return computeVisibleTabIndexes({ containerWidth, itemWidths, moreWidth: MORE, activeIndex })
}

describe('computeVisibleTabIndexes', () => {
  it('비-공허: 기준 판이 9탭이고 전량 가시와 접힘이 서로 다른 결과를 낸다', () => {
    // 두 결과가 같으면 아래 단언들이 무엇도 구분하지 못한다.
    expect(WIDTHS).toHaveLength(9)
    expect(visible(900)).not.toEqual(visible(300))
  })

  it('레이아웃이 없으면(폭 0) 전량 가시다 — 측정의 씨앗을 남긴다', () => {
    // jsdom 은 clientWidth 가 항상 0 이다. 여기서 접으면 유닛 테스트가 탭을 영영 못 본다.
    expect(visible(0)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8])
  })

  it('탭이 0개면 빈 배열이다', () => {
    expect(computeVisibleTabIndexes({ containerWidth: 500, itemWidths: [], moreWidth: MORE, activeIndex: -1 }))
      .toEqual([])
  })

  it('딱 맞으면 전량 가시이고 「더 보기」 폭을 예산에서 빼지 않는다', () => {
    // 총 900. 900 이면 트리거가 렌더되지 않으므로 900 그대로 들어간다.
    expect(visible(900)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8])
  })

  it('1px 모자라면 접히고, 예산에서 「더 보기」 폭이 빠진다', () => {
    // 899 - 80 = 819 → 100 씩 8개(800)가 들어간다.
    expect(visible(899)).toEqual([0, 1, 2, 3, 4, 5, 6, 7])
  })

  it('예산이 한 탭도 못 담아도 최소 1개는 남긴다', () => {
    // 90 - 80 = 10 → 0개가 정답이지만 탭바가 트리거만 남으면 화면을 알 수 없다.
    expect(visible(90)).toEqual([0])
  })

  it('활성 탭이 가시 범위 안이면 앞에서부터 그대로 채운다', () => {
    // 380 - 80 = 300 → 3개. 활성(1)이 그 안이라 핀 고정이 필요 없다.
    expect(visible(380, 1)).toEqual([0, 1, 2])
  })

  it('활성 탭이 범위 밖이면 앞쪽 한 자리를 내주고 끌어온다 (핀 고정)', () => {
    // 3개가 들어가는 폭인데 활성이 7번 — 앞 2개 + 활성.
    expect(visible(380, 7)).toEqual([0, 1, 7])
  })

  it('핀 고정은 가시 1개일 때 활성 탭 하나만 남긴다', () => {
    // 최소 1개 규칙과 핀 고정이 겹치는 자리. 앞쪽을 0개 남기고 활성만 보여준다.
    expect(visible(90, 5)).toEqual([5])
  })

  it('활성 탭이 없으면(-1) 핀 고정이 발동하지 않는다', () => {
    expect(visible(380, -1)).toEqual([0, 1, 2])
  })

  it('경계 — 활성이 가시 개수와 같으면 밖이므로 핀 고정한다', () => {
    // 3개 가시(인덱스 0·1·2)에서 활성 3 은 첫 번째 「밖」이다. off-by-one 을 잡는 자리.
    expect(visible(380, 3)).toEqual([0, 1, 3])
  })

  it('경계 — 활성이 마지막 가시 인덱스면 핀 고정하지 않는다', () => {
    expect(visible(380, 2)).toEqual([0, 1, 2])
  })

  it('탭 폭이 제각각이어도 앞에서부터 누적으로 판정한다', () => {
    // 300 - 80 = 220 예산. 50 + 150 = 200 까지 들어가고 다음 100 은 못 들어간다.
    expect(visible(300, 0, [50, 150, 100, 100])).toEqual([0, 1])
  })

  it('첫 탭이 예산보다 커도 최소 1개 규칙이 이긴다', () => {
    expect(visible(300, 0, [900, 100, 100])).toEqual([0])
  })
})
