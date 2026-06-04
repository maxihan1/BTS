# FR-AU-03 — SAML 2.0 SSO

> slug: fr-au-03-saml-sso
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-04

## Brief

FR-AU-03 SAML 2.0 SSO 구현 — identity-access BC.

선행 FR-AU-01(플러그형 AuthenticationProvider 구조, 완료) 위에:
- `spring-security-saml2-service-provider` 기반 SP-initiated + IdP-initiated 흐름
- `saml_idp_configs` 데이터 모델
- `/sso/saml2/...` 엔드포인트
- Keycloak SAML 모드 Testcontainers 통합 테스트
- IdP 선택 프론트 UI (D6) + E2E (D7)

plan 슬롯: `docs/plan/product/identity-access.md §2.3 (D1~D7)`.

**병행 주의** — 같은 identity-access BC인 `system-admin-role`(#75, 전역 권한 인프라)·`fr-pm-04`(#73)와
SAML Provider 등록(`ProviderRegistry`/`authn_providers`)·`SecurityContext`/SecurityFilterChain 충돌 가능성.
또 FR-IS-08 프론트(#74, issue-tracking BC)와 프론트 공유 인프라(라우터/MSW 핸들러 인덱스/api 공통)
충돌 선점 확인 필요. → spec 단계에서 grep 검증.

## 도메인 정리

- **BC**: identity-access
- **영향 엔티티**:
  - 신규 — `SamlIdpConfig` (IdP 메타데이터/EntityID/서명 인증서 영속. `saml_idp_configs` 테이블)
  - 재사용 — `User`, `user_external_accounts`(provider_id, external_subject=NameID), `Principal`, `Credential.SamlAssertion`(이미 SPI에 정의됨), `ProviderType.SAML(40)`(이미 enum에 존재)
- **새 구현체**: `provider/saml/SamlProvider`(도메인 SPI `AuthenticationProvider` 구현) + Spring Security SAML2 어댑터. ADR `2026-05-20-authentication-provider-spi-naming`의 어댑터 격리 패턴 그대로 적용
- **자동 프로비저닝**: **JIT 채택**(Maxi 결정 2026-06-04, LDAP과 동일). SAML 첫 SSO 로그인 시 BTS 계정 자동 생성. 단 `AutoProvisionService`/`ExternalAccountRepository`가 현재 `provider/ldap/` 패키지에 갇혀 있음 → SAML 공유를 위한 **공용 위치 이동 vs SAML 전용 분리는 spec/plan 단계에서 결정**(learnings `archunit-shared-class-move-repository-package` 주의 — jOOQ 접촉 repo는 .repository 패키지 유지)
- **SAML 흐름**: SP-initiated + IdP-initiated 둘 다(Brief). IdP-initiated의 RelayState/replay 보안은 spec에서 명세
- **새 용어**: IdP, SP, SAML Assertion, SAML metadata, RelayState, NameID → glossary.md 등록 완료(Maxi 승인 2026-06-04)
- **기존 결정 충돌**: 없음. SPI ADR이 SAML을 명시적으로 예견(`Credential.SamlAssertion` 선반영)
- **같은 BC 병행 작업 경계**:
  - #75 system-admin-role(전역 권한 인프라) — SecurityContext/SecurityFilterChain 건드릴 가능성 → SAML 엔드포인트(`/sso/saml2`) 필터 체인 추가 시 충돌 주의
  - #73 fr-pm-04(워크플로우/자동화 권한) — 권한 영역, SAML 인증 영역과 분리. 충돌 가능성 낮음
- **관련 ADR**: 선행 `docs/decisions/2026-05-20-authentication-provider-spi-naming.md`(어댑터 패턴), `2026-05-20-ldap-group-mapping-policy.md`, `2026-05-20-ldap-testcontainers-image.md`(Keycloak SAML Testcontainers 선례). SAML 전용 ADR(JIT 정책 + saml_idp_configs 모델 + 공유자산 위치)은 **spec 단계에서 생성 예정**

## 스펙

전체 스펙: [docs/specs/2026-06-04-fr-au-03-saml-sso.md](../specs/2026-06-04-fr-au-03-saml-sso.md)

핵심 요약.
- SP-initiated + IdP-initiated SAML 흐름. Spring Security SAML2 필터가 검증 주도, BTS 성공 핸들러가 Principal 변환 + JIT 프로비저닝 + JWT 발급
- 신규 `saml_idp_configs`(registration_id/entity_id/sso_url/x509_cert/enabled). users/user_external_accounts 재사용
- 공유 자산(AutoProvisionService/ExternalAccountRepository) ldap→공용 패키지 이동 권장(옵션 A), 별도 refactor 커밋 선행
- 외부 의존성 `spring-security-saml2-service-provider`(+OpenSAML) 신규 → 게이트1 Maxi 승인 대상
- 보안: 서명검증/replay/open-redirect(RelayState 화이트리스트)/XXE 차단/PII 미로깅
- 프론트: 활성 IdP 동적 버튼 렌더(`/login`). #74·#75와 공용파일(router.ts/handlers.ts) 충돌 위험 낮음(영역 분리)

## Brainstorming Check

✅ 통과 (1회 iteration). gap 2건 보강 — SPI↔Spring SAML2 필터 역할 경계(§6b), 첫 IdP 등록 경로(§6c). Maxi 결정 불요.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
