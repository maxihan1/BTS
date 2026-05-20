<!-- ADR: Testcontainers + Docker Desktop 4.x 호환 설정 — 머신 수준 .properties 파일 사용 -->

# ADR — Testcontainers + Docker Desktop 4.x 호환 설정

**일자**. 2026-05-20
**상태**. Accepted (workaround)
**관련 PR**. #2 (`auth/identity-access-authn-poc`)
**작성자**. Maxi + Claude (security-engineer)

## 컨텍스트

T6 통합 테스트(KeycloakIntegrationTest)가 Testcontainers 1.20.3 + Docker Desktop 4.x 환경에서 `IllegalStateException: Could not find a valid Docker environment` 실패.

근본 원인 (바이트코드 분석으로 발견).
- Testcontainers 1.20.3의 shaded `docker-java`가 `GET /v1.41/info` 호출.
- Docker Desktop 4.x는 최소 `v1.52` API 요구. 구버전 요청에 **HTTP 400** 반환.
- 결과. Testcontainers의 환경 점검(`StrategyHealthChecker`)이 실패하고 모든 컨테이너 부팅 불가.

## 후보

| # | 방식 | 장점 | 단점 |
|---|---|---|---|
| 1 | **`~/.docker-java.properties`에 `api.version=1.52` 설정** | 머신 1회 설정, build 영향 0, 모든 Testcontainers 테스트 적용 | 머신 수준 설정 — 신규 개발자 환경에 안내 필요 |
| 2 | Testcontainers 버전 업 (≥1.21) | 라이브러리만 변경 | **카탈로그 §1.16 위반** — 신규 의존성 추가 시 Maxi 확인 필수. 또한 1.21+ 호환성 미검증 |
| 3 | Docker Desktop 다운그레이드 | 가장 단순 | 머신 다른 도구 영향. 비현실적 |
| 4 | colima로 마이그레이션 | OSS, API 버전 통제 | 머신 전체 도커 컨텍스트 교체 — 본 PoC 범위 초과 |
| 5 | 환경변수 `DOCKER_API_VERSION=1.52` | shell init만 수정 | Gradle 포크 JVM에 전파 보장 어려움 |

## 결정

**채택. #1 — 머신 수준 `~/.docker-java.properties`에 `api.version=1.52` 설정. 추가로 `~/.testcontainers.properties`에 `tc.host`, `checks.disable`, `ryuk.disabled` 설정.**

머신 수준 설정 파일 2개.

```properties
# ~/.docker-java.properties
api.version=1.52
```

```properties
# ~/.testcontainers.properties
tc.host=unix:///Users/<USER>/.docker/run/docker.sock
checks.disable=true
ryuk.disabled=true
```

`backend/modules/identity-access/build.gradle.kts`에 백업 environment 주입.

```kotlin
tasks.withType<Test> {
  // 머신 docker context를 환경변수로도 주입 (이중 안전망)
  val dockerHost = providers.exec { commandLine("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}") }
    .standardOutput.asText.get().trim()
  environment("DOCKER_HOST", dockerHost)
}
```

## 근거

1. **근본 원인 해소** — Docker Desktop 4.x가 거부하던 구버전 API 요청이 v1.52로 통과.
2. **라이브러리 변경 없음** — 카탈로그 §1.16 (의존성 추가 시 Maxi 확인) 위반 회피.
3. **테스트 안정성 ↑** — `ryuk.disabled=true`로 Ryuk 컨테이너 부팅 단계 우회 (M-class arm64에서 Ryuk이 종종 실패).
4. **이중 안전망** — Gradle build에서 docker context inspect 결과를 DOCKER_HOST에 주입. `.properties` 파일이 누락된 환경에서도 build 단독으로 일부 보호.

## 영향

### 긍정

- T6 통합 테스트 (KeycloakIntegrationTest 4 tests) 안정 통과.
- 향후 PostgreSQL/MinIO 등 Testcontainers 기반 통합 테스트도 동일 환경 사용.

### 부정 / 위험

- **머신 수준 설정 필요 — 신규 개발자 환경 누락 시 실패**. 완화책.
  - 본 ADR을 `CONTRIBUTING.md` (Phase 1 진입 시 작성)에 인용
  - `scripts/setup-dev-env.sh` (향후) 작성 시 `.properties` 파일 자동 생성 포함
- **Docker Desktop 5.x / 6.x 출시 시 API 버전 재검토 필요**.
- **Linux 환경 (Docker Engine 직접)**. `~/.docker/run/docker.sock` 경로가 다름 (`/var/run/docker.sock`). 본 ADR은 macOS Docker Desktop 4.x 기준 — Linux 호환 패치는 별도 작업.

## 대안 채택 조건

- Testcontainers 1.21+ 가 카탈로그에 정식 등록 + 호환성 검증 완료 → `.properties` 의존 제거 가능.
- Docker Desktop Linux/Linux 컨테이너 표준 인터페이스 확립 → 본 워크어라운드 제거.

## 관련

- `backend/modules/identity-access/build.gradle.kts` — `tasks.withType<Test>` 블록
- `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/KeycloakIntegrationBase.kt`
- 향후 `CONTRIBUTING.md` (Phase 1 진입 시 작성) — `.properties` 파일 설정 안내
- [[../../poc/dependencies.md#§2.5-테스트]] — Testcontainers 1.20+
- Testcontainers Issue tracker (참고. Docker Desktop 4.x 호환 이슈 다수 보고됨)
