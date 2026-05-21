// MSW 요청 핸들러 기본 목록 — 각 테스트 파일에서 server.use()로 추가
import { type RequestHandler } from 'msw'

export const handlers: RequestHandler[] = []
