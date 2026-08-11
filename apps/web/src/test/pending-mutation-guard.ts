// 테스트 종료 시점에 정착하지 않은 mutation 을 잡는 전역 가드 — 「끝났는데 mutation 이 아직 날고 있다」 누수 차단
//
// ## 왜 런타임 가드여야 하는가
// 이 누수를 정적 grep 으로 열거하려는 시도가 두 번 있었고 둘 다 양방향으로 틀렸다 (2026-08-11 실측).
// 「지연 관용구 ∧ 로컬 핸들러 등록」 정의의 34개 파일 중 **27개가 거짓양성**
// (그 정의는 `msw-single-setupserver.test.ts` 가 감시하는 문자열을 포함해 여기 그대로 옮겨 적지 않는다)
// (13개는 mutation 을 아예 만들지 않고, 14개는 만들지만 전부 정착한다) 이고, 실제 누수 10개 중
// **3개가 그 정의 밖**이었다. 그중 2개는 「수동 게이트」 관용구다 —
//
//   let release: () => void = () => {}
//   const held = new Promise<void>((resolve) => { release = resolve })   // executor 인자 1개
//   server.use(http.post('/api/v1/issues', async () => { await held; return HttpResponse.json(...) }))
//
// executor 인자가 1개라 미해결 Promise 정의(`\(\(\) *=>`)에 안 걸리고 `setTimeout`·`delay()` 도
// 쓰지 않아 어떤 정적 정의로도 잡히지 않는다. **그런데 응답이 실제로 도착한다** — 그래서
// 영원히 오지 않는 무한 Promise 보다 위험하다. 정적 열거로는 원리적으로 닫을 수 없다.
//
// ## 왜 `MutationCache.prototype.build` 를 감싸는가
// 선례(`AutomationYamlImportDialog.test.tsx`)는 렌더 헬퍼가 파일 로컬 변수에 queryClient 를
// 넣어 주는 방식이라 전역화하려면 모든 테스트 파일이 등록해 줘야 한다.
// `build` 를 감싸면 **mutation 을 실제로 만든 캐시만** 레지스트리에 들어오므로,
// 「레지스트리가 비었다」가 「이 파일은 mutation 을 아예 만들지 않았다」와 동치가 된다.
// 레지스트리가 비면 무조건 통과하는 항진명제가 구조적으로 생기지 않는다.
//
// ## 쓰지 않는 경로
// `process.on('unhandledRejection', …)` 은 `setup.ts` 가 절대 금지로 못박았다 —
// 리스너가 1개를 넘으면 vitest 가 물러나 종료 코드가 1 → 0 으로 뒤집힌다.

import { MutationCache } from '@tanstack/react-query'

/** mutation 을 실제로 만든 MutationCache 만 들어온다. */
const trackedCaches = new Set<MutationCache>()

let trackerInstalled = false

type BuildMethod = typeof MutationCache.prototype.build

/**
 * `MutationCache.build` 를 감싸 mutation 을 만든 캐시를 추적한다. 멱등이다.
 * `setup.ts` 가 모듈 로드 시점에 한 번 호출한다.
 */
export function installPendingMutationTracker(): void {
  if (trackerInstalled) return
  trackerInstalled = true

  const originalBuild: BuildMethod = MutationCache.prototype.build
  MutationCache.prototype.build = function trackedBuild(
    this: MutationCache,
    ...args: Parameters<BuildMethod>
  ): ReturnType<BuildMethod> {
    trackedCaches.add(this)
    return originalBuild.apply(this, args)
  } as BuildMethod
}

/** 배선 확인용 — `setup.ts` 가 가드를 실제로 켰는지 계약 테스트가 검사한다. */
export function isPendingMutationTrackerInstalled(): boolean {
  return trackerInstalled
}

/** 비-공허 확인용 — 가드가 실제로 캐시를 잡고 있는지 계약 테스트가 검사한다. */
export function trackedCacheCount(): number {
  return trackedCaches.size
}

export interface PendingMutationInfo {
  /** 테스트가 `mutationKey` 를 지정하지 않으면 null 이다. */
  readonly mutationKey: unknown
  readonly variables: unknown
}

/** 추적 중인 모든 캐시에서 아직 정착하지 않은 mutation 을 모은다. */
export function collectPendingMutations(): PendingMutationInfo[] {
  const pending: PendingMutationInfo[] = []
  for (const cache of trackedCaches) {
    for (const mutation of cache.getAll()) {
      if (mutation.state.status !== 'pending') continue
      pending.push({
        mutationKey: mutation.options.mutationKey ?? null,
        variables: mutation.state.variables ?? null,
      })
    }
  }
  return pending
}

/**
 * 레지스트리를 비운다. 전역 `afterEach` 가 단언 **앞에서** 호출한다 —
 * 정리하지 않으면 같은 pending mutation 이 뒤따르는 모든 테스트에서 다시 잡혀 연쇄 실패한다.
 * `build` 가 mutation 마다 호출되므로 캐시는 다음 mutation 때 재등록되고 추적 손실은 없다.
 */
export function resetTrackedCaches(): void {
  trackedCaches.clear()
}

/**
 * 진행 중인 mutation 이 전부 정착할 때까지 기다린다.
 *
 * 진행 중(pending) UI 를 검증하는 테스트는 mutation 을 의도적으로 붙잡아 둔다. 그 테스트가
 * 붙잡은 것을 풀고 이 함수를 마지막에 호출하면 누수가 다음 파일로 번지지 않는다.
 * 무한 Promise(`new Promise(() => {})`)로 붙잡으면 풀 방법이 없으므로,
 * 밖에서 resolve 를 잡아 두는 수동 게이트로 바꾼 뒤 호출해야 한다.
 */
export async function settlePendingMutations(): Promise<void> {
  // ★RTL 을 **동적으로** 가져온다. static import 하면 `setup.ts` 가 이 모듈을 로드하는 순간
  // RTL 을 쓰지 않는 순수 유닛 테스트 전량이 그 로드 비용을 물게 된다
  // (실측 — `src/mocks` 49파일에서 setup 24.4s → 43.0s, +76%).
  // 이 함수를 부르는 쪽은 어차피 RTL 로 렌더한 테스트라 여기서 로드해도 새 비용이 아니다.
  const { waitFor } = await import('@testing-library/react')
  await waitFor(() => {
    const pending = collectPendingMutations()
    if (pending.length > 0) {
      throw new Error(`아직 정착하지 않은 mutation 이 ${pending.length}건 남아 있다`)
    }
  })
}
