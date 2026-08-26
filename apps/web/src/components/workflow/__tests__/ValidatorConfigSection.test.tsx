// ValidatorConfigSection 단위 테스트 — 게이팅/목록/editable 소비/평가 시점 배지/저장 400/하드 삭제/baseline 병합
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { handlers } from '@/mocks/handlers'
import { useAuthStore } from '@/auth/authStore'
import { softwareDefaultFixture } from '@/mocks/workflow-fixtures'
import {
  SEEDED_VALIDATOR_TRANSITION_ID,
  SEEDED_VALIDATOR_WORKFLOW_KEY,
  VALIDATOR_TYPES,
  resetValidatorStore,
} from '@/mocks/validator-handlers'
import type { ValidatorResponse } from '@/api/validators'
import {
  validatorDeleteButtonLabel,
  validatorEditButtonLabel,
  validatorErrorMessage,
  validatorLabels,
} from '@/i18n/validator-labels'
import { ValidatorConfigSection } from '@/components/workflow/ValidatorConfigSection'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 목의 시드 좌표를 그대로 쓴다. 여기에 UUID 를 다시 적으면 목과 갈리는 두 번째 목록이 된다.
// ─────────────────────────────────────────────────────────────────────────────

const WF_KEY = SEEDED_VALIDATOR_WORKFLOW_KEY
const TX_ID = SEEDED_VALIDATOR_TRANSITION_ID
const BASE_PATH = `/api/v1/workflows/${WF_KEY}/transitions/${TX_ID}/validators`

/**
 * 규칙이 하나도 없는 전환 — 빈 상태(E4)를 재현한다.
 *
 * 목은 시드를 **첫 NORMAL 전환 하나에만** 심으므로 그 밖의 전환은 전부 0건이다. id 를 리터럴로
 * 적으면 `<select>` 의 선택지에 없는 값이 되어 `fireEvent.change` 가 조용히 아무 일도 안 한다 —
 * 그래서 픽스처에서 도출하고, 도출에 실패하면 그 자리에서 터뜨린다.
 */
const emptyTransition = softwareDefaultFixture.transitions.find(
  (t) => t.id !== SEEDED_VALIDATOR_TRANSITION_ID,
)
if (emptyTransition === undefined) {
  throw new Error('software-default 픽스처에 시드 전환 말고 다른 전환이 없다 — 빈 상태를 재현할 자리가 사라졌다.')
}
const EMPTY_TX_ID: string = emptyTransition.id

/** 시드 목록에서 편집 불가 두 행이 놓이는 1-based 순번 (displayOrder ASC). */
const CUSTOM_EXPRESSION_ROW = 4
const BROKEN_ROW = 5

const adminUser = {
  userId: 'u1',
  username: 'admin',
  email: 'admin@bts.local',
  authMethod: 'local',
  mustChangePassword: false,
  isSystemAdmin: true,
  mfaEnrollmentRequired: false,
} as const

function loginAs(isSystemAdmin: boolean): void {
  useAuthStore.setState({ accessToken: 'token', user: { ...adminUser, isSystemAdmin } })
}

function renderSection(): void {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <ValidatorConfigSection workflowKey={WF_KEY} transitions={softwareDefaultFixture.transitions} />
    </QueryClientProvider>,
  )
}

/** 전환을 고른다 — 값은 전환 id(UUID) 다(제약 C4). */
function selectTransition(transitionId: string): void {
  fireEvent.change(screen.getByLabelText(validatorLabels.section.transitionSelectLabel), {
    target: { value: transitionId },
  })
}

/** 시드 목록이 화면에 닿을 때까지 기다린다. */
async function waitForSeedRows(): Promise<void> {
  await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())
  await screen.findByText(VALIDATOR_TYPES.customExpression)
}

