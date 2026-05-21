// Vitest 전역 셋업 — jest-dom matcher 확장 + MSW 서버 라이프사이클
/// <reference types="vitest/globals" />
import '@testing-library/jest-dom'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
