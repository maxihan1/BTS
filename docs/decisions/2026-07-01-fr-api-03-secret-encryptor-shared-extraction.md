# ADR: FR-API-03 PR2 — SecretEncryptor의 shared-kernel 추출 + BC별 암호화 키

> 날짜: 2026-07-01
> 상태: Accepted (Maxi 게이트 확정 — 옵션 A)
> 관련 FR: FR-API-03 (Webhook 외부 시스템 통지), search-export-import BC §5.3
> 관련 ADR: [2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md](2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md) (PR1 — BC 배치 + 재사용 방침, 크립토 키관리는 spec 위임)
> 관련 PR: FR-API-03 PR2 (구독 관리 계층)

## 맥락

FR-API-03 PR2는 아웃바운드 webhook 구독의 `secret`(HMAC-SHA256 서명용 공유 비밀)을 DB에 평문 저장하지 않고 암호화 저장해야 한다(`outbound_webhooks.secret_encrypted`).

BTS에는 이미 AES-256-GCM 대칭 암호화 유틸 `SecretEncryptor`가 있다 — 단 **identity-access BC**(`com.atlas.bts.identity.config.SecretEncryptor`)에 있고, OIDC client_secret 암호화 용도로 도입됐다. 클래스 자체는 범용이다(생성자로 `password`+`hexSalt`를 받고, `encrypt`/`decrypt`만 노출). OIDC 전용인 부분은 그 클래스를 만드는 설정(`OidcEncryptionConfig`, `BTS_OIDC_ENCRYPTION_*` 환경변수)뿐이다.

BTS의 BC 격리 원칙(한 PR = 한 BC, 다른 BC 직접 import 금지)상 search-export-import BC는 identity-access의 `SecretEncryptor`를 직접 import할 수 없다. PR1 ADR은 이 크립토 키 관리 정책을 "spec에서 기존 암호화 인프라 재사용 여부 확정"으로 미뤄뒀다.

## 결정

### D1. SecretEncryptor 클래스를 shared-kernel로 추출

`SecretEncryptor`(암호화 로직만 담긴 순수 클래스, cross-BC 의존 없음)를 shared-kernel `com.bts.shared.crypto.SecretEncryptor`로 이동한다. 클래스 본문(AES-256-GCM + random IV, `Encryptors.stronger`)은 변경하지 않는다.

- PR1이 SSRF 가드/HTTP 클라이언트를 shared-kernel `com.bts.shared.http`로 추출한 것과 **동형 패턴**이다.
- 보안 코드(암호화)를 단일 출처로 유지 → 한 번 감사/패치하면 모든 소비 BC에 적용. 복제(옵션 B)가 유발하는 "한쪽만 패치" 회귀를 구조적으로 차단.

**대안 기각**.
- **옵션 B(search BC 내 자체 유틸)**: AES-GCM 보안 코드를 3번째로 복제. PR1 ADR이 SSRF 가드에 대해 복제를 기각한 원칙과 충돌. 기각.
- **옵션 C(shared에 별도 신규 클래스 추가, identity-access 불변)**: identity-access를 안 건드려 범위는 작으나, 동일 알고리즘 클래스가 두 벌 공존(미통합). 단일 출처 원칙 미달. 기각.

### D2. 암호화 키는 BC별로 분리 (webhook ≠ OIDC)

추출된 클래스는 공유하되, **암호화 키는 BC별로 분리**한다.

- identity-access `OidcEncryptionConfig`는 shared 클래스를 import하도록 변경하고 기존 `BTS_OIDC_ENCRYPTION_KEY/SALT`를 그대로 사용(OIDC 암호화 경로 **동작 불변** — 기존 ciphertext 복호화 호환).
- search-export-import는 자체 설정(`BTS_WEBHOOK_ENCRYPTION_KEY/SALT`)으로 별도 `SecretEncryptor` 빈을 생성.
- 근거: webhook secret과 OIDC client_secret이 같은 키를 공유할 이유가 없다. 키 분리는 폭발 반경을 줄이는 키 위생(한 키 유출이 다른 도메인 비밀로 번지지 않음).

### D3. BC 경계 1회 교차 (문서화된 예외)

이 PR이 identity-access BC 코드(`SecretEncryptor.kt` 이동 + `OidcEncryptionConfig`/참조처 import 변경)를 건드리는 것은, 공유 크립토 추출이라는 본질상 불가피하다. PR1이 http 인프라 추출로 notification BC를 건드린 것과 동류의 문서화된 예외다. plan §리스크에 명시하고 reviewer가 사유를 즉시 파악하도록 한다.

## 알려진 한계 / 경계 (수용)

- **부팅/스캔 회귀 위험**: `SecretEncryptor`는 `@Component`가 아니라 `@Configuration`(`OidcEncryptionConfig`)이 수동 인스턴스화하는 클래스라, shared-kernel `@ComponentScan` 회귀(learnings: shared-kernel component 추출 스캔 회귀)의 직접 대상은 아니다. 그러나 패키지 이동으로 identity-access의 모든 참조 import가 바뀌므로, **검증은 identity-access 전체 테스트 스위트**로 수행한다(타깃 테스트만 = 가짜 그린).
- **키 미설정 부팅**: shared 클래스는 기존과 동일하게 키 부재 시 생성자에서 예외를 던지지 않고 `encrypt`/`decrypt` 호출 시점에 검증한다(슬라이스/통합 테스트 부팅 안전성 보존).

## 영향 / 후속

- shared-kernel `com.bts.shared.crypto`는 향후 다른 BC가 비밀값을 암호화 저장할 때 공유할 기반이 된다.
- 실제 webhook secret 암호화 저장/복호화 사용은 본 PR2(구독 CRUD 저장 시)에서 시작. HMAC 서명에 secret을 쓰는 발송 경로는 PR3.
