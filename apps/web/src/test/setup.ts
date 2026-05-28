// Vitest 전역 셋업 — jest-dom matcher 확장 + MSW 서버 라이프사이클 + 모듈 상태 격리
/// <reference types="vitest/globals" />
import '@testing-library/jest-dom'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'
import { resetIssueState } from '@/mocks/issue-handlers'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  // issue-handlers 모듈-스코프 state (deletedKeys / createdIssues) 격리 — 테스트 간 leak 방지
  resetIssueState()
})
afterAll(() => server.close())
