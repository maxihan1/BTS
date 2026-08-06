// BacklogEpicPanel 단위 테스트 — 에픽 목록·「에픽 없음」·다중 선택·프로젝트별 접기 영속·설계 한계 문구 (FR-UX-13 F16 Task 4)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NO_EPIC } from '@/lib/backlog-filter'
import {
  useBacklogCollapsedStore,
  backlogCollapsedStorageKey,
  EPIC_PANEL_SECTION_ID,
} from '@/hooks/use-backlog-collapsed'
import { NO_EPIC_LABEL } from './BacklogFilterBar'
import { backlogLabels } from '@/i18n/backlog-labels'

// ─────────────────────────────────────────────────────────────────────────────
// import — RED 단계: 아직 존재하지 않음
// ─────────────────────────────────────────────────────────────────────────────

import {
  BacklogEpicPanel,
  EPIC_PANEL_TITLE,
  EPIC_LIST_ARIA_LABEL,
  EPIC_SCOPE_NOTICE,
} from './BacklogEpicPanel'
import type { BacklogEpicPanelProps } from './BacklogEpicPanel'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
//
// `epicNames` 는 **`useBacklogEpics`(Task 3) 계약 그대로** 만든다 — `epicKeys` 전량에
// 엔트리가 있고, 이름을 못 얻은 키는 **값이 키 자체**다. 「맵에 엔트리가 없는」 픽스처는
// 만들지 않는다. 그 상태는 훅 계약상 도달 불가라, 그것을 지키는 테스트는 가짜 그린이다
// (메모리 `unreachable-state-fixture-is-fake-green`).
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT = 'ATLAS'
const OTHER_PROJECT = 'OTHER'

const EPIC_ALPHA = 'ATLAS-100'
const EPIC_BETA = 'ATLAS-200'
/** 이름 해석에 실패했거나 조회 상한을 넘은 에픽 — F16-6 은 이때 **키를 그대로** 보이라고 한다 */
const EPIC_UNRESOLVED = 'ATLAS-900'

const EPIC_ALPHA_NAME = '결제 개편'
const EPIC_BETA_NAME = '알림 리팩터'

const EPIC_KEYS: readonly string[] = [EPIC_ALPHA, EPIC_BETA, EPIC_UNRESOLVED]

const EPIC_NAMES: ReadonlyMap<string, string> = new Map([
  [EPIC_ALPHA, EPIC_ALPHA_NAME],
  [EPIC_BETA, EPIC_BETA_NAME],
  [EPIC_UNRESOLVED, EPIC_UNRESOLVED],
])

// ─────────────────────────────────────────────────────────────────────────────
// ★★ C4 짝 테스트의 공유 셀렉터
//
// 에픽 선택 컨트롤의 계약은 **`role="checkbox"` + 접근명 = 에픽 표시 이름**이다.
// 이 파일은 짝의 절반(③-b — **패널에는 있다**)을 소유한다.
// **짝의 나머지 절반(③-a — 필터바에는 0개)은 `BacklogFilterBar.test.tsx` 가 부재로 소유한다.**
// 부재 단언 단독은 아무것도 안 그려도 통과하는 **공허 테스트**라, 두 파일이 **같은 셀렉터**를
// 써야 「어디에도 없음」과 「패널에만 있음」이 구분된다.
// 셀렉터를 여기서 바꾸면 `BacklogFilterBar.test.tsx` 도 같은 PR 에서 함께 고쳐야 한다.
// ─────────────────────────────────────────────────────────────────────────────

const EPIC_CONTROL_ROLE = 'checkbox' as const

