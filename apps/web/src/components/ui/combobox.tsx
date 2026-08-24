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
}: ComboboxProps): React.JSX.Element {
  const [open, setOpen] = React.useState(false)
  const selected = options.find((o) => o.value === value)

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
      <PopoverContent className="w-(--radix-popover-trigger-width) p-0">
        <Command>
          <CommandInput placeholder={placeholder} />
          <CommandList>
            <CommandEmpty>{emptyText}</CommandEmpty>
            <CommandGroup>
              {options.map((option) => (
                <CommandItem
                  key={option.value}
                  value={option.label}
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
                      <span className="text-xs text-(--text-subtle)">{option.description}</span>
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
