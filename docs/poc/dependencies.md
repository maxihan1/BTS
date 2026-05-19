<!-- SDD에서 추출한 Phase 0 PoC 의존성 카탈로그 (실제 설치는 PoC 항목 진입 시) -->

# Phase 0 PoC 의존성 카탈로그

**작성일**. 2026-05-19
**상태**. 명세만 — 실제 설치 보류 (DEVELOPMENT.md §1.16, learnings.md "Claude의 환각" 함정 대응)
**출처**. SDD v0.5.0 — [3장 기술 스택](../sdd/03-tech-stack.md), [16장 인프라](../sdd/16-infrastructure.md), [19장 인증](../sdd/19-authentication.md), [21장 프론트엔드](../sdd/21-frontend.md), [22장 Claude Code 환경](../sdd/22-claude-code-env.md)

## §0. 이 문서의 목적

- SDD에 흩어진 라이브러리 명세를 한 곳에 모음 (BC별, 출처 챕터별)
- Phase 0 PoC 시작 시 `build.gradle.kts` / `package.json`으로 옮겨 쓸 단일 진실 원천
- 신규 의존성 도입 시 **이 카탈로그에 먼저 추가 → Maxi 확인 → 빌드 파일 반영** 순서 (§1.16 준수)

## §1. 절대 규칙 재확인

| 규칙 | 위치 | 적용 |
|---|---|---|
| §1.16 신규 의존성 → Maxi 확인 필수 | DEVELOPMENT.md | 이 카탈로그에 없는 라이브러리는 자동 추가 금지 |
| §1.17 토큰 localStorage 금지 | DEVELOPMENT.md | oidc-client-ts 설정 시 sessionStorage 강제 |
| §1.18 CDN 임의 스크립트 금지 | DEVELOPMENT.md | 모든 의존성은 npm/Maven 경유 |
| §사전 등록 함정 — Claude의 환각 | learnings.md | 모르는 라이브러리는 SDD에 있는지 먼저 확인 |
| §사전 등록 함정 — Kafka/OpenSearch 금지 | learnings.md | 큐는 pgmq, 검색은 PostgreSQL FTS 고수 |

## §2. 백엔드 (Gradle / Maven)

> 빌드. Gradle Kotlin DSL. JDK 21. 모듈러 모놀리스 (`backend/modules/<bc>/`).

### §2.1 핵심 프레임워크 (SDD 3.2)

| 그룹 | 라이브러리 | 버전 가이드 | 출처 |
|---|---|---|---|
| 언어 | Kotlin (`kotlin-stdlib`, `kotlin-reflect`) | 2.0+ (JDK 21 호환) | 3.2 |
| 프레임워크 | `spring-boot-starter-web` | 3.3+ | 3.2 |
| 프레임워크 | `spring-boot-starter-actuator` | 3.3+ | 3.2 (모니터링 16.6) |
| 프레임워크 | `spring-boot-starter-validation` | 3.3+ | 3.2 |
| 프레임워크 | `spring-boot-starter-security` | 3.3+ | 19장 |
| 프레임워크 | `spring-boot-starter-data-jdbc` 또는 jOOQ 자체 | 3.3+ | 3.2 |
| 프레임워크 | `spring-boot-starter-mail` | 3.3+ | 3.2 (알림) |
| 프레임워크 | `spring-boot-starter-websocket` (STOMP) | 3.3+ | 21.8 |
| 검증 | `konform` | 0.7+ | 3.2 |

### §2.2 데이터 계층 (SDD 3.2, 5장)

| 그룹 | 라이브러리 | 버전 가이드 | 출처 |
|---|---|---|---|
| ORM/쿼리 | `org.jooq:jooq` | 3.19+ (codegen 포함) | 3.2 |
| ORM/쿼리 | `org.jooq:jooq-codegen` | (build script) | 3.2 |
| 마이그레이션 | `org.flywaydb:flyway-core` | 10+ | 3.2 |
| 마이그레이션 | `org.flywaydb:flyway-database-postgresql` | 10+ | 3.2 |
| 드라이버 | `org.postgresql:postgresql` | 42.7+ | 3.2 |
| 캐시 | `io.lettuce:lettuce-core` | 6.3+ | 3.2 |

### §2.3 인증 / 보안 (SDD 19장)

