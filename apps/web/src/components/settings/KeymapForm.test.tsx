// KeymapForm 컴포넌트 테스트 — 키 캡처·leader 시퀀스·실시간 충돌 배지·기본값 복원·저장·서버 409 처리 (FR-PF-03 Task 9)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { keymapHandlers, resetKeymapStore } from '@/mocks/keymap-handlers'
import { KeymapForm } from './KeymapForm'
import { CONTEXT_SHORTCUTS } from '@/components/keyboard-shortcuts/context-shortcuts'
import { LEADER_KEY } from '@/components/keyboard-shortcuts/shortcuts'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

function renderForm() {
  const { wrapper } = createWrapper()
  return render(<KeymapForm />, { wrapper })
}

const ALICE_TOKEN = mockAccessToken('alice')

beforeEach(() => {
  resetKeymapStore()
  server.use(...keymapHandlers)
  useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  server.resetHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 — 5종 action의 현재 키 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 렌더', () => {
  it('T1: 로딩 중에는 안내 문구가 렌더된다', () => {
    renderForm()
    expect(screen.getByText('단축키 설정을 불러오는 중입니다.')).toBeInTheDocument()
  })

  it('T2: 로딩 완료 후 5종 action 키 캡처 input이 기본값으로 렌더된다', async () => {
    renderForm()
    expect(await screen.findByLabelText('단축키 도움말 단축키 입력')).toHaveValue('?')
    expect(screen.getByLabelText('새 이슈 생성 단축키 입력')).toHaveValue('c')
    expect(screen.getByLabelText('검색으로 이동 단축키 입력')).toHaveValue('/')
    expect(screen.getByLabelText('내 이슈로 이동 단축키 입력')).toHaveValue('g i')
    expect(screen.getByLabelText('대시보드로 이동 단축키 입력')).toHaveValue('g d')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 키 캡처 — single / leader 시퀀스 / Esc / Enter 제외
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 키 캡처', () => {
  it('T3: 단일 키 입력 시 해당 action의 key_combo가 갱신된다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')

    fireEvent.keyDown(searchInput, { key: 'k' })

    expect(searchInput).toHaveValue('k')
  })

  it('T4: g 다음 x 입력 시 leader 시퀀스 "g x"로 갱신된다', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'g' })
    fireEvent.keyDown(createIssueInput, { key: 'x' })

    expect(createIssueInput).toHaveValue('g x')
  })

  it('T5: Escape 입력 시 대기 중인 leader 캡처가 취소된다(다음 키는 single로 취급)', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'g' })
    fireEvent.keyDown(createIssueInput, { key: 'Escape' })
    fireEvent.keyDown(createIssueInput, { key: 'x' })

    expect(createIssueInput).toHaveValue('x')
  })

  it('T6: Enter 입력은 캡처 대상에서 제외되어 값이 바뀌지 않는다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')

    fireEvent.keyDown(searchInput, { key: 'Enter' })

    expect(searchInput).toHaveValue('/')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 실시간 충돌 배지 — 완전중복 / dead leader
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 실시간 충돌 배지', () => {
  it('T7: 완전중복(search를 create-issue와 같은 "c"로) 시 배지가 즉시 표시된다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')

    fireEvent.keyDown(searchInput, { key: 'c' })

    // 완전중복은 관련된 두 action(create-issue/search) 행 모두에 동일 메시지가 배지로 표시된다.
    const badges = await screen.findAllByText('"c"가 새 이슈 생성, 검색으로 이동에 중복 배정되었습니다.')
    expect(badges).toHaveLength(2)
  })

  it('T8: dead leader("g g") 시 배지가 즉시 표시된다', async () => {
    renderForm()
    const myIssuesInput = await screen.findByLabelText('내 이슈로 이동 단축키 입력')

    fireEvent.keyDown(myIssuesInput, { key: 'g' })
    fireEvent.keyDown(myIssuesInput, { key: 'g' })

    expect(myIssuesInput).toHaveValue('g g')
    expect(await screen.findByText('"g g"는 사용할 수 없습니다. (내 이슈로 이동)')).toBeInTheDocument()
  })

  it('T9: 충돌이 있으면 저장 버튼이 비활성화된다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    fireEvent.keyDown(searchInput, { key: 'c' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '단축키 설정 저장' })).toBeDisabled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컨텍스트 단축키 예약 키 (FR-UX-10 F10)
//
// F10 이 목록 항법 키(j/k/o/t/[)를 도입하면서 전역 단축키와 **같은 키 공간**을 공유하게
// 됐다. 전역 판별이 컨텍스트보다 먼저 돌므로, 사용자가 전역 action 을 `j` 로 재배치하면
// 목록 항법이 조용히 죽고 도움말 모달은 여전히 "j → 다음 이슈로 이동"이라고 광고한다.
// 서버 KeymapValidator 6종은 CONTEXT_SHORTCUTS 를 모르므로(ADR D-1 — 컨텍스트 키는
// 프론트 전용) 이 폼이 유일한 게이트다.
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 컨텍스트 단축키 예약 키 (FR-UX-10 F10)', () => {
  it('★전역 action 을 목록 항법 키 "j" 로 재배치하면 예약 배지가 뜬다', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'j' })

    expect(createIssueInput).toHaveValue('j')
    expect(
      await screen.findByText(
        '"j"는 다음 이슈로 이동에 예약된 키입니다. 다른 키를 선택해 주세요. (새 이슈 생성)',
      ),
    ).toBeInTheDocument()
  })

  it('★예약 키 충돌 시 저장이 막힌다 (죽은 채로 저장되지 않는다)', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'j' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '단축키 설정 저장' })).toBeDisabled()
    })
  })

  it('컨텍스트 키 전부가 예약된다 — 캡처 UI 가 바인딩할 수 있는 키에 한해 (레지스트리 파생)', async () => {
    // 정규식이 아니라 메시지 전문을 조립해 비교한다 — `[` 같은 키가 정규식 특수문자라
    // 이스케이프 실수가 나기 쉽고, 전문 비교가 표기 drift 도 함께 잡는다.
    for (const { key, description } of CONTEXT_SHORTCUTS.filter(
      (s) => !UNBINDABLE_CAPTURE_KEYS.has(s.key),
    )) {
      const { unmount } = renderForm()
      const input = await screen.findByLabelText('새 이슈 생성 단축키 입력')

      fireEvent.keyDown(input, { key })

      expect(
        await screen.findByText(
          `"${key}"는 ${description}에 예약된 키입니다. 다른 키를 선택해 주세요. (새 이슈 생성)`,
        ),
      ).toBeInTheDocument()
      unmount()
    }
  })

  it('★leader combo 는 막지 않는다 — "g j" 는 leader 대기 중이라 컨텍스트와 충돌하지 않는다 (E6)', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'g' })
    fireEvent.keyDown(createIssueInput, { key: 'j' })

    expect(createIssueInput).toHaveValue('g j')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '단축키 설정 저장' })).toBeEnabled()
    })
    expect(screen.queryByText(/예약된 키입니다/)).not.toBeInTheDocument()
  })

  it('비-예약 키는 그대로 통과한다 (가드가 과잉 차단하지 않는다)', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'n' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '단축키 설정 저장' })).toBeEnabled()
    })
    expect(screen.queryByText(/예약된 키입니다/)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 예약 목록이 레지스트리에서 **파생**되는지의 증명 (FR-UX-10 F11 Task 7 / FR7)
//
// F10 의 ADR D-4 가 *"하드코딩하면 F11 이 상세 액션 8종을 추가할 때 이 가드만 뒤처져
// 조용히 뚫린다"* 고 **F11 을 지목해** 경고했다. `KeymapForm.tsx` 는 이미
// `CONTEXT_SHORTCUTS` 에서 파생하므로(`RESERVED_CONTEXT_KEYS`) **프로덕션 코드는 한 줄도
// 바뀌지 않는다** — 아래 세 테스트는 처음부터 green 이다.
//
// green-first 는 TDD 규율의 예외가 아니라 **회귀 가드**다. 그 green 이 가짜가 아님은
// `RESERVED_CONTEXT_KEYS` 를 `new Map([['j','x']])` 로 바꾸는 뮤테이션이 아래 2·3번을
// **모두 red** 로 만드는 것으로 확인했다(계열 `two-lists-never-check-each-other`).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * F11 이 추가한 상세 액션 8종 — **레지스트리에서 파생하지 않고 손으로 적는다.**
 *
 * 파생하면 「레지스트리는 레지스트리와 같다」는 항진명제가 되어 키가 통째로 사라져도
 * 조용히 통과한다. 두 목록이 서로를 검사하려면 한쪽은 독립이어야 한다.
 * (`.` 만 `app-shell` 레이어이고 나머지 7종은 `issue-detail` 이다 — 예약 가드는
 * 레이어를 가리지 않으므로 8종을 한 묶음으로 잰다.)
 */
const F11_DETAIL_ACTION_KEYS: readonly string[] = ['a', 'i', 'm', 'e', 'l', 's', 'w', '.']

/**
 * 예약 여부를 물어볼 후보 키 — 소문자 26종 + 흔한 구두점.
 *
 * 레지스트리 키는 호출부가 합집합으로 더하므로 여기서 빠져도 검사망이 새지 않는다.
 * 이 목록의 역할은 **레지스트리 밖 키가 예약되지 않는다**는 반대 방향을 재는 것이다.
 */
const CANDIDATE_KEY_ALPHABET: readonly string[] = [
  ...'abcdefghijklmnopqrstuvwxyz',
  ...'[].,;/-=`\'',
]

/**
 * 캡처 UI 가 **제어 키**로 소비해 사용자가 바인딩할 수 없는 키.
 *
 * - `LEADER_KEY` — 누르면 leader 대기로 들어가 draft 가 안 바뀐다.
 * - `Escape` — `KeymapForm.handleKeyCapture` 가 leader 대기 취소로 소비한다(F24 로 레지스트리에
 *   들어왔지만 바인딩 대상이 아니다).
 *
 * 🛑 여기에 키를 추가할 때는 「레지스트리에 있다」와 「캡처가 제어로 삼킨다」 **두 전제를 모두**
 *    같은 테스트에서 단언해라. 그러지 않으면 이 집합이 예약 판정을 조용히 면제하는 뒷문이 된다.
 */
const UNBINDABLE_CAPTURE_KEYS = new Set<string>([LEADER_KEY, 'Escape'])

describe('KeymapForm — F11 상세 액션 8종 예약 (FR-UX-10 F11 Task 7)', () => {
  it('★F11 8종이 레지스트리에 실재한다 (독립 목록이 낡았는지 먼저 잰다)', () => {
    const registryKeys = new Set(CONTEXT_SHORTCUTS.map((s) => s.key))

    // 개수가 아니라 **원소 전수 비교** — 개수 가드는 원소가 바뀌어도 숫자만 맞으면 통과한다.
    expect(F11_DETAIL_ACTION_KEYS.filter((key) => !registryKeys.has(key))).toEqual([])
  })

  it('★F11 8종이 전부 예약 키로 막힌다 (KeymapForm.tsx 변경 없이 파생)', async () => {
    for (const key of F11_DETAIL_ACTION_KEYS) {
      const { unmount } = renderForm()
      const input = await screen.findByLabelText('새 이슈 생성 단축키 입력')

      fireEvent.keyDown(input, { key })

      // 캡처 자체가 됐는지를 먼저 확인한다 — 값이 안 바뀐 채 배지만 없는 상태를
      // "예약이 아니다"로 오독하지 않기 위함이다.
      expect(input).toHaveValue(key)
      expect(await screen.findByRole('alert')).toHaveTextContent('예약된 키입니다')
      unmount()
    }
  })

  it('★예약 판정이 레지스트리와 정확히 일치한다 — 후보 키 전수 차집합 0 (하드코딩 회귀 차단)', async () => {
    const registryKeys = new Set<string>(CONTEXT_SHORTCUTS.map((s) => s.key))

    // leader 키는 후보에서 뺀다 — 누르면 draft 가 바뀌지 않고 leader 대기로 들어가
    // 「예약이 아니다」와 「캡처되지 않았다」가 구분되지 않는다. 제외를 정당화하는
    // 전제(leader 키는 컨텍스트 키가 아니다) 자체를 여기서 함께 잰다.
    expect(registryKeys.has(LEADER_KEY)).toBe(false)

    // ★`Escape` 는 레지스트리에 **있고**(F24 드로어 닫기) 캡처 UI 는 그걸 **취소 키**로
    //   소비한다(`KeymapForm.tsx` handleKeyCapture). 즉 사용자가 애초에 바인딩할 수 없으므로
    //   예약될 수도 없다 — 차집합에서 빼되, 「레지스트리에 있다 + 바인딩 불가다」 두 전제를
    //   여기서 함께 재서 조용한 면제가 되지 않게 한다.
    expect(registryKeys.has('Escape')).toBe(true)

    const candidates = [...new Set([...CANDIDATE_KEY_ALPHABET, ...registryKeys])].filter(
      (key) => !UNBINDABLE_CAPTURE_KEYS.has(key),
    )
    const bindableRegistryKeys = new Set(
      [...registryKeys].filter((key) => !UNBINDABLE_CAPTURE_KEYS.has(key)),
    )

    // 후보마다 재마운트하지 않는다 — 키 캡처는 draft 를 통째로 덮어쓰므로 한 폼으로
    // 순회할 수 있고, 40회 가까운 재마운트는 이 스위트에서 워커 기아를 부른다.
    renderForm()
    const input = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    const reserved = new Set<string>()
    for (const key of candidates) {
      fireEvent.keyDown(input, { key })
      // 완전중복(`/` 처럼 다른 action 의 기본 배정과 겹치는 키)은 **다른** 문구라 잡히지
      // 않는다 — 예약 가드가 과잉 차단하지 않는 것까지 같은 순회로 확인된다.
      if (screen.queryAllByText(/예약된 키입니다/).length > 0) reserved.add(key)
    }

    expect(reserved).toEqual(bindableRegistryKeys)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 기본값 복원
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 기본값 복원', () => {
  it('T10: action별 복원 버튼 클릭 시 DEFAULT_KEYMAP 값으로 되돌아간다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    fireEvent.keyDown(searchInput, { key: 'k' })
    expect(searchInput).toHaveValue('k')

    fireEvent.click(screen.getByRole('button', { name: '검색으로 이동 기본값 복원' }))

    expect(searchInput).toHaveValue('/')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 저장 — mutate 호출(5종 replace-all)
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 저장', () => {
  it('T11: 저장 버튼 클릭 시 PATCH가 action 5종 완비 바디로 호출된다', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/keymap', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({
          bindings: [
            { action: 'help', keyCombo: '?', trigger: 'single', customized: false },
            { action: 'create-issue', keyCombo: 'c', trigger: 'single', customized: false },
            { action: 'search', keyCombo: 'n', trigger: 'single', customized: true },
            { action: 'goto-my-issues', keyCombo: 'g i', trigger: 'leader', customized: false },
            { action: 'goto-dashboard', keyCombo: 'g d', trigger: 'leader', customized: false },
          ],
        })
      }),
    )
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    // 'k' 는 FR-UX-10 F10 이 목록 항법에 예약한 키라 저장이 막힌다 — 비예약 키로 검증한다
    fireEvent.keyDown(searchInput, { key: 'n' })

    fireEvent.click(screen.getByRole('button', { name: '단축키 설정 저장' }))

    await waitFor(() => {
      expect(capturedBody).toEqual({
        bindings: [
          { action: 'help', keyCombo: '?' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: 'n' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
          { action: 'goto-dashboard', keyCombo: 'g d' },
        ],
      })
    })
  })

  it('T12: 서버 409(KeymapConflictError) 응답 시 conflicts가 화면에 표시된다', async () => {
    server.use(
      http.patch('/api/v1/users/me/keymap', () =>
        HttpResponse.json(
          {
            code: 'KEYMAP_CONFLICT',
            message: '겹치는 단축키가 있습니다.',
            conflicts: [{ type: 'duplicate', actions: ['create-issue', 'search'], keyCombo: 'x' }],
          },
          { status: 409 },
        ),
      ),
    )
    renderForm()
    // 로컬 검증을 통과하는 무해한 변경(고유 단일 키) — 서버가 409를 내려도 로컬은 충돌을 못 잡는
    // 시나리오를 재현해 저장 버튼이 활성 상태로 클릭 가능함을 보장한다.
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    fireEvent.keyDown(searchInput, { key: 'x' })

    fireEvent.click(screen.getByRole('button', { name: '단축키 설정 저장' }))

    expect(await screen.findByText('겹치는 단축키가 있습니다.')).toBeInTheDocument()
    expect(
      await screen.findByText('"x"가 새 이슈 생성, 검색으로 이동에 중복 배정되었습니다.'),
    ).toBeInTheDocument()
  })
})
