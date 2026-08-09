// Vitest 전역 셋업 — jest-dom matcher 확장 + MSW 서버 라이프사이클 + 모듈 상태 격리
/// <reference types="vitest/globals" />
import '@testing-library/jest-dom'
import { afterAll, afterEach, beforeAll, expect } from 'vitest'
import { server } from './server'
import { resetIssueStateWithEpic } from '@/mocks/issue-handlers'
import { resetFavoriteStore } from '@/mocks/favorite-handlers'

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
 * unhandled rejection 진단 로깅 (TODOS 「전체 스위트 실행에서 pnpm test 가 간헐적으로 exit≠0」).
 *
 * ## 왜 「고침」이 아니라 「진단 배선」인가
 * 이 결함은 **전건 통과(Tests N passed)인데 종료 코드가 0이 아닌** 실행이 섞이는 것이다.
 * vitest 가 `Errors 1` 로 보고하는 unhandled rejection 이 원인인데,
 * **2026-08-09 전체 스위트 5회 실행에서 0회 재현**됐다(4회는 파이프 없이 종료 코드 직접 확인).
 * 미재현은 부재 증명이 아니므로(원 주장 p=0.5 라면 4연속 초록 확률 6.25%) 「고쳤다」로
 * 닫을 근거가 없다. 그래서 이번 라운드의 산출물을 **「다음 발생 때 어느 테스트에서
 * 무슨 스택으로 났는지 로그에 남는다」**로 정의한다.
 *
 * 지금까지의 진단 표면은 「파일 이름」까지였고 그 안 어느 테스트인지는 못 적었다 —
 * 이 리스너의 부재가 그 이유다.
 *
 * ## 인과 정정
 * 「mutation 의 rejection 이 테스트 종료 후 도착」만으로는 unhandled rejection 이 되지 않는다.
 * `useMutation` 이 `observer.mutate(...).catch(noop)` 을 붙이기 때문이다. 전역으로 새는 경로는
 * `onSuccess`/`onError`/`onSettled` **콜백이 던질 때**와 `.catch` 없이 호출된 `mutateAsync`
 * 두 가지뿐이다. 스택을 남겨야 그 둘을 구분할 수 있다.
 *
 * ## 절대 하지 말 것
 * `dangerouslyIgnoreUnhandledErrors: true`. 종료 코드는 초록이 되지만 vitest 가 잡아 주던
 * **유일한 진단 표면이 사라진다** — 봉인이 자기 결함을 재생산하는 양식이다.
 *
 * 리스너는 vitest 자체 리스너에 **가산**될 뿐이라 종료 코드 동작을 바꾸지 않는다.
 * 리스너 자신이 던지면 새 unhandled rejection 을 만드므로 전체를 try/catch 로 감싼다.
 */
process.on('unhandledRejection', (reason: unknown) => {
  let where = '(활성 테스트 없음)'
  try {
    where = expect.getState().currentTestName ?? '(활성 테스트 없음)'
  } catch {
    // expect.getState() 는 활성 테스트 밖에서 던질 수 있다. 이름을 못 얻어도 스택은 남긴다.
  }
  const detail = reason instanceof Error ? (reason.stack ?? reason.message) : String(reason)
  // eslint-disable-next-line no-console
  console.error(`[UNHANDLED-REJECTION] test=${where}\n${detail}`)
})

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  // issue-handlers 모듈-스코프 state (deletedKeys / createdIssues / epicChildrenStore) 격리 — 테스트 간 leak 방지
  resetIssueStateWithEpic()
  // favorite-handlers 모듈-스코프 store (userId → Favorite[] Map) 격리 — 테스트 간 leak 방지
  resetFavoriteStore()
})
afterAll(() => server.close())
