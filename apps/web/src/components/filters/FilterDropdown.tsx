// 필터 한 칸을 드롭다운 버튼으로 감싸는 껍데기 — 지라 기본 검색의 가로 필터 바 (Jira 패리티)
import type { JSX, ReactNode } from 'react'
import { useId } from 'react'
import { ChevronDown } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { filterBarLabels } from '@/i18n/filter-bar-labels'

interface FilterDropdownProps {
  /** 버튼에 보이는 필터 이름 — 「상태」 「담당자」 처럼 짧게 */
  label: string
  /** 선택 개수. 0 이면 배지를 그리지 않는다 */
  selectedCount: number
  /** 드롭다운 안에 들어갈 컨트롤 */
  children: ReactNode
  /** 콘텐츠 폭 — 컨트롤에 따라 넓혀 쓴다 */
  contentClassName?: string
}

/**
 * 필터 한 칸을 여닫는 드롭다운.
 *
 * ## 왜 필요했나
 *
 * 종전 필터 바는 상태·담당자·라벨·컴포넌트 **네 섹션을 전부 펼친 채** 세로로 쌓았다. 상태만
 * 체크박스 6줄이라 카드 하나가 화면 절반을 먹었고, 목록이 좁아지는 사이드패널 표시 방식에서는
 * 이슈 테이블이 접힘선 아래로 밀려났다. 지라 기본 검색은 같은 필터를 **가로 한 줄의 드롭다운**
 * 으로 두고 표를 바로 아래 붙인다.
 *
 * ## 트리거 이름을 안쪽 컨트롤과 다르게 둔다
 *
 * 트리거는 `「상태」 필터` 이고 안쪽 입력은 `담당자` 처럼 제 이름을 그대로 쓴다. 같은 이름을
 * 주면 드롭다운을 연 순간 접근성 이름이 겹쳐 `getByRole` 이 strict 위반으로 죽는다 —
 * e2e 224곳이 이름으로 컨트롤을 잡는 저장소라 그 충돌은 무관한 spec 을 함께 무너뜨린다.
 */
export function FilterDropdown({
  label,
  selectedCount,
  children,
  contentClassName,
}: FilterDropdownProps): JSX.Element {
  const countId = useId()

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="min-h-[44px] gap-1.5"
          aria-label={filterBarLabels.filter.dropdownAriaLabel(label)}
          // 개수는 **이름이 아니라 설명**으로 붙인다. `aria-label` 이 있으면 버튼 안 텍스트가
          // 접근성 이름에서 밀려나 배지가 통째로 사라지는데(화면 보는 사람만 아는 상태),
          // 이름에 개수를 섞으면 `getByRole({ name })` 로 트리거를 잡는 판정·e2e 가 선택 직후
          // 전부 깨진다. `aria-describedby` 는 이름을 건드리지 않고 개수만 읽어 준다.
          aria-describedby={selectedCount > 0 ? countId : undefined}
        >
          <span>{label}</span>
          {selectedCount > 0 && (
            <span
              id={countId}
              className="rounded-full bg-primary px-1.5 py-0.5 text-xs leading-none text-primary-foreground"
            >
              {selectedCount}
            </span>
          )}
          <ChevronDown className="size-3.5 opacity-60" aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className={contentClassName ?? 'w-64'}>
        {children}
      </PopoverContent>
    </Popover>
  )
}
