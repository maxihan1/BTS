// 계정 연결 UI 라벨 단위 테스트 — accountLinkLabels 키 존재 + accountLinkErrorMessage 매핑 검증

import { describe, it, expect } from 'vitest'
import { accountLinkLabels, accountLinkErrorMessage } from './account-link-labels'

describe('accountLinkLabels', () => {
  describe('page 그룹', () => {
    it('heading 키가 존재한다', () => {
      expect(accountLinkLabels.page.heading).toBeTruthy()
    })

    it('description 키가 존재한다', () => {
      expect(accountLinkLabels.page.description).toBeTruthy()
    })

    it('emptyMessage 키가 존재한다', () => {
      expect(accountLinkLabels.page.emptyMessage).toBeTruthy()
    })

    it('addCta 키가 존재한다', () => {
      expect(accountLinkLabels.page.addCta).toBeTruthy()
    })

    it('loadingStatus 키가 존재한다', () => {
      expect(accountLinkLabels.page.loadingStatus).toBeTruthy()
    })

    it('모든 page 문자열은 콜론으로 끝나지 않는다', () => {
      for (const value of Object.values(accountLinkLabels.page)) {
        expect(value).not.toMatch(/:$/)
      }
    })
  })

  describe('card 그룹', () => {
    it('linkedAtLabel 키가 존재한다', () => {
      expect(accountLinkLabels.card.linkedAtLabel).toBeTruthy()
    })

    it('lastLoginLabel 키가 존재한다', () => {
      expect(accountLinkLabels.card.lastLoginLabel).toBeTruthy()
    })

    it('noLoginHistory 키가 존재한다', () => {
      expect(accountLinkLabels.card.noLoginHistory).toBeTruthy()
    })

    it('inactiveBadge 키가 존재한다', () => {
      expect(accountLinkLabels.card.inactiveBadge).toBeTruthy()
    })

    it('unknownProvider 키가 존재한다', () => {
      expect(accountLinkLabels.card.unknownProvider).toBeTruthy()
    })

    it('typeBadge.LDAP 키가 존재한다', () => {
      expect(accountLinkLabels.card.typeBadge.LDAP).toBeTruthy()
    })

    it('typeBadge.SAML 키가 존재한다', () => {
      expect(accountLinkLabels.card.typeBadge.SAML).toBeTruthy()
    })

    it('typeBadge.OIDC 키가 존재한다', () => {
      expect(accountLinkLabels.card.typeBadge.OIDC).toBeTruthy()
    })

    it('모든 card 최상위 문자열은 콜론으로 끝나지 않는다', () => {
      const stringEntries = (
        Object.entries(accountLinkLabels.card) as [string, unknown][]
      ).filter((entry): entry is [string, string] => typeof entry[1] === 'string')
      for (const [, value] of stringEntries) {
        expect(value).not.toMatch(/:$/)
      }
    })
  })

  describe('add 그룹', () => {
    it('addButton 키가 존재한다', () => {
      expect(accountLinkLabels.add.addButton).toBeTruthy()
    })

    it('providerSelectGuide 키가 존재한다', () => {
      expect(accountLinkLabels.add.providerSelectGuide).toBeTruthy()
    })

    it('noLinkableProviders 키가 존재한다', () => {
      expect(accountLinkLabels.add.noLinkableProviders).toBeTruthy()
    })

    it('ldapForm.usernameLabel 키가 존재한다', () => {
      expect(accountLinkLabels.add.ldapForm.usernameLabel).toBeTruthy()
    })

    it('ldapForm.passwordLabel 키가 존재한다', () => {
      expect(accountLinkLabels.add.ldapForm.passwordLabel).toBeTruthy()
    })

    it('ldapForm.submitButton 키가 존재한다', () => {
      expect(accountLinkLabels.add.ldapForm.submitButton).toBeTruthy()
    })

    it('ssoGuide 키가 존재한다', () => {
      expect(accountLinkLabels.add.ssoGuide).toBeTruthy()
    })

    it('모든 add 최상위 문자열은 콜론으로 끝나지 않는다', () => {
      const stringEntries = (
        Object.entries(accountLinkLabels.add) as [string, unknown][]
      ).filter((entry): entry is [string, string] => typeof entry[1] === 'string')
      for (const [, value] of stringEntries) {
        expect(value).not.toMatch(/:$/)
      }
    })
  })

  describe('unlink 그룹', () => {
    it('unlinkButton 키가 존재한다', () => {
      expect(accountLinkLabels.unlink.unlinkButton).toBeTruthy()
    })

    it('dialogTitle 키가 존재한다', () => {
      expect(accountLinkLabels.unlink.dialogTitle).toBeTruthy()
    })

    it('dialogBody 키가 존재한다', () => {
      expect(accountLinkLabels.unlink.dialogBody).toBeTruthy()
    })

    it('confirmButton 키가 존재한다', () => {
      expect(accountLinkLabels.unlink.confirmButton).toBeTruthy()
    })

    it('cancelButton 키가 존재한다', () => {
      expect(accountLinkLabels.unlink.cancelButton).toBeTruthy()
    })

    it('모든 unlink 문자열은 콜론으로 끝나지 않는다', () => {
      for (const value of Object.values(accountLinkLabels.unlink)) {
        expect(value).not.toMatch(/:$/)
      }
    })
  })

  describe('reauth 그룹', () => {
    it('modalTitle 키가 존재한다', () => {
      expect(accountLinkLabels.reauth.modalTitle).toBeTruthy()
    })

    it('modalGuide 키가 존재한다', () => {
      expect(accountLinkLabels.reauth.modalGuide).toBeTruthy()
    })

    it('localPasswordLabel 키가 존재한다', () => {
      expect(accountLinkLabels.reauth.localPasswordLabel).toBeTruthy()
    })

    it('ldapUsernameLabel 키가 존재한다', () => {
      expect(accountLinkLabels.reauth.ldapUsernameLabel).toBeTruthy()
    })

    it('ldapPasswordLabel 키가 존재한다', () => {
      expect(accountLinkLabels.reauth.ldapPasswordLabel).toBeTruthy()
    })

    it('ssoButton 키가 존재한다', () => {
      expect(accountLinkLabels.reauth.ssoButton).toBeTruthy()
    })

    it('submitButton 키가 존재한다', () => {
      expect(accountLinkLabels.reauth.submitButton).toBeTruthy()
    })

    it('모든 reauth 문자열은 콜론으로 끝나지 않는다', () => {
      for (const value of Object.values(accountLinkLabels.reauth)) {
        expect(value).not.toMatch(/:$/)
      }
    })
  })

  describe('toast 그룹', () => {
    it('linkSuccess 키가 존재한다', () => {
      expect(accountLinkLabels.toast.linkSuccess).toBeTruthy()
    })

    it('linkAlreadyLinked 키가 존재한다', () => {
      expect(accountLinkLabels.toast.linkAlreadyLinked).toBeTruthy()
    })

    it('unlinkSuccess 키가 존재한다', () => {
      expect(accountLinkLabels.toast.unlinkSuccess).toBeTruthy()
    })

    it('reauthSuccess 키가 존재한다', () => {
      expect(accountLinkLabels.toast.reauthSuccess).toBeTruthy()
    })

    it('모든 toast 문자열은 콜론으로 끝나지 않는다', () => {
      for (const value of Object.values(accountLinkLabels.toast)) {
        expect(value).not.toMatch(/:$/)
      }
    })
  })

  describe('callback 그룹', () => {
    it('link.success 키가 존재한다', () => {
      expect(accountLinkLabels.callback.link.success).toBeTruthy()
    })

    it('link.alreadyLinked 키가 존재한다', () => {
      expect(accountLinkLabels.callback.link.alreadyLinked).toBeTruthy()
    })

    it('link.conflict 키가 존재한다', () => {
      expect(accountLinkLabels.callback.link.conflict).toBeTruthy()
    })

    it('link.error 키가 존재한다', () => {
      expect(accountLinkLabels.callback.link.error).toBeTruthy()
    })

    it('reauth.success 키가 존재한다', () => {
      expect(accountLinkLabels.callback.reauth.success).toBeTruthy()
    })

    it('reauth.failed 키가 존재한다', () => {
      expect(accountLinkLabels.callback.reauth.failed).toBeTruthy()
    })

    it('모든 callback.link 문자열은 콜론으로 끝나지 않는다', () => {
      for (const value of Object.values(accountLinkLabels.callback.link)) {
        expect(value).not.toMatch(/:$/)
      }
    })

    it('모든 callback.reauth 문자열은 콜론으로 끝나지 않는다', () => {
      for (const value of Object.values(accountLinkLabels.callback.reauth)) {
        expect(value).not.toMatch(/:$/)
      }
    })
  })
})

