// 백로그 화면의 접이식 에픽 필터 패널 — 에픽 다중 선택(제어형) + 프로젝트별 접기 영속 (FR-UX-13 F16 Task 4)
import type { JSX } from 'react'
import { useId, useMemo } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { backlogLabels } from '@/i18n/backlog-labels'
import { NO_EPIC } from '@/lib/backlog-filter'
import { EPIC_PANEL_SECTION_ID, useBacklogCollapsed } from '@/hooks/use-backlog-collapsed'

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 상수
//
// `i18n/backlog-labels.ts` 가 아니라 이 파일이 소유한다 — 접기 토글 이름만 그쪽의
// `collapseSection` 을 **재사용**해 새 버튼 이름을 0개로 유지한다
// (`i18n/__tests__/create-entry-point-names.test.ts` 의 substring 전수 판별식 대상).
// 값은 객체가 아니라 문자열 상수다 — `react-refresh/only-export-components` 의
// `allowConstantExport` 는 원시값만 허용한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 패널 제목 겸 접근명.
 *
 * 즉사 계약(`jira-parity-contract.md` §2)이 고정한 `검색`·`메인 메뉴`·`관리 메뉴`·
 * `프로젝트 뷰 전환` 과 겹치지 않는다.
 */
export const EPIC_PANEL_TITLE = '에픽'

/**
 * 에픽 목록의 접근명.
 *
 * **`적용된 필터` 와 달라야 한다** — `FilterBar` 의 활성 칩 목록이 그 이름의 `role="list"` 이고,
 * 같은 화면에 동명 list 가 둘이면 Playwright strict mode 위반으로 e2e 가 즉사한다.
 */
export const EPIC_LIST_ARIA_LABEL = '에픽 목록'

/**
 * 설계 한계 안내 — 목록 **하단**에 둔다(목록을 훑은 뒤 읽히게).
 *
 * 프로젝트 에픽 목록 API 가 없어 이 패널은 **백로그 응답의 `epicKey` 파생**이다. 즉 백로그에
 * 이슈가 하나도 없는 에픽은 여기 나오지 않는다(스펙 §1-4 · EC5). 말하지 않으면 사용자는
 * 「에픽이 사라졌다」고 읽는다.
 */
export const EPIC_SCOPE_NOTICE = '백로그에 이슈가 있는 에픽만 표시됩니다.'

// ─────────────────────────────────────────────────────────────────────────────
// 목록 파생
// ─────────────────────────────────────────────────────────────────────────────

/** 목록 한 줄이 필요로 하는 최소 데이터 — 값(키)과 표시 이름은 다를 수 있다 */
interface EpicOption {
  /** 필터 값으로 나가는 문자열. 실제 에픽 이슈 키 또는 {@link NO_EPIC} 센티널 */
  readonly key: string
  /** 화면에 보이는 이름 겸 접근명 */
  readonly label: string
}

/**
 * 화면에 그릴 항목을 만든다 — 에픽 전량 + 「에픽 없음」(항상 마지막).
 *
 * 「에픽 없음」은 **에픽이 0종이어도 남는다**(EC2). 항목이 0개면 패널이 통째로 사라져
 * 접기 토글 위치가 화면에서 튀기 때문이다.
 *
 * `epicNames.get(key) ?? key` 의 `?? key` 는 **두 번째 표시 규칙이 아니라 타입 의무**다.
 * `useBacklogEpics`(Task 3) 는 `epicKeys` 전량에 엔트리를 채우고 미해석이면 값이 키 자체라
 * 이 폴백이 실제로 도는 상태는 도달 불가다. `Map.get` 의 반환이 `V | undefined` 라 좁히기만
 * 하며, 폴백값도 훅이 보장하는 값과 **똑같다** — 그래서 「맵에 엔트리가 없다」 픽스처로
 * 이 줄을 검증하지 않는다(도달 불가 상태를 지키는 가짜 그린 방지).
 */
