<!-- ADR: Keycloak 컨테이너 이미지 선택 — 공식 quay.io 이미지 채택 -->

# ADR — Keycloak 컨테이너 이미지 선택

**일자**. 2026-05-20
**상태**. Accepted
**관련 PR**. #2 (`auth/identity-access-authn-poc`)
**작성자**. Maxi + Claude (security-engineer)

## 컨텍스트

identity-access §1 AuthN PoC에서 OIDC IdP로 Keycloak 25.x 사용. 어떤 컨테이너 이미지를 채택할지 결정 필요.

배경.
- BTS는 1,000명 규모 사내 협업 도구. dev 환경은 docker-compose 단일 호스트, prod도 단일 ECS Fargate (SDD 16장).
- 운영 부담을 최소화하는 의존성 전략 (`docs/poc/dependencies.md §1`).

## 후보

| # | 이미지 | 장점 | 단점 |
|---|---|---|---|
| 1 | `quay.io/keycloak/keycloak:25.0` (공식) | 공식 안정성, 정기 보안 패치, 문서 일치, Realm import 표준 흐름 | quay.io 가용성 의존 (사내 미러 미존재 시 단일 장애점) |
| 2 | `docker.io/jboss/keycloak:25` (deprecated) | Docker Hub 캐시 활용 | **deprecated**. v17+는 quay.io로 이전 — 사용 불가 |
| 3 | `bitnami/keycloak:25` (Bitnami) | helm chart와 페어링, 광범위한 환경 변수 | 별도 entrypoint 스크립트, 공식 문서와 미세한 차이 |
| 4 | 자체 빌드 (Dockerfile FROM ubi9 + Keycloak release tar.gz) | 완전 통제, 사내 패치 적용 가능 | 운영 부담 ↑, 1인 개발 부적합, PoC 단계엔 과함 |

## 결정

**채택. #1 — `quay.io/keycloak/keycloak:25.0`.**

## 근거

1. **공식 안정성** — Keycloak 팀이 직접 빌드, 정기 보안 패치, 25.x LTS 라인.
2. **문서 일치** — Keycloak 공식 문서가 quay.io 이미지를 기준으로 모든 명령/설정을 안내. 학습 곡선 ↓.
3. **dev 환경 충분** — 본 PoC + Phase 1 정식 구현에 추가 요구사항 없음.
4. **1인 + Claude Code 모델** — 자체 빌드/Bitnami는 운영 부담만 ↑.
5. **prod 이전 시 동일 이미지** — 단일 호스트 ECS Fargate 환경(SDD 16장)에서도 동일 이미지 사용 가능. 환경 차이 최소화.

## 영향

### 긍정

- 사용 단순. `docker-compose.dev.yml`에서 `image: quay.io/keycloak/keycloak:25.0` 한 줄.
- Testcontainers 통합 테스트(T6)에서 동일 이미지 사용 — dev와 test 환경 일치.

### 부정 / 위험

- **quay.io 가용성 의존**. 비상 시 (rate limit / 일시 장애) 빌드 차단 위험. 완화책. (1) `docker pull`로 로컬 캐시 유지, (2) 향후 사내 컨테이너 레지스트리 미러 구성 시 mirror 등록.

## 대안 채택 조건

다음 중 하나가 발생하면 재검토.
- quay.io rate limit이 운영에 지속 영향 → 사내 미러 또는 Bitnami 검토.
- Keycloak 공식 이미지에 보안 패치 누락 발생 → 자체 빌드 검토.
- Phase 1+ 정식 구현에서 prod에 특정 SPI plugin 필요 → 자체 빌드 (Dockerfile + FROM 공식 + COPY plugin).

## 관련

- `infra/docker-compose.dev.yml` — Keycloak 서비스 정의
- `infra/keycloak/realm-bts.json` — realm 정의 (bts realm + bts-web client + alice 사용자)
- `backend/modules/identity-access/src/test/kotlin/.../integration/KeycloakIntegrationBase.kt` — Testcontainers 동일 이미지 사용
- `docs/sdd/16-infrastructure.md` — Naver Cloud 단일 호스트 인프라 (Keycloak 25)
- [[../../poc/dependencies.md#§4-인프라]] — 의존성 카탈로그
