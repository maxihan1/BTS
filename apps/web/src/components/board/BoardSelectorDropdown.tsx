// 보드 스위처 드롭다운 — 보드 전환 + (선택) 생성 진입점 (FR-BD-01-2c · FR-BD-04 재사용 추출)
import type { JSX } from 'react'
import { useState } from 'react'
import { ChevronDown, Plus } from 'lucide-react'

import type { BoardSummary } from '@/api/boards'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { boardLabels } from '@/i18n/board-labels'
import { CreateBoardForm } from '@/components/board/CreateBoardForm'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'

/** 보드 스위처 props */
export interface BoardSelectorDropdownProps {
  /** 이 프로젝트의 보드 전량 — 1개여도 스위처는 렌더된다 */
  boards: BoardSummary[]
  /** 현재 보고 있는 보드 UUID */
  currentBoardId: string | undefined
  /** 보드가 속한 프로젝트 키 — 생성 폼과 권한 조회에 쓴다 */
  projectKey: string
  /** 다른 보드를 고를 때의 콜백 */
  onSelect: (id: string) => void
  /**
   * 생성 항목(「새 보드」 + 생성 다이얼로그) 노출 여부. 기본 `true` — 보드 화면의 기존 동작이다.
   *
   * **CREATE 권한과는 다른 축**이다. 권한은 「만들 수 있는가」이고 이 prop 은 「여기서 만드는가」다.
   * 보드를 고르기만 하는 자리(백로그 헤더)에서 `false` 로 끈다 — 그 화면에 「새 보드」가 딸려
   * 오면 사용자는 백로그가 보드를 만드는 자리라고 읽는다.
   */
  showCreate?: boolean
}

/**
 * 보드 스위처 — 보드가 1개여도 상시 노출되는 전환 드롭다운 (FR-BD-01-2c).
 *
 * - 트리거는 현재 보드 이름. 목록은 `DropdownMenuRadioGroup`(=`role="menuitemradio"`)이라
 *   「지금 어느 보드인가」가 선택 표시로 드러난다.
 * - CREATE 권한이 있을 때만 구분선 + 「새 보드」 항목을 **렌더한다**. 비활성이 아니라 부재다
 *   (Jira 근거 J5 · FR-BD-01-2d). 권한 조회가 아직 안 끝났으면 `=== true` 가 false 라 fail-closed 다.
 * - 권한을 prop 으로 받지 않고 여기서 직접 조회한다 — `useProjectPermissions` 는 캐시 키가 같아
 *   부모의 호출과 합쳐지므로 왕복이 늘지 않고, 「메뉴 항목을 가리는 조건」이 그 항목 옆에 남는다.
 *
 * 보드 라우트의 비-export 로컬이었던 것을 여기로 옮겼다 — 백로그 헤더가 같은 스위처를 쓰기
 * 때문이다(FR-BD-04). 옮기면서 더한 것은 `showCreate` 하나뿐이고 나머지는 동작 무변경이다.
 */
export function BoardSelectorDropdown({
  boards,
  currentBoardId,
  projectKey,
  onSelect,
  showCreate = true,
}: BoardSelectorDropdownProps): JSX.Element {
  const [createOpen, setCreateOpen] = useState(false)
  const { data: projectPermissions } = useProjectPermissions(projectKey)
  const canCreate: boolean = projectPermissions?.permissions.CREATE === true
  // 두 조건을 한 변수로 합쳐 둔다 — 항목과 다이얼로그가 같은 판정을 봐야 「메뉴에는 없는데
  // 창은 열 수 있는」 상태가 생기지 않는다.
  const showCreateItem: boolean = showCreate && canCreate

  const currentName: string =
    boards.find((b: BoardSummary) => b.boardId === currentBoardId)?.name ??
    boardLabels.boardSelectPlaceholder

  return (
    <div className="flex items-center gap-3">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            type="button"
            variant="outline"
            className="w-64 justify-between"
            aria-label={boardLabels.switcher.triggerAriaLabel(currentName)}
          >
            <span className="truncate">{currentName}</span>
            <ChevronDown aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        {/* 폭은 프리미티브가 트리거 폭(`--radix-dropdown-menu-trigger-width`)에 맞춘다 — 따로 주지 않는다 */}
        <DropdownMenuContent align="start">
          <DropdownMenuLabel>{boardLabels.switcher.groupLabel}</DropdownMenuLabel>
          <DropdownMenuRadioGroup value={currentBoardId ?? ''} onValueChange={onSelect}>
            {boards.map((b: BoardSummary) => (
              <DropdownMenuRadioItem key={b.boardId} value={b.boardId}>
                <span className="truncate">{b.name}</span>
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
          {showCreateItem && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                onSelect={() => {
                  setCreateOpen(true)
                }}
              >
                <Plus aria-hidden="true" />
                {boardLabels.switcher.createItem}
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* 생성 다이얼로그 — 빈 상태와 같은 `CreateBoardForm` 을 그대로 쓴다(신규 폼 없음) */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent aria-describedby={undefined}>
          <DialogHeader>
            <DialogTitle>{boardLabels.switcher.createDialogTitle}</DialogTitle>
          </DialogHeader>
          {/* 🛑 인트로를 끈다 — 그 2줄은 「보드가 없습니다」로 시작한다. 스위처에서 열었다는
              것은 보드가 이미 있다는 뜻이라 그 문장이 사실이 아니게 된다 (C1). */}
          {/* 🛑 닫힘은 `onCreated` 로만 온다 — 보드 **개수 변화**로 성공을 추론하면 목록 refetch 로
              남이 만든 보드가 들어올 때 입력 중이던 창이 닫혀 「만들어졌다」로 오독된다.
              실패하면 신호가 안 와 창이 열린 채 폼 안의 사유가 남는다. */}
          <CreateBoardForm
            projectKey={projectKey}
            showEmptyStateIntro={false}
            onCreated={() => {
              setCreateOpen(false)
            }}
          />
        </DialogContent>
      </Dialog>
    </div>
  )
}