function buildOptions(
  epicKeys: readonly string[],
  epicNames: ReadonlyMap<string, string>,
): EpicOption[] {
  return [
    ...epicKeys.map((key) => ({ key, label: epicNames.get(key) ?? key })),
    { key: NO_EPIC, label: backlogLabels.filter.noEpic },
  ]
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogEpicPanel (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogEpicPanel props */
export interface BacklogEpicPanelProps {
  /** 프로젝트 키 — 접힘 상태를 프로젝트별로 영속하는 데 쓴다 (F16-4) */
  readonly projectKey: string
  /**
   * 백로그에 등장하는 에픽 키 (등장 순, 중복 제거).
   * `useBacklogEpics(view).epicKeys` 를 그대로 넘긴다.
   */
  readonly epicKeys: readonly string[]
  /**
   * 에픽 키 → 표시 이름. `useBacklogEpics(view).epicNames` 를 그대로 넘긴다.
   * 이름을 못 얻은 키는 **값이 키 자체**이므로 화면에 키가 보인다 (F16-6).
   */
  readonly epicNames: ReadonlyMap<string, string>
  /** 현재 선택된 에픽 키. {@link NO_EPIC} 을 포함할 수 있다 */
  readonly value: readonly string[]
  /** 선택이 바뀔 때 **새 배열 전체**를 낸다 — 상태는 부모(`BacklogBoard`)가 소유한다 */
  readonly onChange: (next: string[]) => void
}

/**
 * 백로그 에픽 필터 패널 — 제어형.
 *
 * - 에픽 목록은 백로그 응답 파생이라 **백로그에 이슈가 있는 에픽만** 나온다(EC5, 하단 안내).
 * - 「에픽 없음」({@link NO_EPIC})은 항상 마지막 항목으로 남는다(EC2).
 * - 접힘은 F15 의 {@link useBacklogCollapsed} 에 섹션 id 를 하나 더 얹어 영속한다 —
 *   새 훅·새 저장 키를 만들지 않으므로 프로젝트별 분리와 fail-safe 폴백이 한 벌로 유지된다.
 * - **에픽 선택 컨트롤은 이 패널이 유일 소유**한다(C4). `BacklogFilterBar` 에는 제거 전용
 *   칩으로만 나타난다 — 같은 상태를 두 UI 가 입력하면 어느 쪽이 진실인지 흐려진다.
 *
 * WCAG AA: 체크박스는 보이는 `<label htmlFor>` 와 연결되고 행 전체가 44px 터치 타깃이다.
 * 접기 토글은 `aria-expanded` 로 상태를 말한다(이름은 상태에 따라 바뀌지 않는다).
 */
export function BacklogEpicPanel({
  projectKey,
  epicKeys,
  epicNames,
  value,
  onChange,
}: BacklogEpicPanelProps): JSX.Element {
  const titleId = useId()
  const optionIdPrefix = useId()

  // 접힘은 권한과 무관하다 — 읽기 전용 사용자도 시야를 좁힐 수 있어야 한다(F15 FR-12 동형).
  const { isCollapsed, toggle } = useBacklogCollapsed(projectKey)
  const collapsed = isCollapsed(EPIC_PANEL_SECTION_ID)

  const options = useMemo(() => buildOptions(epicKeys, epicNames), [epicKeys, epicNames])
  const selected = useMemo(() => new Set(value), [value])

  /** 한 항목의 선택을 반전한 **새 배열**을 부모에게 올린다. 순서는 선택한 순서다 */
  const handleToggleEpic = (key: string): void => {
    onChange(selected.has(key) ? value.filter((k) => k !== key) : [...value, key])
  }

  return (
    <section
      aria-labelledby={titleId}
      className="flex w-full flex-col gap-2 rounded-lg border border-border p-3"
    >
      {/* 헤더 — 접기 토글 + 제목.
          ★토글 이름은 `aria-label` 로만 준다. `sr-only` 텍스트를 넣으면 이 region 의
          textContent 선두에 글자가 끼는데, `backlog.spec.ts:253` 의 칸 locator 가
          region textContent 의 `^` 를 앵커링한다(`BacklogColumn.tsx` 와 같은 함정). */}
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="min-h-11 min-w-11 text-muted-foreground md:min-h-0 md:min-w-0"
          aria-label={backlogLabels.collapseSection(EPIC_PANEL_TITLE)}
          aria-expanded={!collapsed}
          onClick={() => {
            toggle(EPIC_PANEL_SECTION_ID)
          }}
        >
          {collapsed ? <ChevronRight /> : <ChevronDown />}
        </Button>
        <span id={titleId} className="text-sm font-semibold text-foreground">
          {EPIC_PANEL_TITLE}
        </span>
      </div>

      {/* 접히면 목록과 안내를 렌더하지 않는다. 헤더(토글)는 남는다 — 위치가 튀지 않게. */}
      {!collapsed && (
        <>
          {/* `<ul>` 에 role 을 명시한다 — Tailwind preflight 가 list-style 을 지우면
              Safari 가 list 시맨틱을 떨어뜨린다. 접근명은 `적용된 필터` 와 반드시 다르다. */}
          <ul role="list" aria-label={EPIC_LIST_ARIA_LABEL} className="flex flex-col">
            {options.map((option) => (
              <EpicOptionRow
                key={option.key}
                controlId={`${optionIdPrefix}${option.key}`}
                label={option.label}
                checked={selected.has(option.key)}
                onToggle={() => {
                  handleToggleEpic(option.key)
                }}
              />
            ))}
          </ul>
          {/* 목록 **뒤**에 온다 — 무엇이 있는지 먼저 보고 왜 그것뿐인지를 읽는 순서다. */}
          <p className="text-xs text-(--text-subtle)">{EPIC_SCOPE_NOTICE}</p>
        </>
      )}
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// EpicOptionRow — 비공개 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface EpicOptionRowProps {
  /** 체크박스 element id — `<label htmlFor>` 연결에 쓴다 */
  readonly controlId: string
  /** 보이는 이름 겸 접근명 */
  readonly label: string
  /** 선택 여부 */
  readonly checked: boolean
  /** 선택 반전 */
  readonly onToggle: () => void
}

/**
 * 에픽 한 줄 — 체크박스 + 이름.
 *
 * 접근명은 **`<label htmlFor>` 하나로만** 준다(`aria-label` 중복 금지). 짝 테스트가
 * `getByRole('checkbox', { name: 에픽 이름 })` 으로 이 계약을 양쪽에서 잰다 —
 * 필터바는 0개, 패널은 있음.
 */
function EpicOptionRow({ controlId, label, checked, onToggle }: EpicOptionRowProps): JSX.Element {
  return (
    <li className="flex items-center gap-2">
      <Checkbox id={controlId} checked={checked} onCheckedChange={onToggle} />
      {/* 행 전체를 라벨로 만들어 터치 타깃을 44px 로 넓힌다(체크박스 자체는 16px).
          데스크톱은 목록 밀도를 위해 낮춘다 — `BacklogColumn` 토글과 같은 결. */}
      <label
        htmlFor={controlId}
        className="flex min-h-11 flex-1 cursor-pointer items-center truncate text-sm text-foreground md:min-h-8"
      >
        {label}
      </label>
    </li>
  )
}
