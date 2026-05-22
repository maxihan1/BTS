// MSW browser worker setup — dev 환경에서 API 요청을 가로채는 Service Worker
import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

export const worker = setupWorker(...handlers)
