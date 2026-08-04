// 단축키 도움말 모달 — SHORTCUTS + CONTEXT_SHORTCUTS 단일 진실 출처를 그룹으로 렌더 (FR-UX-05 · FR-UX-10 F10/F11)
import type { JSX } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DEFAULT_KEYMAP, PALETTE_HELP_ITEM, SHORTCUTS, type Keymap } from './shortcuts'
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
  /**
   * effective 키맵(기본값 + 사용자 override 병합) — 전역 5종 표기에 쓴다.
   *
   * ★생략하면 `DEFAULT_KEYMAP` 으로 폴백한다. `SHORTCUTS[].keys` 를 그대로 쓰면
   * FR-PF-03 으로 재배치한 사용자에게 **없는 키를 광고**하게 된다(독립 리뷰 M-8).
   */
  readonly keymap?: Keymap
}

/**
 * key_combo 문자열(`c`, `g i`)을 `<kbd>` 시퀀스용 배열로 쪼갠다.
 *
 * leader combo 는 공백 1칸으로 두 토큰이 되고, single 은 한 토큰이다
 * (`shortcuts.ts` `parseKeyCombo` 와 같은 형식 규약).
 */
function keysOfCombo(keyCombo: string): readonly string[] {
  return keyCombo.split(' ')
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

/** 도움말 그룹 — 헤딩 하나와 그 아래 행 목록 */
interface HelpGroup {
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
}

/** 컨텍스트 단축키를 도움말 표기 형태로 변환한다 (단건 키 → 1칸 배열) */
function contextItems(context: ShortcutContext): readonly HelpItem[] {
  return CONTEXT_SHORTCUTS.filter((shortcut) => shortcut.context === context).map((shortcut) => ({
    keys: [shortcut.key],
    description: shortcut.description,
  }))
}

/**
 * 설명이 같은 행을 하나로 접고 키 표기만 이어 붙인다 — **한 동작에 열쇠가 둘**인 경우.
 *
 * `.` 은 `Cmd/Ctrl+K` 와 같은 팔레트를 여는 두 번째 열쇠다(스펙 §Jira 대조 3-b).
 * 두 행으로 두면 ① 같은 접근성 이름이 둘이라 텍스트 조회가 strict 위반으로 죽고
 * ② `key={item.description}` 이 React 에서 충돌한다(둘 다 실측). 같은 문구가 두 줄
 * 뜨는 것 자체가 Jira 패리티상 부자연스럽기도 하다.
 *
 * ★특정 키(`.`)를 이름으로 제외하지 않는다. "설명이 같으면 같은 동작"이라는 규칙으로
 * 접어야 별칭이 하나 더 늘어도 다음 사람이 같은 함정을 다시 밟지 않는다.
 *
 * @param items 접기 전 행 목록(레지스트리 순서)
 * @returns 첫 등장 순서를 유지한 채 별칭이 접힌 행 목록
 */
function mergeAliasRows(items: readonly HelpItem[]): readonly HelpItem[] {
  const keysByDescription = new Map<string, string[]>()
  for (const item of items) {
    const merged = keysByDescription.get(item.description)
    if (merged === undefined) keysByDescription.set(item.description, [...item.keys])
    else merged.push(...item.keys)
  }
  return [...keysByDescription].map(([description, keys]) => ({ description, keys }))
}

/**
 * 도움말 그룹을 만든다 — "이 키가 어디서 먹히는지"를 사용자 언어로 묶는다.
 *
 * 항목은 전부 레지스트리에서 파생한다. 하드코딩된 줄이 하나도 없으므로
 * **레지스트리에 없는 키는 표시될 방법이 없다** — 비구현 단축키를 "동작하는 것처럼"
 * 보여주지 않는다는 FR-UX-05 FR8 계약이 구조로 지켜진다.
 *
 * 상수가 아니라 함수인 이유는 전역 5종 표기가 **실효 키맵을 따라야** 하기 때문이다.
 * `SHORTCUTS[].keys` 는 정적 표기라 사용자 재배치를 반영하지 못한다.
 *
 * @param keymap effective 키맵(기본값 + 사용자 override 병합)
 */
function buildHelpGroups(keymap: Keymap): readonly HelpGroup[] {
  // ★순서는 **화면 계층 순**이다 — 어디서나(전역) → 이슈 목록 → 이슈 상세.
  // 사용자가 화면을 파고드는 순서라 "여기까지 오면 이 키가 더 생긴다"로 읽힌다
  // (`CONTEXT_LAYERS` 의 폴백 순서를 뒤집은 것과 같다).
  const groups: readonly HelpGroup[] = [
    {
      id: 'global',
      label: '어디서나',
      items: [
        ...SHORTCUTS.map((shortcut) => ({
          // ★`shortcut.keys`(정적 표기)가 아니라 실효 키맵을 읽는다 — 사용자가
          // 재배치했으면 그 키를 보여줘야 한다.
          keys: keysOfCombo(keymap[shortcut.action]),
          description: shortcut.description,
        })),
        PALETTE_HELP_ITEM,
        ...contextItems('app-shell'),
      ],
    },
    { id: 'issue-list', label: '이슈 목록에서', items: contextItems('issue-list') },
    { id: 'issue-detail', label: '이슈 상세에서', items: contextItems('issue-detail') },
  ]

  // 별칭 접기는 **모든 그룹**에 건다. 한 그룹에만 걸면 다음 별칭이 다른 그룹에 생겼을 때
  // 조용히 뚫린다 — "행 하나 = 설명 하나" 가 `key={item.description}` 의 전제다.
  return groups.map((group) => ({ ...group, items: mergeAliasRows(group.items) }))
}

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
 *   `PALETTE_HELP_ITEM`을 「어디서나」에 함께 싣는다. 같은 팔레트를 여는 두 번째
 *   열쇠 `.`(FR-UX-10 F11)는 **같은 행에 키 표기만 더해** 접는다(`mergeAliasRows`).
 * - 접근성(포커스 트랩·Esc 닫기·포커스 복원)은 radix-ui Dialog가 제공한다
 *   (ResolutionPickerModal 선례). 그룹은 `aria-labelledby`로 헤딩과 묶는다.
 */
export function ShortcutsHelpDialog({
  open,
  onOpenChange,
  keymap = DEFAULT_KEYMAP,
}: ShortcutsHelpDialogProps): JSX.Element {
  const helpGroups = buildHelpGroups(keymap)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>키보드 단축키</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          {helpGroups.map((group) => (
            <section key={group.id} aria-labelledby={`shortcut-group-${group.id}`}>
              {/*
                ★눈확인 반영 — 초안은 헤딩도 `text-muted-foreground` 라 항목 설명과 색이
                같아 "라벨"로 읽히지 않고 목록에 섞여 보였다(라이트/다크 양쪽). 전경색 +
                semibold 로 한 단계 올려 그룹 경계를 세운다.
              */}
              <h3
                id={`shortcut-group-${group.id}`}
                className="mb-2 text-xs font-semibold text-foreground"
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