/** 에픽 선택 컨트롤 후보를 이름별로 전부 긁는다. 해석 이름·미해석 키·센티널 라벨 전부. */
function queryEpicControls(): HTMLElement[] {
  const names = [
    EPIC_ALPHA_NAME,
    EPIC_BETA_NAME,
    EPIC_ALPHA,
    EPIC_BETA,
    EPIC_UNRESOLVED,
    NO_EPIC_LABEL,
  ]
  return names.flatMap((name) => screen.queryAllByRole(EPIC_CONTROL_ROLE, { name }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPanel(props: Partial<BacklogEpicPanelProps> = {}) {
  const onChange = props.onChange ?? vi.fn()
  const view = render(
    <BacklogEpicPanel
      projectKey={props.projectKey ?? PROJECT}
      epicKeys={props.epicKeys ?? EPIC_KEYS}
      epicNames={props.epicNames ?? EPIC_NAMES}
      value={props.value ?? []}
      onChange={onChange}
    />,
  )
  return { ...view, onChange }
}

/** 패널 컨테이너 — 접근명은 패널 제목과 같다 */
function panel(): HTMLElement {
  return screen.getByRole('region', { name: EPIC_PANEL_TITLE })
}

/** 접기/펼치기 토글 — 이름은 F15 의 `collapseSection` 을 재사용한다(새 문자열 0) */
function collapseToggle(): HTMLElement {
  return screen.getByRole('button', { name: backlogLabels.collapseSection(EPIC_PANEL_TITLE) })
}

/** 에픽 목록 — 접근명은 `적용된 필터`(FilterBar)와 **달라야** 한다 */
function epicList(): HTMLElement {
  return screen.getByRole('list', { name: EPIC_LIST_ARIA_LABEL })
}

beforeEach(() => {
  localStorage.clear()
  // zustand 스토어는 모듈 전역 싱글턴이라 테스트 간 상태가 누출된다 — 매 테스트 전 리셋
  useBacklogCollapsedStore.setState({ byProject: {} })
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 에픽 목록 렌더 — 표시는 이름, 값은 키 (F16-5)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S1 에픽 목록', () => {
  it('S1a: ★★ 짝 절반 — 에픽 선택 체크박스가 패널에 있다 (에픽 3종 + 「에픽 없음」 = 4개)', () => {
    renderPanel()

    // 필터바 쪽 짝(`BacklogFilterBar.test.tsx` S3a/S3b)은 **같은 셀렉터로 0개**를 단언한다.
    expect(queryEpicControls()).toHaveLength(4)
  })

  it('S1b: 이름이 해석된 에픽은 이름으로 보인다', () => {
    renderPanel()

    expect(
      screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_ALPHA_NAME }),
    ).toBeInTheDocument()
    expect(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_BETA_NAME })).toBeInTheDocument()
    // 비-공허 짝 — 이름으로 보이는 에픽은 **키로는 안 보인다**(둘 다 보이면 표시 규칙이 없는 것)
    expect(screen.queryAllByRole(EPIC_CONTROL_ROLE, { name: EPIC_ALPHA })).toHaveLength(0)
  })

  it('S1c: 표시는 이름이지만 값은 **키**다 — onChange 가 키를 낸다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderPanel()

    await user.click(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_ALPHA_NAME }))

    expect(onChange).toHaveBeenCalledWith([EPIC_ALPHA])
  })

  it('S1d: 목록 항목 순서는 epicKeys 등장 순이고 「에픽 없음」이 마지막이다', () => {
    renderPanel()

    const list = epicList()
    const labels = within(list)
      .getAllByRole(EPIC_CONTROL_ROLE)
      .map((control) => list.querySelector(`label[for="${control.id}"]`)?.textContent ?? '')

    expect(labels).toEqual([EPIC_ALPHA_NAME, EPIC_BETA_NAME, EPIC_UNRESOLVED, NO_EPIC_LABEL])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 「에픽 없음」 항목 — 항상 있다 (F16-5 · EC2)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S2 「에픽 없음」', () => {
  it('S2a: 「에픽 없음」 항목이 항상 렌더된다', () => {
    renderPanel()

    expect(screen.getByRole(EPIC_CONTROL_ROLE, { name: NO_EPIC_LABEL })).toBeInTheDocument()
  })

  it('S2b: 「에픽 없음」의 값은 NO_EPIC 센티널이다 — 화면에 `__none__` 이 새지 않는다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderPanel()

    await user.click(screen.getByRole(EPIC_CONTROL_ROLE, { name: NO_EPIC_LABEL }))

    expect(onChange).toHaveBeenCalledWith([NO_EPIC])
    expect(screen.queryByText(NO_EPIC)).not.toBeInTheDocument()
  })

  it('S2c: 이미 선택돼 있으면 체크 상태로 보이고 해제하면 빠진다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderPanel({ value: [NO_EPIC] })

    const control = screen.getByRole(EPIC_CONTROL_ROLE, { name: NO_EPIC_LABEL })
    expect(control).toHaveAttribute('aria-checked', 'true')

    await user.click(control)

    expect(onChange).toHaveBeenCalledWith([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 다중 선택/해제 (F16-3 — 패널이 에픽 상태의 유일 입력)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S3 다중 선택', () => {
  it('S3a: 이미 하나가 걸린 상태에서 다른 에픽을 고르면 **둘 다** 담긴 새 배열이 나간다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderPanel({ value: [EPIC_ALPHA] })

    await user.click(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_BETA_NAME }))

    expect(onChange).toHaveBeenCalledWith([EPIC_ALPHA, EPIC_BETA])
  })

  it('S3b: 선택 해제는 그 키만 뺀다 — 나머지는 남는다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderPanel({ value: [EPIC_ALPHA, EPIC_BETA, NO_EPIC] })

    await user.click(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_BETA_NAME }))

    expect(onChange).toHaveBeenCalledWith([EPIC_ALPHA, NO_EPIC])
  })

  it('S3c: 선택 상태는 부모 소유다 — onChange 만 부르고 스스로 체크 상태를 바꾸지 않는다', async () => {
    const user = userEvent.setup()
    renderPanel({ value: [] })

    const control = screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_ALPHA_NAME })
    await user.click(control)

    // 제어형: value 가 그대로면 화면도 그대로여야 한다(로컬 상태를 몰래 들고 있으면 red)
    expect(control).toHaveAttribute('aria-checked', 'false')
  })

  it('S3d: value 에 담긴 키만 체크로 보인다', () => {
    renderPanel({ value: [EPIC_BETA] })

    expect(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_BETA_NAME })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    expect(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_ALPHA_NAME })).toHaveAttribute(
      'aria-checked',
      'false',
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 접기 토글 — **프로젝트별** 영속 (F16-4 · S6)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S4 접기 영속', () => {
  it('S4a: 기본은 펼침이고 aria-expanded 가 상태를 말한다', () => {
    renderPanel()

    expect(collapseToggle()).toHaveAttribute('aria-expanded', 'true')
    expect(epicList()).toBeInTheDocument()
  })

  it('S4b: 접으면 목록이 사라지고 프로젝트별 키로 localStorage 에 저장된다', async () => {
    const user = userEvent.setup()
    renderPanel()

    await user.click(collapseToggle())

    expect(collapseToggle()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('list', { name: EPIC_LIST_ARIA_LABEL })).not.toBeInTheDocument()
    expect(localStorage.getItem(backlogCollapsedStorageKey(PROJECT))).toBe(
      JSON.stringify([EPIC_PANEL_SECTION_ID]),
    )
  })

  it('S4c: 재마운트해도 접힘이 유지된다', async () => {
    const user = userEvent.setup()
    const first = renderPanel()
    await user.click(collapseToggle())
    first.unmount()

    renderPanel()

    expect(collapseToggle()).toHaveAttribute('aria-expanded', 'false')
  })

  it('S4d: ★ 접힘은 **프로젝트별로 독립**이다 — 다른 projectKey 는 펼침 그대로', async () => {
    const user = userEvent.setup()
    const first = renderPanel({ projectKey: PROJECT })
    await user.click(collapseToggle())
    first.unmount()

    renderPanel({ projectKey: OTHER_PROJECT })

    expect(collapseToggle()).toHaveAttribute('aria-expanded', 'true')
    expect(epicList()).toBeInTheDocument()
    expect(localStorage.getItem(backlogCollapsedStorageKey(OTHER_PROJECT))).toBeNull()
  })

  it('S4e: 접혀 있어도 토글은 화면에 남는다 — 토글 위치가 튀지 않는다', async () => {
    const user = userEvent.setup()
    renderPanel()

    await user.click(collapseToggle())

    expect(panel()).toBeInTheDocument()
    expect(collapseToggle()).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. EC2 — 에픽이 0종이어도 패널을 숨기지 않는다
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S5 에픽 0종 (EC2)', () => {
  it('S5a: 에픽이 없어도 패널·토글이 남고 「에픽 없음」 단일 항목이 보인다', () => {
    renderPanel({ epicKeys: [], epicNames: new Map() })

    expect(panel()).toBeInTheDocument()
    expect(collapseToggle()).toBeInTheDocument()
    expect(within(epicList()).getAllByRole(EPIC_CONTROL_ROLE)).toHaveLength(1)
    expect(screen.getByRole(EPIC_CONTROL_ROLE, { name: NO_EPIC_LABEL })).toBeInTheDocument()
  })

  it('S5b: 에픽 0종에서도 「에픽 없음」은 고를 수 있다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderPanel({ epicKeys: [], epicNames: new Map() })

    await user.click(screen.getByRole(EPIC_CONTROL_ROLE, { name: NO_EPIC_LABEL }))

    expect(onChange).toHaveBeenCalledWith([NO_EPIC])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. EC5 — 설계 한계 안내 문구 1줄, **목록 하단**
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S6 설계 한계 안내 (EC5)', () => {
  it('S6a: 「백로그에 이슈가 있는 에픽만 표시된다」 취지의 문구가 보인다', () => {
    renderPanel()

    expect(screen.getByText(EPIC_SCOPE_NOTICE)).toBeInTheDocument()
  })

  it('S6b: 문구는 목록 **뒤**에 온다 — 목록을 훑은 뒤 읽히게', () => {
    renderPanel()

    const notice = screen.getByText(EPIC_SCOPE_NOTICE)
    const list = epicList()

    expect(list.contains(notice)).toBe(false)
    // 정확히 DOCUMENT_POSITION_FOLLOWING(4) — 「뒤에 있고 포함 관계가 아니다」를 한 값으로 못박는다.
    // 목록 안으로 들어가면 20(FOLLOWING|CONTAINED_BY), 앞으로 가면 2(PRECEDING) 라 둘 다 red 다.
    expect(list.compareDocumentPosition(notice)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('S6c: 에픽 0종에서도 문구는 남는다 — 왜 비었는지를 설명하는 자리다', () => {
    renderPanel({ epicKeys: [], epicNames: new Map() })

    expect(screen.getByText(EPIC_SCOPE_NOTICE)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. F16-6 / EC3 — 이름 미해석 에픽은 **키가 그대로** 보인다
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S7 이름 미해석 (F16-6)', () => {
  it('S7a: 이름을 못 얻은 에픽은 키가 그대로 접근명이 된다', () => {
    renderPanel()

    expect(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_UNRESOLVED })).toBeInTheDocument()
  })

  it('S7b: 미해석 에픽도 값은 키이고 선택된다 — 빈 이름으로 사라지지 않는다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderPanel()

    await user.click(screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_UNRESOLVED }))

    expect(onChange).toHaveBeenCalledWith([EPIC_UNRESOLVED])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. 즉사 계약 (jira-parity-contract §2 · plan M-2)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S8 즉사 계약', () => {
  it('S8a: 목록 접근명이 `적용된 필터` 가 아니다 — 같은 화면 동명 list 2개 차단', () => {
    renderPanel()

    expect(screen.queryAllByRole('list', { name: '적용된 필터' })).toHaveLength(0)
    // 비-공허 짝 — list 조회 자체는 살아 있다
    expect(screen.getAllByRole('list', { name: EPIC_LIST_ARIA_LABEL })).toHaveLength(1)
  })

  it('S8b: 이 패널이 붙이는 접근명 3종이 기존 e2e 계약 문자열 4종과 겹치지 않는다', () => {
    renderPanel()

    // 값 자체를 검사한다 — `queryAllByRole(name: '검색')` 로는 「원래 없던 이름」이 항상 0개라
    // 무엇을 구현해도 통과하는 공허 단언이 된다.
    const RESERVED = ['검색', '메인 메뉴', '관리 메뉴', '프로젝트 뷰 전환']
    for (const name of [
      EPIC_PANEL_TITLE,
      EPIC_LIST_ARIA_LABEL,
      backlogLabels.collapseSection(EPIC_PANEL_TITLE),
    ]) {
      expect(RESERVED).not.toContain(name)
    }

    // 비-공허 짝 — 세 이름이 실제로 화면의 요소에 붙어 있다(이름만 상수로 두고 안 쓰면 red)
    expect(panel()).toBeInTheDocument()
    expect(epicList()).toBeInTheDocument()
    expect(collapseToggle()).toBeInTheDocument()
  })

  it('S8c: ★ region textContent 가 `백로그`·`스프린트` 로 시작하지 않는다 (backlog.spec.ts:253 앵커)', () => {
    renderPanel()

    const text = panel().textContent ?? ''
    expect(text.startsWith('백로그')).toBe(false)
    expect(text.startsWith('스프린트')).toBe(false)
    // 비-공허 짝 — textContent 는 비어 있지 않고 패널 제목으로 시작한다
    expect(text.startsWith(EPIC_PANEL_TITLE)).toBe(true)
  })

  it('S8d: 접기 토글 이름은 F15 의 섹션 토글 이름 규칙을 재사용한다 (새 버튼 이름 0)', () => {
    renderPanel()

    expect(collapseToggle()).toBeInTheDocument()
    // 상태로 이름이 갈리지 않는다 — 상태는 aria-expanded 가 말한다
    expect(collapseToggle()).toHaveAttribute('aria-expanded')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S9. WCAG AA — label 연결
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogEpicPanel — S9 접근성', () => {
  it('S9a: 체크박스는 보이는 label 요소와 htmlFor 로 연결된다', () => {
    renderPanel()

    const control = screen.getByRole(EPIC_CONTROL_ROLE, { name: EPIC_ALPHA_NAME })
    const label = screen.getByText(EPIC_ALPHA_NAME)

    expect(label.tagName).toBe('LABEL')
    expect(label).toHaveAttribute('for', control.getAttribute('id'))
  })

  it('S9b: 같은 화면에 두 패널이 있어도 id 가 충돌하지 않는다 (useId)', () => {
    render(
      <>
        <BacklogEpicPanel
          projectKey={PROJECT}
          epicKeys={EPIC_KEYS}
          epicNames={EPIC_NAMES}
          value={[]}
          onChange={vi.fn()}
        />
        <BacklogEpicPanel
          projectKey={OTHER_PROJECT}
          epicKeys={EPIC_KEYS}
          epicNames={EPIC_NAMES}
          value={[]}
          onChange={vi.fn()}
        />
      </>,
    )

    const ids = screen
      .getAllByRole(EPIC_CONTROL_ROLE, { name: EPIC_ALPHA_NAME })
      .map((el) => el.getAttribute('id'))

    expect(ids).toHaveLength(2)
    expect(new Set(ids).size).toBe(2)
  })
})
