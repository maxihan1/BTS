<!-- Phase 0 PoC 실제 설치 체크리스트 — dependencies.md를 build 파일로 옮기는 순서 -->

# Phase 0 PoC 설치 체크리스트

**상태**. Phase 0 PoC 진입 전 (코드 0줄). 각 PoC 항목 시작 시 해당 섹션의 체크박스 완료.
**카탈로그 원본**. [dependencies.md](dependencies.md)
**SDD 매핑**. [22장 §22.6.1 — 13개 PoC 항목](../sdd/22-claude-code-env.md#226-phase-0-poc를-claude-code-환경에-맞춤-재정의)

## §0. 사전 준비 (PoC 시작 1일차 — 1회만)

### 호스트 환경 확인

- [ ] JDK 21 설치 (Temurin 또는 OpenJDK) — `java -version`으로 21 확인
- [ ] pnpm 9.x+ — `corepack enable && corepack prepare pnpm@latest --activate`
- [ ] Node 22 LTS — `node -v`로 22.x 확인
- [ ] Docker 27+ — `docker --version` + `docker compose version`
- [ ] git worktree 동작 확인 — `git worktree list`

### 디렉토리 스캐폴딩 (현재 부재 — Phase 0 첫 작업)

- [ ] `backend/` 생성 + Gradle Kotlin DSL 초기화 (`gradle init --type kotlin-application --dsl kotlin`)
- [ ] `apps/web/` 생성 + Vite + React 19 (`pnpm create vite@latest apps/web -- --template react-ts`)
- [ ] `apps/admin/` (PoC 단계에선 보류 — Phase 1)
- [ ] `packages/` 디렉토리만 생성 (내부 패키지는 필요할 때마다)
- [ ] `infra/docker-compose.dev.yml` 작성 (SDD 16.2 기반, dev 프로필)
- [ ] 루트 `pnpm-workspace.yaml` 작성
- [ ] 루트 `package.json` (scripts only — 워크스페이스 진입점)
- [ ] `.gitignore` 보강 (`node_modules`, `.gradle`, `build/`, `dist/`)
- [ ] `tools/eslint-config`, `tools/tsconfig`, `tools/ktlint` 초기 셋업

## §1. PoC 항목별 의존성 추가 (Δ는 그 PoC에서 처음 추가되는 의존성)

> 한 PoC 항목 = 한 PR. 의존성 추가는 `chore: deps add <이름>` 단일 커밋. 카탈로그 §2 또는 §3에 없는 라이브러리는 **Maxi 확인 필수** (DEVELOPMENT.md §1.16).

### §1.1 워크플로우 엔진 FSM (3일, 백엔드)

- [ ] **Δ Gradle 플러그인**. `kotlin-jvm`, `spring-boot`, `flywayPlugin`, `ktlint`, `detekt`
- [ ] **Δ 의존성**. `spring-boot-starter-web`, `-actuator`, `-validation`, `spring-boot-starter-data-jdbc`
- [ ] **Δ 의존성**. `org.jooq:jooq` + `jooq-codegen`
- [ ] **Δ 의존성**. `org.flywaydb:flyway-core` + `-database-postgresql`
- [ ] **Δ 의존성**. `org.postgresql:postgresql`
- [ ] **Δ 의존성**. `org.jetbrains.kotlinx:kotlinx-coroutines-core` (필요 시)
- [ ] **Δ 의존성**. `io.konform:konform`
- [ ] **Δ 테스트**. `junit-jupiter`, `mockk`, `assertj-core`, `testcontainers` (`postgresql` + `junit-jupiter`)
- [ ] **Δ 인프라**. `postgres:16` 컨테이너 (docker compose)
- [ ] 첫 Flyway 마이그레이션. `V001__init.sql` (워크플로우 FSM 테이블)
- [ ] TDD 사이클. `test:` → `feat:` → `refactor:` (DEVELOPMENT §4)

### §1.2 AQL 파서 + PostgreSQL 변환 (3일, 백엔드)

- [ ] **Δ Gradle 플러그인**. `org.antlr.gradle-plugin`
- [ ] **Δ 의존성**. `org.antlr:antlr4-runtime`
- [ ] AQL 문법 정의. `backend/modules/issue-tracking/aql/Aql.g4`
- [ ] 변환기 구현. AQL AST → jOOQ Condition

### §1.3 LexoRank + 1K 부하 (1일, 백엔드)

- [ ] 외부 의존성 추가 **없음** — 자체 구현 (`backend/shared/lexorank.kt`)
- [ ] 부하 테스트. JUnit5 + 1,000개 정렬 시나리오

### §1.4 AuthN Provider + LDAP/SAML (3일, 백엔드)

- [ ] **Δ 의존성**. `spring-boot-starter-security`, `-oauth2-client`, `-oauth2-resource-server`
- [ ] **Δ 의존성**. `de.mkammerer:argon2-jvm` (비밀번호 해싱 — §1.1)
- [ ] **Δ 테스트**. `spring-security-test`
- [ ] **Δ 인프라**. `quay.io/keycloak/keycloak:25` 컨테이너
- [ ] OIDC Authorization Code + PKCE 설정
- [ ] Keycloak realm import 스크립트

### §1.5 pgmq 트랜잭션 일관성 (1일, 백엔드)

- [ ] **Δ 인프라**. PostgreSQL 16 이미지에 **pgmq 확장 설치** (Dockerfile 확장 또는 tembo-io 이미지 — ADR 필요)
- [ ] **Δ 인프라**. `pg_trgm` 확장 (검색 보강 — SDD 3.5.3)
- [ ] pgmq SQL 함수 호출 래퍼 (jOOQ routine)
- [ ] 트랜잭션 일관성 통합 테스트. "이슈 생성 ↔ 알림 큐 발행" 동일 트랜잭션

### §1.6 React 19 + TanStack Router (1일, 프론트엔드)

- [ ] **Δ 런타임**. `react@19`, `react-dom@19`
- [ ] **Δ 런타임**. `@tanstack/react-router`, `@tanstack/router-devtools`
- [ ] **Δ 런타임**. `@tanstack/react-query`, `@tanstack/query-devtools`
- [ ] **Δ 런타임**. `zustand`
- [ ] **Δ 런타임**. `tailwindcss@4`, `@tailwindcss/vite`
- [ ] **Δ 런타임**. `lucide-react`
- [ ] **Δ 빌드**. `vite@6`, `@vitejs/plugin-react`
- [ ] **Δ 언어**. `typescript@5` + `tsconfig` (`strict: true`, `noUncheckedIndexedAccess: true`)
- [ ] **Δ 테스트**. `vitest`, `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event`
- [ ] **Δ 테스트**. `msw` (API 모킹)
- [ ] **Δ 린트**. `eslint`, `@typescript-eslint/*`, `eslint-plugin-react-hooks`, `prettier`
- [ ] React 19 동시성 + Compiler 호환성 검증

### §1.7 Gantt 차트 (자체 SVG vs lib, 2일, 프론트엔드)

- [ ] **Δ 비교용**. `recharts` (벤치마크 — 자체 SVG와 100개 막대 비교)
- [ ] 자체 SVG 프로토타입 + 라이브러리 프로토타입 둘 다 작성
- [ ] 결정 ADR. `docs/adr/<date>-gantt-choice.md`

### §1.8 TipTap 50종 블록 (v0.5 위키 준비, 2일, 프론트엔드)

- [ ] **Δ 에디터**. `@tiptap/react`, `@tiptap/pm`, `@tiptap/starter-kit`
- [ ] **Δ 에디터**. `@tiptap/extension-link`, `-code-block`, `-mention`, `-image`, `-table`, `-task-list`
- [ ] **Δ 에디터**. `tiptap-markdown`
- [ ] **Δ 시각**. `react-markdown`, `remark-gfm`, `shiki`
- [ ] AtlasEditor variant 추상화 (`issue-body | comment | wiki`)

### §1.9 @dnd-kit 1K 백로그 드래그 (1일, 프론트엔드)

- [ ] **Δ 인터랙션**. `@dnd-kit/core`, `@dnd-kit/sortable`, `@dnd-kit/modifiers`
- [ ] **Δ 시각**. `@tanstack/react-virtual` (1K 가상 스크롤)
- [ ] 60 FPS 유지 확인 (Performance Observer)

### §1.10 STOMP WebSocket 재연결 (1일, 양쪽)

- [ ] **Δ 백엔드**. `spring-boot-starter-websocket`
- [ ] **Δ 프론트**. `@stomp/stompjs`, `reconnecting-websocket`
- [ ] 지수 백오프 5s → 60s 검증
- [ ] 네트워크 분리/복귀 시나리오 통합 테스트

### §1.11 인프라 마무리 (전 항목 끝난 후)

- [ ] **Δ 인프라**. `redis:7-alpine`, `minio/minio`, `nginx:1.27-alpine` 컴포즈에 통합
- [ ] **Δ 백엔드**. `lettuce-core`, `minio` (Object Storage SDK)
- [ ] **Δ 백엔드**. `spring-boot-starter-mail` (알림)
- [ ] `docker compose up` 한 줄로 전체 스택 기동 확인

## §2. 카탈로그 외 의존성 추가 절차 (§1.16 준수)

새 라이브러리가 SDD/카탈로그에 없으면.

1. **stop** — 진행 중인 task 일시 중단
2. Maxi에게 보고. 라이브러리명 + 사용 목적 + 대안 (가능하면 카탈로그 내 라이브러리)
3. 승인 시. SDD 해당 챕터에 추가 → 이 카탈로그 §2/§3에 추가
4. ADR 작성. `docs/adr/<date>-<lib>-adoption.md` — "왜 이게 필요했나, 대안은?"
5. 그제서야 `chore: deps add <lib>` 커밋

## §3. 검증 (모든 PoC 항목 종료 시)

- [ ] `./gradlew build` 통과 (백엔드 전체)
- [ ] `pnpm verify` 통과 (lint + typecheck + test + build)
- [ ] `docker compose up` 후 헬스체크 모두 green
- [ ] 의존성 라이선스 감사. `pnpm licenses list` + Gradle license plugin
- [ ] 보안 스캔. `pnpm audit` + Dependabot 활성화 (SDD 3.4)
- [ ] CHANGELOG.md 초안. PoC 결과 요약

## §4. 변경 이력

- 2026-05-19. 초안. 카탈로그 §2.6의 PoC ↔ 의존성 매핑을 체크박스로 전개.
