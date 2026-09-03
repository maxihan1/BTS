// 탭바 폭 부족 시 가시 탭 개수 산출 — 순수 판정 + ResizeObserver 측정 래퍼 (Jira 패리티 J5)
import { useCallback, useLayoutEffect, useRef, useState } from 'react'

/** {@link computeVisibleTabIndexes} 입력 — 전부 px 단위 숫자다(DOM 을 모른다) */
export interface TabOverflowInput {
  /** 탭이 들어갈 수 있는 폭. `0` 이면 「레이아웃이 아직 없다」는 뜻이다 */
  readonly containerWidth: number
  /** 탭별 점유 폭. **gap 을 이미 포함**한다 */
  readonly itemWidths: readonly number[]
  /** 「더 보기」 트리거가 점유하는 폭. gap 포함 */
  readonly moreWidth: number
  /** 활성 탭 인덱스. 활성 탭이 없으면 `-1` */
  readonly activeIndex: number
}

/**
 * 지금 폭에서 **보여 줄 탭 인덱스**를 오름차순으로 돌려준다.
 *
 * 이 함수가 오버플로 판정의 유일한 소유자다 — 훅은 재는 일만 하고 판정하지 않는다.
 * DOM 을 모르는 순수 함수라 jsdom 레이아웃 없이도 경계값을 전수로 단언할 수 있다.
 *
 * ### 규칙
 * 1. `containerWidth === 0` → **전량 가시**. 레이아웃이 아직 없다는 뜻이고(jsdom·첫 렌더),
 *    여기서 접어 버리면 측정도 못 한 채 탭이 사라진다. 측정의 씨앗을 남기는 것이 목적이다.
 * 2. 전부 들어가면 전량 가시. 이때 「더 보기」는 렌더되지 않으므로 그 폭을 빼지 않는다.
 * 3. 모자라면 「더 보기」 자리를 뺀 예산 안에서 **앞에서부터** 최대 개수를 담되 **최소 1개**는
 *    남긴다. 0개가 되면 탭바가 트리거만 남아 무슨 화면인지 알 수 없다.
 * 4. **활성 탭 핀 고정** — 활성 탭이 그 범위 밖이면 앞쪽 하나를 접고 활성 탭을 끌어온다.
 *    지금 보고 있는 화면이 탭바에서 사라지는 것이 가장 나쁜 상태다.
 */
export function computeVisibleTabIndexes(input: TabOverflowInput): readonly number[] {
  const { containerWidth, itemWidths, moreWidth, activeIndex } = input
  const all = itemWidths.map((_, index) => index)
  if (itemWidths.length === 0) return all

  // ① 레이아웃 없음 — 접지 않는다.
  if (containerWidth === 0) return all

  // ② 전부 들어간다 — 「더 보기」 미렌더라 그 폭을 예산에서 빼지 않는다.
  const total = itemWidths.reduce((sum, width) => sum + width, 0)
  if (total <= containerWidth) return all

  // ③ 「더 보기」 자리를 뺀 예산으로 앞에서부터 채운다. 최소 1개.
  const budget = containerWidth - moreWidth
  let used = 0
  let count = 0
  for (const width of itemWidths) {
    if (used + width > budget) break
    used += width
    count += 1
  }
  const visibleCount = Math.max(count, 1)
  if (visibleCount >= itemWidths.length) return all

  // ④ 활성 탭 핀 고정 — 범위 밖이면 앞쪽 한 자리를 내주고 끌어온다.
  if (activeIndex >= visibleCount) {
    const head = all.slice(0, visibleCount - 1)
    return [...head, activeIndex]
  }
  return all.slice(0, visibleCount)
}

/** {@link useTabOverflow} 반환 — 콜백 ref 3종 + 가시/은닉 인덱스 */
export interface TabOverflow {
  /** 탭이 들어갈 컨테이너에 건다. 폭 변화를 여기서 관찰한다 */
  readonly setContainer: (element: HTMLElement | null) => void
  /** `index` 번째 탭 요소에 건다 — 폭을 한 번 재고 캐시한다 */
  readonly setItem: (index: number) => (element: HTMLElement | null) => void
  /** 「더 보기」 트리거에 건다 */
  readonly setMore: (element: HTMLElement | null) => void
  /** 보여 줄 탭 인덱스 (오름차순) */
  readonly visibleIndexes: readonly number[]
  /** 접힌 탭 인덱스 (오름차순) */
  readonly hiddenIndexes: readonly number[]
  /**
   * 「더 보기」 트리거를 렌더해야 하는가.
   *
   * 🛑 `hiddenIndexes.length > 0` 만으로 판정하면 **트리거 폭을 영영 못 잰다.** 첫 렌더는
   * 전량 가시라 접힌 탭이 0 이고, 그러면 트리거가 없어 `moreWidth = 0` 으로 계산되어 탭이
   * 한 개 더 들어간다고 오판한다(그 한 개가 트리거에 가려진다). 그래서 **측정이 끝나기
   * 전까지는 무조건 렌더**해 재고, 끝난 뒤에 접힌 탭 유무로 판정한다.
   * 측정은 `useLayoutEffect` 라 브라우저에서는 페인트 전에 끝나 깜빡임이 없다.
   */
  readonly shouldRenderMore: boolean
}

