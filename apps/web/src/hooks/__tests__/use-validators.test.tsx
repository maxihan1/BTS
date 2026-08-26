// 전환 규칙(validator) CRUD 훅 테스트 — 등록된 MSW 목 위에서 stateful 왕복과 invalidate-only 를 잰다
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { handlers } from '@/mocks/handlers'
import { ValidatorApiError } from '@/api/validators'
import {
  SEEDED_VALIDATOR_TRANSITION_ID,
  SEEDED_VALIDATOR_WORKFLOW_KEY,
  VALIDATOR_TYPES,
  resetValidatorStore,
} from '@/mocks/validator-handlers'
import {
  VALIDATOR_KEYS,
  useAddValidator,
  useDeleteValidator,
  useUpdateValidator,
  useValidators,
} from '../use-validators'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 픽스처
//
// 경로 세그먼트에 싣는 것은 **전환 id(UUID)** 뿐이다(제약 C4). 종전 합성 키는 새 소비자가
// 쓰지 않으므로 이 테스트도 쓰지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

const WF_KEY = SEEDED_VALIDATOR_WORKFLOW_KEY
const TX_ID = SEEDED_VALIDATOR_TRANSITION_ID
const BASE_PATH = `/api/v1/workflows/${WF_KEY}/transitions/${TX_ID}/validators`

/** 규칙이 하나도 없는 전환 — 빈 상태(E4) 를 재현한다. */
const EMPTY_TX_ID = '00000000-0000-4000-8000-019999999999'

/** 테스트마다 독립 캐시를 가진 QueryClient 를 만든다. */
function createClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

