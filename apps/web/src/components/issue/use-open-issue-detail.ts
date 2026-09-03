// 이슈 상세 진입점 13곳이 공유하는 열기 핸들러 — 모달로 열되 새 탭/딥링크는 그대로 둔다 (J1)
import type { MouseEvent } from 'react'
import { useCallback } from 'react'
import { useIssueDetailModalStore } from './issueDetailModalStore'

/**
 * 상세를 여는 클릭 핸들러를 만든다.
 *
 * ## 왜 훅으로 묶나
 *
 * 상세 진입점이 13곳이다 — 보드 카드 · 백로그 카드 · 목록 셀 · 인박스 · 프로젝트 활동 피드 ·
 * 대시보드 가젯 · 최근 항목 · 커맨드 팔레트 · 캘린더 · 상단바 · 에픽 자식 · 링크 그래프.
 * 각자 modifier 검사를 손으로 쓰면 한 곳만 빠뜨려도 **거기서만 새 탭이 안 열린다** — 사용자는
 * 그 화면이 고장 났다고 느끼고, 리뷰는 13곳 중 하나가 다르다는 것을 잘 못 본다.
 *
 * ## 새 탭·딥링크를 지키는 규칙
 *
 * Jira 원문(J1)이 명시한다 — "you can continue to use Cmd or Ctrl + click, or right-click to
 * open it in a new tab". 그래서 **`<Link>` 의 `href` 를 없애지 않는다.** 아래 조건 중 하나라도
 * 맞으면 `preventDefault` 를 하지 않고 브라우저 기본 동작(새 탭/새 창)에 넘긴다.
 *
 * | 조건 | 의미 |
 * |---|---|
 * | `metaKey`(⌘) · `ctrlKey` | 새 탭 |
 * | `shiftKey` | 새 창 |
 * | `altKey` | 다운로드/기타 브라우저 동작 |
 * | `button !== 0` | 가운데 클릭(새 탭) · 우클릭(컨텍스트 메뉴) |
 * | `defaultPrevented` | 앞선 핸들러가 이미 처리했다 (예: 드래그 중인 보드 카드) |
 *
 * 그 외의 평범한 좌클릭만 모달로 가로챈다.
 *
 * @returns `(issueKey, event) => void` — `<Link onClick>` 에 그대로 물린다
 */
export function useOpenIssueDetail(): (issueKey: string, event: MouseEvent) => void {
  const open = useIssueDetailModalStore((s) => s.open)

  return useCallback(
    (issueKey: string, event: MouseEvent) => {
      if (
        event.defaultPrevented ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey ||
        event.button !== 0
      ) {
        return
      }
      event.preventDefault()
      open(issueKey)
    },
    [open],
  )
}
