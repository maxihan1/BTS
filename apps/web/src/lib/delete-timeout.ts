// 파괴적 삭제 요청에 상한을 씌우고 상한을 넘기면 요청 자체를 취소하는 공용 헬퍼
/**
 * 삭제 요청을 스스로 끊는 상한 (밀리초).
 *
 * **왜 훅에 타임아웃이 필요한가.** 삭제 확인 창(`components/ui/confirm-dialog.tsx`)은
 * `confirming` 인 동안 취소·Esc·오버레이·X 를 **전부** 잠근다. 그 설계는 KDoc 이 밝히듯
 * 「파괴적 조작이고 이미 확인을 누른 뒤라 **기다림은 짧다**」를 전제한다. 네트워크가 끊겨
 * 요청이 pending 에 머물면 그 전제가 깨지고 사용자가 창에 갇힌다.
 *
 * 프리미티브를 고치지 않고 여기서 끊는다 — 프리미티브는 소비처가 여럿이고, 여기서 reject 하면
 * `isPending` 이 풀려 창이 다시 조작 가능해지며 사유는 **이미 설계된 실패 경로**(창 안 `error`)로
 * 흐른다. 새 UI 상태가 늘지 않는다.
 *
 * 값의 근거. 정상 204 응답은 수백 ms 다. 10초는 느린 회선의 정상 응답을 성급히 자르지 않으면서,
 * 사람이 「멈췄다」고 느껴 창을 강제로 벗어나려 하기 전에 조작권을 돌려주는 상한이다.
 *
 * 보드·스프린트 등 삭제 조작이 **이 값 하나를 공유한다** — 조작마다 따로 두면 확인 창의
 * 체감 상한이 화면마다 갈린다.
 */
export const DELETE_TIMEOUT_MS = 10_000

/**
 * 응답을 기다리다 [DELETE_TIMEOUT_MS] 를 넘겨 스스로 끊은 실패.
 *
 * 서버가 준 실패(`ApiError`)와 구별되는 별도 타입이다 — 소비자는 상태 코드가 없는 실패로 묶어
 * 「응답이 없다」는 안내를 고른다.
 */
export class DeleteTimeoutError extends Error {
  constructor() {
    super(`삭제 응답이 ${DELETE_TIMEOUT_MS}ms 안에 오지 않았습니다`)
    this.name = 'DeleteTimeoutError'
  }
}

/**
 * 삭제 요청에 [DELETE_TIMEOUT_MS] 상한을 씌우고, 상한을 넘기면 **요청을 취소하면서 동시에
 * 반환 Promise 를 거절한다.** 둘 중 하나만 해서는 안 된다.
 *
 * **취소해야 하는 이유.** 상한만 재고 요청을 살려 두면 화면은 실패로 돌아섰는데 서버는 계속
 * 지우는 상태가 남는다. 그래서 `request` 는 Promise 가 아니라 **signal 을 받는 함수**다 —
 * 취소 신호를 넘길 자리가 없으면 애초에 끊을 방법이 없다.
 *
 * **거절도 함께 내야 하는 이유.** abort 전파 하나에 기대면, 요청이 abort 를 관측하지 않는 구간에
 * 걸려 있을 때 반환 Promise 가 **영원히 pending 으로 남아** 확인 창이 무기한 잠긴다. `apiFetch` 의
 * 401 경로가 실제로 그런 구간이다 — refresh 대기(`api/client.ts` 의 `doRefresh`)에는 signal 이
 * 실리지 않는다. refresh Promise 가 여러 요청이 공유하는 전역 lock 이라 한 요청의 abort 로 죽일 수
 * 없기 때문이고, 그 판단은 옳다. 그래서 상한이 스스로 거절을 낸다.
 * 금지되는 것은 `Promise.race` 자체가 아니라 **abort 없이 상한만 재는 것**이다.
 *
 * `request` 는 signal 을 실제 요청까지 이어줘야 한다(`api/client.ts` 의 `ApiFetchOptions.signal`).
 * 이어주지 않으면 abort 가 아무 일도 하지 못해 요청이 서버에서 계속 살아 있다.
 *
 * @param request 취소 신호를 받아 삭제 요청을 시작하는 함수
 * @returns 요청이 상한 안에 끝났을 때의 결과
 * @throws DeleteTimeoutError 상한을 넘겨 요청을 끊었을 때
 */
export async function withDeleteTimeout<T>(
  request: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const controller = new AbortController()
  // 타이머는 이 executor 안에서만 만든다 — abort 와 거절이 **같은 자리**에 있어야
  // 한쪽만 남기는 수정이 나오지 않는다.
  let timer: ReturnType<typeof setTimeout> | undefined
  const expiry = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      controller.abort()
      reject(new DeleteTimeoutError())
    }, DELETE_TIMEOUT_MS)
  })

  try {
    return await Promise.race([request(controller.signal), expiry])
  } catch (cause) {
    // 상한이 끊은 요청은 플랫폼의 AbortError 로 실패한다. abort 는 동기로 전파되므로 위 race 에서
    // 요청이 그 사유로 **먼저** 지는 경로가 여전히 있다(상한이 스스로 거절을 내도 마찬가지다).
    // 그 원문을 그대로 흘리면 소비자가 「서버가 준 실패」와 구별할 수 없으므로 사유를 이 헬퍼의
    // 타입으로 옮긴다. 상한을 넘기지 않았다면 요청 자신의 실패이므로 덮지 않는다.
    //
    // 판정 근거는 signal 하나다. 이 controller 는 여기서만 만들고 여기서만 abort 하므로
    // `aborted` 가 곧 「상한이 끊었다」이고, 별도 플래그를 두면 같은 사실의 장부가 둘이 된다.
    if (controller.signal.aborted) {
      throw new DeleteTimeoutError()
    }
    throw cause
  } finally {
    clearTimeout(timer)
  }
}
