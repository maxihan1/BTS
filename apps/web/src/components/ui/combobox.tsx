// 검색 가능한 단일 선택 combobox — command(검색) + popover(띄우기) 합성
import * as React from 'react'
import { CheckIcon, ChevronsUpDownIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Popover, PopoverContent, PopoverTrigger } from './popover'
import { Command, CommandInput, CommandList, CommandEmpty, CommandGroup, CommandItem } from './command'
import { Button } from './button'

/** combobox 한 항목 */
interface ComboboxOption {
  /** 콜백으로 돌려줄 값 */
  value: string
  /** 사람이 읽는 라벨 — 검색 대상이자 표시 문자열 */
  label: string
  /** 라벨 아래 보조 설명 (선택) */
  description?: string
}

interface ComboboxProps {
  /** 고를 수 있는 항목 */
  options: ComboboxOption[]
  /** 지금 고른 값. 없으면 null */
  value: string | null
  /** 고를 때 — 값을 준다 */
  onChange: (value: string) => void
  /** 트리거 button 의 접근성 이름. 화면에서 고유해야 한다 */
  ariaLabel: string
  /** 검색 입력 placeholder */
  placeholder: string
  /** 일치가 없을 때 문구 */
  emptyText: string
  /** 값이 없을 때 트리거에 보여줄 문구 */
  triggerPlaceholder: string
  /** 잠금 */
  disabled?: boolean
  /**
   * popover 를 그릴 컨테이너. **다이얼로그 안에서 쓸 때 반드시 준다** — 생략하면
   * `document.body` 에 그려지고 열린 다이얼로그가 그것을 `aria-hidden` 으로 덮어
   * 옵션이 접근성 트리에서 사라진다.
   */
  container?: HTMLElement | null
}

/**
 * 검색해서 하나를 고른다.
 *
 * `command.tsx`(cmdk)와 `popover.tsx` 를 합성한다 — `jira-parity-contract.md` §4 재사용 자산
 * 레지스트리가 이 둘을 이미 가리키고 있어 새 검색 UI 를 만들지 않는다.
 *
 * 트리거에 **라벨을 보여준다.** `value` 를 그대로 그리면 사용자가 `in-progress` 같은 키를
 * 읽게 된다 — 고른 것과 보이는 것이 어긋나는 자리다.
 */
function Combobox({
  options,
  value,
  onChange,
  ariaLabel,
  placeholder,
  emptyText,
  triggerPlaceholder,
  disabled = false,
  container,
}: ComboboxProps): React.JSX.Element {
  const [open, setOpen] = React.useState(false)
  const selected = options.find((o) => o.value === value)
  // 설명 요소 id 접두 — 한 화면에 combobox 가 여럿이어도 id 가 겹치지 않게 인스턴스마다 다르다
  const descPrefix = `${React.useId()}-desc-`

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          role="combobox"
          aria-label={ariaLabel}
          aria-expanded={open}
          disabled={disabled}
          className="w-full justify-between font-normal"
        >
          <span className={cn(selected === undefined && 'text-(--text-subtle)')}>
            {selected?.label ?? triggerPlaceholder}
          </span>
          <ChevronsUpDownIcon aria-hidden="true" className="size-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent container={container} className="w-(--radix-popover-trigger-width) p-0">
        <Command>
          <CommandInput placeholder={placeholder} />
          <CommandList>
            <CommandEmpty>{emptyText}</CommandEmpty>
            <CommandGroup>
              {options.map((option) => (
                <CommandItem
                  key={option.value}
                  value={option.label}
                  // ★ 접근성 이름을 **라벨로 고정한다.** 그냥 두면 이름이 「라벨 + 설명」으로
                  // 합쳐져 설명이 있는 항목만 셀렉터가 어긋난다 — 설명 없는 항목은 통과하고
                  // 있는 항목만 못 찾는 것을 실측했다. E2E 가 이름으로 항목을 집으므로
                  // 이름은 예측 가능해야 한다(§2 즉사 계약). 설명은 `aria-describedby` 로
                  // 남겨 스크린리더가 여전히 읽는다.
                  aria-label={option.label}
                  aria-describedby={option.description !== undefined ? `${descPrefix}${option.value}` : undefined}
                  onSelect={() => {
                    onChange(option.value)
                    setOpen(false)
                  }}
                >
                  <CheckIcon
                    aria-hidden="true"
                    className={cn('size-4', option.value === value ? 'opacity-100' : 'opacity-0')}
                  />
                  <span className="flex flex-col">
                    <span>{option.label}</span>
                    {option.description !== undefined ? (
                      <span id={`${descPrefix}${option.value}`} className="text-xs text-(--text-subtle)">
                        {option.description}
                      </span>
                    ) : null}
                  </span>
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}

export { Combobox }
export type { ComboboxProps, ComboboxOption }
