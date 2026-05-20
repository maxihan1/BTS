<!-- ADR: Argon2id 패스워드 해싱 파라미터 — OWASP 2024 권장값 채택 -->

# ADR — Argon2id 패스워드 해싱 파라미터

**일자**. 2026-05-20
**상태**. Accepted
**관련 PR**. #2 (`auth/identity-access-authn-poc`)
**작성자**. Maxi + Claude (security-engineer)

## 컨텍스트

identity-access §1 AuthN PoC의 F1 LocalCredentialService에서 평문 비밀번호를 해싱해 저장. DEVELOPMENT.md §1.1 절대 규칙. 알고리즘은 카탈로그에 명시된 `de.mkammerer:argon2-jvm:2.11+` (Argon2id 지원).

파라미터 결정 필요. memory / iterations / parallelism / hash length / salt length.

## 후보

OWASP Cheat Sheet (2024)의 Argon2id 권장.

| Preset | memory (KiB) | iterations | parallelism | 비고 |
|---|---|---|---|---|
| OWASP A | 47104 (46 MiB) | 1 | 1 | 최소 권장 (저성능 환경) |
| OWASP B | 19456 (19 MiB) | 2 | 1 | 절충 (Argon2 RFC 9106 §4 권장) |
| **OWASP C** | **65536 (64 MiB)** | **3** | **4** | **충분한 보안 + 적정 latency. 권장** |
| OWASP D (높음) | 65536 | 4 | 8 | 고성능 서버 |

## 결정

**채택. OWASP C — `memory=65536, iterations=3, parallelism=4`.**

코드 정의 (`backend/modules/identity-access/src/main/kotlin/.../Argon2Params.kt`).

```kotlin
object Argon2Params {
  const val MEMORY_KB = 65536    // 64 MiB
  const val ITERATIONS = 3
  const val PARALLELISM = 4
}
```

## 근거

1. **OWASP 2024 권장 충족** — 메모리·반복·병렬 모두 권장 범위 내.
2. **측정값** — M-class arm64 (개발 머신) 기준 hash 단가 50~60ms (T2 측정). 첫 호출 JVM warm-up 포함 185ms.
3. **prod 임계 부합** — 로그인 응답 NFR `p95 < 800ms` (spec §3) 대비 해싱 50~60ms는 충분히 여유. 사용자 체감 영향 최소.
4. **`parallelism=4` 선택** — Naver Cloud Standard 인스턴스 vCPU 4+ 가정. parallelism=1로 낮추면 메모리 동시 점유 분산이 약해져 ASIC 공격 방어력 약화. 1 vs 4 선택은 인스턴스 vCPU 가용량과 균형.
5. **`memory=64 MiB` 한도** — 1,000 동접 가정 시 총 64 MiB × 동시 인증 수. 100 동접 가정 (NFR §2.3.2) 시 ~6.4 GiB 일시 점유 가능 — 인증 흐름은 짧아 실시간 동시는 100 미만 — 안전 마진 큼.
6. **사용자 환경 변경 시 대응** — OWASP 권장은 매년 갱신. 본 파라미터는 Argon2Params 상수로 분리 — 향후 application.yml로 외부화 가능 (Phase 1+).

## 영향

### 긍정

- 비밀번호 해싱 보안 OWASP 표준 충족.
- 평문 + wipeArray 패턴 (Argon2.wipeArray)으로 메모리 잔존 위험 최소화.

### 부정 / 위험

- **prod 부하 측정 미실시 (PoC 단계)**. 실측은 Phase 1+ 정식 구현 시점. 부하 테스트 후 파라미터 재조정 가능.
- **JVM warm-up 단가 185ms** — 첫 사용자 로그인 시 응답 지연 가능. 완화책. (1) HikariCP-style warm-up, (2) ApplicationReadyEvent에서 dummy hash 1회 실행으로 워밍업. PoC 단계엔 미적용.

## 대안 채택 조건

- prod 부하 테스트에서 응답 p95 > 800ms → OWASP B 또는 A로 다운그레이드 (memory 또는 iterations 축소).
- 사용자 수 1만 이상으로 확장 → 메모리 점유 부담 ↑ → parallelism 축소 또는 외부 인증 위임 (Keycloak 자체 처리).
- OWASP가 새 권장값 발표 → 본 ADR 갱신 + 데이터 마이그레이션 (`upgrade-encoder` 패턴).

## 관련

- `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/LocalCredentialService.kt`
- `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/Argon2Params.kt`
- `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/LocalCredentialServiceTest.kt`
- DEVELOPMENT.md §1.1 — 평문 비밀번호 저장 금지
- [[../../poc/dependencies.md#§2.3-인증-보안]] — `de.mkammerer:argon2-jvm:2.11+`
- OWASP Password Storage Cheat Sheet 2024
