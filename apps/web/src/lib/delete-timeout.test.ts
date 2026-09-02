// 삭제 요청 상한 헬퍼 단위 테스트 — 상한이 진행 중인 요청을 실제로 끊는지 검증 (FR-BD-01-2b)
import { describe, it, expect, vi } from 'vitest'
import { withDeleteTimeout, DeleteTimeoutError, DELETE_TIMEOUT_MS } from './delete-timeout'
import { deleteBoard } from '@/api/boards'

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
function settle<T>(promise: Promise<T>): Promise<T | unknown> {
  return promise.catch((error: unknown) => error)
}

describe('withDeleteTimeout', () => {
  it('T-DT-1: 상한 전에는 요청을 끊지 않는다 — 판정이 「항상 참」이 아님을 여기서 본다', async () => {
    vi.useFakeTimers()
    try {
      const signals: AbortSignal[] = []
      const settled = settle(
        withDeleteTimeout((signal) => {
          signals.push(signal)
          return abortableRequest(signal)
        }),
      )

      await vi.advanceTimersByTimeAsync(DELETE_TIMEOUT_MS - 1)

      expect(signals[0]?.aborted).toBe(false)
      void settled
    } finally {
      vi.useRealTimers()
    }
  })

  it('T-DT-2: 상한을 넘기면 요청에 넘긴 signal 이 abort 된다 — 요청이 살아남지 않는다', async () => {
    vi.useFakeTimers()
    try {
      const signals: AbortSignal[] = []
      const settled = settle(
        withDeleteTimeout((signal) => {
          signals.push(signal)
          return abortableRequest(signal)
        }),
      )

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
