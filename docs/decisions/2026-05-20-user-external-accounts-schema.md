<!-- ADR: user_external_accounts + authn_providers V002 스키마 결정 -->

# ADR: V002 스키마 — user_external_accounts ON DELETE 정책 + 인덱스

> 날짜: 2026-05-20
> 상태: 결정됨
> 연관: FR-AU-02, V002__authn_providers_and_user_external_accounts.sql

## 맥락

FR-AU-02 에서 `authn_providers` + `user_external_accounts` 두 테이블을 신규 도입한다. FK 삭제 정책과 인덱스 전략을 결정해야 한다.

## FK ON DELETE 정책

### authn_providers ← user_external_accounts: ON DELETE RESTRICT

Provider 를 삭제하려면 해당 Provider 에 매핑된 모든 user_external_accounts 행이 먼저 삭제되어야 한다.

**이유**: Provider 삭제 시 매핑 행이 남으면 "미아 데이터(orphan)"가 생긴다. RESTRICT 로 명시적 삭제를 강제하여 데이터 무결성 보호.

**대안 (미채택)**: CASCADE — Provider 삭제 시 모든 사용자 매핑도 자동 삭제됨. 대규모 비의도적 데이터 손실 위험.

### users ← user_external_accounts: ON DELETE CASCADE

User 삭제 시 해당 User 의 모든 external account 매핑도 함께 삭제.

**이유**: User 는 external account 보다 생명주기가 길다. GDPR 사용자 삭제 요청 시 매핑 데이터도 함께 삭제되어야 한다. CASCADE 가 자연스러운 생명주기 관리.

## 인덱스 전략

| 인덱스 | 대상 | 이유 |
|---|---|---|
| `idx_authn_providers_type_enabled` | `authn_providers(type, enabled) WHERE enabled=true` | 로그인 핫패스 — type=LDAP 활성 Provider 조회. 부분 인덱스로 비활성 행 제외 |
| `idx_uea_user_id` | `user_external_accounts(user_id)` | User 기준 매핑 조회 (사용자 프로필 페이지 등) |
| `idx_uea_provider_subject` | `user_external_accounts(provider_id, external_subject)` | 로그인 핫패스 — provider_id + external_subject 조합으로 기존 매핑 탐색 |

### 미도입 인덱스

- `groups` JSONB GIN 인덱스: 본 PR 그룹 검색 query 없음. FR-PM-01 (권한 매핑) 도입 시 추가 검토.
- `locked_until` 인덱스: 잠금 만료 조회는 개별 행 수준 — 풀스캔 필요 없음.

## external_subject 설계

LDAP DN (`uid=alice,ou=people,dc=example,dc=org`), OIDC sub, SAML NameID 등 다양한 형식을 수용하기 위해 VARCHAR(512). UNIQUE(provider_id, external_subject) 제약으로 동일 Provider 내 중복 방지.
