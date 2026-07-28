// AuditLogTable 컴포넌트 단위 테스트 — 행 렌더·주체 폴백·IP null·메타데이터·빈상태
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { AuditLogEntry } from '@/api/audit-logs'
import { AuditLogTable } from './AuditLogTable'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 fixture
// ─────────────────────────────────────────────────────────────────────────────

const aliceEntry: AuditLogEntry = {
  id: 1,
  userId: '00000000-0000-4000-8000-000000000001',
  username: 'alice',
  displayName: '김앨리스',
  eventType: 'LOGIN_SUCCESS',
  providerId: 'local',
  ipAddress: '192.168.1.1',
  userAgent: 'Mozilla/5.0',
  metadata: { sid: 'sid-1' },
  createdAt: '2026-06-10T12:00:00Z',
}

const bobEntry: AuditLogEntry = {
  id: 2,
  userId: '00000000-0000-4000-8000-000000000002',
  username: 'bob',
  displayName: null,
  eventType: 'LOGIN_FAILURE',
  providerId: 'ldap',
  ipAddress: null,
  userAgent: null,
  metadata: {},
  createdAt: '2026-06-10T11:00:00Z',
}

const nullUserEntry: AuditLogEntry = {
  id: 3,
  userId: null,
  username: null,
  displayName: null,
  eventType: 'LOGIN_FAILURE',
  providerId: 'local',
  ipAddress: '203.0.113.5',
  userAgent: 'curl/7.68',
  metadata: { reason: 'bad_credentials' },
  createdAt: '2026-06-10T10:00:00Z',
}

const unknownEventEntry: AuditLogEntry = {
  id: 4,
  userId: '00000000-0000-4000-8000-000000000001',
  username: 'alice',
  displayName: '김앨리스',
  eventType: 'FUTURE_UNKNOWN_EVENT',
  providerId: 'local',
  ipAddress: '192.168.1.1',
  userAgent: null,
  metadata: {},
  createdAt: '2026-06-10T09:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('AuditLogTable', () => {
  it('빈 배열이면 빈 상태 메시지를 표시한다', () => {
    render(<AuditLogTable entries={[]} isLoading={false} />)
    expect(screen.getByText('조건에 맞는 로그가 없습니다.')).toBeInTheDocument()
  })

  it('isLoading=true이면 스켈레톤/로딩 표시가 렌더된다', () => {
    render(<AuditLogTable entries={[]} isLoading={true} />)
    // 로딩 상태 — aria-label이나 role로 식별
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('displayName이 있으면 displayName을 주체 셀에 표시한다', () => {
    render(<AuditLogTable entries={[aliceEntry]} isLoading={false} />)
    expect(screen.getByText('김앨리스')).toBeInTheDocument()
  })

  it('displayName=null이면 username을 주체 셀에 표시한다', () => {
    render(<AuditLogTable entries={[bobEntry]} isLoading={false} />)
    expect(screen.getByText('bob')).toBeInTheDocument()
  })

  it('userId·username·displayName 모두 null이면 "(알 수 없음)" 폴백을 표시한다', () => {
    render(<AuditLogTable entries={[nullUserEntry]} isLoading={false} />)
    expect(screen.getByText('(알 수 없음)')).toBeInTheDocument()
  })

  it('ipAddress=null이면 "—"를 표시한다', () => {
    render(<AuditLogTable entries={[bobEntry]} isLoading={false} />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('ipAddress가 있으면 IP를 표시한다', () => {
    render(<AuditLogTable entries={[aliceEntry]} isLoading={false} />)
    expect(screen.getByText('192.168.1.1')).toBeInTheDocument()
  })

  it('eventType이 알려진 값이면 한국어 라벨로 표시한다', () => {
    render(<AuditLogTable entries={[aliceEntry]} isLoading={false} />)
    // LOGIN_SUCCESS → '로그인 성공'
    expect(screen.getByText('로그인 성공')).toBeInTheDocument()
  })

  it('eventType이 미지 값이면 원문을 표시한다 (전방호환)', () => {
    render(<AuditLogTable entries={[unknownEventEntry]} isLoading={false} />)
    expect(screen.getByText('FUTURE_UNKNOWN_EVENT')).toBeInTheDocument()
  })

  it('providerId를 표시한다', () => {
    render(<AuditLogTable entries={[aliceEntry]} isLoading={false} />)
    expect(screen.getByText('local')).toBeInTheDocument()
  })

  it('metadata가 있으면 요약 텍스트를 표시한다', () => {
    render(<AuditLogTable entries={[aliceEntry]} isLoading={false} />)
    // metadata: { sid: 'sid-1' } → 요약 표시
    expect(screen.getByText(/sid/)).toBeInTheDocument()
  })

  it('여러 항목이 모두 렌더된다', () => {
    render(
      <AuditLogTable entries={[aliceEntry, bobEntry, nullUserEntry]} isLoading={false} />,
    )
    expect(screen.getByText('김앨리스')).toBeInTheDocument()
    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.getByText('(알 수 없음)')).toBeInTheDocument()
  })
})