| 그룹 | 라이브러리 | 버전 가이드 | 출처 |
|---|---|---|---|
| OIDC | `spring-boot-starter-oauth2-client` | 3.3+ | 19장 |
| OIDC | `spring-boot-starter-oauth2-resource-server` | 3.3+ | 19장 |
| Keycloak | (별도 의존성 없음 — OIDC만 사용) | — | 19장 |
| 비밀번호 | `de.mkammerer:argon2-jvm` | 2.11+ | DEVELOPMENT §1.1 |

### §2.4 통합 / 도메인 (SDD 3.2)

| 그룹 | 라이브러리 | 버전 가이드 | 출처 |
|---|---|---|---|
| AQL 파서 | `org.antlr:antlr4-runtime` | 4.13+ | 3.2 |
| AQL 파서 | `org.antlr:antlr4` (plugin) | (build) | 3.2 |
| 마크다운 | `com.vladsch.flexmark:flexmark-all` | 0.64+ | 3.2 |
| PDF | `com.openhtmltopdf:openhtmltopdf-core` + `pdfbox` | 1.0+ | 3.2 |
| Object Storage | `io.minio:minio` | 8.5+ | 3.2 |
| API 문서 | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.5+ | 3.2 |
| LexoRank | **자체 구현** (`backend/shared/lexorank.kt`) | — | 3.2 |
| pgmq | **DB 확장** (라이브러리 아님 — SQL로 호출) | — | 3.6 |

### §2.5 테스트 (SDD 3.2, 22.7.2)

| 그룹 | 라이브러리 | 버전 가이드 | 출처 |
|---|---|---|---|
| 단위 | `junit-jupiter` | 5.10+ | 3.2 |
| 모킹 | `io.mockk:mockk` | 1.13+ | 3.2 |
| 어서션 | `org.assertj:assertj-core` | 3.25+ | (관행) |
| 통합 | `org.testcontainers:testcontainers` | 1.20+ | 3.2 |
| 통합 | `org.testcontainers:postgresql` | 1.20+ | 3.2 |
| 통합 | `org.testcontainers:junit-jupiter` | 1.20+ | 3.2 |
| 보안 | `spring-security-test` | 6.3+ | 19장 |

### §2.6 정적 분석 / 린트 (DEVELOPMENT §5)

| 그룹 | 도구 | 버전 가이드 | 출처 |
|---|---|---|---|
| 린트 | `org.jlleitschuh.gradle.ktlint` (plugin) | 12+ | DEV §5 |
| 정적 분석 | `io.gitlab.arturbosch.detekt` (plugin) | 1.23+ | DEV §5 |

## §3. 프론트엔드 (pnpm workspace)

> 패키지 매니저. **pnpm only** (DEVELOPMENT §5 — npm/yarn 금지). React 19 + TS 5 strict + noUncheckedIndexedAccess.

### §3.1 핵심 런타임 (SDD 21.2)

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| 프레임워크 | `react`, `react-dom` | 19.x | 21.2 |
| 라우팅 | `@tanstack/react-router` | 1.x (최신) | 21.2 |
| 서버 상태 | `@tanstack/react-query` | 5.x | 21.2 |
| 클라이언트 상태 | `zustand` | 5.x | 21.2 |
| 스타일 | `tailwindcss` | 4.x | 21.2 |
| 스타일 | `@tailwindcss/vite` | 4.x | 21.2 |
| UI 헤드리스 | `@radix-ui/react-dialog`, `-tooltip`, `-popover`, `-dropdown-menu`, `-select`, `-tabs`, `-toast` 등 필요한 컴포넌트별 | 1.x | 21.3 |
| UI 컴포넌트 | **shadcn/ui** (CLI로 컴포넌트 카피 — npm 의존성 아님) | — | 3.3 |
| 아이콘 | `lucide-react` | 0.4xx | 21.2 |

### §3.2 데이터 표시 (SDD 21.3)

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| 테이블 | `@tanstack/react-table` | 8.x | 21.3 |
| 가상 스크롤 | `@tanstack/react-virtual` | 3.x | 21.3 |
| 트리 뷰 | `react-arborist` | 3.x | 21.3 |

### §3.3 인터랙션 (SDD 21.3)

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| 드래그앤드롭 | `@dnd-kit/core`, `@dnd-kit/sortable`, `@dnd-kit/modifiers` | 6.x | 21.3 |
| 그리드 레이아웃 | `react-grid-layout` | 1.x | 21.3 |
| 명령 팔레트 | `cmdk` | 1.x | 21.3 |
| 단축키 | `react-hotkeys-hook` | 4.x | 21.3 |
| 토스트 | `sonner` | 1.x | 21.3 |

