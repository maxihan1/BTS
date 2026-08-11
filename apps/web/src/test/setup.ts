// Vitest 전역 셋업 — jest-dom matcher 확장 + MSW 서버 라이프사이클 + 모듈 상태 격리
/// <reference types="vitest/globals" />
import '@testing-library/jest-dom'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, expect } from 'vitest'
import {
  collectPendingMutations,
  installPendingMutationTracker,
  resetTrackedCaches,
} from './pending-mutation-guard'
import { server } from './server'
import { resetIssueStateWithEpic } from '@/mocks/issue-handlers'
import { resetFavoriteStore } from '@/mocks/favorite-handlers'

// mutation 추적을 켠다. 모듈 로드 시점이라 어떤 테스트보다 먼저 설치된다.
installPendingMutationTracker()

// jsdom은 ResizeObserver를 구현하지 않는다.
// Radix UI RadioGroup.Item 등이 내부적으로 ResizeObserver를 사용하므로 no-op mock으로 polyfill한다.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserver {
    observe(): void { /* no-op */ }
    unobserve(): void { /* no-op */ }
    disconnect(): void { /* no-op */ }
  }
}

/**
 * ★`process.on('unhandledRejection', …)` 를 여기에 **추가하지 말 것.**
 *
 * TODOS 「전체 스위트 실행에서 `pnpm test` 가 간헐적으로 exit≠0」을 진단하려고 한 번
 * 넣었다가 되돌렸다(2026-08-10 게이트2 리뷰). vitest 는 `unhandledRejection` 리스너가
 * **이미 등록돼 있으면 「사용자 코드가 처리했다」고 보고 물러난다.** 그래서 리스너를 하나
 * 더 붙이는 순간 vitest 자신의 보고가 통째로 꺼지고 **종료 코드가 1 → 0 으로 뒤집힌다.**
 *
 * ## A/B 실측 (2026-08-10)
 * 늦게 도착하는 rejection 을 만든 테스트 파일 하나로 잰 결과.
 *
 * | 조건 | 종료 코드 | 리포트 |
 * |---|---|---|
 * | 리스너 추가 | **0** | 없음 |
 * | 리스너 없음 | **1** | `Errors 1 error` + 전체 스택 + 원인 테스트 이름 |
 *
 * 즉 그 리스너는 이 파일이 「절대 금지」로 못박은 `dangerouslyIgnoreUnhandledErrors: true`
 * 와 **같은 효과**였다 — 추적하려던 결함을 원인은 그대로 둔 채 **관측 불가능하게** 만든다.
 *
 * ## 그리고 애초에 필요 없었다
 * 「진단 표면이 파일 이름까지고 어느 테스트인지는 모른다」가 리스너를 넣은 근거였는데
 * **거짓이었다.** vitest 4 는 이미 다음을 전부 출력한다 —
 * 전체 스택 · 원인 파일 · `The latest test that might've caused the error is "«테스트 이름»"`.
 *
 * 귀속을 더 강화하고 싶다면 **process 리스너를 늘리지 않는 경로**를 써야 한다
 * (커스텀 리포터의 `onUnhandledError` 훅 등). vitest 는 리스너가 1개를 넘으면 물러난다.
 */

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  /**
   * ★DOM 정리를 **직접** 먼저 부른다.
   *
   * RTL 의 자동 cleanup 은 이 파일의 `afterEach` 와 **같은 root 레벨**에 등록돼 순서가 보장되지
   * 않는다. 아래 pending 단언이 throw 하면 뒤따르는 훅이 실행되지 않아 DOM 이 남고, 다음 테스트가
   * 「Found multiple elements」 로 **연쇄 실패**한다 (실측 — FavoriteButton 에서 진짜 위반 3건이
   * 5건으로 부풀었다). 가드가 자기가 잡으려던 것 대신 엉뚱한 실패를 만드는 형태다.
   *
   * cleanup 은 멱등이라 RTL 이 나중에 또 불러도 무해하다. 그리고 unmount 뒤에 재는 편이
   * 판정이 더 엄밀하다 — 컴포넌트가 사라졌는데도 남아 있는 mutation 만 누수로 잡힌다.
   */
  cleanup()

  // pending mutation 누수 가드. 수집 → 레지스트리 정리 → 단언 순서다.
  // 단언을 마지막에 두어야 throw 해도 아래 격리가 전부 끝난다.
  const pendingMutations = collectPendingMutations()
  resetTrackedCaches()

  server.resetHandlers()
  // issue-handlers 모듈-스코프 state (deletedKeys / createdIssues / epicChildrenStore) 격리 — 테스트 간 leak 방지
  resetIssueStateWithEpic()
  // favorite-handlers 모듈-스코프 store (userId → Favorite[] Map) 격리 — 테스트 간 leak 방지
  resetFavoriteStore()

  expect(
    pendingMutations,
    '테스트가 끝났는데 mutation 이 아직 날고 있다 — 정착을 기다리지 않고 끝난 테스트가 있다.\n' +
      '진행 중 UI 를 검증하려고 응답을 붙잡았다면, 테스트 마지막에 붙잡은 것을 풀고 ' +
      '`await settlePendingMutations()` (src/test/pending-mutation-guard.ts) 를 호출하라.',
  ).toEqual([])
})
afterAll(() => server.close())
