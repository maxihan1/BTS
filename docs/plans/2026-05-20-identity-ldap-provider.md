<!-- BTS plan — identity-access FR-AU-02 LDAP/AD Provider 정식 구현 -->

# FR-AU-02 — LDAP/AD Provider 정식 구현

> slug: identity-ldap-provider
> type: auth
> agent: security-engineer (primary) + db-engineer + designer + frontend-engineer + qa-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 마스터플랜: `docs/plan/product/identity-access.md §2.2`
> 선행 PR: #3 (FR-AU-01 SPI 인프라)

## Brief

PR #3 가 도입한 `AuthenticationProvider` SPI 의 **첫 실제 구현**. 사내 LDAP/AD 서버를 통한 인증 + 사용자/그룹 매핑 + 계정 잠금 정책. 마스터플랜 §2.2 D1~D7.

본 PR 에 동반되는 첫 DB 마이그레이션 V002 — `authn_providers` (LDAP/SAML/OIDC config) + `user_external_accounts` (provider, externalId 매핑). FR-AU-01 가 보류했던 D3 도 함께 도입.

또한 PR #3 머지 후 잔여 작업: `scripts/verify/bootjar-no-fakes.sh` 실행 권한 부여 (Maxi 가 머지 후 `! chmod +x` 직접 실행, stash 보관 후 worktree 에서 pop) — chore 커밋 1건으로 묶음.

## 도메인 정리

- **BC**. identity-access (단독)
- **영향 엔티티 (신규)**.
  - DB. `authn_providers` (Provider 메타 + JSONB config), `user_external_accounts` (User ↔ external subject 매핑)
  - Kotlin VO. `LdapConfig` (server_url, base_dn, bind_dn, bind_password_env, user_search_filter/base, group_search_base/filter, group_mapping), `LockoutPolicy` (max_attempts, lockout_minutes, scope)
  - Kotlin 엔티티. `ExternalAccount(provider_id, external_subject, user_id, last_login_at)` — `user_external_accounts` 매핑