/** 주어진 client 로 감싸는 wrapper. 목록 훅과 mutation 훅이 같은 캐시를 보게 할 때 쓴다. */
function wrapperOf(client: QueryClient) {
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

beforeEach(() => {
  // ★ 개별 핸들러가 아니라 **통합 배열**을 켠다. `src/test/server` 의 기본 목록에는 refresh 하나뿐이라
  //   유닛 테스트는 쓸 핸들러를 직접 켜야 하는데, 여기서 `validatorHandlers` 를 직접 spread 하면
  //   `mocks/handlers.ts` 등록을 빠뜨려도 초록이 된다 — 그러면 화면(Task 6·8)만 요청이 안 잡힌다.
  //   통합 배열을 켜면 등록 누락이 이 파일에서 red 로 드러난다.
  server.use(...handlers)
  // 목 store 는 모듈 스코프다 — 만든 행이 다음 테스트로 새지 않게 시드 상태로 되돌린다.
  resetValidatorStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-1. queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('VALIDATOR_KEYS', () => {
  it('T5-1a: 전환마다 다른 목록 키를 만든다', () => {
    expect(VALIDATOR_KEYS.list(WF_KEY, TX_ID)).toEqual(['validators', WF_KEY, TX_ID])
    expect(VALIDATOR_KEYS.list(WF_KEY, TX_ID)).not.toEqual(VALIDATOR_KEYS.list(WF_KEY, EMPTY_TX_ID))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-2. useValidators — 조회
//
// ★ 이 describe 는 `server.use` 로 목을 갈아끼우지 **않는다.** `handlers.ts` 에 등록된 핸들러가
//   실제로 요청을 잡는지까지 재기 위해서다(등록을 빠뜨리면 `onUnhandledRequest: 'error'` 로 red).
// ─────────────────────────────────────────────────────────────────────────────

describe('useValidators', () => {
  it('T5-2a: 전환을 고르면 그 전환의 목록을 displayOrder 순으로 가져온다', async () => {
    const { result } = renderHook(() => useValidators(WF_KEY, TX_ID), {
      wrapper: wrapperOf(createClient()),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const rows = result.current.data ?? []
    expect(rows.length).toBeGreaterThan(0)
    expect(rows.map((row) => row.displayOrder)).toEqual(
      [...rows.map((row) => row.displayOrder)].sort((a, b) => a - b),
    )
  })

  it('T5-2b: 시드가 편집 가능 3종 · CustomExpression · 깨진 행을 전부 담는다', async () => {
    const { result } = renderHook(() => useValidators(WF_KEY, TX_ID), {
      wrapper: wrapperOf(createClient()),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const rows = result.current.data ?? []

    // 편집 가능 3종 — `RequiredField` 만 EXECUTION 이고 나머지는 SPI 기본값 AVAILABILITY 를 상속한다.
    const requiredField = rows.find((row) => row.type === VALIDATOR_TYPES.requiredField && row.editable)
    expect(requiredField?.phase).toBe('EXECUTION')
    expect(requiredField?.config).toMatchObject({ field: expect.any(String) })

    const permissionCheck = rows.find((row) => row.type === VALIDATOR_TYPES.permissionCheck)
    expect(permissionCheck?.phase).toBe('AVAILABILITY')
    expect(permissionCheck?.editable).toBe(true)

    const notStatusCategory = rows.find((row) => row.type === VALIDATOR_TYPES.notStatusCategory)
    expect(notStatusCategory?.phase).toBe('AVAILABILITY')
    expect(notStatusCategory?.editable).toBe(true)

    // 편집 불가 행 — 목록에서 숨기지 않는다(FR-2 · S4).
    const customExpression = rows.find((row) => row.type === VALIDATOR_TYPES.customExpression)
    expect(customExpression?.phase).toBe('AVAILABILITY')
    expect(customExpression?.editable).toBe(false)

    // 인스턴스화 실패 행 (S5) — `phase = null` 과 `editable = false` 가 짝을 이룬다.
    const broken = rows.find((row) => row.phase === null)
    expect(broken).toBeDefined()
    expect(broken?.editable).toBe(false)
  })

  it('T5-2c: 규칙이 없는 전환은 빈 목록이다', async () => {
    const { result } = renderHook(() => useValidators(WF_KEY, EMPTY_TX_ID), {
      wrapper: wrapperOf(createClient()),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([])
  })

  it('T5-2d: 전환 id 가 빈 문자열이면 query 가 비활성이다', () => {
    const { result } = renderHook(() => useValidators(WF_KEY, ''), {
      wrapper: wrapperOf(createClient()),
    })

    expect(result.current.fetchStatus).toBe('idle')
    expect(result.current.data).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-3. useAddValidator — 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('useAddValidator', () => {
  it('T5-3a: validator 를 만들면 목록에 남는다 (stateful)', async () => {
    const client = createClient()
    const wrapper = wrapperOf(client)

    const listHook = renderHook(() => useValidators(WF_KEY, TX_ID), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const before = listHook.result.current.data?.length ?? 0

    const { result } = renderHook(() => useAddValidator(WF_KEY, TX_ID), { wrapper })
    act(() => {
      result.current.mutate({
        type: VALIDATOR_TYPES.requiredField,
        config: { field: 'assignee' },
        displayOrder: before,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(listHook.result.current.data).toHaveLength(before + 1))
    expect(
      listHook.result.current.data?.some((row) => row.config['field'] === 'assignee'),
    ).toBe(true)
  })

  it('T5-3b: 추가 성공 후 목록 쿼리가 무효화된다 (invalidate-only)', async () => {
    let listFetchCount = 0
    server.use(
      http.get(BASE_PATH, () => {
        listFetchCount++
        return HttpResponse.json({ data: [] })
      }),
      http.post(BASE_PATH, () =>
        HttpResponse.json(
          {
            data: {
              id: '99999999-9999-4999-8999-999999999999',
              type: VALIDATOR_TYPES.requiredField,
              config: { field: 'resolution' },
              displayOrder: 0,
              phase: 'EXECUTION',
              editable: true,
            },
          },
          { status: 201 },
        ),
      ),
    )

    const client = createClient()
    const wrapper = wrapperOf(client)

    const listHook = renderHook(() => useValidators(WF_KEY, TX_ID), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const prevFetchCount = listFetchCount

    const { result } = renderHook(() => useAddValidator(WF_KEY, TX_ID), { wrapper })
    act(() => {
      result.current.mutate({
        type: VALIDATOR_TYPES.requiredField,
        config: { field: 'resolution' },
        displayOrder: 0,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // 캐시를 응답으로 덮어쓰지 않고 refetch 한다 — 목록이 서버 상태(빈 배열)를 그대로 따른다.
    await waitFor(() => expect(listFetchCount).toBeGreaterThan(prevFetchCount))
    expect(listHook.result.current.data).toEqual([])
  })

  it('T5-3c: 편집 불가 type 은 400 봉투로 거절된다', async () => {
    const { result } = renderHook(() => useAddValidator(WF_KEY, TX_ID), {
      wrapper: wrapperOf(createClient()),
    })

    act(() => {
      result.current.mutate({
        type: VALIDATOR_TYPES.customExpression,
        config: { expression: 'true' },
        displayOrder: 9,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    const error = result.current.error
    if (error instanceof ValidatorApiError) {
      expect(error.status).toBe(400)
      expect(error.errorCode).toBe('WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE')
    } else {
      expect.unreachable('ValidatorApiError 가 아니다')
    }
  })

  it('T5-3d: 필수 config 키가 비면 400 WORKFLOW_VALIDATOR_INVALID 다', async () => {
    const { result } = renderHook(() => useAddValidator(WF_KEY, TX_ID), {
      wrapper: wrapperOf(createClient()),
    })

    act(() => {
      result.current.mutate({
        type: VALIDATOR_TYPES.requiredField,
        config: {},
        displayOrder: 9,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    const error = result.current.error
    if (error instanceof ValidatorApiError) {
      expect(error.status).toBe(400)
      expect(error.errorCode).toBe('WORKFLOW_VALIDATOR_INVALID')
    } else {
      expect.unreachable('ValidatorApiError 가 아니다')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-4. useUpdateValidator — 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateValidator', () => {
  it('T5-4a: 수정하면 목록의 그 행 값이 바뀐다 (stateful)', async () => {
    const client = createClient()
    const wrapper = wrapperOf(client)

    const listHook = renderHook(() => useValidators(WF_KEY, TX_ID), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const target = listHook.result.current.data?.find(
      (row) => row.type === VALIDATOR_TYPES.requiredField && row.editable,
    )
    expect(target).toBeDefined()
    const targetId = target?.id ?? ''

    const { result } = renderHook(() => useUpdateValidator(WF_KEY, TX_ID, targetId), { wrapper })
    act(() => {
      result.current.mutate({
        type: VALIDATOR_TYPES.requiredField,
        config: { field: 'assignee' },
        displayOrder: target?.displayOrder ?? 0,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() =>
      expect(
        listHook.result.current.data?.find((row) => row.id === targetId)?.config['field'],
      ).toBe('assignee'),
    )
  })

  it('T5-4b: 없는 id 를 수정하면 404 WORKFLOW_VALIDATOR_NOT_FOUND 다', async () => {
    const missingId = '88888888-8888-4888-8888-888888888888'
    const { result } = renderHook(() => useUpdateValidator(WF_KEY, TX_ID, missingId), {
      wrapper: wrapperOf(createClient()),
    })

    act(() => {
      result.current.mutate({
        type: VALIDATOR_TYPES.requiredField,
        config: { field: 'assignee' },
        displayOrder: 0,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    const error = result.current.error
    if (error instanceof ValidatorApiError) {
      expect(error.status).toBe(404)
      expect(error.errorCode).toBe('WORKFLOW_VALIDATOR_NOT_FOUND')
    } else {
      expect.unreachable('ValidatorApiError 가 아니다')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-5. useDeleteValidator — 삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteValidator', () => {
  it('T5-5a: 삭제하면 목록에서 사라진다', async () => {
    const client = createClient()
    const wrapper = wrapperOf(client)

    const listHook = renderHook(() => useValidators(WF_KEY, TX_ID), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const before = listHook.result.current.data ?? []
    const targetId = before[0]?.id ?? ''

    const { result } = renderHook(() => useDeleteValidator(WF_KEY, TX_ID), { wrapper })
    act(() => {
      result.current.mutate(targetId)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(listHook.result.current.data).toHaveLength(before.length - 1))
    expect(listHook.result.current.data?.some((row) => row.id === targetId)).toBe(false)
  })

  it('T5-5b: 편집 불가 행도 삭제된다 (백엔드가 막지 않는다)', async () => {
    const client = createClient()
    const wrapper = wrapperOf(client)

    const listHook = renderHook(() => useValidators(WF_KEY, TX_ID), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const targetId =
      listHook.result.current.data?.find(
        (row) => row.type === VALIDATOR_TYPES.customExpression,
      )?.id ?? ''

    const { result } = renderHook(() => useDeleteValidator(WF_KEY, TX_ID), { wrapper })
    act(() => {
      result.current.mutate(targetId)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() =>
      expect(
        listHook.result.current.data?.some((row) => row.id === targetId),
      ).toBe(false),
    )
  })
})
