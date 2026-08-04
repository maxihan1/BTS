// 이슈 목록 우선순위 셀 편집부 테스트 — 선택 · 권한 fail-closed · 저장 중 잠금 (FR-UX-11 F9 FR6·FR10·NFR3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { issueDetailStrings } from '@/i18n/ko'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import type { IssueResponse } from '@/api/issues'
import { PriorityCell, PriorityCellDisplay, PriorityCellEditor } from './PriorityCell'

// 권한 조회를 UPDATE:true 로 고정한다 — 그래야 **필드 단위** 가부만 분별할 수 있다.
// 단위 테스트에는 인증 토큰이 없어 실제 조회는 401 이 되고, 그러면 이슈 단위 권한에
// 가려져 필드 권한 가드가 공허해진다.
vi.mock('@/hooks/use-issue-permissions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/use-issue-permissions')>()
  return {
    ...actual,
    useIssuePermissions: () => ({
      data: {
        issueKey: 'ATLAS-1',
        permissions: { UPDATE: true, SOFT_DELETE: true, TRANSITION: true },
      },
      isLoading: false,
    }),
  }
})

describe('PriorityCellDisplay', () => {
  it('닫힌 셀도 한국어 라벨 정본을 쓴다 — 백엔드 영어 표기를 화면에 내지 않는다 (Maxi 확정 2026-08-04)', () => {
    render(<PriorityCellDisplay priority={3} />)

    expect(screen.getByText(issueDetailStrings.priorityNames[3])).toBeInTheDocument()
    // ★백엔드 priorityName('Medium')이 화면에 새어 나오면 닫힘/열림이 다른 언어로 보인다
    expect(screen.queryByText('Medium')).not.toBeInTheDocument()
  })

  it('1~5 전 범위가 라벨 정본과 1:1 대응한다', () => {
    for (const p of [1, 2, 3, 4, 5] as const) {
      const { unmount } = render(<PriorityCellDisplay priority={p} />)
      expect(screen.getByText(issueDetailStrings.priorityNames[p])).toBeInTheDocument()
      unmount()
    }
  })

  it('정본 범위를 벗어난 값은 숨기지 않고 숫자를 그대로 보인다', () => {
    // 값이 사라지면 사용자는 "우선순위가 없다"로 오독한다 — 모르는 값도 보여준다
    render(<PriorityCellDisplay priority={9} />)

    expect(screen.getByText('9')).toBeInTheDocument()
  })
})

