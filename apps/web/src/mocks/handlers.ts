// MSW 핸들러 통합 인덱스 — BC별 handlers를 spread해서 내보낸다
import { workflowHandlers } from './workflow-handlers'

export const handlers = [
  ...workflowHandlers,
]
