<!-- ADR — JWT 서명 키(kid) 관리 정책. PoC 키 1개 + rotation 메커니즘은 schema 후속 -->

# ADR — JWT 서명 키(kid) 관리 정책

**일자.** 2026-05-20
**상태.** Accepted
**관련 PR.** #7 (`fr-au-09-securityfilterchain-local-provider-pr-6-7`)
**작성자.** security-engineer (Claude Sonnet 4.6)

---

## 컨텍스트

BTS는 RSA RS256 서명 JWT를 자체 발급한다(`2026-05-20-jwt-issuer-strategy.md` 참조).
JWT 헤더의 `kid`(Key ID) 클레임은 검증자(Resource Server, SPA)가 어느 공개키로 서명을 검증해야 하는지 식별하기 위해 사용된다.

서명 키 관리에는 다음 결정이 필요하다.

1. **PoC 단계 키 개수.** 키 1개로 시작할지, 처음부터 rotation 구조를 만들지.
2. **키 저장 위치.** application.yml PEM 경로 vs DB 테이블(`jwt_signing_keys` V007) vs KMS.
3. **`kid` 값.** 상수로 고정할지, DB에서 동적으로 가져올지.
4. **KeyProvider 추상화.** 환경(dev/prod)별 키 로딩 전략을 어떻게 분리할지.

---

## 결정

### 1. PoC 단계: 키 1개, `kid = "k-01"` 상수

본 PR에서는 RSA 키를 1개만 사용한다. `kid` 클레임 값은 `"k-01"` 상수다.

키를 추가하거나 교체하면 JWT Bearer Token 검증 전체에 영향을 주므로, PoC 단계에서는 단순성을 우선한다.

### 2. `jwt_signing_keys` 테이블(V007) — 본 PR 미도입

spec §5에서 V007은 선택 사항으로 정의됐으며, 본 PR에서는 도입하지 않는다.

| 항목 | 결정 |
|---|---|
| V007 `jwt_signing_keys` 테이블 | **미도입.** 후속 rotation PR에서 추가 |
| `kid` 관리 | 본 PR: `"k-01"` 상수. rotation 도입 시 DB에서 active key 조회 |
| rotation 메커니즘 코드 | **미구현.** 후속 PR |

미래 rotation 도입 시 V007 스키마 예정.

```sql
-- 후속 PR 예시 (본 PR에는 존재하지 않음)
CREATE TABLE jwt_signing_keys (
  kid          VARCHAR(64)  PRIMARY KEY,
  algorithm    VARCHAR(16)  NOT NULL DEFAULT 'RS256',
  private_key  TEXT         NOT NULL,  -- KMS 암호화 후 저장 (DEVELOPMENT.md §1 외부 비밀값 규칙)
  public_key   TEXT         NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'active',  -- 'active' | 'retiring' | 'revoked'
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  retired_at   TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ
);
```

`private_key`는 KMS 암호화 후 저장해야 한다. DB에 평문 private key 저장은 DEVELOPMENT.md §1 외부 비밀값 규칙 위반이다.

### 3. KeyProvider 추상화 — 환경별 분리

`KeyProvider` 인터페이스로 키 로딩 전략을 추상화하고, 환경별로 다른 구현체를 Spring Bean으로 등록한다.

```
KeyProvider (interface)
  ├── DevMemoryKeyProvider   -- @Profile("!prod")  테스트/개발 전용 인메모리 RSA 키 자동 생성
  └── PemFileKeyProvider     -- @Profile("prod")   환경 변수 경로의 PEM 파일 로드
```

| 구현체 | 활성 프로파일 | 키 출처 | 재시작 시 키 변경 |
|---|---|---|---|
| `DevMemoryKeyProvider` | `!prod` (dev, test) | `RSAKeyPairGenerator`로 런타임 생성 | 변경됨 (기존 token 무효화) |
| `PemFileKeyProvider` | `prod` | `bts.auth.jwt.private-key-pem-path` 환경 변수 경로의 PEM | 변경 안 됨 (동일 키 영구 유지) |

`DevMemoryKeyProvider`는 재시작 시 새 키를 생성하므로 기존 Access Token이 무효화된다. 이는 개발 환경에서 허용된 동작이다.

`PemFileKeyProvider`는 PEM 파일 누락 또는 형식 오류 시 Application 기동 실패를 발생시킨다. 명확한 에러 메시지로 설정 확인을 안내해야 한다(EC-19/EC-30, spec §6).

### 4. 공개키 노출: `GET /.well-known/jwks.json`

현재 키의 공개키를 JWK Set 형식으로 노출한다.

```json
{
  "keys": [
    {"kty": "RSA", "use": "sig", "kid": "k-01", "alg": "RS256", "n": "...", "e": "AQAB"}
  ]
}
```

`Cache-Control: public, max-age=86400` (24시간). Resource Server가 이 endpoint를 캐싱하므로, rotation 도입 시 캐시 무효화 전략(신/구 키 병렬 노출 기간)을 함께 설계해야 한다.

