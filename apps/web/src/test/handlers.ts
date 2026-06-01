// MSW 요청 핸들러 기본 목록 — 각 테스트 파일에서 server.use()로 추가
import { http, HttpResponse, type RequestHandler } from 'msw'

export const handlers: RequestHandler[] = [
  /**
   * 기본 refresh 핸들러 — 테스트 환경에서 apiFetch의 401 자동 재시도 로직이
   * "POST /api/v1/auth/refresh" 미핸들 MSW 에러를 내지 않도록 한다.
   * 새 토큰을 발급하면 401 테스트 케이스에서 원래 요청을 retry 후
   * 다시 401을 받아 ProjectMemberApiError(401)로 정상 변환된다.
   */
  http.post('/api/v1/auth/refresh', () => {
    return HttpResponse.json({
      access_token: 'test-refreshed-token',
      token_type: 'Bearer',
      expires_in: 900,
    })
  }),
]