beforeEach(() => {
  // 통합 배열을 켠다 — `mocks/handlers.ts` 등록 누락이 여기서도 red 가 되게 한다.
  server.use(...handlers)
  resetValidatorStore()
  loginAs(true)
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 게이팅
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorConfigSection — isSystemAdmin 게이팅', () => {
  it('T6-1: isSystemAdmin 이 아니면 렌더하지 않는다', () => {
    loginAs(false)
    renderSection()
    expect(screen.queryByLabelText(validatorLabels.section.transitionSelectLabel)).not.toBeInTheDocument()
    expect(screen.queryByText(validatorLabels.section.title)).not.toBeInTheDocument()
  })

  it('T6-1b: 미인증(user=null)이면 렌더하지 않는다', () => {
    useAuthStore.setState({ accessToken: null, user: null })
    renderSection()
    expect(screen.queryByLabelText(validatorLabels.section.transitionSelectLabel)).not.toBeInTheDocument()
  })

  it('T6-1c: 시스템 관리자에게는 섹션이 보인다', () => {
    renderSection()
    expect(screen.getByText(validatorLabels.section.title)).toBeInTheDocument()
    expect(screen.getByLabelText(validatorLabels.section.transitionSelectLabel)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 목록 — 로딩/에러/빈/표
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorConfigSection — 목록', () => {
  it('T6-2: 전환을 고르면 규칙 목록이 뜬다', async () => {
    renderSection()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()

    selectTransition(TX_ID)
    await waitForSeedRows()

    // 시드 5행이 전부 보인다 — 편집 불가 행도 숨기지 않는다(FR-2).
    expect(screen.getAllByRole('row')).toHaveLength(6) // 헤더 1 + 본문 5
    expect(screen.getByText('field=resolution')).toBeInTheDocument()
  })

  it('T6-3: 규칙 0건이면 빈 상태와 추가 유도가 보인다', async () => {
    renderSection()
    selectTransition(EMPTY_TX_ID)

    expect(await screen.findByText(validatorLabels.list.emptyTitle)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: validatorLabels.list.emptyActionButton }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('T6-4: 목록 조회가 실패하면 빈 화면이 아니라 안내와 재시도가 뜬다', async () => {
    server.use(http.get(BASE_PATH, () => new HttpResponse(null, { status: 500 })))
    renderSection()
    selectTransition(TX_ID)

    expect(await screen.findByText(validatorLabels.error.loadFailed)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: validatorLabels.error.retryButton })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// editable 소비 (S4·S5) + 평가 시점 배지
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorConfigSection — editable 과 평가 시점', () => {
  it('T6-5: 편집 불가 행이 목록에 보이고 편집이 비활성이며 이유가 뜬다 (S4)', async () => {
    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    // 숨기지 않는다.
    expect(screen.getByText(VALIDATOR_TYPES.customExpression)).toBeInTheDocument()

    const editButton = screen.getByLabelText(
      validatorEditButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW),
    )
    expect(editButton).toBeDisabled()
    expect(screen.getByText(validatorLabels.list.notEditableTypeReason)).toBeInTheDocument()

    // 삭제는 막지 않는다 — backend 가 막지 않는다.
    expect(
      screen.getByLabelText(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW)),
    ).toBeEnabled()
  })

  it('T6-6: phase=null 행은 판정 불가 배지 + 편집 비활성이다 (S5)', async () => {
    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    expect(screen.getByText(validatorLabels.list.phase.unknown)).toBeInTheDocument()
    expect(
      screen.getByLabelText(validatorEditButtonLabel(VALIDATOR_TYPES.requiredField, BROKEN_ROW)),
    ).toBeDisabled()
    expect(screen.getByText(validatorLabels.list.notEditableBrokenReason)).toBeInTheDocument()
  })

  it('T6-7: 평가 시점 배지가 색이 아니라 텍스트로도 뜻을 전한다', async () => {
    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    expect(screen.getByText(validatorLabels.list.phase.EXECUTION)).toBeInTheDocument()
    expect(screen.getAllByText(validatorLabels.list.phase.AVAILABILITY).length).toBeGreaterThan(0)
    expect(screen.getByText(validatorLabels.list.phase.unknown)).toBeInTheDocument()
  })

  it('T6-8: editable 은 응답 값을 그대로 쓴다 — 같은 type 이라도 응답이 true 면 편집이 열린다', async () => {
    // `CustomExpression` 인데 editable=true 인 응답. 화면이 type 으로 판정하면 여기서 red 가 난다.
    const row: ValidatorResponse = {
      id: '99999999-9999-4999-8999-999999999999',
      type: VALIDATOR_TYPES.customExpression,
      config: { expression: 'true' },
      displayOrder: 0,
      phase: 'AVAILABILITY',
      editable: true,
    }
    server.use(http.get(BASE_PATH, () => HttpResponse.json({ data: [row] })))

    renderSection()
    selectTransition(TX_ID)

    const editButton = await screen.findByLabelText(
      validatorEditButtonLabel(VALIDATOR_TYPES.customExpression, 1),
    )
    expect(editButton).toBeEnabled()
    expect(screen.queryByText(validatorLabels.list.notEditableTypeReason)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 추가 — type 선택지 + 저장 실패
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorConfigSection — 규칙 추가', () => {
  it('T6-9: 추가 폼의 type 선택지에 CustomExpression 이 없다', async () => {
    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    fireEvent.click(screen.getByRole('button', { name: validatorLabels.section.addButton }))

    const dialog = await screen.findByRole('dialog', { name: validatorLabels.dialog.createTitle })
    const typeSelect = within(dialog).getByLabelText(validatorLabels.form.typeLabel)
    const options = within(typeSelect).getAllByRole('option').map((o) => (o as HTMLOptionElement).value)

    expect(options).not.toContain(VALIDATOR_TYPES.customExpression)
    expect(options).toContain(VALIDATOR_TYPES.requiredField)
    expect(options).toContain(VALIDATOR_TYPES.permissionCheck)
    expect(options).toContain(VALIDATOR_TYPES.notStatusCategory)
  })

  it('T6-10: 추가에 성공하면 목록이 갱신된다', async () => {
    renderSection()
    selectTransition(EMPTY_TX_ID)
    await screen.findByText(validatorLabels.list.emptyTitle)

    fireEvent.click(screen.getByRole('button', { name: validatorLabels.list.emptyActionButton }))
    const dialog = await screen.findByRole('dialog', { name: validatorLabels.dialog.createTitle })
    fireEvent.change(within(dialog).getByLabelText(validatorLabels.form.typeLabel), {
      target: { value: VALIDATOR_TYPES.notStatusCategory },
    })
    fireEvent.change(within(dialog).getByLabelText(/category/), { target: { value: 'DONE' } })
    fireEvent.click(within(dialog).getByRole('button', { name: validatorLabels.dialog.createButton }))

    await waitFor(() => expect(screen.getByText('category=DONE')).toBeInTheDocument())
    expect(screen.queryByText(validatorLabels.list.emptyTitle)).not.toBeInTheDocument()
  })

  it('T6-11: 저장 400 이 다이얼로그 안에 뜨고 다이얼로그가 닫히지 않는다 (S6)', async () => {
    // 경로는 **고른 전환**으로 나간다 — 시드 전환의 경로로 목을 걸면 요청이 안 잡혀 400 이 재현되지 않는다.
    server.use(
      http.post(`/api/v1/workflows/${WF_KEY}/transitions/${EMPTY_TX_ID}/validators`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_VALIDATOR_INVALID', message: 'config 가 부적합합니다' } },
          { status: 400 },
        ),
      ),
    )

    renderSection()
    selectTransition(EMPTY_TX_ID)
    await screen.findByText(validatorLabels.list.emptyTitle)

    fireEvent.click(screen.getByRole('button', { name: validatorLabels.section.addButton }))
    const dialog = await screen.findByRole('dialog', { name: validatorLabels.dialog.createTitle })
    fireEvent.change(within(dialog).getByLabelText(validatorLabels.form.typeLabel), {
      target: { value: VALIDATOR_TYPES.requiredField },
    })
    fireEvent.change(within(dialog).getByLabelText(/field/), { target: { value: 'resolution' } })
    fireEvent.click(within(dialog).getByRole('button', { name: validatorLabels.dialog.createButton }))

    const message = validatorErrorMessage('WORKFLOW_VALIDATOR_INVALID')
    expect(await within(dialog).findByText(message)).toBeInTheDocument()
    // 닫히지 않는다.
    expect(screen.getByRole('dialog', { name: validatorLabels.dialog.createTitle })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 수정 — 프리필 격리(형제 E2E T4 회귀) + baseline 병합(제약 C6)
// ─────────────────────────────────────────────────────────────────────────────

/** 같은 type 두 행 — 프리필이 행 사이로 새는지 재기 위한 최소 픽스처. */
const rowA: ValidatorResponse = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  type: VALIDATOR_TYPES.requiredField,
  config: { field: 'alpha' },
  displayOrder: 0,
  phase: 'EXECUTION',
  editable: true,
}

const rowB: ValidatorResponse = {
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  type: VALIDATOR_TYPES.requiredField,
  config: { field: 'beta' },
  displayOrder: 1,
  phase: 'EXECUTION',
  editable: true,
}

describe('ValidatorConfigSection — 규칙 수정', () => {
  it('T6-12: 2행 중 A 를 편집하면 A 값이 프리필되고 B 값이 새지 않는다', async () => {
    server.use(http.get(BASE_PATH, () => HttpResponse.json({ data: [rowA, rowB] })))

    renderSection()
    selectTransition(TX_ID)
    await screen.findByText('field=alpha')

    // 먼저 B 를 연다 — 그 다음 A 를 열었을 때 B 값이 남아 있으면 그것이 회귀다.
    fireEvent.click(screen.getByLabelText(validatorEditButtonLabel(VALIDATOR_TYPES.requiredField, 2)))
    const dialogB = await screen.findByRole('dialog', { name: validatorLabels.dialog.editTitle })
    expect(within(dialogB).getByLabelText(/field/)).toHaveValue('beta')
    fireEvent.click(within(dialogB).getByRole('button', { name: validatorLabels.dialog.cancelButton }))
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: validatorLabels.dialog.editTitle })).not.toBeInTheDocument(),
    )

    fireEvent.click(screen.getByLabelText(validatorEditButtonLabel(VALIDATOR_TYPES.requiredField, 1)))
    const dialogA = await screen.findByRole('dialog', { name: validatorLabels.dialog.editTitle })
    expect(within(dialogA).getByLabelText(/field/)).toHaveValue('alpha')
  })

  /**
   * ★ 제약 C6 의 본단언.
   *
   * 「보존한다」를 화면 텍스트로 재면 공허하다 — 목록은 폼이 아는 키만 요약하므로 지워진 키가
   * 사라져도 화면은 그대로다. 그래서 **PUT 요청 본문을 가로채** 폼이 모르는 키가 실려 나가는지
   * 직접 잰다. 형제 `PostActionConfigSection` 처럼 `config: { 아는 키만 }` 으로 전체 교체하면
   * 여기서 red 가 난다.
   */
  it('T6-13: 수정 시 폼이 모르는 config 키가 요청 본문에 보존된다 (제약 C6)', async () => {
    const legacyRow: ValidatorResponse = {
      id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      type: VALIDATOR_TYPES.requiredField,
      config: { field: 'resolution', legacyNote: 'keep-me', retries: 3 },
      displayOrder: 0,
      phase: 'EXECUTION',
      editable: true,
    }
    let captured: unknown = null
    server.use(
      http.get(BASE_PATH, () => HttpResponse.json({ data: [legacyRow] })),
      http.put(`${BASE_PATH}/:id`, async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ data: { ...legacyRow, config: { ...legacyRow.config, field: 'assignee' } } })
      }),
    )

    renderSection()
    selectTransition(TX_ID)
    await screen.findByText('field=resolution')

    fireEvent.click(screen.getByLabelText(validatorEditButtonLabel(VALIDATOR_TYPES.requiredField, 1)))
    const dialog = await screen.findByRole('dialog', { name: validatorLabels.dialog.editTitle })
    fireEvent.change(within(dialog).getByLabelText(/field/), { target: { value: 'assignee' } })
    fireEvent.click(within(dialog).getByRole('button', { name: validatorLabels.dialog.saveButton }))

    await waitFor(() => expect(captured).not.toBeNull())
    expect(captured).toEqual({
      type: VALIDATOR_TYPES.requiredField,
      // 아는 키는 덮어쓰고, 모르는 키(`legacyNote`·`retries`)는 그대로 실려 나간다.
      config: { field: 'assignee', legacyNote: 'keep-me', retries: 3 },
      // displayOrder 입력칸은 없다 — 로드한 값을 그대로 되돌려 보낸다(제약 C7).
      displayOrder: 0,
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 삭제 — 하드 삭제 확인 (ADR 2026-08-25)
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorConfigSection — 규칙 삭제', () => {
  it('T6-14: 삭제는 되돌릴 수 없음을 확인받은 뒤에만 실행된다', async () => {
    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    fireEvent.click(
      screen.getByLabelText(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW)),
    )

    const confirm = await screen.findByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    expect(within(confirm).getByText(validatorLabels.dialog.deleteDescription)).toBeInTheDocument()
    expect(validatorLabels.dialog.deleteDescription).toContain('되돌릴 수 없습니다')
    // 확인 전에는 지워지지 않는다.
    expect(screen.getByText(VALIDATOR_TYPES.customExpression)).toBeInTheDocument()

    fireEvent.click(within(confirm).getByRole('button', { name: validatorLabels.dialog.deleteConfirmButton }))

    await waitFor(() =>
      expect(screen.queryByText(VALIDATOR_TYPES.customExpression)).not.toBeInTheDocument(),
    )
  })

  it('T6-15: 확인 다이얼로그에서 되돌아가면 아무것도 지워지지 않는다', async () => {
    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    fireEvent.click(
      screen.getByLabelText(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW)),
    )
    const confirm = await screen.findByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    fireEvent.click(within(confirm).getByRole('button', { name: validatorLabels.dialog.deleteCancelButton }))

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: validatorLabels.dialog.deleteTitle })).not.toBeInTheDocument(),
    )
    expect(screen.getByText(VALIDATOR_TYPES.customExpression)).toBeInTheDocument()
  })

  /**
   * T6-16. 삭제가 실패하면 **확인 창 안에** 사유가 뜬다.
   *
   * ★스코프가 있어야 한다. 종전 제목은 「화면에 남는 안내」였고 판정도 `screen.findByText` 라
   * 「창 안」과 「페이지 위 배너」를 구분하지 못했다 — 부채 139 를 닫으면서 그 구분이 load-bearing
   * 이 됐다(배너는 지웠고 사유는 창 안으로 옮겼다). 스코프 없는 판정은 배너가 되살아나도 초록이다.
   */
  it('T6-16: 삭제가 실패하면 확인 창 안에 사유가 뜬다', async () => {
    server.use(
      http.delete(`${BASE_PATH}/:id`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_SCHEME_ACCESS_DENIED', message: '권한이 없습니다' } },
          { status: 403 },
        ),
      ),
    )

    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    fireEvent.click(
      screen.getByLabelText(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW)),
    )
    const confirm = await screen.findByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    fireEvent.click(within(confirm).getByRole('button', { name: validatorLabels.dialog.deleteConfirmButton }))

    const stillOpen = await screen.findByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    expect(
      within(stillOpen).getByText(validatorErrorMessage('WORKFLOW_SCHEME_ACCESS_DENIED')),
    ).toBeInTheDocument()
    expect(screen.getByText(VALIDATOR_TYPES.customExpression)).toBeInTheDocument()
  })

  /**
   * T6-17. 봉투 없는 삭제 실패는 **삭제 전용 문구**를 쓰고 확인 다이얼로그를 닫지 않는다.
   *
   * `validatorErrorMessage` 는 코드를 못 읽으면 `error.unknown`(「규칙을 **저장**하지 못했습니다.
   * **값을 확인하고** 다시 시도해 주세요.」)으로 떨어진다 — 삭제 경로에는 고칠 값이 없어
   * 사용자가 무엇을 하라는 말인지 알 수 없다. 500 · 프록시 502 · 빈 본문이 그 갈래다.
   *
   * ★ 「removeFailed 가 뜬다」만 재면 공허하다 — 둘 다 띄우는 구현도 통과한다. 그래서
   * **`unknown` 문구가 뜨지 않는 것**을 함께 단언한다.
   */
  it('T6-17: 봉투 없는 삭제 실패는 삭제 전용 문구를 쓰고 확인 창을 닫지 않는다', async () => {
    server.use(http.delete(`${BASE_PATH}/:id`, () => new HttpResponse(null, { status: 500 })))

    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    fireEvent.click(
      screen.getByLabelText(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW)),
    )
    const confirm = await screen.findByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    fireEvent.click(within(confirm).getByRole('button', { name: validatorLabels.dialog.deleteConfirmButton }))

    expect(await screen.findByText(validatorLabels.error.removeFailed)).toBeInTheDocument()
    expect(screen.queryByText(validatorLabels.error.unknown)).not.toBeInTheDocument()

    // 삭제가 실패했으므로 행은 목록에 남는다.
    expect(screen.getByText(VALIDATOR_TYPES.customExpression)).toBeInTheDocument()

    // ★확인 창이 **열린 채 남고**, 사유가 **그 창 안**에 있다.
    //   종전에는 공용 `ConfirmDialog` 가 `onConfirm()` 직후 스스로 닫아 둘 다 불가능했고,
    //   이 자리의 주석이 「재지 않는다」고 적혀 있었다 — 제목은 「닫지 않는다」인데 판정이
    //   없는 상태였다. 부채 139 를 닫으면서 그 판정을 실제로 넣는다.
    const stillOpen = screen.getByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    expect(stillOpen).toBeInTheDocument()
    expect(within(stillOpen).getByText(validatorLabels.error.removeFailed)).toBeInTheDocument()
  })

  /**
   * T6-18. 삭제에 성공하면 확인 창이 닫힌다.
   *
   * T6-17 의 거울상이다. 「실패하면 남는다」만 재면 **아무 때도 안 닫는** 구현이 통과한다.
   */
  it('T6-18: 삭제에 성공하면 확인 창이 닫힌다', async () => {
    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    fireEvent.click(
      screen.getByLabelText(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW)),
    )
    const confirm = await screen.findByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    fireEvent.click(within(confirm).getByRole('button', { name: validatorLabels.dialog.deleteConfirmButton }))

    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: validatorLabels.dialog.deleteTitle }),
      ).not.toBeInTheDocument()
    })
  })

  /**
   * T6-19. 처리 중에는 확인 버튼이 잠긴다 — **실제 경로로** 잰다.
   *
   * `confirm-dialog.test.tsx` 는 `confirming` 을 **직접 넘겨** 재므로 prop 계약만 본다.
   * 종전에는 프리미티브가 확인 직후 닫아 그 상태에 **도달 자체가 불가능**했고, 그래서 그
   * 판정만으로는 도달 불가 조합을 지키는 가짜 그린이었다
   * (`unreachable-state-fixture-is-fake-green`). 여기서 응답을 붙잡아 실제로 그 상태를 만든다.
   */
  it('T6-19: 삭제 처리 중에는 확인 버튼이 잠긴다 (도달 가능해진 상태)', async () => {
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.delete(`${BASE_PATH}/:id`, async () => {
        await held
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderSection()
    selectTransition(TX_ID)
    await waitForSeedRows()

    fireEvent.click(
      screen.getByLabelText(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, CUSTOM_EXPRESSION_ROW)),
    )
    const confirm = await screen.findByRole('dialog', { name: validatorLabels.dialog.deleteTitle })
    const confirmButton = within(confirm).getByRole('button', {
      name: validatorLabels.dialog.deleteConfirmButton,
    })
    fireEvent.click(confirmButton)

    await waitFor(() => {
      expect(confirmButton).toBeDisabled()
    })

    release?.()
    // 응답을 풀어 준 뒤 창이 닫히는 것까지 본다 — 잠긴 채 영원히 남지 않는다.
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: validatorLabels.dialog.deleteTitle }),
      ).not.toBeInTheDocument()
    })
  })
})