describe('PriorityCellEditor', () => {
  it('우선순위를 고르면 그 값으로 onChange 를 부른다 (FR6)', async () => {
    const onChange = vi.fn()
    render(<PriorityCellEditor value={3} canEdit onChange={onChange} isSaving={false} />)

    await userEvent.click(screen.getByRole('button', { name: '높음' }))

    expect(onChange).toHaveBeenCalledWith(2)
  })

  it('권한이 없으면 모든 선택지가 비활성이다 (FR10 fail-closed)', () => {
    render(<PriorityCellEditor value={3} canEdit={false} onChange={vi.fn()} isSaving={false} />)

    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
    }
    expect(screen.getByText('편집 권한이 없습니다.')).toBeInTheDocument()
  })

  it('저장 중이면 비활성이다 (NFR3 중복 제출 차단)', () => {
    render(<PriorityCellEditor value={3} canEdit onChange={vi.fn()} isSaving />)

    expect(screen.getByRole('button', { name: '높음' })).toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // QA F1 — 현재 값이 **눈으로도** 구별돼야 한다
  //
  // `aria-current` 만 있으면 스크린리더 사용자만 지금 값을 안다. 담당자 셀은 현재 값을
  // popover 맨 위에 보여주는데(`cell-assignee-current`) 우선순위만 빠져 있어, 같은 PR 안에서
  // 규칙이 어긋나 있었다.
  //
  // jsdom 은 Tailwind 를 적용하지 않아 **계산값 단언이 공허해진다**(F8 커서 단언 사고와 같은
  // 함정). 그래서 클래스 문자열을 본다 — `EditableCell` 의 `--border`·`select-text` 가드와
  // 같은 처방이고, 최종 판정은 브라우저 눈확인이다.
  // ───────────────────────────────────────────────────────────────────────────
  it('현재 값에 시각 표기(체크)를 보이고 나머지는 숨긴다 (QA F1)', () => {
    render(<PriorityCellEditor value={3} canEdit onChange={vi.fn()} isSaving={false} />)

    // ★`.className` 을 쓰지 마라 — SVG 요소에서는 문자열이 아니라 `SVGAnimatedString` 객체라
    // `toContain` 이 배열 포함 검사로 빠져 조용히 어긋난다(2026-08-04 실측). `class` 속성을 읽는다.
    expect(screen.getByTestId('cell-priority-mark-3').getAttribute('class')).toContain('opacity-100')

    for (const other of [1, 2, 4, 5] as const) {
      expect(screen.getByTestId(`cell-priority-mark-${other}`).getAttribute('class')).not.toContain(
        'opacity-100',
      )
    }
  })

  it('표기는 전 항목에 렌더돼 라벨 정렬이 흔들리지 않는다 (조건부 삽입 금지)', () => {
    render(<PriorityCellEditor value={3} canEdit onChange={vi.fn()} isSaving={false} />)

    for (const p of [1, 2, 3, 4, 5] as const) {
      expect(screen.getByTestId(`cell-priority-mark-${p}`)).toBeInTheDocument()
    }
  })

  it('시각 표기는 aria-current 를 대체하지 않는다 — 스크린리더 경로 보존', () => {
    render(<PriorityCellEditor value={3} canEdit onChange={vi.fn()} isSaving={false} />)

    expect(screen.getByRole('button', { name: issueDetailStrings.priorityNames[3] })).toHaveAttribute(
      'aria-current',
      'true',
    )
    expect(screen.getByRole('button', { name: issueDetailStrings.priorityNames[2] })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('표기가 버튼의 접근성 이름을 오염시키지 않는다 (QA E2E exact 셀렉터 보호)', () => {
    render(<PriorityCellEditor value={3} canEdit onChange={vi.fn()} isSaving={false} />)

    // QA E2E(S2)는 `getByRole('button', { name: '높음', exact: true })` 로 고른다.
    // 표기를 보이는 텍스트(예: `현재` 배지)로 바꾸면 이름이 `현재 보통` 이 되어 그 셀렉터가
    // 즉사한다 — 그래서 표기는 **아이콘**이어야 한다.
    //
    // ★`aria-hidden` **속성 자체**는 단언하지 않는다. lucide-react 가 모든 아이콘에 기본
    // 부여하는 것을 실측했고(2026-08-04), 그러면 내 코드가 무엇을 하든 참이라 공허해진다.
    // 대신 내가 실제로 통제하는 **결과**(접근성 이름)를 잰다.
    //
    // ★`exact` 를 붙이지 마라 — Playwright 의 `getByRole` 과 달리 Testing Library 에는 그런
    // 옵션이 **없다**. 런타임은 여분 프로퍼티를 조용히 무시해 초록으로 지나가고 tsc 만 잡는다
    // (2026-08-04 실측). 문자열 `name` 은 정규화 후 **완전 일치**라 이미 exact 다 —
    // `높음` 은 `가장 높음` 과 매칭되지 않는다.
    for (const p of [1, 2, 3, 4, 5] as const) {
      expect(
        screen.getByRole('button', { name: issueDetailStrings.priorityNames[p] }),
      ).toBeInTheDocument()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C2 — 필드 단위 권한
//
// 이슈 단위 UPDATE 만 보면, 관리자가 `priority` 를 편집 불가로 잠가도 목록에서는 그대로
// 고칠 수 있어 보인다(저장은 서버가 거절 → 사용자는 이유 모를 실패를 본다). 상세 화면
// `IssueMetaPanel.tsx:315` 는 이미 필드 단위까지 본다. `noneditableFields` 는 목록 응답에도
// 실려 오므로 추가 요청 0 이다.
// ─────────────────────────────────────────────────────────────────────────────

/** 열린 우선순위 셀을 렌더한다 */
async function renderOpenedPriorityCell(overrides: Partial<IssueResponse> = {}): Promise<void> {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <PriorityCell
        issue={{ ...issueAtlas1Fixture, ...overrides }}
        listQueryKey={['issues', 'ATLAS', 0, {}, null]}
      />
    </QueryClientProvider>,
  )
  await userEvent.click(
    screen.getByRole('button', { name: `${issueAtlas1Fixture.key} 우선순위 변경` }),
  )
}

describe('PriorityCell — 필드 단위 권한 (리뷰 C2)', () => {
  it('noneditableFields 에 priority 가 있으면 UPDATE 권한이 있어도 선택지가 비활성이다', async () => {
    await renderOpenedPriorityCell({ noneditableFields: ['priority'] })

    expect(screen.getByRole('button', { name: issueDetailStrings.priorityNames[2] })).toBeDisabled()
  })

  it('noneditableFields 가 비어 있으면 선택지가 활성이다 (비-공허 짝)', async () => {
    // 이 짝이 없으면 위 단언은 "항상 비활성"과 구분되지 않아 공허해진다
    await renderOpenedPriorityCell({ noneditableFields: [] })

    expect(screen.getByRole('button', { name: issueDetailStrings.priorityNames[2] })).toBeEnabled()
  })

  it('다른 필드가 잠겨 있어도 priority 는 영향받지 않는다 (필드 키 정확도)', async () => {
    await renderOpenedPriorityCell({ noneditableFields: ['assigneeId', 'labels'] })

    expect(screen.getByRole('button', { name: issueDetailStrings.priorityNames[2] })).toBeEnabled()
  })
})
