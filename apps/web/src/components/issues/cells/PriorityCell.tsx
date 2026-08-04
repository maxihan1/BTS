// 이슈 목록 우선순위 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import { useState } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import type { IssueResponse } from '@/api/issues'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
// 필드 단위 편집가부 판정 정본 — 복제하지 않고 재사용한다.
// `meta/IssueCustomFieldsEdit.tsx:12` 가 같은 방식으로 값 import 하는 선례가 있다.
import { isFieldDisabled } from '@/components/issue/IssueMetaPanel'
import { useIssueListCellField } from '@/hooks/use-issue-list-cell-field'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { CELL_OPTION_CLASS, EditableCell } from './EditableCell'

/** 선택 가능한 우선순위 (1=가장 높음 ~ 5=가장 낮음) — IssuePrioritySelect 와 동일 집합 */
const PRIORITIES = [1, 2, 3, 4, 5] as const

/** `PRIORITIES` 의 원소 타입 — 라벨 정본(`priorityNames`)의 키 타입과 같다 */
type PriorityValue = (typeof PRIORITIES)[number]

/**
 * 우선순위 값이 라벨 정본의 키 범위(1~5)인지 좁힌다.
 *
 * `IssueResponse.priority` 의 TS 타입은 `number` 라서(zod `min(1).max(5)` 는 런타임 검증이라
 * 리터럴 유니온으로 좁혀지지 않는다) 그대로는 `priorityNames` 를 인덱싱할 수 없다.
 * `as Record<number, string>` 캐스팅 대신 명시적 가드를 쓴다 — 캐스팅은 정본에 없는 값이
 * 들어와도 조용히 통과시킨다.
 *
 * @param value 검사할 우선순위 값
 * @returns 라벨 정본의 키면 true
 */
function isPriorityValue(value: number): value is PriorityValue {
  return Number.isInteger(value) && value >= 1 && value <= 5
}

/**
 * 우선순위 값을 화면 표기로 바꾼다.
 *
 * ★표기 정본은 `issueDetailStrings.priorityNames` **하나**다 (Maxi 확정 2026-08-04).
 * 백엔드 `priorityName`(`Medium`·`High` 등 영어)을 화면에 직접 쓰지 않는다 — 그러면 같은
 * 셀이 닫힘/열림에 따라 다른 언어로 보이고, 목록·popover·이슈 상세가 서로 다른 말을 한다.
 *
 * **하류 이득.** 다국어(i18n)를 넣을 때 갈아끼울 지점이 `i18n/ko.ts` 한 곳으로 모인다.
 * 영어 원문을 화면에 섞어 두면 나중에 찾아 고칠 자리가 컴포넌트마다 흩어진다.
 *
 * export 하지 않는다 — 이 파일은 컴포넌트 모듈이라 함수를 내보내면 react-refresh 경고가
 * 나고(lint-staged 는 `--max-warnings 0`) 표기 해석이 밖으로 새어 정본이 둘이 된다.
 * 검증은 {@link PriorityCellDisplay} 렌더 결과로 한다(내부가 아니라 동작을 잰다).
 *
 * @param priority 우선순위 1(가장 높음)~5(가장 낮음)
 * @returns 한국어 표기. 정본 범위를 벗어난 값이면 숫자를 그대로 보인다(값을 숨기지 않는다)
 */
function resolvePriorityLabel(priority: number): string {
  return isPriorityValue(priority) ? issueDetailStrings.priorityNames[priority] : String(priority)
}

/**
 * 닫힌 상태에서 보이는 우선순위 텍스트.
 *
 * 마크업(`<span>` + `text-(--text-default)`)은 `issue-columns.ts` 의 기존 `renderPriorityCell`
 * 그대로다. 편집 비활성(`ctx.edit` 부재) 경로도 이 컴포넌트를 소비하므로 두 경로가 같은
 * DOM 을 낸다 — 이것이 회귀 0 의 장치다. **표기만** 백엔드 영어에서 한국어 정본으로 바뀌었다.
 *
 * `priorityName`(문자열)이 아니라 `priority`(숫자)를 받는 이유 — 라벨 조회를 이 컴포넌트
 * 안에 두어야 정본이 하나로 유지된다. 호출부(`issue-columns.ts`)가 미리 해석해 넘기면
 * 순수 컬럼 정의 모듈에 i18n 결합이 새어 들어가고, 형제 `PriorityCellEditor`(`value: number`)
 * 와도 모양이 어긋난다.
 *
 * @param props 표시할 우선순위 값 1~5
 * @returns 우선순위 텍스트 span
 */
