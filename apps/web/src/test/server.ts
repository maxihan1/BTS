// MSW Node 서버 인스턴스 — 테스트 환경에서 HTTP 요청을 가로채는 역할
import { setupServer } from 'msw/node'
import { handlers } from './handlers'

export const server = setupServer(...handlers)
