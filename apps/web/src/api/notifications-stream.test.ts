// STOMP 인앱 알림 스트림 클라이언트 단위 테스트
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createNotificationStream, inAppNotificationSchema } from './notifications-stream'
import { Client } from '@stomp/stompjs'

// ─────────────────────────────────────────────────────────────────────────────
// @stomp/stompjs Client mock
// vi.mock은 모듈 최상위에서 호이스팅됨 — factory 안에서 vi.fn() 직접 사용
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@stomp/stompjs', () => {
  const ClientMock = vi.fn()
  return { Client: ClientMock }
})

// ─────────────────────────────────────────────────────────────────────────────
// 유효한 페이로드 픽스처 — 백엔드 InAppNotificationPayload.kt 1:1 정합
// ─────────────────────────────────────────────────────────────────────────────
const validPayload = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  eventType: 'issue.assigned',
  issueKey: 'BTS-42',
  title: '이슈가 나에게 할당됐습니다',
  body: '담당자가 변경되었습니다.',
  occurredAt: '2026-06-13T10:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 각 테스트마다 새 mock 인스턴스 제공 헬퍼
// ─────────────────────────────────────────────────────────────────────────────
type MockInstance = {
  brokerURL: string
  connectHeaders: Record<string, string>
  reconnectDelay: number
  onConnect: ((frame: unknown) => void) | null
  beforeConnect: (() => void) | null
  activate: ReturnType<typeof vi.fn>
  deactivate: ReturnType<typeof vi.fn>
  subscribe: ReturnType<typeof vi.fn>
}

function makeInstance(): MockInstance {
  return {
    brokerURL: '',
    connectHeaders: {},
    reconnectDelay: 0,
    onConnect: null,
    beforeConnect: null,
    activate: vi.fn(),
    deactivate: vi.fn(),
    subscribe: vi.fn(),
  }
}

const MockedClient = vi.mocked(Client)
let mockInstance: MockInstance

beforeEach(() => {
  mockInstance = makeInstance()
  // vi.fn() 생성자 mock은 function 키워드 필수 — arrow function은 new 호출 불가
  // StompConfig 타입을 그대로 받아 mockInstance에 복사
  MockedClient.mockImplementation(
    function (this: unknown, config: unknown) {
      Object.assign(mockInstance, config)
      return mockInstance as unknown as InstanceType<typeof Client>
    } as unknown as typeof Client,
  )
})

afterEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-Z. Zod 스키마 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('inAppNotificationSchema', () => {
  it('유효한 페이로드를 파싱한다', () => {
    const result = inAppNotificationSchema.safeParse(validPayload)
    expect(result.success).toBe(true)
  })

  it('issueKey=null인 경우도 파싱 성공한다', () => {
    const result = inAppNotificationSchema.safeParse({ ...validPayload, issueKey: null })
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.issueKey).toBeNull()
    }
  })

  it('body=null인 경우도 파싱 성공한다', () => {
    const result = inAppNotificationSchema.safeParse({ ...validPayload, body: null })
    expect(result.success).toBe(true)
  })

  it('id가 UUID 형식이 아니면 파싱 실패한다', () => {
    const result = inAppNotificationSchema.safeParse({ ...validPayload, id: 'not-a-uuid' })
    expect(result.success).toBe(false)
  })

  it('title이 없으면 파싱 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { title: _title, ...withoutTitle } = validPayload
    const result = inAppNotificationSchema.safeParse(withoutTitle)
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-1. brokerURL — jsdom location.host 기반으로 생성된다 (C3)
// ─────────────────────────────────────────────────────────────────────────────
describe('createNotificationStream — brokerURL', () => {
  it('brokerURL이 jsdom location.host를 기반으로 ws:// 프로토콜로 구성된다', () => {
    // jsdom의 location.host를 실측해 하드코딩 금지 — C3 관련
    const expectedBrokerURL = `ws://${location.host}/ws`

    createNotificationStream({ getToken: () => 'test-token', onMessage: vi.fn() })

    expect(mockInstance.brokerURL).toBe(expectedBrokerURL)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-2. connectHeaders — Authorization Bearer 포함
// ─────────────────────────────────────────────────────────────────────────────
describe('createNotificationStream — connectHeaders', () => {
  it('초기 connectHeaders에 Authorization: Bearer <getToken()>가 설정된다', () => {
    const getToken = () => 'initial-access-token'

    createNotificationStream({ getToken, onMessage: vi.fn() })

    expect(mockInstance.connectHeaders['Authorization']).toBe('Bearer initial-access-token')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-3. beforeConnect — 매 연결 시 최신 토큰으로 재평가 (C1)
// ─────────────────────────────────────────────────────────────────────────────
describe('createNotificationStream — beforeConnect 토큰 재평가 (C1)', () => {
  it('beforeConnect 콜백이 호출될 때 getToken()의 최신값으로 Authorization 헤더를 재설정한다', () => {
    let currentToken = 'token-v1'
    const getToken = () => currentToken

    createNotificationStream({ getToken, onMessage: vi.fn() })

    // 토큰 교체 시뮬레이션
    currentToken = 'token-v2-refreshed'

    // beforeConnect 콜백 직접 실행
    if (typeof mockInstance.beforeConnect !== 'function') {
      throw new Error('beforeConnect가 설정되지 않았습니다')
    }
    mockInstance.beforeConnect()

    expect(mockInstance.connectHeaders['Authorization']).toBe('Bearer token-v2-refreshed')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-4. onConnect — /user/queue/notifications 구독
// ─────────────────────────────────────────────────────────────────────────────
describe('createNotificationStream — onConnect 구독', () => {
  it('onConnect 호출 시 /user/queue/notifications를 구독한다', () => {
    createNotificationStream({ getToken: () => 'tok', onMessage: vi.fn() })

    if (typeof mockInstance.onConnect !== 'function') {
      throw new Error('onConnect가 설정되지 않았습니다')
    }
    mockInstance.onConnect({})

    expect(mockInstance.subscribe).toHaveBeenCalledWith(
      '/user/queue/notifications',
      expect.any(Function),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-5. 유효 메시지 → onMessage 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('createNotificationStream — 메시지 수신', () => {
  it('유효 JSON 프레임 수신 시 Zod 파싱 후 onMessage가 호출된다', () => {
    const onMessage = vi.fn()

    createNotificationStream({ getToken: () => 'tok', onMessage })

    if (typeof mockInstance.onConnect !== 'function') {
      throw new Error('onConnect가 설정되지 않았습니다')
    }
    mockInstance.onConnect({})

    const subscribeCallback = mockInstance.subscribe.mock.calls[0]?.[1] as (frame: { body: string }) => void
    subscribeCallback({ body: JSON.stringify(validPayload) })

    expect(onMessage).toHaveBeenCalledOnce()
    expect(onMessage).toHaveBeenCalledWith(
      expect.objectContaining({ id: validPayload.id, title: validPayload.title }),
    )
  })

  it('body=null 페이로드도 onMessage가 호출된다', () => {
    const onMessage = vi.fn()

    createNotificationStream({ getToken: () => 'tok', onMessage })

    if (typeof mockInstance.onConnect !== 'function') {
      throw new Error('onConnect가 설정되지 않았습니다')
    }
    mockInstance.onConnect({})

    const subscribeCallback = mockInstance.subscribe.mock.calls[0]?.[1] as (frame: { body: string }) => void
    subscribeCallback({ body: JSON.stringify({ ...validPayload, body: null }) })

    expect(onMessage).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-6. 스키마 불일치 → onMessage 미호출 + console.warn (앱 크래시 금지)
// ─────────────────────────────────────────────────────────────────────────────
describe('createNotificationStream — 유효하지 않은 메시지 처리', () => {
  it('스키마 불일치 프레임 수신 시 onMessage를 호출하지 않고 console.warn을 1회 출력한다', () => {
    const onMessage = vi.fn()
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)

    createNotificationStream({ getToken: () => 'tok', onMessage })

    if (typeof mockInstance.onConnect !== 'function') {
      throw new Error('onConnect가 설정되지 않았습니다')
    }
    mockInstance.onConnect({})

    const subscribeCallback = mockInstance.subscribe.mock.calls[0]?.[1] as (frame: { body: string }) => void
    // id 필드 누락 — 스키마 실패
    subscribeCallback({ body: JSON.stringify({ eventType: 'issue.created', title: '제목' }) })

    expect(onMessage).not.toHaveBeenCalled()
    expect(warnSpy).toHaveBeenCalledOnce()

    warnSpy.mockRestore()
  })

  it('비JSON 프레임 수신 시 onMessage를 호출하지 않고 console.warn을 1회 출력한다', () => {
    const onMessage = vi.fn()
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)

    createNotificationStream({ getToken: () => 'tok', onMessage })

    if (typeof mockInstance.onConnect !== 'function') {
      throw new Error('onConnect가 설정되지 않았습니다')
    }
    mockInstance.onConnect({})

    const subscribeCallback = mockInstance.subscribe.mock.calls[0]?.[1] as (frame: { body: string }) => void
    subscribeCallback({ body: 'not-valid-json{{{{' })

    expect(onMessage).not.toHaveBeenCalled()
    expect(warnSpy).toHaveBeenCalledOnce()

    warnSpy.mockRestore()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-7. activate / deactivate 위임
// ─────────────────────────────────────────────────────────────────────────────
describe('createNotificationStream — activate / deactivate', () => {
  it('반환 객체의 activate()가 client.activate()를 호출한다', () => {
    const stream = createNotificationStream({ getToken: () => 'tok', onMessage: vi.fn() })

    stream.activate()

    expect(mockInstance.activate).toHaveBeenCalledOnce()
  })

  it('반환 객체의 deactivate()가 client.deactivate()를 호출한다', () => {
    const stream = createNotificationStream({ getToken: () => 'tok', onMessage: vi.fn() })

    stream.deactivate()

    expect(mockInstance.deactivate).toHaveBeenCalledOnce()
  })
})
