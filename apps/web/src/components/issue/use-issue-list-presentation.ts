// 이슈 목록이 표시 방식(모달 ↔ 사이드패널)을 따르게 하는 훅 — 진입 분기 + URL 정리 (Jira 패리티 J1)
import { useEffect } from 'react'
import { useIssueDetailModalStore } from './issueDetailModalStore'

interface UseIssueListPresentationArgs {
  /** URL 의 `?selected=` — split view 페인에 열린 이슈 키 */
  selected: string | undefined
  /** `selected` 를 URL 에서 제거한다 (어댑터의 navigate) */
  clearSelected: () => void
}

/**
 * 목록에서 상세를 열 때 표시 방식을 적용하고, 전환 직후의 URL 을 정리한다.
 *
 * ## 왜 훅으로 빼나
 *
 * `IssueListRouteAdapter` 가 200줄 래칫 경계에 붙어 있다. 이 로직을 어댑터 안에 두면 경계를
 * 넘어 red 가 되고, 베이스라인을 올리는 것은 「더 긴 컴포넌트를 하나 더 승인한다」는 뜻이다.
 * 표시 방식은 어댑터의 다른 상태(필터·정렬·페이지)와 아무것도 공유하지 않아 떼어내는 데
 * 비용이 없다.
 *
 * ## 표시 방식이 가르는 것은 행 클릭뿐이다
 *
 * `selected` 가 URL 에 있다는 것은 **명시적 요청**이다 — 딥링크(북마크·공유)이거나 커서
 * 단축키 `j`/`k` 가 미리보기를 옮긴 것이다. 여기에 모달 선호를 얹으면 그 둘이 통째로 죽는다:
 * 딥링크는 빈 목록이 되고, 커서는 누를 때마다 모달이 튀어나온다.
 *
 * @returns `openViaPresentation` — 모달 선호면 그 이슈를 모달로 열고 `true`. 사이드바 선호면
 *   아무것도 하지 않고 `false` 를 돌려 호출부가 종전 `selected` 경로를 타게 한다.
 */
export function useIssueListPresentation({
  selected,
  clearSelected,
}: UseIssueListPresentationArgs): {
  openViaPresentation: (issueKey: string) => boolean
  /** 상세 모달이 떠 있는가 — 목록 커서 단축키를 끊는 데 쓴다 */
  detailModalOpen: boolean
} {
  const presentation = useIssueDetailModalStore((s) => s.presentation)
  const openKey = useIssueDetailModalStore((s) => s.openKey)
  const open = useIssueDetailModalStore((s) => s.open)
  const close = useIssueDetailModalStore((s) => s.close)

  /**
   * 사이드바 → 모달 즉시 전환 뒤의 URL 정리.
   *
   * ★`openKey === selected` 가 방아쇠다. 「모달 선호이고 selected 가 있다」만으로 지우면 커서가
   * 옮긴 `selected` 까지 매번 증발해 미리보기가 못 움직인다. 상세의 `⋯` 가 모달을 고를 때 같은
   * 키로 `open()` 을 부르므로, 두 값이 같아지는 순간이 곧 「이 이슈는 이제 모달이 들고 있다」는
   * 뜻이다 — 그때만 URL 을 비운다. 남겨 두면 뒤로가기 한 번에 페인이 되살아나 모달과 겹친다.
   */
  useEffect(() => {
    if (presentation !== 'modal' || selected === undefined || openKey !== selected) return
    clearSelected()
    // `clearSelected` 는 렌더마다 새로 만들어지는 지역 함수라 의존성에 넣으면 매 렌더 재실행된다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [presentation, selected, openKey])

  /**
   * 목록 페인이 열리면 전역 껍데기를 비운다 — 같은 이슈가 두 번 그려지는 것을 막는다.
   *
   * 사이드바 선호에서 전역 패널을 열어 둔 채 목록으로 이동해 커서 `j`/`k` 를 누르면 `selected`
   * 와 `openKey` 가 **둘 다** 살아난다. 그러면 split 페인과 전역 패널이 나란히 떠 상세가 화면에
   * 두 벌 그려진다 — 확률은 낮지만 막지 않으면 일어난다. 목록에 있는 동안은 페인이 그 자리를
   * 맡으므로 전역 쪽을 닫는 것이 옳다.
   *
   * 모달 선호는 대상이 아니다 — 그쪽은 위 useEffect 가 반대로 URL 을 비워 모달에 넘긴다.
   */
  useEffect(() => {
    if (presentation !== 'sidePanel' || selected === undefined || openKey === null) return
    close()
  }, [presentation, selected, openKey, close])

  return {
    detailModalOpen: openKey !== null,
    openViaPresentation: (issueKey: string): boolean => {
      if (presentation !== 'modal') return false
      open(issueKey)
      return true
    },
  }
}