### §3.4 시각화 (SDD 21.3)

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| 차트 | `recharts` | 2.x | 21.3 |
| 미니 차트 | `react-sparklines` | 1.x | 21.3 |
| Gantt | **자체 SVG** (PoC 후 결정) | — | 3.3 |

### §3.5 에디터 (SDD 21.10, 3.7)

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| 에디터 코어 | `@tiptap/react`, `@tiptap/pm` | 2.x | 21.10 |
| 에디터 키트 | `@tiptap/starter-kit` | 2.x | 21.10 |
| 에디터 확장 (Phase 1) | `@tiptap/extension-link`, `-code-block` | 2.x | 21.10 |
| 에디터 확장 (Phase 2+) | `@tiptap/extension-mention`, `-image`, `-table`, `-task-list` | 2.x | 21.10 |
| Markdown 저장 | `tiptap-markdown` | 0.x | 21.10 |
| Markdown 렌더 | `react-markdown` + `remark-gfm` | 9.x / 4.x | 21.3 |
| 코드 하이라이트 | `shiki` | 1.x | 21.3 |
| 수식 (v0.5+) | `katex` | 0.16+ | 21.3 |

### §3.6 파일 (SDD 21.3)

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| 업로드 | `react-dropzone` | 14.x | 21.3 |
| PDF | `react-pdf` | 9.x | 21.3 |
| 동영상 | `video.js` (또는 native — PoC 후 결정) | 8.x | 21.3 |
| CSV | `papaparse` | 5.x | 21.3 |

### §3.7 통신 / 폼 / 인증 / i18n

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| HTTP | `ky` | 1.x | 21.2 |
| WebSocket | `@stomp/stompjs` | 7.x | 21.2 |
| WebSocket | `reconnecting-websocket` | 4.x | 21.8 |
| 폼 | `react-hook-form` | 7.x | 21.2 |
| 검증 | `zod` | 3.x | 21.2 |
| 폼 어댑터 | `@hookform/resolvers` | 3.x | 21.9 |
| 인증 | `oidc-client-ts` | 3.x (sessionStorage 모드 — §1.17) | 21.11 |
| 날짜 | `date-fns`, `date-fns-tz` | 3.x | 21.2 |
| i18n | `i18next`, `react-i18next` | 23.x / 14.x | 21.12 |

### §3.8 개발 도구

| 그룹 | 패키지 | 버전 가이드 | 출처 |
|---|---|---|---|
| 빌드 | `vite` | 6.x | 21.2 |
| 빌드 플러그인 | `@vitejs/plugin-react` | 4.x | 21.2 |
| 언어 | `typescript` | 5.x (strict + noUncheckedIndexedAccess) | 3.3 |
| 단위 테스트 | `vitest` | 2.x | 21.15 |
| 단위 테스트 | `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event` | 16.x / 6.x / 14.x | 21.15 |
| 통합 모킹 | `msw` | 2.x | 21.15 |
| E2E | `@playwright/test` | 1.4x | 21.15 |
| 접근성 | `@axe-core/playwright` 또는 `vitest-axe` | (CI) | 21.13 |
| 린트 | `eslint` + `@typescript-eslint/*` + `eslint-plugin-react-hooks` | 9.x / 8.x | DEV §5 |
| 포맷 | `prettier` | 3.x | DEV §5 |

### §3.9 모노레포 (SDD 21.5)

