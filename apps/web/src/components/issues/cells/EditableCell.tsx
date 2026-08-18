// 이슈 목록의 편집 가능 셀 공통 래퍼 — hover 어포던스·행 클릭 전파 차단·popover (FR-UX-11 F9)
import type { JSX, ReactNode } from 'react'
import { useState } from 'react'
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover'

/**
 * popover 안 선택지에 붙이는 공통 크기 클래스 (디자인 리뷰 Pass 6, Maxi 확정 2026-08-04).
 *
 * 마우스는 28px(`h-7`, 목록 밀도 유지), **손가락은 44px**. `pointer: coarse` 는 정밀 포인터가
 * 없는 입력(터치)에서만 참이라 데스크톱을 건드리지 않는다. 상세 화면 `IssueAssigneeSelect` 가
 * 이미 `min-h-[44px]` 계약을 갖고 있어 일관성도 맞는다.
 *
 * `pointer-coarse:` variant 는 Tailwind 4.3.0 에 실재한다 — 실측으로 `@media (pointer: coarse)`
 * 규칙이 생성되는 것을 확인했다. 없는 variant 는 조용히 무시돼 44px 가 적용되지 않는다.
 */
export const CELL_OPTION_CLASS = 'w-full justify-start pointer-coarse:min-h-[44px]'

/** EditableCell props */
export interface EditableCellProps {
  /** 트리거의 동작 이름 — 셀마다 고유해야 e2e strict mode 충돌이 없다. 예: `ATLAS-1 담당자 변경` */
  label: string
  /**
   * 현재 값의 **읽을 수 있는 표기** — 접근성 이름에 함께 실린다 (리뷰 C4).
   *
   * ★`aria-label` 은 자식 텍스트를 **덮는다.** 값만 그리던 `<span>` 을 트리거로 감싸면서
   * `label` 만 이름으로 쓰면, 화면낭독기에는 "ATLAS-1 담당자 변경" 만 들리고 **실제 값이
   * 사라진다**. main 의 평범한 `<span>` 에서는 값이 읽혔으므로 회귀다(NFR4).
   *
   * **선택이 아니라 필수로 둔다.** 빠뜨려도 화면에는 아무 표시가 없어 눈으로 못 잡는다 —
   * 타입이 유일한 방어다.
   */
  valueLabel: string
  /** 닫힌 상태에서 보이는 내용 (배지·텍스트 등). role 을 가진 노드를 그대로 넣을 수 있다 */
  display: ReactNode
  /** popover 내용. **열렸을 때만 마운트된다** (FR12 — 전환·권한 조회를 지연시키는 장치) */
  children: ReactNode
  /**
   * 제어형 열림 상태. 미전달이면 자체 관리한다.
   *
   * 제어형이 필요한 이유 둘 — ① 저장 성공 시 **코드로 닫는다**(Maxi 확정 2026-08-04)
   * ② 종료 전환은 popover 를 닫고 결의안 모달로 넘긴다(FR14).
   */
  open?: boolean
  /** 제어형일 때 열림 상태 변경 콜백 */
  onOpenChange?: (open: boolean) => void
}

/**
 * 편집 가능 셀 래퍼.
 *
 * - **행 클릭 전파 차단**. `IssueTable.tsx` 는 `<TableRow onClick>` 으로 행 전체를 상세로
 *   보낸다. 편집 트리거의 클릭이 거기까지 올라가면 편집과 이동이 동시에 일어난다.
 *   체크박스(`IssueTable.tsx`)·키 링크(`issue-columns.ts`)가 쓰는 것과 같은 처방이다.
 * - **닫힘 시 자식 미마운트**. Radix `PopoverContent` 는 닫히면 언마운트되므로 자식이 가진
 *   조회 훅도 돌지 않는다. 이것이 D-3(셀 열 때만 조회)의 실제 이행 수단이다.
 * - **어포던스**. hover·focus 시 테두리를 띄워 "여기는 바꿀 수 있다" 를 클릭 전에 알린다(FR2).
 *   색은 ADS 토큰만 쓴다(NFR5).
 *
 * @param props 라벨·표시 내용·popover 내용과 선택적 제어형 열림 상태
 * @returns popover 로 감싼 편집 가능 셀
 */
