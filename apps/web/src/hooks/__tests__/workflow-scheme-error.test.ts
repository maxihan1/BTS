// 스킴 변경 409(WF-02-1)가 실제 백엔드 응답 모양 그대로 화면 안내까지 이어지는지 재는 테스트
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { assignSchemeToProject, WorkflowSchemeApiError } from '@/api/workflow-schemes'
import { notifySchemeError } from '../workflow-scheme-error'

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

import { toast } from 'sonner'

const DETAIL = '새 워크플로우에 없는 상태에 이슈가 남습니다: review 3건. 이슈 타입마다 옮길 상태를 선택하세요'

describe('스킴 변경이 이슈를 남길 때 (WF-02-1)', () => {
  it('백엔드 ProblemDetail 의 errorCode 를 읽고 남는 상태를 안내한다', async () => {
    // 백엔드 WorkflowSchemeExceptionHandler 가 싣는 모양 그대로 — `code` 가 아니라 `errorCode` 다.
    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json(
          { errorCode: 'SCHEME_CHANGE_STRANDS_ISSUES', detail: DETAIL, strandedByStatus: { review: 3 } },
          { status: 409 },
        ),
      ),
    )

    const error = await assignSchemeToProject('BTS', { schemeKey: 'plain-scheme' }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(WorkflowSchemeApiError)
    expect((error as WorkflowSchemeApiError).errorCode).toBe('SCHEME_CHANGE_STRANDS_ISSUES')

    notifySchemeError(error)
    expect(toast.error).toHaveBeenCalledWith('새 워크플로우에 없는 상태에 이슈가 남아 변경할 수 없습니다', {
      description: DETAIL,
    })
  })
})
