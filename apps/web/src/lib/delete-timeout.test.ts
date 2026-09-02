// 삭제 요청 상한 헬퍼 단위 테스트 — 상한이 진행 중인 요청을 실제로 끊는지 검증 (FR-BD-01-2b)
import { describe, it, expect, vi } from 'vitest'
import { withDeleteTimeout, DeleteTimeoutError, DELETE_TIMEOUT_MS } from './delete-timeout'
import { deleteBoard } from '@/api/boards'
import { apiFetch } from '@/api/client'

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'

/**
 * 실제 `fetch` 의 취소 의미를 흉내내는 가짜 요청.
 *
 * abort 되기 전에는 영원히 pending 이고, abort 되면 `AbortError` 로 reject 한다.
 * 이 헬퍼의 계약은 「요청에 상한을 씌운다」가 아니라 「요청을 **끊는다**」이므로
 * signal 을 무시하는 mock 으로는 그 계약을 잴 수 없다.
 */
function abortableRequest(signal: AbortSignal): Promise<string> {
  return new Promise<string>((_resolve, reject) => {
    signal.addEventListener('abort', () => {
      reject(new DOMException('The operation was aborted.', 'AbortError'))
    })
  })
}

/** reject 를 값으로 바꿔 부유 rejection 없이 결과를 단언하게 한다 */
function settle(promise: Promise<unknown>): Promise<unknown> {
  return promise.catch((error: unknown) => error)
}

/** 상한이 지났는데도 아직 settle 하지 않은 Promise 를 가리키는 표식 */
const STILL_PENDING = '반환 Promise 가 아직 settle 하지 않음'

/**
 * 이미 settle 한 Promise 면 그 결과를, 아직 pending 이면 [STILL_PENDING] 을 준다.
 *
 * 그냥 `await` 하면 pending 인 채로 vitest 기본 타임아웃까지 매달려 실패 사유가
 * 「Test timed out」이 된다 — 「상한이 창을 못 푼다」는 진짜 사유가 가려진다.
 * `Promise.race` 는 인자 순서대로 반응을 큐에 넣으므로 이미 settle 한 쪽이 항상 먼저 이긴다.
 */
function outcomeNow(settled: Promise<unknown>): Promise<unknown> {
  return Promise.race([settled, Promise.resolve(STILL_PENDING)])
}

/**
 * 상한이 걸린 삭제 요청 하나를 띄운다.
 *
 * @returns 요청이 받은 signal 목록(비어 있으면 signal 자체가 안 넘어온 것)과 최종 결과
 */
function startTimedDelete(): { signals: AbortSignal[]; settled: Promise<unknown> } {
  const signals: AbortSignal[] = []
  const settled = settle(
    withDeleteTimeout((signal) => {
      signals.push(signal)
      return abortableRequest(signal)
    }),
  )
  return { signals, settled }
}