export function PriorityCellDisplay({ priority }: { priority: number }): JSX.Element {
  return <span className="text-(--text-default)">{resolvePriorityLabel(priority)}</span>
}

/** PriorityCellEditor props */
export interface PriorityCellEditorProps {
  /** 현재 우선순위 — props 파생, useState 초기화 금지 (stale state 회귀 방지) */
  value: number
  /** 수정 권한 여부 — false 면 전 선택지 disabled (fail-closed) */
  canEdit: boolean
  /** 저장 진행 중 — true 면 중복 제출을 막기 위해 disabled (NFR3) */
  isSaving: boolean
  /** 우선순위 변경 콜백 — number 전달 */
  onChange: (priority: number) => void
}

/**
 * popover 안에 뜨는 우선순위 선택 목록.
 *
 * 상세 화면의 `IssuePrioritySelect` 는 `min-h-[44px]`·`w-full` 네이티브 `<select>` 라
 * 목록 셀 popover 안에서는 과하다. 값 집합(1~5)과 라벨(`priorityNames`)은 **같은 정본**을
 * 쓰되 표현만 목록에 맞춘다.
 *
 * 조립(`EditableCell` 로 감싸기)은 `IssueColumnRenderContext` 가 확정된 뒤 붙인다 —
 * 이 컴포넌트는 순수 프레젠테이션이라 조회 훅을 갖지 않는다.
 *
 * @param props 현재 값 · 권한 · 저장 상태 · 변경 콜백
 * @returns 우선순위 선택 버튼 목록
 */
export function PriorityCellEditor({
  value,
  canEdit,
  isSaving,
  onChange,
}: PriorityCellEditorProps): JSX.Element {
  return (
    <div className="flex flex-col gap-0.5">
      {!canEdit && <p className="px-2 py-1 text-xs text-(--text-subtle)">편집 권한이 없습니다.</p>}
      {PRIORITIES.map((p) => (
        <Button
          key={p}
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canEdit || isSaving}
          // 🛑 `aria-current` 를 지우지 마라 — 아래 체크 표시는 **추가**이지 대체가 아니다.
          //    시각 표기만 남기면 스크린리더 경로가 사라진다.
          aria-current={p === value ? 'true' : undefined}
          onClick={() => onChange(p)}
          className={CELL_OPTION_CLASS}
        >
          {/*
            현재 값 표기 (QA F1). `aria-current` 만으로는 **눈으로 볼 수 없어** 스크린리더
            사용자만 지금 값을 알았다.

            ★`ProjectSwitcher.tsx:160` 의 체크 관례를 그대로 쓴다 — 같은 저장소의 popover
            선택 목록 자산이고(`jira-parity-contract` §4), `ui/select.tsx:119` 의 Radix
            `ItemIndicator` 도 같은 모양이다. Jira 역시 현재 값에 체크를 단다.

            **항상 렌더하고 투명도만 토글한다.** 조건부로 넣고 빼면 현재 값 행만 라벨이
            밀려 목록이 들쭉날쭉해진다.

            **색을 새로 만들지 않는다** — 아이콘은 `currentColor` 를 상속하므로 라이트/다크
            양쪽에서 버튼 글자와 같은 색이다. 대비가 글자와 동일해 별도 토큰이 필요 없다(NFR5).
          */}
          <Check
            // lucide 가 기본으로 넣어 주지만 `ProjectSwitcher.tsx:161` 처럼 명시한다 —
            // 의도를 코드에 남기고, lucide 기본값이 바뀌어도 이름이 오염되지 않는다.
            aria-hidden="true"
            data-testid={`cell-priority-mark-${p}`}
            className={`size-3.5 shrink-0 ${p === value ? 'opacity-100' : 'opacity-0'}`}
          />
          {issueDetailStrings.priorityNames[p]}
        </Button>
      ))}
    </div>
  )
}

