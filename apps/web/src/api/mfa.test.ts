// MFA(TOTP) API 클라이언트 단위 테스트 — MSW + Zod 파싱 + CSRF 헤더 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import {
  MfaSetupResponseSchema,
  MfaStatusResponseSchema,
  MfaRequiredResponseSchema,
  LoginOrMfaResponseSchema,
  TokenResponseSchema,
} from './schemas'
import {
  setupMfa,
  getMfaStatus,
  enableMfa,
  disableMfa,
  verifyMfa,
} from './mfa'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — CSRF 헤더 검증용
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-mfa-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture
// ─────────────────────────────────────────────────────────────────────────────

const setupResponseFixture = {
  otpauth_uri: 'otpauth://totp/BTS:alice?secret=BASE32SECRET&issuer=BTS',
  qr_png_data_uri: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  secret_base32: 'BASE32SECRET',
}

const statusEnabledFixture = { enabled: true }
const statusDisabledFixture = { enabled: false }

const tokenResponseFixture = {
  access_token: 'eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.test',
  token_type: 'Bearer' as const,
  expires_in: 900,
}

const mfaRequiredFixture = {
  mfa_required: true as const,
  mfa_challenge_token: 'challenge.jwt.token',
  expires_in: 300,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-S. Zod 스키마 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('MfaSetupResponseSchema', () => {
  it('T-MFA-S1: 정상 필드를 파싱한다', () => {
    const result = MfaSetupResponseSchema.parse(setupResponseFixture)
    expect(result.otpauth_uri).toBe(setupResponseFixture.otpauth_uri)
    expect(result.qr_png_data_uri).toBe(setupResponseFixture.qr_png_data_uri)
    expect(result.secret_base32).toBe(setupResponseFixture.secret_base32)
  })

  it('T-MFA-S2: 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => MfaSetupResponseSchema.parse({ otpauth_uri: 'x' })).toThrow()
  })
})

describe('MfaStatusResponseSchema', () => {
  it('T-MFA-S3: enabled:true를 파싱한다', () => {
    const result = MfaStatusResponseSchema.parse(statusEnabledFixture)
    expect(result.enabled).toBe(true)
  })

  it('T-MFA-S4: enabled:false를 파싱한다', () => {
    const result = MfaStatusResponseSchema.parse(statusDisabledFixture)
    expect(result.enabled).toBe(false)
  })
})

describe('MfaRequiredResponseSchema', () => {
  it('T-MFA-S5: mfa_required:true + challenge 필드를 파싱한다', () => {
    const result = MfaRequiredResponseSchema.parse(mfaRequiredFixture)
    expect(result.mfa_required).toBe(true)
    expect(result.mfa_challenge_token).toBe('challenge.jwt.token')
    expect(result.expires_in).toBe(300)
  })

  it('T-MFA-S6: mfa_required:false이면 ZodError를 throw한다', () => {
    expect(() =>
      MfaRequiredResponseSchema.parse({ ...mfaRequiredFixture, mfa_required: false }),
    ).toThrow()
  })
})

