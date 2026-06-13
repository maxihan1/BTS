// WebAuthn(Passkey/하드웨어 키) MSW stateful 핸들러 — 등록/삭제/조회/인증시작 (FR-MF-03)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — CSRF 검사
// 존재 여부만 확인 (실제 값 검증은 백엔드 몫 — mfa-handlers 선례)
// ─────────────────────────────────────────────────────────────────────────────

function checkCsrf(request: Request): boolean {
  return request.headers.get('X-XSRF-TOKEN') !== null
}

function csrfMissingResponse(): Response {
  return HttpResponse.json({ error: 'csrf_token_missing' }, { status: 403 })
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (notification-policy-handlers 동형)
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
// WebAuthn 키 store — 백엔드 WebAuthnKeyResponse 형태 일치
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장된 WebAuthn 키 항목.
 * 백엔드 WebAuthnKeyResponse DTO와 필드 일치 (name·lastUsedAt 키 보존, null 허용).
 */
interface WebAuthnKey {
  /** 내부 UUID — DELETE/목록 조회용 */
  id: string
  /** 사용자가 등록 시 지정한 키 이름 (null 허용) */
  name: string | null
  /** credential.id — 중복 방지용 */
  credentialId: string
  /** 등록 일시 ISO 8601 */
  createdAt: string
  /** 마지막 사용 일시 ISO 8601 (미사용이면 null) */
  lastUsedAt: string | null
}

/** WebAuthn 인메모리 store — resetWebauthnStore()로 테스트/E2E 격리 */
let webauthnStore: WebAuthnKey[] = []

/**
 * WebAuthn store를 빈 상태로 초기화한다.
 * Vitest 단위 테스트 afterEach 또는 E2E 시드 전 호출해 테스트 간 격리를 보장한다.
 */
export function resetWebauthnStore(): void {
  webauthnStore = []
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/webauthn/register/start
// CSRF 불요 (세션 인증만 필요). 성공 → WebAuthn 등록 옵션 반환.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WebAuthn 등록 시작 — @simplewebauthn 클라이언트가 디코드할 옵션 반환.
 * challenge·rp·user·pubKeyCredParams·attestation·excludeCredentials 포함.
 */
const registerStartHandler = http.post('/api/v1/auth/mfa/webauthn/register/start', () => {
  return HttpResponse.json({
    challenge: 'bW9jay1jaGFsbGVuZ2UtYmFzZTY0dXJs',
    rp: { id: 'bts.local', name: 'BTS Atlas' },
    user: {
      id: 'bW9jay11c2VyLWlk',
      name: 'alice',
      displayName: 'Alice',
    },
    pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
    timeout: 60000,
    attestation: 'none',
    excludeCredentials: [],
    authenticatorSelection: {
      authenticatorAttachment: 'cross-platform',
      residentKey: 'discouraged',
      userVerification: 'preferred',
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/webauthn/register/finish
// CSRF 필수. body: { credential, name }. 중복 credential.id → 409. 성공 → 빈 201.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WebAuthn 등록 완료 — credential을 store에 추가한다.
 * 같은 credential.id가 이미 존재하면 409 already_registered 반환.
 * 성공 시 본문 없는 201 반환 (백엔드 응답과 동일).
 */
const registerFinishHandler = http.post('/api/v1/auth/mfa/webauthn/register/finish', async ({ request }) => {
  if (!checkCsrf(request)) return csrfMissingResponse()

  const body = await request.json() as {
    credential: { id: string; type: string; rawId: string }
    name?: string | null
  }

  const credentialId = body.credential.id
  const duplicate = webauthnStore.find((k) => k.credentialId === credentialId)
  if (duplicate !== undefined) {
    return HttpResponse.json({ error: 'already_registered' }, { status: 409 })
  }

  const now = new Date().toISOString()
  webauthnStore.push({
    id: generateUuidV4(),
    name: body.name ?? null,
    credentialId,
    createdAt: now,
    lastUsedAt: null,
  })

  return new HttpResponse(null, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/mfa/webauthn
// CSRF 불요 (읽기 전용). 성공 → { keys: WebAuthnKeyResponse[] }.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WebAuthn 키 목록 조회 — 등록된 모든 키를 반환한다.
 * 각 항목은 백엔드 WebAuthnKeyResponse 형태 (credentialId 내부 필드 제외).
 */
const listKeysHandler = http.get('/api/v1/auth/mfa/webauthn', () => {
  const keys = webauthnStore.map(({ id, name, createdAt, lastUsedAt }) => ({
    id,
    name,
    createdAt,
    lastUsedAt,
  }))
  return HttpResponse.json({ keys })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/webauthn/:id
// CSRF 필수. 없는 id → 404. 성공 → 빈 204.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WebAuthn 키 삭제 — store에서 제거한다.
 * 존재하지 않는 id이면 404 not_found 반환.
 */
const deleteKeyHandler = http.delete('/api/v1/auth/mfa/webauthn/:id', ({ request, params }) => {
  if (!checkCsrf(request)) return csrfMissingResponse()

  const id = params['id'] as string
  const index = webauthnStore.findIndex((k) => k.id === id)
  if (index === -1) {
    return HttpResponse.json({ error: 'not_found' }, { status: 404 })
  }

  webauthnStore.splice(index, 1)
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/webauthn/authenticate/start
// CSRF 불요 (세션 전 permitAll 경로). body: { mfa_challenge_token }.
// "__invalid_token__" → 401 mfa_challenge_expired. 성공 → 인증 옵션 반환.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WebAuthn 인증 시작 — 챌린지 옵션과 허용된 credential 목록 반환.
 * mfa_challenge_token이 "__invalid_token__"이면 401 mfa_challenge_expired 반환.
 * allowCredentials는 store에 등록된 키의 base64url id로 구성한다.
 */
const authenticateStartHandler = http.post(
  '/api/v1/auth/mfa/webauthn/authenticate/start',
  async ({ request }) => {
    const body = await request.json() as { mfa_challenge_token?: string }

    if (body.mfa_challenge_token === '__invalid_token__') {
      return HttpResponse.json({ error: 'mfa_challenge_expired' }, { status: 401 })
    }

    const allowCredentials = webauthnStore.map((k) => ({
      type: 'public-key',
      id: btoa(k.credentialId).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, ''),
    }))

    return HttpResponse.json({
      challenge: 'bW9jay1hdXRoLWNoYWxsZW5nZQ',
      rpId: 'bts.local',
      allowCredentials,
      userVerification: 'preferred',
      timeout: 60000,
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 배열 export
// ─────────────────────────────────────────────────────────────────────────────

export const webauthnHandlers = [
  registerStartHandler,
  registerFinishHandler,
  listKeysHandler,
  deleteKeyHandler,
  authenticateStartHandler,
]
