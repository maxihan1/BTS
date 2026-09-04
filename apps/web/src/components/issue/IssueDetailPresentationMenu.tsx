// 상세 헤더의 표시 방식 `⋯` 메뉴 — 모달 ↔ 사이드바 토글 (Jira 패리티 J1)
import type { JSX } from 'react'
import { MoreHorizontal } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { issueDetailStrings } from '@/i18n/ko'
import { useIssueDetailModalStore } from './issueDetailModalStore'

interface IssueDetailPresentationMenuProps {
  /** 지금 보고 있는 이슈 키 — 모달로 되돌릴 때 그대로 넘긴다 */
  issueKey: string
}

/**
 * 상세를 모달로 볼지 사이드바로 볼지 고르는 `⋯` 메뉴.
 *
 * Jira 원문이 이 자리를 못 박는다 — "revert to sidebar by opening an issue in the modal,
 * clicking the '…' and selecting 'Open issues in sidebar'".
 *
 * ## 왜 별도 파일인가
 *
 * `IssueDetailPage` 는 이미 1,000줄을 넘어 래칫에 동결돼 있다. 메뉴를 그 안에 인라인으로 두면
 * 같은 함수가 40줄 더 길어져 래칫이 red 가 되고, 베이스라인을 올리는 것은 「더 긴 컴포넌트를
 * 하나 더 승인한다」는 뜻이다. 토글은 상세 본문과 아무 상태도 공유하지 않으므로 떼어내는 데
 * 비용이 없다 — 필요한 것은 이슈 키 하나뿐이다.
 *
 * ## 전체화면에는 달지 않는다
 *
 * 호출부(`variant === 'pane'`)가 그 판단을 쥔다. 전체화면은 모달도 사이드바도 아니라서, 거기서
 * 표시 방식을 바꿔도 지금 보고 있는 화면은 그대로다 — 무엇이 바뀌었는지 알 수 없다.
 */
export function IssueDetailPresentationMenu({
  issueKey,
}: IssueDetailPresentationMenuProps): JSX.Element {
  const presentation = useIssueDetailModalStore((s) => s.presentation)
  const setPresentation = useIssueDetailModalStore((s) => s.setPresentation)
  const openInModal = useIssueDetailModalStore((s) => s.open)

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={issueDetailStrings.presentationMenuAriaLabel}
        >
          <MoreHorizontal className="size-4" aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem
          onSelect={() => {
            const next = presentation === 'modal' ? 'sidePanel' : 'modal'
            setPresentation(next)
            // 모달로 갈 때는 이 이슈를 모달에 넘겨야 한다 — split view 에서 골랐다면 열림
            // 상태가 URL(`selected`)에만 있어서, 넘기지 않으면 페인이 닫히는 순간 상세가
            // 화면에서 그냥 사라진다. 사이드바 방향은 넘기지 않는다: 이미 페인으로 보고 있는데
            // 전역 패널까지 뜨면 같은 이슈가 두 번 그려진다.
            if (next === 'modal') openInModal(issueKey)
          }}
        >
          {presentation === 'modal'
            ? issueDetailStrings.openInSidePanelItem
            : issueDetailStrings.openInModalItem}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
