// WebAuthn E2E용 navigator.credentials stub — addInitScript로 브라우저에 주입 (FR-MF-03)
//
// @simplewebauthn/browser 의 startRegistration / startAuthentication 은
// navigator.credentials.create / get 을 직접 호출한다.
// E2E 환경에서는 실제 하드웨어 인증기가 없으므로 가짜 PublicKeyCredential 을 반환하도록
// stub 함수를 주입한다.
//
// stub 규칙.
//   - localStorage['__bts_e2e_webauthn_cancel'] === 'true' 이면 NotAllowedError throw.
//   - 그 외에는 @simplewebauthn 이 직렬화 가능한 최소 PublicKeyCredential 형태 반환.
//   - addInitScript 는 JSON 직렬화 후 브라우저에서 실행되므로, 외부 변수 참조 금지.
//     함수 본문 내에서 모든 ArrayBuffer 를 직접 생성해야 한다.
//
// 취소 시뮬레이션 사용 예.
//   await page.addInitScript(() => { localStorage.setItem('__bts_e2e_webauthn_cancel', 'true') })
//   injectWebauthnStub(page)

import type { Page } from '@playwright/test'

/** addInitScript 로 navigator.credentials.create / get 을 stub 으로 교체한다. */
export async function injectWebauthnStub(page: Page): Promise<void> {
  await page.addInitScript(() => {
    // ──────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼 — 비어있는 ArrayBuffer(1 바이트) 생성
    // ──────────────────────────────────────────────────────────────────────────
    function makeBuffer(): ArrayBuffer {
      return new Uint8Array([0]).buffer
    }

    // ──────────────────────────────────────────────────────────────────────────
    // stub — navigator.credentials.create (등록)
    // @simplewebauthn/browser 의 startRegistration 이 읽는 필드를 충족한다.
    // ──────────────────────────────────────────────────────────────────────────
    const originalCreate = navigator.credentials.create.bind(navigator.credentials)

    navigator.credentials.create = function (
      options?: CredentialCreationOptions,
    ): Promise<Credential | null> {
      void options // 스펙 준수용 — stub 에서는 옵션 무시

      if (localStorage.getItem('__bts_e2e_webauthn_cancel') === 'true') {
        return Promise.reject(new DOMException('The operation either timed out or was not allowed.', 'NotAllowedError'))
      }

      // @simplewebauthn/browser 가 기대하는 PublicKeyCredential 모양.
      // getTransports / getAuthenticatorData / getPublicKey / getPublicKeyAlgorithm 은
      // try-catch 내에서 호출될 수 있으므로 함수로 제공한다.
      const fakeCred = {
        id: 'AAAAAAAAAAAAAAAAAAAAAAAAAA',
        rawId: makeBuffer(),
        type: 'public-key',
        authenticatorAttachment: 'platform',
        response: {
          clientDataJSON: makeBuffer(),
          attestationObject: makeBuffer(),
          getTransports: () => ['internal'],
          getAuthenticatorData: () => makeBuffer(),
          getPublicKey: () => makeBuffer(),
          getPublicKeyAlgorithm: () => -7,
        },
        getClientExtensionResults: () => ({}),
      }

      return Promise.resolve(fakeCred as unknown as PublicKeyCredential)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // stub — navigator.credentials.get (인증)
    // @simplewebauthn/browser 의 startAuthentication 이 읽는 필드를 충족한다.
    // ──────────────────────────────────────────────────────────────────────────
    const originalGet = navigator.credentials.get.bind(navigator.credentials)

    navigator.credentials.get = function (
      options?: CredentialRequestOptions,
    ): Promise<Credential | null> {
      void options

      if (localStorage.getItem('__bts_e2e_webauthn_cancel') === 'true') {
        return Promise.reject(new DOMException('The operation either timed out or was not allowed.', 'NotAllowedError'))
      }

      const fakeCred = {
        id: 'AAAAAAAAAAAAAAAAAAAAAAAAAA',
        rawId: makeBuffer(),
        type: 'public-key',
        authenticatorAttachment: 'platform',
        response: {
          clientDataJSON: makeBuffer(),
          authenticatorData: makeBuffer(),
          signature: makeBuffer(),
          userHandle: null,
        },
        getClientExtensionResults: () => ({}),
      }

      return Promise.resolve(fakeCred as unknown as PublicKeyCredential)
    }

    // 원본을 사용하지 않을 경우 린터 경고 방지 (실제로는 덮어쓴다)
    void originalCreate
    void originalGet
  })
}