/**
 * 탭바 폭을 관찰해 가시 탭을 정한다.
 *
 * ### 왜 폭을 캐시하는가
 * 접힌 탭은 DOM 에 없어 **다시 잴 수 없다.** 첫 레이아웃에서 전량이 렌더된 순간(규칙 ①)
 * 한 번 재서 캐시하고, 그 뒤로는 캐시로만 계산한다. 캐시가 없으면 「접었다 → 못 잰다 →
 * 못 펴다」로 굳는다.
 *
 * ### 왜 브레이크포인트를 쓰지 않는가
 * `sidebar-breakpoint-alignment.test.ts` 가 프로덕션 소스 전량에서 `max-md` 외의 상한 변형을
 * 금지한다. 그리고 탭 개수는 화면 폭이 아니라 **사이드바 폭·탭 라벨 길이**에 달려 있어
 * 브레이크포인트로는 애초에 못 맞춘다. 실측이 정답이다.
 *
 * @param tabCount 탭 개수
 * @param activeIndex 활성 탭 인덱스. 없으면 `-1`
 */
export function useTabOverflow(tabCount: number, activeIndex: number): TabOverflow {
  const containerRef = useRef<HTMLElement | null>(null)
  const itemsRef = useRef<Map<number, HTMLElement>>(new Map())
  const moreRef = useRef<HTMLElement | null>(null)
  /** 첫 레이아웃에서 한 번 잰 폭. 접힌 뒤에는 다시 못 재므로 이것이 정본이다 */
  const widthsRef = useRef<readonly number[] | null>(null)
  const moreWidthRef = useRef(0)

  const [containerWidth, setContainerWidth] = useState(0)
  /**
   * 첫 측정 시도가 끝났는가. **폭이 0 이어서 못 잰 경우에도 true 다** — 「시도했다」가 기준이다.
   * 성공을 기준으로 두면 jsdom(폭 0)에서 영원히 false 라 트리거가 계속 렌더된다.
   */
  const [measured, setMeasured] = useState(false)

  const setContainer = useCallback((element: HTMLElement | null) => {
    containerRef.current = element
  }, [])

  const setItem = useCallback(
    (index: number) => (element: HTMLElement | null) => {
      if (element === null) itemsRef.current.delete(index)
      else itemsRef.current.set(index, element)
    },
    [],
  )

  const setMore = useCallback((element: HTMLElement | null) => {
    moreRef.current = element
  }, [])

  useLayoutEffect(() => {
    setMeasured(true)
    const container = containerRef.current
    if (container === null) return

    /**
     * 폭 하나를 받아 캐시를 채우고 상태를 올린다.
     *
     * 🛑 `width === 0` 이면 아무것도 하지 않는다 — 레이아웃이 없다는 뜻이고(jsdom 이 항상
     *    그렇다) 캐시에 0 이 박히면 그 뒤 계산이 전부 0 예산으로 굳는다.
     *    **다만 관찰은 폭과 무관하게 건다**(아래) — 첫 프레임에 폭이 0 이어도 나중에 생기면
     *    이 함수가 그때 불린다. 폭 0 에서 조기 반환하며 관찰까지 건너뛰면 그 마운트는
     *    영영 전량 가시로 굳어 회복할 길이 없다.
     */
    const capture = (width: number): void => {
      if (width === 0) return
      if (widthsRef.current === null && itemsRef.current.size === tabCount) {
        const gap = readColumnGap(container)
        widthsRef.current = Array.from({ length: tabCount }, (_, index) => {
          const element = itemsRef.current.get(index)
          return element === undefined ? 0 : element.offsetWidth + gap
        })
        moreWidthRef.current = (moreRef.current?.offsetWidth ?? 0) + gap
      }
      setContainerWidth(width)
    }

    capture(container.clientWidth)

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry !== undefined) capture(entry.contentRect.width)
    })
    observer.observe(container)
    return () => { observer.disconnect() }
  }, [tabCount])

  const visibleIndexes = computeVisibleTabIndexes({
    containerWidth,
    itemWidths: widthsRef.current ?? Array.from({ length: tabCount }, () => 0),
    moreWidth: moreWidthRef.current,
    activeIndex,
  })
  const visible = new Set(visibleIndexes)
  const hiddenIndexes = Array.from({ length: tabCount }, (_, index) => index).filter(
    (index) => !visible.has(index),
  )

  return {
    setContainer,
    setItem,
    setMore,
    visibleIndexes,
    hiddenIndexes,
    shouldRenderMore: !measured || hiddenIndexes.length > 0,
  }
}

/**
 * 컨테이너의 `column-gap` 을 px 로 읽는다.
 *
 * 상수로 박지 않는 이유는 Tailwind 클래스와 두 벌이 되기 때문이다 — 클래스만 바꾸면 계산이
 * 조용히 틀어진다. 읽지 못하면 0 으로 본다(간격을 무시하면 조금 일찍 접힐 뿐 깨지지 않는다).
 */
function readColumnGap(container: HTMLElement): number {
  const raw = getComputedStyle(container).columnGap
  const parsed = Number.parseFloat(raw)
  return Number.isFinite(parsed) ? parsed : 0
}
