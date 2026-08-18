// 스프린트 완료 다이얼로그 테스트 — 이관 순서·부분 실패·완료 직전 재검증·truncated 차단 (FR-UX-13 F15 FR-5·FR-6·FR-7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ReactNode, ReactElement } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { z } from 'zod'
import { server } from '@/test/server'
import { allWorkflowFixtures } from '@/mocks/workflow-fixtures'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'
import type { BacklogIssue, BacklogView, SprintMeta, SprintWithIssues } from '@/api/backlog'
import {
  CompleteSprintDialog,
  BLOCK_COMPLETE_WHEN_TRUNCATED,
  isSubmitBlockedByTruncation,
} from './CompleteSprintDialog'
import type { CompleteSprintDialogProps } from './CompleteSprintDialog'

// ─────────────────────────────────────────────────────────────────────────────
// Radix/shadcn Select → 네이티브 <select> mock
// jsdom 에서 hasPointerCapture 제약으로 Radix Select 클릭 인터랙션이 불가하므로
// 네이티브 select 로 대체한다 (PatCreateForm.test.tsx 정본 템플릿 · ResolutionPickerModal 동형).
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/select', async () => {
  const { createElement: ce, useRef, Children } = await vi.importActual<typeof import('react')>('react')

  function Select({
    children,
    onValueChange,
    value,
    disabled,
  }: {
    children: ReactNode
    onValueChange?: (v: string) => void
    value?: string
    disabled?: boolean
  }) {
    const triggerLabel = useRef<string>('')
    const contentOptions = useRef<ReactNode>(null)

    Children.forEach(children, (child) => {
      if (child !== null && typeof child === 'object' && 'props' in (child as object)) {
        const el = child as ReactElement<{ 'aria-label'?: string; children?: ReactNode }>
        if (el.props['aria-label']) {
          triggerLabel.current = el.props['aria-label']
        }
        if (el.props.children) {
          contentOptions.current = el.props.children
        }
      }
    })

    return ce(
      'select',
      {
        'aria-label': triggerLabel.current,
        value: value ?? '',
        disabled,
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => {
          if (onValueChange) onValueChange(e.target.value)
        },
      },
      contentOptions.current,
    )
  }

  function SelectTrigger({
    children,
    'aria-label': ariaLabel,
  }: {
    children?: ReactNode
    'aria-label'?: string
    id?: string
    className?: string
  }) {
    return ce('span', { 'aria-label': ariaLabel }, children)
  }

  function SelectValue() {
    return null
  }

  function SelectContent({ children }: { children: ReactNode }) {
    return ce('span', {}, children)
  }

  function SelectItem({ value, children }: { value: string; children: ReactNode }) {
    return ce('option', { value }, children)
  }

  return { Select, SelectTrigger, SelectValue, SelectContent, SelectItem }
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const L = backlogLabels.completeDialog

const PROJECT_KEY = 'ATLAS'

/** 완료 대상(원본) 스프린트 */
const SOURCE_ID = '11111111-1111-4111-8111-111111111111'
/** 이관 후보 — PLANNED */
const PLANNED_ID = '22222222-2222-4222-8222-222222222222'
/** 이관 후보 — ACTIVE (자기 자신이 아닌 다른 진행 스프린트) */
const OTHER_ACTIVE_ID = '33333333-3333-4333-8333-333333333333'
/** 이관 후보에서 **빠져야 하는** COMPLETED */
const COMPLETED_ID = '44444444-4444-4444-8444-444444444444'

/**
 * 스프린트 메타 픽스처.
 *
 * `status` 는 백엔드 응답이 문자열이므로 그대로 문자열로 둔다 (`sprintMetaSchema` 미러).
 */
function meta(sprintId: string, name: string, status: string): SprintMeta {
  return { sprintId, name, goal: null, status, startDate: null, endDate: null, version: 1 }
}