describe('LoginOrMfaResponseSchema', () => {
  it('T-MFA-S7: mfa_required:true이면 MfaRequiredResponse로 파싱된다', () => {
    const result = LoginOrMfaResponseSchema.parse(mfaRequiredFixture)
    // 'mfa_required' in 가드로 union narrowing
    expect('mfa_required' in result).toBe(true)
    if ('mfa_required' in result) {
      expect(result.mfa_required).toBe(true)
      expect(result.mfa_challenge_token).toBe('challenge.jwt.token')
    }
  })

  it('T-MFA-S8: mfa_required 없으면 TokenResponse로 파싱된다', () => {
    const result = LoginOrMfaResponseSchema.parse(tokenResponseFixture)
    expect('access_token' in result).toBe(true)
    if (!('mfa_required' in result)) {
      expect(result.access_token).toBe(tokenResponseFixture.access_token)
    }
  })

  it('T-MFA-S9: mfa_required:true + access_token 동봉 시 MfaRequired로 파싱된다 (MFA 우선)', () => {
    // access_token이 있어도 mfa_required:true면 MfaRequired 경로로 파싱해야 한다
    const ambiguous = { ...mfaRequiredFixture, ...tokenResponseFixture }
    const result = LoginOrMfaResponseSchema.parse(ambiguous)
    // union의 첫 번째 후보(MfaRequiredResponseSchema)가 먼저 매칭돼야 한다
    expect('mfa_required' in result).toBe(true)
    if ('mfa_required' in result) {
      expect(result.mfa_required).toBe(true)
      expect(result.mfa_challenge_token).toBe('challenge.jwt.token')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-1. setupMfa — POST /api/v1/auth/mfa/totp/setup
// ─────────────────────────────────────────────────────────────────────────────
describe('setupMfa', () => {
  it('T-MFA-1a: 200 응답 → MfaSetupResponse 반환', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/totp/setup', () =>
        HttpResponse.json(setupResponseFixture, { status: 200 }),
      ),
    )
    const result = await setupMfa()
    expect(result.otpauth_uri).toBe(setupResponseFixture.otpauth_uri)
    expect(result.qr_png_data_uri).toBe(setupResponseFixture.qr_png_data_uri)
    expect(result.secret_base32).toBe(setupResponseFixture.secret_base32)
  })

  it('T-MFA-1b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/totp/setup', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(setupResponseFixture, { status: 200 })
      }),
    )
    await setupMfa()
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-MFA-1c: 409 already_enabled → ApiError(409) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/totp/setup', () =>
        HttpResponse.json({ error: 'already_enabled' }, { status: 409 }),
      ),
    )
    await expect(setupMfa()).rejects.toBeInstanceOf(ApiError)
    let thrown: unknown
    try {
      await setupMfa()
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-2. getMfaStatus — GET /api/v1/auth/mfa/totp
// ─────────────────────────────────────────────────────────────────────────────
describe('getMfaStatus', () => {
  it('T-MFA-2a: enabled:true 응답을 파싱한다', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/totp', () =>
        HttpResponse.json(statusEnabledFixture),
      ),
    )
    const result = await getMfaStatus()
    expect(result.enabled).toBe(true)
  })

  it('T-MFA-2b: enabled:false 응답을 파싱한다', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/totp', () =>
        HttpResponse.json(statusDisabledFixture),
      ),
    )
    const result = await getMfaStatus()
    expect(result.enabled).toBe(false)
  })

  it('T-MFA-2c: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (GET 읽기 요청)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.get('/api/v1/auth/mfa/totp', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(statusEnabledFixture)
      }),
    )
    await getMfaStatus()
    expect(capturedXsrf).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-3. enableMfa — POST /api/v1/auth/mfa/totp/enable
