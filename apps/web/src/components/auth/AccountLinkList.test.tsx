// AccountLinkList·AccountLinkCard 컴포넌트 단위 테스트 — RTL 기반
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { AccountLinkList } from './AccountLinkList'
import type { AccountLinkResponse } from '@/api/account-links'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 fixture
// ─────────────────────────────────────────────────────────────────────────────

const LDAP_LINK: AccountLinkResponse = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  providerId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  providerName: 'BTS LDAP',
  providerType: 'LDAP',
  providerEnabled: true,
  externalSubjectMasked: 'ali***',
  linkedAt: '2026-05-01T00:00:00Z',
  lastLoginAt: '2026-06-01T10:00:00Z',
}

const SAML_LINK_NO_LOGIN: AccountLinkResponse = {
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  providerId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  providerName: 'Corp SAML',
  providerType: 'SAML',
  providerEnabled: true,
  externalSubjectMasked: 'ali***@corp.example.com',
  linkedAt: '2026-05-10T00:00:00Z',
  lastLoginAt: null,
}

const INACTIVE_LINK: AccountLinkResponse = {
  id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  providerId: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
  providerName: 'Old Provider',
  providerType: 'OIDC',
  providerEnabled: false,
  externalSubjectMasked: 'old***@example.com',
  linkedAt: '2026-01-01T00:00:00Z',
  lastLoginAt: null,
}