/**
 * 백로그 이슈 픽스처.
 *
 * `currentStateKey` 기본값 `'open'` 은 MSW 워크플로우 픽스처에서 `TODO` 라 **미완료**,
 * `'done'` 은 전 워크플로우에서 `DONE` 하나뿐이라 **완료**로 판정된다.
 */
function issue(key: string, currentStateKey = 'open'): BacklogIssue {
  return {
    key,
    summary: `${key} 제목`,
    currentStateKey,
    assigneeId: null,
    priority: 3,
    rank: null,
    version: 1,
    epicKey: null,
    typeKey: 'task',
    labels: [],
    originalEstimateSeconds: null,
  }
}

const SOURCE_META = meta(SOURCE_ID, '진행 중 스프린트', 'ACTIVE')
const PLANNED_META = meta(PLANNED_ID, '예정 스프린트', 'PLANNED')
const OTHER_ACTIVE_META = meta(OTHER_ACTIVE_ID, '다른 진행 스프린트', 'ACTIVE')
const COMPLETED_META = meta(COMPLETED_ID, '완료된 스프린트', 'COMPLETED')

/** 미완료 3건 + 완료 1건 */
const SOURCE_SPRINT: SprintWithIssues = {
  sprint: SOURCE_META,
  issues: [issue('ATLAS-1'), issue('ATLAS-2'), issue('ATLAS-3'), issue('ATLAS-9', 'done')],
}

/** E11 — 미완료 0건 */
const DONE_ONLY_SPRINT: SprintWithIssues = {
  sprint: SOURCE_META,
  issues: [issue('ATLAS-9', 'done')],
}

/** ★ COMPLETED·PLANNED·ACTIVE 를 **전부** 담는다 — 짝 단언이 공허해지지 않게 하는 조건 */
const ALL_SPRINTS: readonly SprintMeta[] = [
  SOURCE_META,
  PLANNED_META,
  OTHER_ACTIVE_META,
  COMPLETED_META,
]

// ─────────────────────────────────────────────────────────────────────────────
// MSW 시나리오 — 요청 순서·횟수를 직접 기록한다
// ─────────────────────────────────────────────────────────────────────────────

/** 관측된 요청 순서 */
let calls: string[] = []

/** 이슈 키 → 이 순서대로 반환할 실패 status. 큐가 비면 성공한다 */
let deleteOutcomes: Record<string, number[]> = {}
let postOutcomes: Record<string, number[]> = {}

/** `POST /complete` 가 반환할 status. 200 이면 성공 */
let completeStatus = 200

/** C-7 재검증이 받아 볼 백로그 응답 */
let freshBacklog: BacklogView = emptyFreshBacklog()

/** DELETE 를 붙잡아 두는 게이트. null 이면 즉시 응답한다 (E19 용) */
let deleteGate: Promise<void> | null = null

/**
 * `GET /api/v1/workflows` 를 붙잡아 두는 게이트. null 이면 즉시 응답한다.
 *
 * 백로그 화면은 워크플로우를 미리 부르지 않으므로 창을 여는 순간이 **매번 콜드 페치**다.
 * 그 사이 상태 분류가 없다는 사실을 재려면 응답을 실제로 붙잡아 둬야 한다.
 */
let workflowGate: Promise<void> | null = null

const del = (issueKey: string): string => `DELETE ${SOURCE_ID}/${issueKey}`
const post = (targetId: string, issueKey: string): string => `POST ${targetId}/${issueKey}`
const GET_BACKLOG = 'GET backlog'
const COMPLETE = `COMPLETE ${SOURCE_ID}`

/** 이관이 전부 끝난 뒤의 백로그 — 원본 스프린트에 완료 이슈만 남는다 */
function emptyFreshBacklog(): BacklogView {
  return {
    backlog: [],
    sprints: [{ sprint: SOURCE_META, issues: [issue('ATLAS-9', 'done')] }],
    truncated: false,
  }
}

/** `POST /sprints/{id}/issues` 요청 body 스키마 — 캐스팅 없이 issueKey 를 꺼낸다 */
const assignBodySchema = z.object({ issueKey: z.string() })