// ─────────────────────────────────────────────────────────────────────────────
describe('enableMfa', () => {
  it('T-MFA-3a: 204 응답 → void 반환 (에러 없음)', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/totp/enable', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(enableMfa('123456')).resolves.toBeUndefined()
  })

  it('T-MFA-3b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/totp/enable', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await enableMfa('123456')
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-MFA-3c: 요청 바디에 code가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/totp/enable', async ({ request }) => {
        capturedBody = await request.json()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await enableMfa('654321')
    expect((capturedBody as Record<string, unknown> | null)?.code).toBe('654321')
  })

  it('T-MFA-3d: 400 invalid_code → ApiError(400) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/totp/enable', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 400 }),
      ),
    )
    let thrown: unknown
    try {
      await enableMfa('000000')
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-4. disableMfa — DELETE /api/v1/auth/mfa/totp
// ─────────────────────────────────────────────────────────────────────────────
describe('disableMfa', () => {
  it('T-MFA-4a: 204 응답 → void 반환 (에러 없음)', async () => {
    server.use(
      http.delete('/api/v1/auth/mfa/totp', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(disableMfa('123456')).resolves.toBeUndefined()
  })

  it('T-MFA-4b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/auth/mfa/totp', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await disableMfa('123456')
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-MFA-4c: 요청 바디에 code가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.delete('/api/v1/auth/mfa/totp', async ({ request }) => {
        capturedBody = await request.json()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await disableMfa('654321')
    expect((capturedBody as Record<string, unknown> | null)?.code).toBe('654321')
  })

  it('T-MFA-4d: 400 invalid_code → ApiError(400) throw', async () => {
    server.use(
      http.delete('/api/v1/auth/mfa/totp', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 400 }),
      ),
    )
    let thrown: unknown
    try {
      await disableMfa('000000')
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-5. verifyMfa — POST /api/v1/auth/mfa/verify
// ─────────────────────────────────────────────────────────────────────────────
describe('verifyMfa', () => {
  it('T-MFA-5a: 200 응답 → TokenResponse 반환', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json(tokenResponseFixture, { status: 200 }),
      ),
    )
    const result = await verifyMfa('challenge.jwt.token', '123456')
    expect(result.access_token).toBe(tokenResponseFixture.access_token)
    expect(result.token_type).toBe('Bearer')
  })

  it('T-MFA-5b: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (permitAll + CSRF-ignore 경로)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyMfa('challenge.jwt.token', '123456')
    expect(capturedXsrf).toBeNull()
  })

  it('T-MFA-5c: 요청 바디에 mfa_challenge_token과 code가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyMfa('challenge.jwt.token', '123456')
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.mfa_challenge_token).toBe('challenge.jwt.token')
    expect(body?.code).toBe('123456')
  })

  it('T-MFA-5d: 401 invalid_code → ApiError(401) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
    )
    let thrown: unknown
    try {
      await verifyMfa('challenge.jwt.token', '000000')
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(401)
  })

  it('T-MFA-5d-prod: refresh가 401을 반환하는 프로덕션 조건에서도 verify 401 invalid_code가 그대로 전파된다', async () => {
    // 프로덕션 환경 시뮬레이션 — 아직 세션이 없어 refresh도 401을 반환한다.
    // verifyMfa가 apiFetch를 사용하면 refresh 시도 → refresh 401 → clearSession 부수효과 + ApiError(401 from refresh)가 throw된다.
    // raw fetch를 사용하면 refresh를 전혀 호출하지 않으므로 verify 원래 401 invalid_code가 그대로 throw된다.
    let refreshCallCount = 0
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount++
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )

    let thrown: unknown
    try {
      await verifyMfa('challenge.jwt.token', '000000')
    } catch (e) {
      thrown = e
    }

    // refresh가 단 한 번도 호출되지 않아야 한다 (raw fetch는 refresh를 우회)
    expect(refreshCallCount).toBe(0)
    // verify 원래 에러 코드가 그대로 전파되어야 한다
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(401)
    expect((thrown.body as Record<string, unknown> | null)?.error).toBe('invalid_code')
  })

  it('T-MFA-5e: 429 too_many_attempts → ApiError(429) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'too_many_attempts' }, { status: 429 }),
      ),
    )
    let thrown: unknown
    try {
      await verifyMfa('challenge.jwt.token', '000000')
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(429)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-6. TokenResponseSchema 재활용 — schemas.ts에서 import
// ─────────────────────────────────────────────────────────────────────────────
describe('TokenResponseSchema (재활용)', () => {
  it('T-MFA-6a: verifyMfa 응답이 TokenResponseSchema로 파싱된다', () => {
    const result = TokenResponseSchema.parse(tokenResponseFixture)
    expect(result.access_token).toBe(tokenResponseFixture.access_token)
    expect(result.token_type).toBe('Bearer')
    expect(result.expires_in).toBe(900)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-BC-S. 백업코드 Zod 스키마 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
import {
  BackupCodesResponseSchema,
  BackupCodesStatusResponseSchema,
} from './schemas'
import {
  generateBackupCodes,
  getBackupCodesStatus,
} from './mfa'

const backupCodesFixture = {
  codes: [
    'AAAA-BBBB-1111',
    'CCCC-DDDD-2222',
    'EEEE-FFFF-3333',
    'GGGG-HHHH-4444',
    'IIII-JJJJ-5555',
    'KKKK-LLLL-6666',
    'MMMM-NNNN-7777',
    'OOOO-PPPP-8888',
    'QQQQ-RRRR-9999',
    'SSSS-TTTT-0000',
  ],
}

const backupCodesStatusFixture = {
  generated: true,
  remaining: 7,
}

describe('BackupCodesResponseSchema', () => {
  it('T-MFA-BC-S1: 10개 코드 배열을 파싱한다', () => {
    const result = BackupCodesResponseSchema.parse(backupCodesFixture)
    expect(result.codes).toHaveLength(10)
    expect(result.codes[0]).toBe('AAAA-BBBB-1111')
  })

  it('T-MFA-BC-S2: 빈 배열이면 ZodError를 throw한다', () => {
    expect(() => BackupCodesResponseSchema.parse({ codes: [] })).toThrow()
  })

  it('T-MFA-BC-S3: codes 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => BackupCodesResponseSchema.parse({})).toThrow()
  })
})

describe('BackupCodesStatusResponseSchema', () => {
  it('T-MFA-BC-S4: generated:true + remaining 숫자를 파싱한다', () => {
    const result = BackupCodesStatusResponseSchema.parse(backupCodesStatusFixture)
    expect(result.generated).toBe(true)
    expect(result.remaining).toBe(7)
  })

  it('T-MFA-BC-S5: generated:false + remaining:0을 파싱한다', () => {
    const result = BackupCodesStatusResponseSchema.parse({ generated: false, remaining: 0 })
    expect(result.generated).toBe(false)
    expect(result.remaining).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-7. generateBackupCodes — POST /api/v1/auth/mfa/backup-codes
// ─────────────────────────────────────────────────────────────────────────────
describe('generateBackupCodes', () => {
  it('T-MFA-7a: 200 응답 → BackupCodesResponse 반환', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json(backupCodesFixture, { status: 200 }),
      ),
    )
    const result = await generateBackupCodes()
    expect(result.codes).toHaveLength(10)
    expect(result.codes[0]).toBe('AAAA-BBBB-1111')
  })

  it('T-MFA-7b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/backup-codes', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(backupCodesFixture, { status: 200 })
      }),
    )
    await generateBackupCodes()
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-MFA-7c: 409 totp_not_active → ApiError(409) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ error: 'totp_not_active' }, { status: 409 }),
      ),
    )
    let thrown: unknown
    try {
      await generateBackupCodes()
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-8. getBackupCodesStatus — GET /api/v1/auth/mfa/backup-codes
// ─────────────────────────────────────────────────────────────────────────────
describe('getBackupCodesStatus', () => {
  it('T-MFA-8a: generated:true + remaining 응답을 파싱한다', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json(backupCodesStatusFixture),
      ),
    )
    const result = await getBackupCodesStatus()
    expect(result.generated).toBe(true)
    expect(result.remaining).toBe(7)
  })

  it('T-MFA-8b: generated:false + remaining:0 응답을 파싱한다', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/backup-codes', () =>
        HttpResponse.json({ generated: false, remaining: 0 }),
      ),
    )
    const result = await getBackupCodesStatus()
    expect(result.generated).toBe(false)
    expect(result.remaining).toBe(0)
  })

  it('T-MFA-8c: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (GET 읽기 요청)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.get('/api/v1/auth/mfa/backup-codes', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(backupCodesStatusFixture)
      }),
    )
    await getBackupCodesStatus()
    expect(capturedXsrf).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-10. verifyMfa — trustDevice 인자 (FR-MF-05 신뢰 디바이스)
