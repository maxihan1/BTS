// 상세 열기 훅의 modifier 분기 판별식 — 새 탭·딥링크를 지키는 계약 (Jira 패리티 J1)
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import type { MouseEvent } from 'react'
import { useOpenIssueDetail } from '../use-open-issue-detail'
import { useIssueDetailModalStore } from '../issueDetailModalStore'

/**
 * `useOpenIssueDetail` 판별식.
 *
 * ## 무엇을 지키나
 *
 * Jira 원문(J1) — "you can continue to use Cmd or Ctrl + click, or right-click to open it in a
 * new tab". 모달로 가로채면서 이 경로를 함께 막으면 사용자는 **이슈를 두 개 나란히 볼 방법을
 * 잃는다**. 그래서 훅은 평범한 좌클릭만 가로채고 나머지는 브라우저 기본 동작에 넘긴다.
 *
 * ## 왜 훅 단위로 재나
 *
 * 진입점이 13곳이다. 각 컴포넌트마다 이 분기를 렌더 테스트로 재면 13벌이 필요하고, 한 곳이
 * 빠져도 나머지 12개가 초록이라 눈치채지 못한다. 분기를 훅 하나로 모으고 **그 훅을 전수로**
 * 재는 편이 모집단이 명확하다.
 */

/** 최소 MouseEvent 스텁 — 훅이 읽는 속성만 담는다. */
function mouseEvent(overrides: Partial<MouseEvent> = {}): MouseEvent {
  let prevented = false
  return {
    button: 0,
    metaKey: false,
    ctrlKey: false,
    shiftKey: false,
    altKey: false,
    get defaultPrevented() {
      return prevented
    },
    preventDefault: () => {
      prevented = true
    },
    ...overrides,
  } as unknown as MouseEvent
}

describe('useOpenIssueDetail — 모달 가로채기와 새 탭 보존 (J1)', () => {
  beforeEach(() => {
    useIssueDetailModalStore.setState({ openKey: null })
  })

  it('평범한 좌클릭은 모달을 열고 기본 동작을 막는다', () => {
    const { result } = renderHook(() => useOpenIssueDetail())
    const event = mouseEvent()

    act(() => { result.current('ATLAS-1', event) })

    expect(useIssueDetailModalStore.getState().openKey).toBe('ATLAS-1')
    expect(event.defaultPrevented).toBe(true)
  })

  // ★열거가 아니라 표로 전수를 돈다 — modifier 를 하나 더 다뤄야 할 때 여기 한 줄이 늘고,
  //   빠뜨리면 그 조합이 조용히 모달로 빨려 들어간다.
  const passThrough: ReadonlyArray<readonly [string, Partial<MouseEvent>]> = [
    ['⌘+클릭 (새 탭)', { metaKey: true }],
    ['Ctrl+클릭 (새 탭)', { ctrlKey: true }],
    ['Shift+클릭 (새 창)', { shiftKey: true }],
    ['Alt+클릭 (브라우저 동작)', { altKey: true }],
    ['가운데 클릭 (새 탭)', { button: 1 }],
    ['우클릭 (컨텍스트 메뉴)', { button: 2 }],
  ]

  passThrough.forEach(([label, overrides]) => {
    it(`${label}은 모달을 열지 않고 기본 동작에 넘긴다`, () => {
      const { result } = renderHook(() => useOpenIssueDetail())
      const event = mouseEvent(overrides)

      act(() => { result.current('ATLAS-1', event) })

      expect(useIssueDetailModalStore.getState().openKey).toBeNull()
      expect(event.defaultPrevented).toBe(false)
    })
  })

  it('앞선 핸들러가 이미 preventDefault 했으면 물러난다 — 드래그 중인 카드', () => {
    // BoardCard/BacklogCard 가 드래그 중 preventDefault 를 먼저 부른다. 그 상태에서 모달을
    // 열면 카드를 옮길 때마다 상세가 뜬다.
    const { result } = renderHook(() => useOpenIssueDetail())
    const event = mouseEvent()
    event.preventDefault()

    act(() => { result.current('ATLAS-1', event) })

    expect(useIssueDetailModalStore.getState().openKey).toBeNull()
  })

  it('비-공허 짝 — 스텁이 실제로 상태를 바꾼다', () => {
    // 위 6건이 「무엇을 해도 안 열린다」로 죽어 있지 않은지 되잰다.
    const { result } = renderHook(() => useOpenIssueDetail())

    act(() => { result.current('ATLAS-9', mouseEvent()) })

    expect(useIssueDetailModalStore.getState().openKey).toBe('ATLAS-9')
  })
})