function installHandlers(): void {
  server.use(
    // `test/handlers.ts` 는 refresh 하나뿐이다 — 이 파일이 쓰는 엔드포인트는 전부 여기서 깐다.
    http.get('/api/v1/workflows', async () => {
      if (workflowGate !== null) await workflowGate
      return HttpResponse.json({ data: allWorkflowFixtures })
    }),
    http.delete('/api/v1/sprints/:sprintId/issues/:issueKey', async ({ params }) => {
      const issueKey = String(params['issueKey'])
      calls.push(`DELETE ${String(params['sprintId'])}/${issueKey}`)
      if (deleteGate !== null) await deleteGate
      const failure = deleteOutcomes[issueKey]?.shift()
      if (failure !== undefined) return new HttpResponse(null, { status: failure })
      return new HttpResponse(null, { status: 204 })
    }),
    http.post('/api/v1/sprints/:sprintId/issues', async ({ params, request }) => {
      const body = assignBodySchema.parse(await request.json())
      calls.push(`POST ${String(params['sprintId'])}/${body.issueKey}`)
      const failure = postOutcomes[body.issueKey]?.shift()
      if (failure !== undefined) return new HttpResponse(null, { status: failure })
      return new HttpResponse(null, { status: 201 })
    }),
    http.post('/api/v1/sprints/:sprintId/complete', ({ params }) => {
      calls.push(`COMPLETE ${String(params['sprintId'])}`)
      if (completeStatus !== 200) return new HttpResponse(null, { status: completeStatus })
      return HttpResponse.json({ data: { ...SOURCE_META, status: 'COMPLETED', version: 2 } })
    }),
    http.get('/api/v1/projects/:projectKey/backlog', () => {
      calls.push(GET_BACKLOG)
      return HttpResponse.json({ data: freshBacklog })
    }),
  )
}