---

## 대안 검토

### 대안 1. 처음부터 V007 도입 + rotation 구현 (불채택)

V007 테이블을 본 PR에 포함하고 `active/retiring` 상태 전환 로직을 구현한다.

**불채택 이유.**
- PoC 단계에서 키를 교체해야 할 운영 필요성이 없다. 단일 호스트 Docker Compose 환경에서 키는 volume mount로 영구 유지된다.
- rotation 구현에는 신 키 등록 → 구 키 `retiring` 상태 유지(기존 token 검증 허용) → 구 키 revoke의 3단계 상태 전환이 필요하다. JWK Set 캐시 TTL(24h) 동안 구 키를 유지해야 하는 등 복잡한 처리가 따른다.
- V007 스키마의 `private_key` 컬럼은 KMS 암호화 없이 저장하면 안 된다. KMS 연동은 본 PR 범위 밖이다.

### 대안 2. application.yml에 PEM inline 저장 (불채택)

`application.yml`에 PEM 문자열을 직접 삽입한다.

**불채택 이유.**
- `application.yml`이 Git에 커밋되면 private key가 유출된다. DEVELOPMENT.md §1 외부 비밀값 규칙 위반.
- `application-prod.yml`을 별도로 관리해도 파일 관리 실수 위험이 있다.
- 환경 변수(`bts.auth.jwt.private-key-pem-path`)로 파일 경로를 주입하는 `PemFileKeyProvider`가 더 안전하다.

### 대안 3. KMS(Naver Cloud Key Management) 직접 사용 (PoC 단계 불채택)

Naver Cloud KMS API를 호출해 JWT 서명을 수행한다. Private key가 BTS 프로세스에 노출되지 않는다.

**PoC 단계 불채택 이유.**
- KMS API 연동 구현 복잡도가 PoC 단계 목표와 맞지 않는다.
- 네트워크 왕복이 발생해 JWT 발급 지연이 생긴다(spec NFR 로그인 < 500ms p95).
- `PemFileKeyProvider`에서 KMS로의 전환은 `KeyProvider` 인터페이스 교체로 충분하므로, 추후 도입이 용이하다.

---

## 결정 근거

1. **단순성 우선 (PoC).** 키 1개 + 상수 `kid`로 JWT 발급/검증 흐름 전체를 먼저 안정화한다.
2. **미래 rotation 대비.** `KeyProvider` 인터페이스 + `kid` 클레임 구조를 갖춰 rotation 도입 시 코드 변경을 최소화한다.
3. **환경 격리.** `DevMemoryKeyProvider` / `PemFileKeyProvider` 분리로 개발 환경에서 PEM 파일 관리 부담을 없앤다.
4. **보안 원칙.** Private key는 Git에 커밋하지 않는다. prod 환경에서는 volume mount된 PEM 파일을 환경 변수로 참조한다.

---

## 결과

### 긍정

- PoC 단계 구현 범위가 최소화된다. 키 발급/검증 흐름 안정화에 집중할 수 있다.
- 인터페이스 추상화로 rotation 도입 시 변경 범위가 `KeyProvider` 구현체 추가와 V007 마이그레이션으로 한정된다.

### 부정 / 위험

- `DevMemoryKeyProvider` 재시작 시 기존 Access Token 무효화. 개발/테스트 환경 허용 동작이나, 개발 중 예상치 못한 토큰 만료로 혼란 가능.
- 키 rotation 없는 운영 기간이 길어질수록 단일 키 유출 시 전체 Access Token 위조 위험이 있다. PEM 파일 접근 권한 관리(OS 파일 권한, Docker volume mount 권한)를 운영 절차에 포함해야 한다.
- JWK Set 캐시 TTL 24h. 키를 긴급 revoke할 경우 최대 24h 동안 기존 Access Token이 검증될 수 있다. rotation 도입 시 캐시 무효화 전략이 필수다.

### 후속 조건

- 운영 환경에서 키 교체가 필요해지면 → 별도 PR에서 V007 도입 + `active/retiring` rotation 로직 구현.
- KMS 도입이 확정되면 → `KeyProvider` 구현체 추가. 기존 인터페이스 변경 없음.

---

## 참조

- `docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` §5 V007 (jwt_signing_keys 선택 결정)
- `docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` §3 NFR 성능 (JWT key zero-downtime)
- `docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` §6 EC-19/EC-30 (PEM 누락 기동 실패)
- `docs/decisions/2026-05-20-jwt-issuer-strategy.md` (BTS 자체 JWT 발급 전략 — nimbus-jose-jwt + JwtIssuer)
- `docs/decisions/2026-05-20-session-pat-schema.md` (sid 클레임 — kid와 함께 JWT 헤더/페이로드 구성)
- DEVELOPMENT.md §1 (외부 비밀값 KMS 암호화 절대 규칙)
- SDD 19.2 (플러그형 Provider — JWT 발급 흐름)
- SDD 19.5 (Session/Token 관리 — Access JWT 15분, Refresh 14일)
