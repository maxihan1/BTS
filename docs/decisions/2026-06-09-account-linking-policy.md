<!-- ADR — FR-AU-08 계정 통합(Account Linking): 명시적 수동 연결 전용 + 재인증 강제 + 충돌 거부 + 마지막 수단 보호 -->

# ADR: 계정 통합 (Account Linking) 정책

> 결정일. 2026-06-09
> 상태. Accepted
> 컨텍스트. identity-access BC §2.8 FR-AU-08 (slug `fr-au-08-account-linking`)
> 선행. ADR `2026-05-20-user-external-accounts-schema`(V002 — User × External Identity 매핑 + RESTRICT/CASCADE), `2026-06-09-multi-provider-explicit-selection`(FR-AU-06 — 명시 선택, 자동 fallback 폐기), `2026-06-09-domain-based-provider-routing`(FR-AU-07 — 계정 열거 0 기조), `2026-06-08-local-account-signup`(FR-AU-05).

## 컨텍스트

현재(FR-AU-09까지) 외부 IdP 로그인은 **JIT 자동 프로비저닝** 하나뿐이다. `AutoProvisionService.provision()`이 `users`를 **`ON CONFLICT (username)`** 기준으로 UPSERT하고 `user_external_accounts`를 `(provider_id, external_subject)` 기준으로 UPSERT한다. 즉 지금은 *username이 우연히 같으면* 서로 다른 Provider 로그인이 암묵적으로 같은 계정에 붙는다. 사용자가 **스스로** 자기 계정에 추가 로그인 수단을 붙이는 명시적 self-service 연결 기능은 0건이다.

FR-AU-08은 로그인한 사용자가 자기 계정에 추가 외부 신원을 명시적으로 연결/해제/조회하는 워크플로우를 추가한다.

데이터 모델은 V002부터 이미 다대일을 지원한다. `user_external_accounts.user_id`에 UNIQUE가 없어 한 User가 여러 외부 계정을 가질 수 있다. **신규 스키마/마이그레이션 불필요**.

### SDD 19.4 deviation (명시)

SDD 19.4는 엔티티를 `UserIdentity`(Long id, `email`·`verifiedAt` 컬럼 포함)로 스케치했으나, 실재 테이블은 `user_external_accounts`(UUID PK, `email`·`verified_at` 컬럼 **없음**)다. 구현은 실재 스키마를 따른다. SDD 스케치는 stale — `email` 기반 매칭을 전제하지 않으므로(아래 D1) `email` 컬럼은 본 FR에서도 추가하지 않는다.

## 결정

### D1. 연결 방식 — 명시적 수동 연결 전용 (Maxi 2026-06-09)

이메일 일치 기반 자동 연결을 **도입하지 않는다**. 연결은 로그인한 사용자가 명시적으로 개시하고, **대상 Provider로 실제 인증에 성공**해야만 성립한다.

- 근거. (1) "외부 IdP가 보낸 이메일을 신뢰"한다는 가정은 이메일 위조가 가능한 Provider가 섞이면 **계정 탈취(account takeover)** 벡터가 된다. (2) FR-AU-06(자동 fallback 폐기)·FR-AU-07(계정 열거 0)이 세운 "편의보다 보안" 기조와 일관. (3) 기존 JIT 매칭이 username 기준이라 email 자동 연결은 매칭 로직 자체를 흔든다.
- 기존 JIT 자동 프로비저닝 흐름은 **변경하지 않는다**. 연결은 별도 self-service 경로로만 일어난다.

### D2. 기능 범위 — 연결 + 해제 + 목록 전체 (Maxi 2026-06-09)

① 내 연결 계정 목록 조회 ② 새 외부 Provider 연결(재인증 강제) ③ 연결 해제. 모두 본 FR 범위.

### D3. 연결 대상 — 외부 Provider(LDAP/SAML/OIDC) 한정

"계정 연결"은 `user_external_accounts`에 행을 갖는 외부 신원만 대상으로 한다. **LOCAL 비밀번호 추가/제거는 범위 밖**이다.

- 근거. LOCAL 자격증명은 `StoredPasswordCredential`(per-user, userId 키)에 저장되며 `user_external_accounts`에 행이 없다. product §2.8 D3가 데이터 모델로 `user_external_accounts`를 명시한 것과 정합. 비밀번호 설정/변경은 FR-AU-05(가입)·후속 비밀번호 관리 영역.
- 단, 아래 D5(마지막 수단 보호)의 "남은 로그인 수단" 카운트에는 LOCAL 비밀번호 보유 여부를 **포함**한다(존재 확인만, 읽기 전용).