export function EditableCell({
  label,
  valueLabel,
  display,
  children,
  open: controlledOpen,
  onOpenChange,
}: EditableCellProps): JSX.Element {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false)
  const open = controlledOpen ?? uncontrolledOpen
  const setOpen = onOpenChange ?? setUncontrolledOpen

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        // ★값과 동작을 **둘 다** 이름에 담는다 (리뷰 C4). `aria-label` 은 자식 텍스트를
        // 덮으므로 여기에 값을 싣지 않으면 화면낭독기에서 셀 값이 통째로 사라진다.
        // e2e 는 `^{key} {field} 변경, 현재 ` 앵커 정규식으로 잡는다 — 값이 바뀌어도
        // 로케이터가 살아 있도록 **접두는 고정**이다.
        aria-label={`${label}, 현재 ${valueLabel}`}
        // ★토큰명 주의 — `--border-default` 는 **존재하지 않는다**(index.css 실측). 실제 이름은
        // `--border`(라이트 #DCDFE4 · 다크 #2C333A). 없는 토큰을 쓰면 ring 색이 비어 hover
        // 어포던스(FR2)가 조용히 사라진다 — 디자인 리뷰 Pass 5 가 잡은 실버그.
        //
        // 🛑 `select-text` 를 지우지 마라 — 장식이 아니다. `PopoverTrigger` 는 `<button>` 으로
        //    렌더되고, `<button>` 에서 `user-select: auto` 는 CSS UI 규격상 **none 으로
        //    해석**된다(Chromium 실측). 셀을 트리거로 감싼 순간 그 셀 텍스트를 **드래그 복사할
        //    수 없게 되는** 회귀가 생긴다 — F8 이 이슈 제목에서 실제로 겪고 같은 클래스로
        //    막았다(`issues.$key.tsx:741`). 스펙 §시각 검증 7번이 이 항목이다.
        className="w-full select-text rounded px-1 text-left ring-1 ring-transparent hover:ring-(--border) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        onClick={(event) => event.stopPropagation()}
      >
        {display}
      </PopoverTrigger>
      {/* onClick 전파 차단 — popover 내용은 DOM 상 행 밖(portal)이지만 React 합성 이벤트는
          트리거 기준으로 버블링하므로 내용 클릭도 행까지 올라간다. */}
      <PopoverContent
        className="w-64 p-2"
        onClick={(event) => event.stopPropagation()}
        // ★편집 중에는 목록 단축키를 막는다 (E10).
        //
        // `shortcuts.ts` 의 `shouldIgnoreEvent` 는 `isComposing` · 수식키 ·
        // `isEditableTarget`(input/textarea/select/contentEditable)만 본다. popover 안에서
        // 포커스가 **버튼**에 있으면 편집 요소가 아니라 `j`/`k`(F10 커서 이동)가 그대로
        // 발동해 편집 도중 목록 커서가 움직이고 상세가 바뀐다. 담당자 셀의 검색 `<input>`
        // 은 우연히 안전하지만 우선순위·상태 셀은 아니다 (2026-08-04 실측).
        //
        // 발화 지점(`useKeyboardShortcuts.ts:186`)이 **bubble 단계** document 리스너라
        // 여기서 끊으면 도달하지 않는다. React 합성 `stopPropagation` 은 네이티브
        // `stopPropagation` 도 함께 호출한다.
        //
        // 🛑 `Escape` 는 **통과시킨다.** Radix `DismissableLayer` 가 document keydown 으로
        //    닫기를 처리하므로 막으면 popover 가 Esc 로 닫히지 않아 E2·FR4 가 깨진다.
        onKeyDown={(event) => {
          if (event.key !== 'Escape') event.stopPropagation()
        }}
      >
        {children}
      </PopoverContent>
    </Popover>
  )
}
