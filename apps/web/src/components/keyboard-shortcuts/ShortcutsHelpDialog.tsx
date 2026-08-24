// 단축키 도움말 모달 — SHORTCUTS + CONTEXT_SHORTCUTS 단일 진실 출처를 그룹으로 렌더 (FR-UX-05 · FR-UX-10 F10/F11)
import { Fragment, type JSX } from 'react'
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
 * 별칭 구분자 — 「Cmd/Ctrl K **또는** .」.
 *
 * 순차 입력(`g` 다음 `i`)과 **대안**(둘 중 아무거나)을 눈으로 가른다. 칩만 나란히 두면
 * `g` `i` 와 모양이 같아 "Cmd → K → . 순서로 누른다"로 읽힌다(눈확인 실측).
 * 색만 흐리게 하는 안은 기각됐다 — 연한 회색은 「못 쓰는 키」로 오해되고, 색만으로
 * 의미를 전하면 색약자·스크린리더에 닿지 않는다. 그래서 **텍스트**로 넣어 낭독 순서가
 * 그대로 "…K 또는 ." 이 되게 하고, 톤만 한 단계 낮춘다.
 *
 * ★i18n 실측 — 이 모듈은 사용자 문자열을 인라인으로 둔다. 모달 제목·그룹 라벨·설명 21종이
 * 전부 인라인이고 `src/i18n/` 에 keyboard-shortcuts 라벨 모듈이 없다(e2e 도 "하드코딩
 * 선례"로 문서화). 한 문자열만 `ko.ts` 로 빼면 모듈이 반쪽만 이주해 오히려 drift 가 된다.
 */
const ALIAS_SEPARATOR = '또는'

/**
 * 단축키 표기를 `<kbd>` 시퀀스로 그린다 (예: `[['g', 'i']]` → `g` `i`).
 *
 * 대안이 둘 이상이면 사이에 [ALIAS_SEPARATOR] 를 끼운다
 * (`[['Cmd/Ctrl', 'K'], ['.']]` → `Cmd/Ctrl` `K` 또는 `.`).
 *
 * @param keyGroups 대안 목록. 각 원소는 키 시퀀스 하나
 */
function ShortcutKeys({
  keyGroups,
}: {
  readonly keyGroups: readonly (readonly string[])[]
}): JSX.Element {
  return (
    <span className="flex items-center gap-1">
      {keyGroups.map((keys, groupIndex) => (
        <Fragment key={`group-${groupIndex}`}>
          {groupIndex > 0 && (
            <span className="text-xs text-muted-foreground">{ALIAS_SEPARATOR}</span>
          )}
          {keys.map((key, index) => (
            <kbd
              key={`${key}-${index}`}
              className="rounded border border-border bg-muted px-1.5 py-0.5 text-xs font-mono"
            >
              {key}
            </kbd>
          ))}
        </Fragment>
      ))}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 그룹 구성 — 두 레지스트리를 화면 기준으로 묶는다 (단일 진실 출처 유지)
// ─────────────────────────────────────────────────────────────────────────────

/** 도움말 한 줄(접기 전) — 두 레지스트리의 서로 다른 키 표기를 공통 형태로 맞춘다 */
interface HelpItem {
  readonly keys: readonly string[]
  readonly description: string
}

/** 도움말 한 줄(접힌 뒤) — 같은 동작을 여는 **대안**들을 나눠 들고 있다 */
interface HelpRow {
  /**
   * 대안 목록. 각 원소가 키 시퀀스 하나다.
   *
   * `[['g','i']]` = 대안 1개(순차 입력) · `[['Cmd/Ctrl','K'], ['.']]` = 대안 2개.
   * ★대안을 **평평한 배열로 합치지 않는 이유**가 여기다 — 합치면 순차 입력과 구별할
   * 정보가 사라져 구분자를 넣을 자리를 잃는다.
   */
  readonly keyGroups: readonly (readonly string[])[]
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
  readonly items: readonly HelpRow[]
}

/** 컨텍스트 단축키를 도움말 표기 형태로 변환한다 (단건 키 → 1칸 배열) */
function contextItems(context: ShortcutContext): readonly HelpItem[] {
  return CONTEXT_SHORTCUTS.filter((shortcut) => shortcut.context === context).map((shortcut) => ({
    keys: [shortcut.key],
    description: shortcut.description,
  }))
}

/**
 * 설명이 같은 행을 하나로 접고 키 표기를 **대안으로 나란히** 든다 — 한 동작에 열쇠가 둘인 경우.
 *
 * `.` 은 `Cmd/Ctrl+K` 와 같은 팔레트를 여는 두 번째 열쇠다(스펙 §Jira 대조 3-b).
 * 두 행으로 두면 ① 같은 접근성 이름이 둘이라 텍스트 조회가 strict 위반으로 죽고
 * ② `key={item.description}` 이 React 에서 충돌한다(둘 다 실측). 같은 문구가 두 줄
 * 뜨는 것 자체가 Jira 패리티상 부자연스럽기도 하다.
 *
 * ★특정 키(`.`)를 이름으로 제외하지 않는다. "설명이 같으면 같은 동작"이라는 규칙으로
 * 접어야 별칭이 하나 더 늘어도 다음 사람이 같은 함정을 다시 밟지 않는다.
 *
 * ★대안은 **한 배열로 합치지 않고 그룹째 쌓는다**. 렌더가 그룹 경계에서만 구분자
 * (「또는」)를 넣기 때문이며, 그래서 대안이 1개인 행은 표기가 예전 그대로다.
 *
 * @param items 접기 전 행 목록(레지스트리 순서)
 * @returns 첫 등장 순서를 유지한 채 별칭이 접힌 행 목록
 */
function mergeAliasRows(items: readonly HelpItem[]): readonly HelpRow[] {
  const groupsByDescription = new Map<string, (readonly string[])[]>()
  for (const item of items) {
    const merged = groupsByDescription.get(item.description)
    if (merged === undefined) groupsByDescription.set(item.description, [item.keys])
    else merged.push(item.keys)
  }
  return [...groupsByDescription].map(([description, keyGroups]) => ({ description, keyGroups }))
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
  const groups: readonly (Omit<HelpGroup, 'items'> & { readonly items: readonly HelpItem[] })[] = [
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
        // ★`sidebar-drawer` 를 별도 그룹으로 빼지 않는다 — 모바일에서만 살아 있는 한 줄이라
        //   전용 섹션을 두면 데스크톱 사용자에게 빈 그룹처럼 읽힌다. 전역 그룹에 함께 싣는다.
        //   🛑 새 `ShortcutContext` 를 만들면 여기에도 실어야 한다 — 안 실으면 그 키가 도움말에서
        //      조용히 사라진다. 짝 판별식 = 이 파일 테스트의 「모든 컨텍스트가 어느 그룹엔가 실린다」.
        ...contextItems('sidebar-drawer'),
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
                      <ShortcutKeys keyGroups={item.keyGroups} />
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