beforeEach(() => {
  calls = []
  deleteOutcomes = {}
  postOutcomes = {}
  completeStatus = 200
  freshBacklog = emptyFreshBacklog()
  deleteGate = null
  workflowGate = null
  installHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderDialog(overrides: Partial<CompleteSprintDialogProps> = {}) {
  const onOpenChange = vi.fn()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const user = userEvent.setup({ delay: null })
  const props: CompleteSprintDialogProps = {
    open: true,
    onOpenChange,
    projectKey: PROJECT_KEY,
    sprint: SOURCE_SPRINT,
    allSprints: ALL_SPRINTS,
    truncated: false,
    canReorderIssue: true,
    ...overrides,
  }
  const utils = render(
    <QueryClientProvider client={client}>
      <CompleteSprintDialog key={props.sprint.sprint.sprintId} {...props} />
    </QueryClientProvider>,
  )
  return { user, onOpenChange, client, ...utils }
}

/** 미완료 목록의 n 번째 행. `noUncheckedIndexedAccess` 가드를 한 곳에 모은다 */
function rowAt(index: number): HTMLElement {
  const rows = screen.getAllByRole('listitem')
  const row = rows[index]
  if (row === undefined) throw new Error(`미완료 목록에 ${index} 번째 행이 없다`)
  return row
}

/** 이관 대상 Select (네이티브 select mock) */
function moveTargetSelect(): HTMLElement {
  return screen.getByRole('combobox', { name: L.moveTargetLabel })
}

/** 푸터 제출 버튼 */
function submitButton(): HTMLElement {
  return screen.getByRole('button', { name: backlogLabels.completeSprint })
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 — 요약 · 미완료 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — 요약과 미완료 목록 (FR-5)', () => {
  it('완료/미완료 건수 요약과 미완료 이슈만 목록에 그린다', async () => {
    renderDialog()

    expect(await screen.findByText(L.summary(1, 3))).toBeInTheDocument()
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-3')).toBeInTheDocument()
    // 완료(카테고리 집합이 정확히 {DONE})는 이관 대상이 아니다
    expect(screen.queryByText('ATLAS-9')).not.toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })

  it('다이얼로그 이름은 트리거와 같은 「스프린트 완료」다 (FR-10 즉사 계약)', async () => {
    renderDialog()
    expect(
      await screen.findByRole('dialog', { name: backlogLabels.completeSprint }),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CP-1 — 이관 대상 옵션 (짝 단언)
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — T-CP-1 이관 대상 옵션 (FR-5)', () => {
  it('백로그 + PLANNED + ACTIVE 만 담고 COMPLETED 와 자기 자신은 뺀다', async () => {
    renderDialog()
    await screen.findByText(L.summary(1, 3))

    const options = within(moveTargetSelect()).getAllByRole('option')
    const names = options.map((option) => option.textContent)

    // ★ 짝 단언 — 「COMPLETED 가 없다」만 두면 픽스처에 COMPLETED 가 0개일 때 자동 통과한다.
    //   PLANNED·ACTIVE 가 **실제로 들어 있는지**와 **개수**를 함께 잰다.
    expect(names).toContain(L.backlogOption)
    expect(names).toContain(PLANNED_META.name)
    expect(names).toContain(OTHER_ACTIVE_META.name)
    expect(names).not.toContain(COMPLETED_META.name)
    expect(names).not.toContain(SOURCE_META.name)
    expect(options).toHaveLength(3)
  })

  it('기본 선택은 「백로그」다', async () => {
    renderDialog()
    await screen.findByText(L.summary(1, 3))
    expect(within(moveTargetSelect()).getByRole('option', { name: L.backlogOption })).toBeInstanceOf(
      HTMLOptionElement,
    )
    expect((moveTargetSelect() as HTMLSelectElement).selectedOptions[0]?.textContent).toBe(
      L.backlogOption,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CP-2 — 이관을 전부 끝낸 뒤에 완료한다
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — T-CP-2 요청 순서 (FR-6)', () => {
  it('백로그 이관은 이슈당 DELETE 1회이고, 전부 끝난 뒤에 complete 가 나간다', async () => {
    const { user, onOpenChange } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())

    await waitFor(() => expect(calls).toContain(COMPLETE))
    expect(calls).toEqual([
      del('ATLAS-1'),
      del('ATLAS-2'),
      del('ATLAS-3'),
      GET_BACKLOG,
      COMPLETE,
    ])
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('다른 스프린트 이관은 이슈당 DELETE → POST 2회이고, 완료는 맨 마지막이다', async () => {
    const { user } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.selectOptions(moveTargetSelect(), PLANNED_ID)
    await user.click(submitButton())

    await waitFor(() => expect(calls).toContain(COMPLETE))
    expect(calls).toEqual([
      del('ATLAS-1'),
      post(PLANNED_ID, 'ATLAS-1'),
      del('ATLAS-2'),
      post(PLANNED_ID, 'ATLAS-2'),
      del('ATLAS-3'),
      post(PLANNED_ID, 'ATLAS-3'),
      GET_BACKLOG,
      COMPLETE,
    ])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CP-3 / T-CP-4 — 부분 실패와 재시도
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — T-CP-3 부분 실패 (FR-6)', () => {
  it('3건 중 1건이 실패하면 complete 를 한 번도 보내지 않는다', async () => {
    deleteOutcomes = { 'ATLAS-2': [500] }
    const { user, onOpenChange } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())

    expect(await screen.findByText(L.moveFailedAlert(3, 1))).toBeInTheDocument()
    expect(calls.filter((c) => c === COMPLETE)).toHaveLength(0)
    expect(calls).not.toContain(GET_BACKLOG)
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('성공 행은 「이관됨」으로 잠기고 실패 행만 「이관 실패」다', async () => {
    deleteOutcomes = { 'ATLAS-2': [500] }
    const { user } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())
    await screen.findByText(L.moveFailedAlert(3, 1))

    expect(within(rowAt(0)).getByText(L.rowMoved)).toBeInTheDocument()
    expect(within(rowAt(1)).getByText(L.rowFailed)).toBeInTheDocument()
    expect(within(rowAt(2)).getByText(L.rowMoved)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: backlogLabels.retry })).toBeInTheDocument()
  })

  it('실패 요약은 role="alert" 로 노출된다', async () => {
    deleteOutcomes = { 'ATLAS-2': [500] }
    const { user } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(L.moveFailedAlert(3, 1))
  })
})

describe('CompleteSprintDialog — T-CP-4 재시도 단위 (FR-6)', () => {
  it('재시도는 실패한 이슈만 다시 보낸다 — 성공한 2건의 요청 수가 늘지 않는다', async () => {
    deleteOutcomes = { 'ATLAS-2': [500] }
    const { user } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())
    await screen.findByText(L.moveFailedAlert(3, 1))

    const before = { one: countOf(del('ATLAS-1')), three: countOf(del('ATLAS-3')) }
    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    await waitFor(() => expect(calls).toContain(COMPLETE))
    expect(countOf(del('ATLAS-1'))).toBe(before.one)
    expect(countOf(del('ATLAS-3'))).toBe(before.three)
    expect(countOf(del('ATLAS-2'))).toBe(2)
  })
})

/** `calls` 안에서 정확히 일치하는 항목의 개수 */
function countOf(entry: string): number {
  return calls.filter((c) => c === entry).length
}

// ─────────────────────────────────────────────────────────────────────────────
// E12 — 이관 도중 대상이 COMPLETED 로 전환
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — E12 대상 스프린트가 이관 중 COMPLETED 로 전환', () => {
  it('POST 409 인 행만 실패하고 Select 는 잠기지 않는다', async () => {
    postOutcomes = { 'ATLAS-2': [409] }
    const { user } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.selectOptions(moveTargetSelect(), PLANNED_ID)
    await user.click(submitButton())

    expect(await screen.findByText(L.moveFailedAlert(3, 1))).toBeInTheDocument()
    expect(within(rowAt(1)).getByText(L.rowFailed)).toBeInTheDocument()
    expect(screen.getAllByText(L.rowMoved)).toHaveLength(2)
    // 대상을 다시 골라야 하므로 잠그지 않는다
    expect(moveTargetSelect()).not.toBeDisabled()
    expect(calls.filter((c) => c === COMPLETE)).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C-15 — 이관 권한(UPDATE)과 완료 권한(CREATE)이 다르다
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — C-15 권한 게이팅', () => {
  it('canReorderIssue=false 이면 이관 UI 를 감추고 제출을 막는다', async () => {
    renderDialog({ canReorderIssue: false })
    await screen.findByText(L.summary(1, 3))

    expect(screen.queryByRole('combobox', { name: L.moveTargetLabel })).not.toBeInTheDocument()
    expect(await screen.findByText(L.moveForbidden)).toBeInTheDocument()
    expect(submitButton()).toBeDisabled()
  })

  it('이관이 403 으로 끊기면 권한 없음 문구를 보인다', async () => {
    deleteOutcomes = { 'ATLAS-1': [403], 'ATLAS-2': [403], 'ATLAS-3': [403] }
    const { user } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())

    expect(await screen.findByText(L.moveForbidden)).toBeInTheDocument()
    expect(calls.filter((c) => c === COMPLETE)).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E19 — 이관 진행 중에는 닫히지 않는다
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — E19 이관 중 닫기 차단', () => {
  it('이관 진행 중 Esc 로 닫히지 않는다', async () => {
    let release: () => void = () => undefined
    deleteGate = new Promise<void>((resolve) => {
      release = resolve
    })
    const { user, onOpenChange } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())
    await screen.findByText(L.moveProgress(0, 3))

    await user.keyboard('{Escape}')
    expect(onOpenChange).not.toHaveBeenCalled()

    release()
    await waitFor(() => expect(calls).toContain(COMPLETE))
  })

  it('짝 — 유휴 상태에서는 Esc 로 닫힌다 (위 단언이 공허하지 않다는 증거)', async () => {
    const { user, onOpenChange } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.keyboard('{Escape}')

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E11 — 미완료 0건
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — E11 미완료 0건', () => {
  it('목록·Select 없이 안내만 보이고 이관 요청 없이 완료한다', async () => {
    const { user } = renderDialog({ sprint: DONE_ONLY_SPRINT })

    expect(await screen.findByText(L.noIssuesToMove)).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: L.moveTargetLabel })).not.toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)

    await user.click(submitButton())

    await waitFor(() => expect(calls).toContain(COMPLETE))
    expect(calls).toEqual([GET_BACKLOG, COMPLETE])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E15 · truncated 정책 — 두 값 각각
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — E15 truncated 차단 정책', () => {
  it('정책 상수는 「차단한다」로 고정돼 있다 (Maxi 재확정 2026-08-05)', () => {
    expect(BLOCK_COMPLETE_WHEN_TRUNCATED).toBe(true)
  })

  it('정책 두 값 각각이 실제로 다른 결과를 만든다', () => {
    // ★ 리터럴 타입으로 좁히면 이 짝이 도달 불가가 되어 가짜 그린이 된다.
    //   정책을 인자로 받게 해 두 값 모두 실제로 실행한다.
    expect(isSubmitBlockedByTruncation(true, true)).toBe(true)
    expect(isSubmitBlockedByTruncation(true, false)).toBe(false)
    expect(isSubmitBlockedByTruncation(false, true)).toBe(false)
    expect(isSubmitBlockedByTruncation(false, false)).toBe(false)
  })

  it('truncated=true 면 제출 버튼이 비활성이고 차단 문구를 보인다', async () => {
    renderDialog({ truncated: true })

    expect(await screen.findByText(L.truncatedBlocked)).toBeInTheDocument()
    expect(submitButton()).toBeDisabled()
  })

  it('truncated=false 면 제출할 수 있다 (반대 분기)', async () => {
    renderDialog({ truncated: false })
    await screen.findByText(L.summary(1, 3))

    expect(screen.queryByText(L.truncatedBlocked)).not.toBeInTheDocument()
    expect(submitButton()).not.toBeDisabled()
  })

  it('C-8 — 미완료 0건이어도 truncated 가 이긴다', async () => {
    renderDialog({ sprint: DONE_ONLY_SPRINT, truncated: true })

    expect(await screen.findByText(L.truncatedBlocked)).toBeInTheDocument()
    // 목록이 불완전하면 「0건」이라는 관측 자체를 믿을 수 없다
    expect(screen.queryByText(L.noIssuesToMove)).not.toBeInTheDocument()
    expect(submitButton()).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E14 — 워크플로우 조회 실패 (fail-safe)
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — E14 워크플로우 조회 실패', () => {
  /** 조회를 500 으로 끊는다 — `useWorkflows().data` 가 undefined 로 남는다 */
  function breakWorkflows(): void {
    server.use(http.get('/api/v1/workflows', () => new HttpResponse(null, { status: 500 })))
  }

  it('전부 미완료로 보고 안내를 띄우되 제출은 잠근다', async () => {
    breakWorkflows()
    renderDialog()

    expect(await screen.findByText(L.workflowLoadFailed)).toBeInTheDocument()
    expect(screen.getByText(L.summary(0, 4))).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(4)
    // ★ 분류를 모르는 채 완료하면 `isIssueIncomplete` 가 전건을 미완료로 봐서
    //   **완료된 이슈까지 전량 반출**된다. COMPLETED 스프린트에는 되돌려 넣을 수 없다(409).
    expect(submitButton()).toBeDisabled()
  })

  it('T15 — 안내가 「막았다」는 사실과 처방을 함께 말한다', async () => {
    breakWorkflows()
    renderDialog()

    // 버튼만 잠그고 문구는 「모든 이슈를 미완료로 봅니다」까지만 말하면, 사용자는 왜
    // 눌리지 않는지 알 수 없다 — 이 PR 이 BLOCKER 로 다뤄 온 「안내가 사실과 다름」이다.
    const alert = await screen.findByText(L.workflowLoadFailed)
    expect(alert).toHaveTextContent('완료할 수 없습니다')
    expect(alert).toHaveTextContent('다시 시도')
    expect(submitButton()).toBeDisabled()
  })

  it('그 상태에서 제출을 눌러도 이관·완료 요청이 한 건도 나가지 않는다', async () => {
    breakWorkflows()
    const { user, onOpenChange } = renderDialog()
    await screen.findByText(L.workflowLoadFailed)

    await user.click(submitButton())

    // 잠갔다는 표시(`disabled`)만으로는 부족하다 — 실제로 무엇이 나갔는지를 잰다
    expect(calls).toHaveLength(0)
    expect(onOpenChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 워크플로우 로딩 중 — 콜드 페치가 끝나기 전에는 분류를 모른다
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — 워크플로우 로딩 중 제출 차단', () => {
  it('로딩 중에는 제출이 잠기고 눌러도 요청이 0건이며, 도착하면 같은 버튼이 열린다', async () => {
    let release: () => void = () => undefined
    workflowGate = new Promise<void>((resolve) => {
      release = resolve
    })
    const { user } = renderDialog()

    expect(submitButton()).toBeDisabled()
    await user.click(submitButton())
    expect(calls).toHaveLength(0)

    // 짝 — 분류가 도착하면 같은 버튼이 실제로 열린다. 위 단언이 「항상 잠김」이 아님을 증명한다
    release()
    await screen.findByText(L.summary(1, 3))
    expect(submitButton()).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C-7 — complete 직전 재검증
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — C-7 완료 직전 재검증', () => {
  it('그 사이 남이 이슈를 추가했으면 완료를 중단하고 목록을 갱신한다', async () => {
    freshBacklog = {
      backlog: [],
      sprints: [
        { sprint: SOURCE_META, issues: [issue('ATLAS-9', 'done'), issue('ATLAS-77')] },
      ],
      truncated: false,
    }
    const { user, onOpenChange } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())

    await waitFor(() => expect(calls).toContain(GET_BACKLOG))
    expect(calls.filter((c) => c === COMPLETE)).toHaveLength(0)
    expect(onOpenChange).not.toHaveBeenCalled()
    // 목록이 최신 미완료로 갱신된다
    expect(await screen.findByText('ATLAS-77')).toBeInTheDocument()
    // T15 — 전용 문구를 쓴다. 시작 다이얼로그의 409 문구를 빌려 쓰면 「입력하신 값은
    // 그대로 두었으니」가 **입력 폼이 없는** 이 다이얼로그에서 거짓이 된다.
    expect(screen.getByRole('alert')).toHaveTextContent(L.staleBlocked)
    expect(
      screen.queryByText(backlogLabels.startDialog.patchConflict),
    ).not.toBeInTheDocument()
  })

  it('재검증 응답이 truncated 면 완료를 보내지 않는다', async () => {
    freshBacklog = { ...emptyFreshBacklog(), truncated: true }
    const { user } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(submitButton())

    await waitFor(() => expect(calls).toContain(GET_BACKLOG))
    expect(calls.filter((c) => c === COMPLETE)).toHaveLength(0)
    expect(await screen.findByText(L.truncatedBlocked)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 푸터 — 취소
// ─────────────────────────────────────────────────────────────────────────────

describe('CompleteSprintDialog — 푸터', () => {
  it('취소 버튼은 기존 문자열을 재사용하고 다이얼로그를 닫는다', async () => {
    const { user, onOpenChange } = renderDialog()
    await screen.findByText(L.summary(1, 3))

    await user.click(screen.getByRole('button', { name: issueCreateStrings.cancelButton }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(calls).toHaveLength(0)
  })
})