| 영역 | 도구 | 비고 |
|---|---|---|
| 워크스페이스 | pnpm workspace (`pnpm-workspace.yaml`) | apps/* + packages/* + features/* + tools/* |
| 패키지 (자체) | `packages/ui`, `packages/api-client`, `packages/auth`, `packages/markdown`, `packages/i18n`, `packages/utils`, `packages/design-tokens`, `packages/icons` | **외부 의존성 아님** — SDD 21.5의 내부 패키지 구조 |

## §4. 인프라 (Docker 이미지)

> 출처. SDD 16.2 (Docker Compose). 단일 호스트 (Naver Cloud Standard).

| 이미지 | 태그 | 용도 | 출처 |
|---|---|---|---|
| `nginx` | `1.27-alpine` | 리버스 프록시, TLS 종단 | 16.2 |
| `postgres` | `16` | DB + 큐(pgmq) + 검색(FTS) | 16.2, 3.5, 3.6 |
| `redis` | `7-alpine` | 캐시, 세션 | 16.2 |
| `minio/minio` | (최신 stable) | 첨부 파일 (S3 호환) | 16.2 |
| `quay.io/keycloak/keycloak` | `25` | OIDC/SAML/LDAP | 16.2, 19장 |

### §4.1 PostgreSQL 확장 (이미지 빌드 시 추가 설치)

| 확장 | 용도 | 출처 |
|---|---|---|
| `pgmq` | DB 기반 큐 (Kafka 대체) | 3.6 |
| `pg_trgm` | 한글 부분 문자열 매칭 가속 | 3.5.3 |

**노트**. `postgres:16` 공식 이미지에는 `pgmq` 미포함. PoC 시 `Dockerfile` 확장 또는 `tembo-io/pgmq` 사전 빌드 이미지 검토 필요 (별도 ADR).

## §5. 호스트 도구 (개발자 로컬)

| 도구 | 버전 | 설치 | 출처 |
|---|---|---|---|
| JDK | 21 (LTS, Temurin 권장) | sdkman 또는 brew | 3.2 |
| Gradle | (wrapper 사용 — 호스트 설치 불필요) | — | DEV §5 |
| pnpm | 9.x+ | corepack enable / brew | DEV §5 |
| Node | 22.x LTS | brew / fnm | (현재 scripts/workflow 동작 조건) |
| Docker | 27+ | Docker Desktop / colima | 16.2 |
| docker compose | v2 (Docker CLI 통합) | (Docker 포함) | 16.2 |

## §6. Phase 0 PoC 항목 ↔ 의존성 매핑

> 출처. SDD 22.6.1 — 13개 PoC 항목. 각 항목 시작 시 필요한 의존성만 점진적 추가.

| PoC 항목 | 기간 | 필요 백엔드 | 필요 프론트 | 필요 인프라 |
|---|---|---|---|---|
| 워크플로우 엔진 FSM | 3일 | 핵심 + jOOQ + Flyway + JUnit5 + Testcontainers | — | postgres:16 |
| AQL 파서 + PG 변환 | 3일 | ANTLR 4 + 핵심 + jOOQ + Testcontainers | — | postgres:16 |
| LexoRank + 1K 부하 | 1일 | 핵심 (자체 구현 — 외부 의존성 없음) | — | — |
| AuthN Provider + LDAP/SAML | 3일 | OIDC + Spring Security + Keycloak Admin | — | keycloak:25 + postgres:16 |
| pgmq 트랜잭션 일관성 | 1일 | jOOQ + pgmq SQL + Testcontainers (pgmq 이미지) | — | postgres:16 + pgmq |
| React 19 + TanStack Router | 1일 | — | 핵심 런타임 §3.1 + 빌드 §3.8 | — |
| Gantt 차트 (자체 SVG vs lib) | 2일 | — | §3.1 + Recharts (비교용) | — |
| TipTap 50종 블록 (v0.5 준비) | 2일 | — | §3.5 전체 + dnd-kit | — |
| @dnd-kit 1K 백로그 | 1일 | — | §3.1 + @dnd-kit + react-virtual | — |
| STOMP 재연결 안정성 | 1일 | spring-boot-starter-websocket | @stomp/stompjs + reconnecting-websocket | — |
| CLAUDE.md 효과성 | 2일 | (관찰) | (관찰) | — |
| Skills 트리거 정확성 | 2일 | (관찰) | (관찰) | — |
| Maxi 검토 사이클 시간 | 진행 중 | (관찰) | (관찰) | — |

## §7. 도입 절차 (앞으로)

1. **PoC 항목 결정** → `/bts <PoC 항목>` 호출
2. **`/bts-domain`** → 해당 BC의 도메인 용어 정리 (`Maxi_wiki/BTS/domain/<bc>.md`)
3. **`/bts-spec`** → 그 PoC가 검증할 가설/성공 기준 명세
4. **`/bts-plan` → 의존성 추가가 필요하면**.
   - 이 카탈로그에 있는지 확인 → 있으면 plan에 라이브러리 명시
   - 없으면 **stop & ask Maxi**. SDD에 추가 → 이 카탈로그에 추가 → 그제서야 plan 진행 (DEVELOPMENT.md §1.16)
5. **`/bts-impl`** → TDD red→green→refactor. 의존성은 `feat:` 커밋 직전 별도 `chore:` 커밋
6. **`/bts-codereview`** → §1.16 위반 자동 검출 (의존성 추가가 카탈로그에 없으면 BLOCKER)

## §8. 변경 이력

- 2026-05-19. 초안. SDD v0.5.0 — 3장/16장/19장/21장/22장에서 추출. 실제 설치는 PoC 항목 시작 시.
