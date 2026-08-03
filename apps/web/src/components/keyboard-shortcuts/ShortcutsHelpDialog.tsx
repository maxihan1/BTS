// 단축키 도움말 모달 — SHORTCUTS + CONTEXT_SHORTCUTS 단일 진실 출처를 그룹으로 렌더 (FR-UX-05 · FR-UX-10 F10)
import type { JSX } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { PALETTE_HELP_ITEM, SHORTCUTS } from './shortcuts'
import { CONTEXT_SHORTCUTS, type ShortcutContext } from './context-shortcuts'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ShortcutsHelpDialog Props */
export interface ShortcutsHelpDialogProps {
  /** 모달 열림 여부 */
  readonly open: boolean
  /** 열림/닫힘 상태 변경 시 호출(Esc·오버레이 클릭·`?` 토글은 Radix/호출부가 발화) */
  readonly onOpenChange: (open: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 보조 컴포넌트 — 키 조합 표기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 단축키 키 배열을 `<kbd>` 시퀀스로 표기한다 (예: `['g', 'i']` → `g` `i`).
 *
 * @param keys 표기할 키 목록
 */
function ShortcutKeys({ keys }: { readonly keys: readonly string[] }): JSX.Element {
  return (
    <span className="flex gap-1">
      {keys.map((key, index) => (
        <kbd
          key={`${key}-${index}`}
          className="rounded border border-border bg-muted px-1.5 py-0.5 text-xs font-mono"
        >
          {key}
        </kbd>
      ))}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 그룹 구성 — 두 레지스트리를 화면 기준으로 묶는다 (단일 진실 출처 유지)
// ─────────────────────────────────────────────────────────────────────────────

/** 도움말 한 줄 — 두 레지스트리의 서로 다른 키 표기를 공통 형태로 맞춘다 */
interface HelpItem {
  readonly keys: readonly string[]
  readonly description: string
}

/** 컨텍스트 단축키를 도움말 표기 형태로 변환한다 (단건 키 → 1칸 배열) */
function contextItems(context: ShortcutContext): readonly HelpItem[] {
  return CONTEXT_SHORTCUTS.filter((shortcut) => shortcut.context === context).map((shortcut) => ({
    keys: [shortcut.key],
    description: shortcut.description,
  }))
}

/**
 * 도움말 그룹 — "이 키가 어디서 먹히는지"를 사용자 언어로 묶는다.
 *
 * 항목은 전부 레지스트리에서 파생한다. 하드코딩된 줄이 하나도 없으므로
 * 레지스트리에 없는 키(F11 상세 액션 8종)는 **표시될 방법이 없다**
 * — 비구현 단축키를 "동작하는 것처럼" 보여주지 않는다는 FR-UX-05 FR8 계약이
 * 구조로 지켜진다.
 */
const HELP_GROUPS: readonly {
  /**
   * `aria-labelledby` 로 헤딩과 묶을 때 쓸 ID 조각.
   *
   * ★한국어 라벨을 그대로 ID 에 넣으면 안 된다 — `aria-labelledby` 는 **공백으로
   * 구분된 ID 목록**이라 "이슈 목록에서" 같은 라벨은 ID 세 개로 해석되고, 그러면
   * 연결이 끊겨 `<section>` 이 접근성 트리에서 이름 없는 요소가 된다(스크린리더가
   * 그룹 이름을 못 읽는다). 공백 없는 ASCII 슬러그를 따로 둔다.
   */
  readonly id: string
  readonly label: string
  readonly items: readonly HelpItem[]
}[] = [
  {
    id: 'global',
    label: '어디서나',
    items: [
      ...SHORTCUTS.map((shortcut) => ({
        keys: shortcut.keys,
        description: shortcut.description,
      })),
      PALETTE_HELP_ITEM,
      ...contextItems('app-shell'),
    ],
  },
  {
    id: 'issue-list',
    label: '이슈 목록에서',
    items: contextItems('issue-list'),
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 키보드 단축키 도움말 모달.
 *
 * - 두 레지스트리(`SHORTCUTS` 전역 · `CONTEXT_SHORTCUTS` 화면별)를 그대로
 *   렌더한다 — 표기 drift 없는 단일 진실 출처(FR8). 레지스트리에 없는 키는
 *   자동으로 표시되지 않는다.
 * - **전체를 보여주되 그룹으로 나눈다.** 현재 화면 것만 걸러 보여주면 화면마다
 *   목록이 바뀌어 학습이 안 된다 — 어디서 무엇이 먹히는지를 라벨로 알린다.
 * - 명령 팔레트(`Cmd+K`, FR-UX-04)는 별도 훅이 처리하므로 표기 전용 항목
 *   `PALETTE_HELP_ITEM`을 「어디서나」에 함께 싣는다.
 * - 접근성(포커스 트랩·Esc 닫기·포커스 복원)은 radix-ui Dialog가 제공한다
 *   (ResolutionPickerModal 선례). 그룹은 `aria-labelledby`로 헤딩과 묶는다.
 */
export function ShortcutsHelpDialog({
  open,
  onOpenChange,
}: ShortcutsHelpDialogProps): JSX.Element {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>키보드 단축키</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          {HELP_GROUPS.map((group) => (
            <section key={group.id} aria-labelledby={`shortcut-group-${group.id}`}>
              <h3
                id={`shortcut-group-${group.id}`}
                className="mb-2 text-xs font-medium text-muted-foreground"
              >
                {group.label}
              </h3>
              <dl className="space-y-2">
                {group.items.map((item) => (
                  <div key={item.description} className="flex items-center justify-between gap-4">
                    <dt className="text-sm text-muted-foreground">{item.description}</dt>
                    <dd>
                      <ShortcutKeys keys={item.keys} />
                    </dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  )
}