describe('withDeleteTimeout', () => {
  it('T-DT-1: 상한 전에는 요청을 끊지 않는다 — 판정이 「항상 참」이 아님을 여기서 본다', async () => {
    vi.useFakeTimers()
    try {
      const { signals } = startTimedDelete()

      await vi.advanceTimersByTimeAsync(DELETE_TIMEOUT_MS - 1)

      expect(signals[0]?.aborted).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('T-DT-2: 상한을 넘기면 요청에 넘긴 signal 이 abort 된다 — 요청이 살아남지 않는다', async () => {
    vi.useFakeTimers()
    try {
      const { signals, settled } = startTimedDelete()

      await vi.advanceTimersByTimeAsync(DELETE_TIMEOUT_MS + 1)

      // ★장부 146. 상한만 재면 `Promise.race` 로도 초록이라 판별력이 0이다 —
      //   실제로 취소됐는지는 signal 하나만 증언한다.
      expect(signals[0]?.aborted).toBe(true)
      // 사유는 플랫폼의 AbortError 가 아니라 이 헬퍼의 타입으로 옮겨 온다.
      await expect(settled).resolves.toBeInstanceOf(DeleteTimeoutError)
    } finally {
      vi.useRealTimers()
    }
  })

  it('T-DT-3: 상한 안에 끝나면 값을 그대로 돌려주고 요청을 끊지 않는다', async () => {
    vi.useFakeTimers()
    try {
      const signals: AbortSignal[] = []
      const result = await withDeleteTimeout((signal) => {
        signals.push(signal)
        return Promise.resolve('done')
      })

      // 성공 뒤 타이머가 남아 있으면 여기서 뒤늦게 abort 된다.
      await vi.advanceTimersByTimeAsync(DELETE_TIMEOUT_MS * 2)

      expect(result).toBe('done')
      expect(signals[0]?.aborted).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('T-DT-4: 요청이 스스로 실패하면 그 사유가 그대로 전파된다 — 상한이 덮지 않는다', async () => {
    const serverFailure = new Error('403')

    const settled = await settle(withDeleteTimeout(() => Promise.reject(serverFailure)))

    expect(settled).toBe(serverFailure)
    expect(settled).not.toBeInstanceOf(DeleteTimeoutError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 배선 — 상한이 실제 HTTP 요청까지 닿는지 (장부 146)
//
// 이 블록이 lib 테스트에 사는 이유. 「요청이 살아 있다」는 결함은 헬퍼 하나가 아니라
// `withDeleteTimeout → api/boards.deleteBoard → api/client.apiFetch → fetch` 사슬 전체가
// signal 을 이어줘야 닫힌다. 중간 한 마디만 끊겨도 화면은 그대로 갇히므로 사슬을 통째로 잰다.
// 경계는 플랫폼 `fetch` 하나뿐이고, 그 자리는 실제 fetch 의 취소 의미로 대체한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('withDeleteTimeout — api 사슬 배선', () => {
  it('T-DT-5: 상한이 fetch 에 넘어간 signal 까지 끊는다', async () => {
    const signals: (AbortSignal | undefined)[] = []
    vi.stubGlobal('fetch', (_input: unknown, init?: RequestInit) => {
      const signal = init?.signal ?? undefined
      signals.push(signal)
      return new Promise<Response>((_resolve, reject) => {
        signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'))
        })
      })
    })
    vi.useFakeTimers()

    try {
      const settled = settle(withDeleteTimeout((signal) => deleteBoard(BOARD_ID, signal)))

      await vi.advanceTimersByTimeAsync(DELETE_TIMEOUT_MS + 1)

      expect(signals[0]).toBeInstanceOf(AbortSignal)
      expect(signals[0]?.aborted).toBe(true)
      await expect(settled).resolves.toBeInstanceOf(DeleteTimeoutError)
    } finally {
      vi.useRealTimers()
      vi.unstubAllGlobals()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// abort 가 닿지 않는 구간 (장부 145 회귀 가드)
//
// `apiFetch` 는 signal 을 받지만, 401 을 받으면 **signal 이 실리지 않은** refresh 대기
// (`api/client.ts` 의 `doRefresh`)에 주차한다. refresh Promise 는 여러 요청이 공유하는 전역
// lock 이라 한 요청의 abort 로 죽일 수 없어서 그렇게 둔 것이고, 그 판단은 유지한다.
// 그래서 상한은 abort 전파에만 기대면 안 된다 — 요청이 abort 를 관측하지 않는 구간에 걸려
// 있어도 **반환 Promise 가 거절돼야** `isPending` 이 풀리고 확인 창이 다시 조작 가능해진다.
//
// ★이 블록은 파일 맨 끝에 둔다. 여기서 띄운 refresh 는 끝내 응답하지 않아 `client.ts` 의
//   `refreshPromise` lock 이 풀리지 않은 채 남는다 — 뒤에 401 을 쓰는 테스트가 오면 그 lock 을
//   물려받는다.
// ─────────────────────────────────────────────────────────────────────────────

describe('withDeleteTimeout — refresh 가 멈춘 401 경로', () => {
  it('T-DT-6: refresh 응답이 오지 않아도 상한이 반환 Promise 를 거절한다', async () => {
    const fetchSignals: (AbortSignal | undefined)[] = []
    vi.stubGlobal('fetch', (_input: unknown, init?: RequestInit) => {
      fetchSignals.push(init?.signal ?? undefined)
      // 1회차 = DELETE. access token 이 만료돼 401 이 돌아온다.
      if (fetchSignals.length === 1) {
        return Promise.resolve(new Response(null, { status: 401 }))
      }
      // 2회차 = /api/v1/auth/refresh. 응답 없이 멈춘 연결 — 상한이 대비하는 바로 그 장애다.
      return new Promise<Response>(() => {})
    })
    vi.useFakeTimers()

    try {
      const settled = settle(
        withDeleteTimeout((signal) => apiFetch(`/api/v1/boards/${BOARD_ID}`, { method: 'DELETE', signal })),
      )

      await vi.advanceTimersByTimeAsync(DELETE_TIMEOUT_MS + 1)

      // 전제 확인 — 401 분기에 실제로 진입했고 refresh 에는 signal 이 실리지 않았다.
      // 이게 깨지면 아래 단언은 결함이 아니라 다른 것을 재고 있는 것이다.
      expect(fetchSignals).toHaveLength(2)
      expect(fetchSignals[1]).toBeUndefined()
      // 장부 146 은 그대로 — abort 는 여전히 난다.
      expect(fetchSignals[0]?.aborted).toBe(true)
      // 장부 145 — abort 가 닿지 않는 구간에 걸려 있어도 반환 Promise 가 **거절된다.**
      await expect(outcomeNow(settled)).resolves.toBeInstanceOf(DeleteTimeoutError)
    } finally {
      vi.useRealTimers()
      vi.unstubAllGlobals()
    }
  })
})