describe('accountLinkErrorMessage', () => {
  it('account_already_linked → 이미 연결된 신원 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('account_already_linked')).toBe(
      '이미 다른 계정에 연결된 신원입니다.',
    )
  })

  it('last_login_method → 마지막 로그인 수단 해제 불가 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('last_login_method')).toBe(
      '마지막 로그인 수단은 해제할 수 없습니다.',
    )
  })

  it('provider_unavailable → 인증 서버 일시 불가 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('provider_unavailable')).toBe(
      '인증 서버를 일시적으로 사용할 수 없습니다.',
    )
  })

  it('link_authentication_failed → 연결 인증 실패 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('link_authentication_failed')).toBeTruthy()
  })

  it('reauth_failed → 재인증 실패 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('reauth_failed')).toBe(
      '재인증에 실패했습니다.',
    )
  })

  it('step_up_required → 재인증 필요 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('step_up_required')).toBeTruthy()
  })

  it('reauth_fields_required → 입력 필드 필요 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('reauth_fields_required')).toBeTruthy()
  })

  it('알 수 없는 코드 → 기본 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage('UNKNOWN_CODE')).toBeTruthy()
  })

  it('null → 기본 메시지를 반환한다', () => {
    expect(accountLinkErrorMessage(null)).toBeTruthy()
  })

  it('반환 메시지는 콜론으로 끝나지 않는다', () => {
    const codes = [
      'account_already_linked',
      'last_login_method',
      'provider_unavailable',
      'link_authentication_failed',
      'reauth_failed',
      'step_up_required',
      'reauth_fields_required',
      'UNKNOWN',
      null,
    ] as const
    for (const code of codes) {
      expect(accountLinkErrorMessage(code)).not.toMatch(/:$/)
    }
  })
})