// ─────────────────────────────────────────────────────────────────────────────
describe('verifyMfa — trustDevice 인자', () => {
  it('T-MFA-10a: trustDevice:true 전달 시 body에 trust_device:true가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyMfa('challenge.jwt.token', '123456', 'totp', true)
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.trust_device).toBe(true)
  })

  it('T-MFA-10b: trustDevice 생략(기본값) 시 body에 trust_device:false가 포함된다 (하위호환)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyMfa('challenge.jwt.token', '123456')
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.trust_device).toBe(false)
  })

  it('T-MFA-10c: trustDevice:true 전달해도 /refresh를 호출하지 않는다 (raw fetch 유지 — NFR-2 회귀)', async () => {
    let refreshCallCount = 0
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount++
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )
    let thrown: unknown
    try {
      await verifyMfa('challenge.jwt.token', '000000', 'totp', true)
    } catch (e) {
      thrown = e
    }
    expect(refreshCallCount).toBe(0)
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MFA-9. verifyMfa — method 파라미터 확장 (하위호환 검증)
// ─────────────────────────────────────────────────────────────────────────────
describe('verifyMfa — method 파라미터', () => {
  it('T-MFA-9a: method 생략 시 body에 method:"totp"가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyMfa('challenge.jwt.token', '123456')
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.method).toBe('totp')
  })

  it('T-MFA-9b: method:"backup_code" 전달 시 body에 method:"backup_code"가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyMfa('challenge.jwt.token', 'AAAA-BBBB-1111', 'backup_code')
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.method).toBe('backup_code')
    expect(body?.mfa_challenge_token).toBe('challenge.jwt.token')
    expect(body?.code).toBe('AAAA-BBBB-1111')
  })

  it('T-MFA-9c: method:"totp" 명시 전달 시 body에 method:"totp"가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyMfa('challenge.jwt.token', '123456', 'totp')
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.method).toBe('totp')
  })
})