const NULL_PROVIDER_LINK: AccountLinkResponse = {
  id: '11111111-1111-4111-8111-111111111111',
  providerId: '22222222-2222-4222-8222-222222222222',
  providerName: null,
  providerType: null,
  providerEnabled: false,
  externalSubjectMasked: 'del***',
  linkedAt: '2026-01-15T00:00:00Z',
  lastLoginAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 빈 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinkList — 빈 목록', () => {
  it('빈 배열이면 emptyMessage를 렌더한다', () => {
    render(<AccountLinkList links={[]} onUnlink={vi.fn()} onAddLink={vi.fn()} />)
    expect(screen.getByText('연결된 외부 계정이 없습니다.')).toBeInTheDocument()
  })

  it('빈 배열이면 addCta 버튼을 렌더하고 클릭 시 onAddLink를 호출한다', () => {
    const onAddLink = vi.fn()
    render(<AccountLinkList links={[]} onUnlink={vi.fn()} onAddLink={onAddLink} />)
    fireEvent.click(screen.getByRole('button', { name: '외부 계정 연결하기' }))
    expect(onAddLink).toHaveBeenCalledTimes(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 목록 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinkList — 목록 렌더', () => {
  it('각 link에 대해 providerName을 렌더한다', () => {
    render(
      <AccountLinkList
        links={[LDAP_LINK, SAML_LINK_NO_LOGIN]}
        onUnlink={vi.fn()}
        onAddLink={vi.fn()}
      />,
    )
    expect(screen.getByText('BTS LDAP')).toBeInTheDocument()
    expect(screen.getByText('Corp SAML')).toBeInTheDocument()
  })

  it('타입 배지를 렌더한다 (LDAP/SAML)', () => {
    render(
      <AccountLinkList
        links={[LDAP_LINK, SAML_LINK_NO_LOGIN]}
        onUnlink={vi.fn()}
        onAddLink={vi.fn()}
      />,
    )
    expect(screen.getByText('LDAP')).toBeInTheDocument()
    expect(screen.getByText('SAML')).toBeInTheDocument()
  })

  it('마스킹된 외부 식별자(externalSubjectMasked)를 렌더한다', () => {
    render(<AccountLinkList links={[LDAP_LINK]} onUnlink={vi.fn()} onAddLink={vi.fn()} />)
    expect(screen.getByText('ali***')).toBeInTheDocument()
  })

  it('lastLoginAt이 있으면 날짜를 렌더한다', () => {
    render(<AccountLinkList links={[LDAP_LINK]} onUnlink={vi.fn()} onAddLink={vi.fn()} />)
    // 마지막 로그인 라벨 다음 dd 요소에 연도 포함 여부 확인
    const cardEl = screen.getByTestId(`account-link-card-${LDAP_LINK.id}`)
    // LDAP_LINK.lastLoginAt = '2026-06-01T10:00:00Z' → KST 2026년 6월 포함
    const lastLoginDt = within(cardEl).getByText('마지막 로그인')
    const lastLoginDd = lastLoginDt.nextElementSibling
    expect(lastLoginDd?.textContent).toMatch(/2026/)
  })

  it('lastLoginAt이 null이면 "로그인 기록 없음"을 렌더한다', () => {
    render(
      <AccountLinkList links={[SAML_LINK_NO_LOGIN]} onUnlink={vi.fn()} onAddLink={vi.fn()} />,
    )
    expect(screen.getByText('로그인 기록 없음')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// providerName null fallback
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinkCard — providerName null fallback', () => {
  it('providerName이 null이면 unknownProvider 텍스트를 렌더한다', () => {
    render(
      <AccountLinkList links={[NULL_PROVIDER_LINK]} onUnlink={vi.fn()} onAddLink={vi.fn()} />,
    )
    expect(screen.getByText('알 수 없는 공급자')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 비활성 배지
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinkCard — providerEnabled=false 비활성 배지', () => {
  it('providerEnabled=false이면 "비활성" 배지를 렌더한다', () => {
    render(<AccountLinkList links={[INACTIVE_LINK]} onUnlink={vi.fn()} onAddLink={vi.fn()} />)
    expect(screen.getByText('비활성')).toBeInTheDocument()
  })

  it('providerEnabled=true이면 "비활성" 배지가 없다', () => {
    render(<AccountLinkList links={[LDAP_LINK]} onUnlink={vi.fn()} onAddLink={vi.fn()} />)
    expect(screen.queryByText('비활성')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 해제 흐름
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinkCard — 해제 다이얼로그', () => {
  it('"해제" 버튼 클릭 시 AlertDialog가 열린다', () => {
    render(<AccountLinkList links={[LDAP_LINK]} onUnlink={vi.fn()} onAddLink={vi.fn()} />)
    const card = screen.getByTestId(`account-link-card-${LDAP_LINK.id}`)
    fireEvent.click(within(card).getByRole('button', { name: '해제' }))
    expect(screen.getByText('계정 연결 해제')).toBeInTheDocument()
  })

  it('AlertDialog 확인 버튼 클릭 시 onUnlink(id)를 호출한다', () => {
    const onUnlink = vi.fn()
    render(<AccountLinkList links={[LDAP_LINK]} onUnlink={onUnlink} onAddLink={vi.fn()} />)

    const card = screen.getByTestId(`account-link-card-${LDAP_LINK.id}`)
    fireEvent.click(within(card).getByRole('button', { name: '해제' }))

    // 다이얼로그 내 확인(해제) 버튼 — 다이얼로그 내부에서 찾기
    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '해제' }))

    expect(onUnlink).toHaveBeenCalledWith(LDAP_LINK.id)
  })

  it('AlertDialog 취소 버튼 클릭 시 onUnlink가 호출되지 않는다', () => {
    const onUnlink = vi.fn()
    render(<AccountLinkList links={[LDAP_LINK]} onUnlink={onUnlink} onAddLink={vi.fn()} />)

    const card = screen.getByTestId(`account-link-card-${LDAP_LINK.id}`)
    fireEvent.click(within(card).getByRole('button', { name: '해제' }))

    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))

    expect(onUnlink).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// isUnlinking disabled
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinkCard — isUnlinking', () => {
  it('isUnlinking=true이면 해제 버튼이 disabled된다', () => {
    render(
      <AccountLinkList
        links={[LDAP_LINK]}
        onUnlink={vi.fn()}
        onAddLink={vi.fn()}
        unlinkingId={LDAP_LINK.id}
      />,
    )
    const card = screen.getByTestId(`account-link-card-${LDAP_LINK.id}`)
    expect(within(card).getByRole('button', { name: '해제' })).toBeDisabled()
  })

  it('다른 link의 해제 중에는 이 카드 버튼이 disabled되지 않는다', () => {
    render(
      <AccountLinkList
        links={[LDAP_LINK, SAML_LINK_NO_LOGIN]}
        onUnlink={vi.fn()}
        onAddLink={vi.fn()}
        unlinkingId={SAML_LINK_NO_LOGIN.id}
      />,
    )
    const ldapCard = screen.getByTestId(`account-link-card-${LDAP_LINK.id}`)
    expect(within(ldapCard).getByRole('button', { name: '해제' })).not.toBeDisabled()
  })
})