/** PriorityCell props */
export interface PriorityCellProps {
  /** 대상 이슈 — 표시값·`expectedVersion` 의 출처 */
  issue: IssueResponse
  /** 목록 queryKey — mutation 이 이 캐시를 낙관적으로 patch 한다 */
  listQueryKey: QueryKey
}

/** PriorityCellPopoverBody props */
interface PriorityCellPopoverBodyProps {
  issue: IssueResponse
  isSaving: boolean
  onChange: (priority: number) => void
}

/**
 * popover 가 열렸을 때만 마운트된다 — 권한 조회가 여기서만 발생한다 (FR12·NFR1).
 *
 * 이 컴포넌트를 `EditableCell` **밖으로** 끌어올리면 목록 초기 렌더에서 행 수만큼
 * 권한 조회가 터진다. `IssueTable.test.tsx` 의 FR12 가드가 그 회귀를 잡는다.
 *
 * @param props 대상 이슈 · 저장 진행 여부 · 변경 콜백
 * @returns 우선순위 선택 목록
 */
function PriorityCellPopoverBody({
  issue,
  isSaving,
  onChange,
}: PriorityCellPopoverBodyProps): JSX.Element {
  const permissions = useIssuePermissions(issue.key)

  return (
    <PriorityCellEditor
      value={issue.priority}
      // fail-closed — 권한이 확정되기 전에는 false (D-6).
      // ★이슈 단위 UPDATE **와** 필드 단위 편집가부를 둘 다 본다 (리뷰 C2).
      // `noneditableFields` 는 목록 응답에도 이미 실려 오므로 **추가 요청 0** 이다.
      canEdit={
        !isFieldDisabled(
          'priority',
          permissions.data?.permissions.UPDATE === true,
          issue.noneditableFields,
        )
      }
      isSaving={isSaving}
      onChange={onChange}
    />
  )
}

/**
 * 우선순위 셀 조립 — 텍스트(닫힘) + 선택 목록(열림).
 *
 * mutation 훅은 popover **밖**(이 컴포넌트)에 둔다. 저장은 popover 를 닫은 뒤 시작하므로
 * (Maxi 확정 2026-08-04) 훅이 popover 안에 있으면 mutate 직후 언마운트돼 관찰자가 사라진다.
 * `useMutation` 은 네트워크를 유발하지 않으므로 밖에 두어도 FR12(조회 지연)와 무관하다.
 *
 * @param props 대상 이슈 · 목록 queryKey
 * @returns 편집 가능한 우선순위 셀
 */
export function PriorityCell({ issue, listQueryKey }: PriorityCellProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const mutation = useIssueListCellField(listQueryKey)

  /** 우선순위 선택 — 먼저 닫고 저장한다. 낙관적 patch 라 닫아도 결과가 셀에 즉시 보인다 */
  function handleChange(next: number): void {
    setOpen(false)
    mutation.mutate({
      issueKey: issue.key,
      field: 'priority',
      toPriority: next,
      expectedVersion: issue.version,
    })
  }

  return (
    <EditableCell
      open={open}
      onOpenChange={setOpen}
      // ★목록은 행이 여러 개다. 이슈 키를 접두로 붙이지 않으면 e2e strict mode 로 즉사한다
      label={`${issue.key} 우선순위 변경`}
      // 접근성 이름에 현재 값을 함께 싣는다 (리뷰 C4) — 표기 정본을 그대로 쓴다
      valueLabel={resolvePriorityLabel(issue.priority)}
      display={<PriorityCellDisplay priority={issue.priority} />}
    >
      <PriorityCellPopoverBody issue={issue} isSaving={mutation.isPending} onChange={handleChange} />
    </EditableCell>
  )
}
