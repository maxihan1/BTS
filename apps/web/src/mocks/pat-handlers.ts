// PAT(Personal Access Token) 셀프서비스 MSW stateful 핸들러 — 발급(raw token 1회)·목록·취소 (FR-API-04 Task 9)
import { http, HttpResponse } from 'msw'
import { PAT_SCOPE_CATALOG, type Pat, type PatIssued, type PatScope } from '@/api/pats'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 — 신규 의존성 금지, crypto.randomUUID 표준 API 사용
// (webhook-fixtures.ts / board-fixtures.ts 동일 패턴 — Zod v4 z.string().uuid() 통과 보장)
// ─────────────────────────────────────────────────────────────────────────────

function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// stateful store — id → { pat, revoked }
//
// 폐기는 물리 삭제가 아닌 revoked 플래그로 소프트 처리한다 — 백엔드 계약(api/pats.ts revokePat
// 문서)이 "이미 폐기된 PAT 재호출도 204"(멱등)이면서 "존재한 적 없는/타인 소유 id는 404"를
// 구분하기 때문이다. 물리 delete만 쓰면 이 두 경로를 구분할 수 없다.
//
// 각 Playwright 테스트는 새 브라우저 컨텍스트(→ 새 페이지 로드 → 모듈 재평가)이므로 이 모듈 top-level
// 변수(Map 자체는 mutate만 하고 재할당하지 않는다)는 테스트마다 자동으로 빈 상태로 초기화된다 — 별도 리셋 API 불필요
// (webhook-fixtures.ts 동형, msw-derived-behavior-shared-store-e2e 학습 반영).
//
// 검증 상수는 백엔드 PersonalAccessTokenService.Companion 미러 (MAX_ACTIVE_TOKENS=20,
// MIN_EXPIRY_DAYS=1, MAX_EXPIRY_DAYS=365).
// ─────────────────────────────────────────────────────────────────────────────

const MAX_ACTIVE_TOKENS = 20
const MIN_EXPIRY_DAYS = 1
const MAX_EXPIRY_DAYS = 365

interface PatRecord {
  pat: Pat
  revoked: boolean
}

const patStore: Map<string, PatRecord> = new Map()

/** PAT 에러 응답 body 형태 — `{ error: <code> }` (account-link-handlers/session-handlers 관례). */
interface PatErrorBody {
  error: string
}

function errorResponse(status: number, code: string): HttpResponse<PatErrorBody> {
  return HttpResponse.json<PatErrorBody>({ error: code }, { status })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/pats
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/users/me/pats — 본인 PAT 요약 목록(취소되지 않은 전부, 만료 포함) 조회.
 *
 * revoked=true 항목은 목록에서 제외한다(폐기된 토큰은 다시 보이지 않아야 함).
 * 성공 → 200 `{ pats: [...] }` (삽입 순서 유지, raw token/hash 미포함).
 */
const listPatsHandler = http.get('/api/v1/users/me/pats', () => {
  const pats = Array.from(patStore.values())
    .filter((record) => !record.revoked)
    .map((record) => record.pat)
  return HttpResponse.json({ pats })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/users/me/pats
// ─────────────────────────────────────────────────────────────────────────────

interface CreatePatBody {
  name?: string
  scopes?: PatScope[]
  expiresInDays?: number
}

/**
 * POST /api/v1/users/me/pats — PAT 발급. raw token은 이 응답에서만 1회 노출된다(EC-26).
 *
 * 검증(PersonalAccessTokenService 미러).
 * - name trim 공백 → 400 invalid_name
 * - scopes 빈 배열 또는 카탈로그({@link PAT_SCOPE_CATALOG}) 외 값 포함 → 400 invalid_scope
 * - expiresInDays가 1..365 범위 밖 → 400 invalid_expiry
 * - 활성(미폐기) PAT 개수가 {@link MAX_ACTIVE_TOKENS}(20) 이상 → 403 quota_exceeded
 *
 * 성공 → 201 PatIssued(id/name/scopes/token/expiresAt/createdAt).
 */
const createPatHandler = http.post('/api/v1/users/me/pats', async ({ request }) => {
  const body = (await request.json()) as CreatePatBody
  const name = (body.name ?? '').trim()
  if (name === '') {
    return errorResponse(400, 'invalid_name')
  }

  const scopes = body.scopes ?? []
  const scopesValid = scopes.length > 0 && scopes.every((s) => PAT_SCOPE_CATALOG.includes(s))
  if (!scopesValid) {
    return errorResponse(400, 'invalid_scope')
  }

  const expiresInDays = body.expiresInDays ?? 0
  if (expiresInDays < MIN_EXPIRY_DAYS || expiresInDays > MAX_EXPIRY_DAYS) {
    return errorResponse(400, 'invalid_expiry')
  }

  const activeCount = Array.from(patStore.values()).filter((r) => !r.revoked).length
  if (activeCount >= MAX_ACTIVE_TOKENS) {
    return errorResponse(403, 'quota_exceeded')
  }

  const now = new Date()
  const createdAt = now.toISOString()
  const expiresAt = new Date(now.getTime() + expiresInDays * 24 * 60 * 60 * 1000).toISOString()
  const id = generateUuidV4()

  const summary: Pat = {
    id,
    name,
    scopes,
    expiresAt,
    lastUsedAt: null,
    createdAt,
  }
  patStore.set(id, { pat: summary, revoked: false })

  const issued: PatIssued = {
    id,
    name,
    scopes,
    // prefix 포함 raw token 형태 시뮬레이션 — 실제 값 형식은 백엔드 BASE62_ALPHABET 인코딩이지만
    // 목록/화면 표시 검증(1회 노출·목록 미노출)에는 고유 문자열이면 충분하다.
    token: `bts_pat_${generateUuidV4().replace(/-/g, '')}`,
    expiresAt,
    createdAt,
  }

  return HttpResponse.json(issued, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/users/me/pats/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DELETE /api/v1/users/me/pats/{id} — PAT 폐기(소프트 삭제).
 *
 * - store에 존재하는 id(이미 폐기된 상태 포함) → revoked=true로 세팅 후 204(멱등).
 * - store에 존재한 적 없는 id(IDOR 포함) → 404 not_found.
 */
const revokePatHandler = http.delete('/api/v1/users/me/pats/:id', ({ params }) => {
  const id = params['id'] as string
  const record = patStore.get(id)
  if (record === undefined) {
    return errorResponse(404, 'not_found')
  }
  record.revoked = true
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// export
// ─────────────────────────────────────────────────────────────────────────────

/** PAT 셀프서비스 MSW 핸들러 배열 — handlers.ts에서 spread해 등록한다. */
export const patHandlers = [listPatsHandler, createPatHandler, revokePatHandler]