### D4. 재인증 강제 (step-up) (product §2.8 D4 mandate)

민감 동작(연결 개시·연결 해제)은 현재 세션이 **최근 재인증(step-up)** 을 통과했을 때만 허용한다. 탈취/방치된 세션이 조용히 로그인 수단을 추가·제거하는 것을 차단한다.

- 연결의 경우 "대상 Provider로 실제 인증 성공"(D1)이 그 자체로 새 신원에 대한 소유 증명이다. 추가로 현재 세션의 재인증 신선도(freshness)도 요구한다.
- 해제는 새 Provider 인증이 없으므로 재인증은 **현재 자격증명 재확인**(또는 세션 신선도 임계) 의미다.
- **재인증 구체 메커니즘**(비밀번호 재입력 vs SSO 재수행 vs 세션 age 임계 + freshness 윈도우 길이)은 spec 단계 결정 사항으로 남긴다. 본 ADR은 "재인증을 반드시 강제한다"는 불변식만 확정.
- **확정(spec, 보안 리뷰 2026-06-09)**. 1차는 **재인증 챌린지 + 윈도우형 step-up**(Caffeine `sid→expiry` 5분, 윈도우 내 다회 허용). `sid`는 **JWT `sid` 클레임에서만** 추출(요청 페이로드 불수용 — confused-deputy/replay 차단). reauth 응답은 sid/토큰 미노출.

### D5. 충돌 처리 (product §2.8 D5 mandate)

- **타계정 선점 거부**. 연결하려는 `(provider_id, external_subject)`가 **이미 다른 user에 매핑**돼 있으면 거부한다(계정 탈취 차단). 기존 `UNIQUE (provider_id, external_subject)` 제약이 DB 차원 안전망.
- **멱등 연결**. 동일 신원이 이미 **현재 사용자**에 연결돼 있으면 no-op(중복 연결 아님).
- **마지막 수단 보호**. 연결 해제 후 그 사용자의 남은 로그인 수단이 **0이 되면 거부**한다. 스스로 잠기는 것(self-lockout) 방지. **카운트 정의(보안 리뷰 C4)** = **enabled provider의 링크 수 + LOCAL 비밀번호 보유(0/1)**. 비활성/삭제된 provider의 링크는 실제 로그인 불가이므로 카운트에서 제외 — 포함 시 "로그인 불가한데 해제도 막힌" 영구 락 발생. TOCTOU는 userId advisory lock(`hashtextextended` 전폭 해시) 직렬화로 차단.

## 결과

- **스키마 변경 없음**. 기존 `user_external_accounts`(V002) 재사용. `email` 컬럼 미추가(D1 — email 기반 연결 안 함).
- **신규 self-service API**(인증 필수, 본인 계정만). 목록 조회 / 연결 / 해제. 연결은 대상 Provider 인증 성공을 전제로 현재 세션의 userId에 `external_subject`를 붙인다(JIT 신규 user 생성 경로와 분리).
- **기존 JIT 흐름 무변경**. `AutoProvisionService`/SSO 성공 핸들러의 일반 로그인 경로는 그대로. 연결 모드는 별도 분기.
- 프론트 "계정 연결" 설정 페이지(D6) + E2E(D7)는 후속 단계.

## 보안 (DEVELOPMENT.md §1)

- **계정 탈취 차단**. email 자동 연결 미도입(D1) + 타계정 선점 거부(D5). 연결은 대상 Provider 실제 인증 성공이 전제.
- **세션 탈취 내성**. 재인증 강제(D4)로 방치/탈취 세션의 무단 수단 추가·제거 차단.
- **self-lockout 방지**. 마지막 로그인 수단 해제 거부(D5).
- **계정 열거 0**. 연결/해제 응답은 본인 계정 한정. 타 사용자 매핑 존재 여부를 식별 가능한 형태로 노출하지 않는다(충돌은 일반화된 거부 메시지).
- **권한 경계**. 모든 연결 API는 인증 필수 + 본인(userId) 자원만. PAT 취급은 세션 관리 선례(FR-AU-09 — PAT 403)와 정합 검토.
- **SQL prepared**. NamedParameterJdbcTemplate(현 repository 관례).
- **PII 로그 금지**. `external_subject`(PII)를 로그에 직접 출력하지 않는다(현 AutoProvisionService 관례).

## 관련

- 마스터플랜 §2.8 (`docs/plan/product/identity-access.md`)
- 선행 ADR `docs/decisions/2026-05-20-user-external-accounts-schema.md` (V002 스키마)
- SDD 19.4 (계정 통합)
