<!-- BTS plan — StoredPasswordCredential 엔티티 도입 (PoC #2 미완성 마무리) -->

# StoredPasswordCredential 엔티티 도입 — PoC #2 비밀번호 저장 부분 마무리

> slug: identity-stored-password-credential
> type: auth (security-engineer 책임 + plan-eng + plan-ceo 리뷰)
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 선행 PR. #2 (PoC AuthN) / #3 (FR-AU-01 SPI) / #4 (FR-AU-02 LDAP), 모두 머지됨
> 트리거. PR #4 머지 후 정리 작업 점검 중 phantom 발견 — 다수 ADR/spec/plan 이 `UserCredential` 엔티티를 "PoC #2 도입" 으로 기술했으나 실제 코드에는 `LocalCredentialService` (해시 계산 로직만) 뿐. DB 저장부 미도입

## Brief

PoC #2 가 빠뜨린 비밀번호 영속 저장 계층을 정식 도입.

1. **V003 마이그레이션** — `user_credentials` 테이블 (user_id PK, password_hash, algo_version, updated_at, 인덱스/제약)
2. **`StoredPasswordCredential` JPA 엔티티 + Repository** — 다수 ADR 가 "UserCredential" 로 가설한 이름을 SPI 의 `Credential` 과 헷갈리지 않도록 `StoredPasswordCredential` 로 처음부터 명명
3. **`LocalCredentialService` 통합** — 현재 Argon2 해시 계산만 함. 본 PR 이 해시 저장 + 검증 시 조회 메서드 연결
4. **DEVELOPMENT.md / DATA.md 갱신** — 비밀번호 저장 정책 (해시 알고리즘 / pepper / 키 회전 / 평문 미저장 / 마이그레이션 정책) 명시
5. **회귀 검증** — PoC #2 OIDC 흐름 (Keycloak) 과 충돌 없는지. LDAP 흐름 (해시 저장 안 함) 과 분리 명확

본 PR 은 새로 도입된 `/bts-impl` wave 병렬 dispatch 의 dogfood 대상이지만, auth 타입이라 fast-track 미적용 — `/bts-domain` + `/bts-spec` + `/bts-review-plan` (plan-eng + plan-ceo) 거쳐서 진입.

## 도메인 정리 (← /bts-domain 채움)

(아직 비어 있음 — `/bts-domain` 진입 시 grill-with-docs 가 채울 영역)

## 스펙 (← /bts-spec Phase A 채움)

(아직 비어 있음 — `/bts-spec` 진입 시 office-hours 가 채울 영역)

## Brainstorming Check (← /bts-spec Phase B 채움)

(아직 비어 있음)

## Plan (← /bts-plan 채움)

(아직 비어 있음 — `/bts-plan` 진입 시 writing-plans 가 채울 영역. task 메타 블록 형식 [agent / files / depends-on] 적용 예정)

## 리뷰 결과 (← /bts-review-plan 채움)

(아직 비어 있음)

## phantom 발견 컨텍스트 (Learnings 후보)

**무엇이 잘못됐나**. 다수 문서 (`docs/decisions/2026-05-20-authentication-provider-spi-naming.md`, `docs/specs/2026-05-20-identity-authn-provider.md`, `docs/plans/2026-05-20-identity-*.md`) 가 `UserCredential` 을 "PoC #2 도입 엔티티" 로 기재. 실제 코드는 service 만 있고 entity 없음.

**근본 원인**. PoC #2 plan 이 "활용 엔티티 (기존, 신설 없음)" 라 표기 — implementer 가 신규 도입 작업 아니라고 해석. 그러나 PoC 이전 상태 = repo 비어 있음 (Phase 0 PoC 진입 직전). 따라서 "기존" 은 사실상 거짓. 후속 PR (#3 / #4) 의 ADR 이 phantom 을 "있는 것"으로 가정하고 작성.

**예방**. plan 의 "활용 엔티티" 항목은 `grep -rn "class <Name>"` 로 실재 검증. ADR / spec 작성 시 참조 코드 경로 (`backend/modules/.../<File>.kt:<line>`) 명시.

(merge 시 `Maxi_wiki/BTS/learnings.md` 에 정식 등록 예정)