- **기존 엔티티 (영향)**.
  - `User` — PoC #2 의 `users` 테이블 그대로. 본 PR 은 새 행 INSERT 만 (LDAP 첫 로그인 시 자동 프로비저닝)
  - `UserCredential` — 영향 없음 (LDAP 인증은 외부 검증, 비밀번호 BTS DB 미저장)
  - `AuthenticationProvider` (SPI, PR #3) — `LdapProvider` 가 첫 실제 구현
- **새 용어** (glossary 추가 후보, Maxi 승인 필요).
  - `ExternalAccount` — User 의 외부 IdP 측 식별자 매핑 (provider × external_subject → user_id). DB 영속
  - `LdapConfig` — LDAP 연결 + 검색 + 그룹 매핑 설정 VO. JSONB 로 `authn_providers.config` 에 직렬화
  - `LockoutPolicy` — N회 실패 시 M분 잠금 정책 VO. BTS 측 자체 lockout (LDAP 서버 측 의존 안 함)
  - `BaseDN` — LDAP 검색 시작점 (예. `dc=bts,dc=local`). 표준 용어
  - `BindDN` — LDAP 인증 수행 계정의 DN (서비스 계정). 표준 용어
  - `External Subject` — 외부 IdP 가 발급한 사용자 고유 식별자 (LDAP DN, OIDC sub, SAML NameID). `user_external_accounts.external_subject` 컬럼명
- **기존 결정과의 정합**.
  - PR #3 ADR `2026-05-20-authentication-provider-spi-naming.md` 의 `Credential.LdapBind` sealed 변종 약속 → 본 PR 이 실제 구현 (placeholder 였던 sealed 변종을 실제 작동 코드로 변환). 충돌 아닌 약속 이행.
  - PR #3 spec §5 가 의도적으로 보류한 D3 (`authn_providers` 테이블) → 본 PR 에 통합. spec NFR 명시.
- **기존 ADR 영향**.
  - `argon2id-parameters` (PoC #2) — Local 비밀번호 해싱, LDAP 외부 검증과 무관 ✅
  - `csrf-cookie-mode` (PoC #2) — 모든 인증 흐름에 적용, LDAP 도 동일 ✅
  - `keycloak-image-selection` (PoC #2) — OIDC IdP, LDAP 와 별개 (다른 컨테이너) ✅
  - `testcontainers-docker-desktop-config` (PoC #2) — OpenLDAP Testcontainers 도 같은 docker socket 설정 적용 ✅
  - `authentication-provider-spi-naming` (PR #3) — 본 PR `LdapProvider` 가 `com.atlas.bts.identity.spi.AuthenticationProvider` 구현, 패키지 경계 + ArchUnit 룰 모두 준수 ✅
- **본 PR 신규 ADR 후보 (impl 단계에서 결정 시점에 작성)**.
  - `2026-05-20-ldap-testcontainers-image.md` — OpenLDAP 이미지 선정 (osixia / bitnami / 공식 후보 비교)
  - `2026-05-20-user-external-accounts-schema.md` — V002 스키마 (provider_id FK ON DELETE 정책, external_subject 인덱스, last_login_at 갱신 시점)
  - `2026-05-20-lockout-policy-location.md` — BTS 측 자체 lockout 결정 (LDAP 서버 측 의존 안 함, 이유 + 데이터 모델)
  - `2026-05-20-ldap-group-mapping-policy.md` — LDAP 그룹 DN → BTS role 매핑 (1:1 명시 매핑 vs 패턴 매칭)
- **grill-with-docs 우회 사유**. PR #3 ADR (`authentication-provider-spi-naming`) 가 본 PR 의 도메인 결정 (sealed Credential 확장 + Provider 패키지 규칙) 을 이미 설정함. LDAP 용어 (BaseDN/BindDN/ExternalSubject) 는 표준이라 모호성 적음. 1인 부담 + Auto mode 합리적 판단으로 직접 분석 (PoC 패턴 일관성).

## 스펙

전체 스펙. [docs/specs/2026-05-20-identity-ldap-provider.md](../specs/2026-05-20-identity-ldap-provider.md)

핵심 5줄.
- **`Credential.LdapBind` sealed 변종 추가** — PR #3 ADR 약속 이행
- **`LdapProvider` 구현** — Spring `LdapAuthenticationProvider` 위 BTS SPI 어댑터 + LockoutPolicy + 자동 프로비저닝 (단일 트랜잭션)
- **Flyway V002 마이그레이션** — `authn_providers` + `user_external_accounts` (FR-AU-01 보류 D3 통합)
- **Testcontainers OpenLDAP** — 7 시나리오 (S-01~S-07) 모두 통합 테스트
- **로그인 폼 UI + Playwright E2E 별도 PR** (§10 분할 정당화) — design-consultation 트리거 + 디자인 시스템 신중도

**PR 분할 결정**. D1~D5 본 PR / D6~D7 PR #5, #6 위임. 게이트 1 에서 Maxi 확정 필요.

명시적 비-스코프 (spec §9). FR-AU-06 다중 활성, FR-AU-07 도메인 라우팅, FR-AU-08 계정 통합, FR-PM-* 권한 매핑, UserCredential 리네임.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 10건 식별 — 4건 spec 본문 보강, 6건 impl 단계 결정).

### Phase B 직접 수행 — 발견된 gap

1. Spring Data JDBC vs JPA vs plain JDBC — impl 결정 (plain JDBC 권장)
2. **bind password env var 로딩 시점** — startup 1회 + restart 필요 (매 인증 syscall 회피). spec FR-2 보강 ✅
3. seed.ldif 위치 = `infra/ldap/seed.ldif` (PoC #2 패턴 일관성)
4. **authn_providers 0행 처리** — lazy init + Failure(PROVIDER_UNAVAILABLE). 부팅 실패 X (다른 Provider 만으로 동작 가능). spec FR-2 보강 ✅
5. **groups JSONB 인덱스** — 본 PR 검색 query 없음, 인덱스 X. FR-PM-01 도입 시 GIN 검토. spec NFR 보강 ✅
6. @Transactional 의존성 — Spring Boot starter 자동 활성. impl 결정
7. AuthenticationManager vs LdapAuthenticationProvider — impl 결정 (Spring 표준 LdapAuthenticationProvider 위 BTS SPI 어댑터)
8. ArchUnit 룰 — `provider.ldap` 패키지는 spi 아님, Spring Security 자유 import. plan-eng-review 검토 항목
9. @Component 자동 등록 + @Profile("test-spi") 가짜 Provider — production / test 프로필 분리 OK
10. **PoC #2 + PR #3 회귀 격리** — LdapProvider 가 통합 테스트 컨텍스트에 자동 로드됨, lazy init 분기로 안전. ProviderRegistryTest assertion 갱신 가능성 — impl 단계. spec NFR 보강 ✅

### iteration 결정

gap 4건 spec 본문 보강 + 6건 impl 단계 결정 가능. iteration 불필요. office-hours/brainstorming 대화형 우회 사유. PoC 패턴 일관성. `/bts-plan` 진입.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
