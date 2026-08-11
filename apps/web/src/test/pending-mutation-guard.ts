// 테스트 종료 시점에 정착하지 않은 mutation 을 잡는 전역 가드 — 「끝났는데 mutation 이 아직 날고 있다」 누수 차단
//
// ## 왜 런타임 가드여야 하는가
// 이 누수를 정적 grep 으로 열거하려는 시도가 두 번 있었고 둘 다 양방향으로 틀렸다 (2026-08-11 실측).
// 「지연 관용구 ∧ 로컬 핸들러 등록」 정의의 34개 파일 중 **27개가 거짓양성**
// (그 정의는 `msw-single-setupserver.test.ts` 가 감시하는 문자열을 포함해 여기 그대로 옮겨 적지 않는다)
// (13개는 mutation 을 아예 만들지 않고, 14개는 만들지만 전부 정착한다) 이고, 실제 누수 10개 중
// **3개가 그 정의 밖**이었다.
//
// ★이 「10」은 측정 목록이고 **하한**이다. 가드를 켠 red 는 11파일 15건이었고 `WatchersSection`
// 2건을 측정이 놓쳤다 — 프로브의 `afterEach` 가 `setup.ts` 것보다 늦게 실행돼 그 사이 정착한 것을
// 못 봤다. **측정 목록이 아니라 가드를 켠 red 목록이 정본이다.**
//
// 정의 밖 3개 중 2개는 「수동 게이트」 관용구다 —
//
//   let release: () => void = () => {}
//   const held = new Promise<void>((resolve) => { release = resolve })   // executor 인자 1개
//   server.use(http.post('/api/v1/issues', async () => { await held; return HttpResponse.json(...) }))
//
// executor 인자가 1개라 미해결 Promise 정의(`\(\(\) *=>`)에 안 걸리고 `setTimeout`·`delay()` 도
// 쓰지 않아 어떤 정적 정의로도 잡히지 않는다. **그런데 응답이 실제로 도착한다** — 그래서
// 영원히 오지 않는 무한 Promise 보다 위험하다. 정적 열거로는 원리적으로 닫을 수 없다.
//
// ## 왜 `MutationCache.build` 를 감싸는가
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
 *
 * ★멱등 보증은 **이 모듈 인스턴스 스코프**다. 어떤 테스트가 `vi.resetModules()` 후 이 모듈을
 * 다시 import 하면 `trackerInstalled` 가 false 인 새 인스턴스가 생겨, 이미 감싼 `build` 위에
 * 두 번째 래퍼가 얹히고 새 인스턴스의 레지스트리는 `setup.ts` 가 붙잡은 원본과 달라 고아가 된다.
 * 현재 `vi.resetModules()` 사용처(`src/hooks/**`)는 이 모듈을 import 하지 않아 실피해가 없다.
 * 이 모듈을 import 하는 테스트에서 `vi.resetModules()` 를 쓰지 말 것.
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

/**
 * 배선 확인용 — 계약 테스트가 읽는다.
 *
 * ★이 플래그 하나만으로는 배선 증명이 되지 않는다. 플래그만 true 로 두고 감싸기를 건너뛰어도
 * 이 함수는 true 를 돌려준다(코드리뷰 뮤테이션 M-B 로 실증). 실제 배선은
 * `pending-mutation-guard.test.ts` 의 소스 훑기 계약이 봉인한다.
 */
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
  /**
   * ★**값이 아니라 키 이름만** 담는다. 이 배열은 실패 시 vitest 가 그대로 출력하므로
   * 원본 `variables` 를 담으면 `currentPassword` 같은 자격증명이 CI 로그에 찍힌다
   * (보안 리뷰가 프로브로 재현). `DEVELOPMENT.md §1.1-2` 가 로그의 비밀 노출을 절대 금지한다.
   * 어느 테스트가 범인인지 찾는 데는 키 이름으로 충분하다.
   */
  readonly variableKeys: readonly string[] | string | null
}

/** `variables` 에서 값을 뺀 식별 정보만 남긴다. 값은 어떤 경로로도 밖으로 나가지 않는다. */
function describeVariables(variables: unknown): readonly string[] | string | null {
  if (variables === null || variables === undefined) return null
  if (typeof variables === 'object') return Object.keys(variables)
  return `(${typeof variables})`
}

/** 추적 중인 모든 캐시에서 아직 정착하지 않은 mutation 을 모은다. */
export function collectPendingMutations(): PendingMutationInfo[] {
  const pending: PendingMutationInfo[] = []
  for (const cache of trackedCaches) {
    for (const mutation of cache.getAll()) {
      if (mutation.state.status !== 'pending') continue
      pending.push({
        mutationKey: mutation.options.mutationKey ?? null,
        variableKeys: describeVariables(mutation.state.variables),
      })
    }
  }
  return pending
}

/**
 * 레지스트리를 비운다. 정리하지 않으면 같은 pending mutation 이 뒤따르는 모든 테스트에서
 * 다시 잡혀 연쇄 실패한다. `build` 가 mutation 마다 호출되므로 캐시는 다음 mutation 때
 * 재등록되고 추적 손실은 없다.
 *
 * 전역 `afterEach` 는 이것을 단언 **앞에서** 부른다. 지금 단언은 `expect.soft` 라 throw 하지
 * 않으므로 위치가 강제되지는 않지만, 하드 단언으로 되돌아가도 정리가 건너뛰어지지 않도록
 * 방어적으로 앞에 둔다.
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
  // RTL 을 쓰지 않는 순수 유닛 테스트 전량이 그 로드 비용을 물게 된다.
  // 실측(`src/mocks` 49파일) — 배선 없음 24.4s · static import 43.0s · 동적 import 36.6s.
  // 즉 동적 import 가 회수하는 것은 **6.4s** 이고, 나머지 **12.2s 는 가드 배선 자체 비용**이라
  // 이 전환으로 사라지지 않는다. 「+76% 를 이걸로 해결했다」로 읽으면 효과를 3배 부풀리는 것이다.
  const { waitFor } = await import('@testing-library/react')
  await waitFor(
    () => {
      const pending = collectPendingMutations()
      if (pending.length > 0) {
        // 개수만 알려주면 어느 mutation 이 안 끝났는지 모른다. 키 이름은 값이 아니라 안전하다.
        throw new Error(
          `아직 정착하지 않은 mutation 이 ${pending.length}건 남아 있다 — ${JSON.stringify(pending)}`,
        )
      }
    },
    // ★RTL 기본 대기 상한 1000ms 는 이 스위트에 부족하다. `vitest.config.ts` 가 기록한 대로
    // 전량 실행에서 워커 기아로 53ms 작업이 벽시계 5000ms 를 넘는다(약 94배). 여기 호출부 6곳은
    // 실제 지연 응답(50~200ms)을 기다리므로 기본값이면 기아 때 상한을 친다. 그때 메시지가
    // 「정착하지 않았다」라서 **스케줄러 기아를 진짜 누수로 오진**하게 만든다.
    // config 의 `testTimeout` 은 `waitFor` 내부 예산에 도달하지 못하므로 여기서 직접 준다.
    // `testTimeout`(15_000)보다 작아야 hang 대신 명확한 실패로 끝난다.
    { timeout: 10_000, interval: 25 },
  )
}
