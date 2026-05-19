# CLAUDE.md

> **이 파일은 Claude Code가 매 세션 시작 시 자동으로 읽습니다.**
> **반드시 지켜야 하는 규칙만 담고, 상세 가이드는 Skills로 분리되어 있습니다.**

## 프로젝트 개요

**Project Atlas** — 사내 1,000명 규모 협업 워크스페이스 (Jira + Notion 대체).

- 두 개 모듈: **Atlas Issues** (이슈 트래커, 현재 개발) + **Atlas Wiki** (위키, v0.5 예정)
- 개발 모델: Maxi 1인 + Claude Code
- 배포: Naver Cloud (기본) / 온프레미스 / 하이브리드
- 자세한 비전: `docs/sdd/00-overview.md`, `docs/sdd/01-vision.md`

## 기술 스택 (한눈에)

**백엔드**
- Kotlin (JDK 21) + Spring Boot 3.3+
- PostgreSQL 16 (데이터 + FTS + pgmq 큐)
- Redis 7 (캐시), MinIO (객체)
- Keycloak (인증 백엔드)
- jOOQ (쿼리), Flyway (마이그레이션)
- ANTLR 4 (AQL 파서)

**프론트엔드**
- React 19 + TypeScript 5 (strict + noUncheckedIndexedAccess)
- Vite + pnpm 모노레포
- TanStack (Router/Query/Table/Virtual)
- Tailwind v4 + shadcn/ui + Radix UI
- TipTap (에디터, 이슈 + 위키 공통)
- React Hook Form + Zod
- @dnd-kit, Recharts, react-grid-layout

**테스트**
- 백엔드: JUnit 5 + Testcontainers + MockK
- 프론트엔드: Vitest + Testing Library
- E2E: Playwright

자세한 결정 근거: `docs/sdd/03-tech-stack.md`

## 절대 규칙 (NEVER)

다음 규칙은 어떤 경우에도 위반하지 않는다.

### 보안
1. **DB에 평문 비밀번호/토큰/API 키 저장 금지.** Argon2id 해시 (패스워드) 또는 KMS 암호화 (토큰).
2. **로그에 PII/비밀번호/토큰 출력 금지.** 마스킹 필수.
3. **SQL 문자열 결합 금지.** 항상 prepared statement 또는 jOOQ.
4. **인증 없는 엔드포인트 추가 금지.** Spring Security 필터 우회 금지.
5. **CSRF 토큰 검증 비활성화 금지.**
6. **검증되지 않은 사용자 입력으로 외부 명령 실행 금지** (RCE 방지).

### 데이터 무결성
7. **`DELETE` 쿼리는 항상 `WHERE` 조건 필수.** 소프트 삭제 우선.
8. **PostgreSQL 마이그레이션은 Flyway만.** 직접 ALTER TABLE 금지.
9. **트랜잭션 경계 명시.** `@Transactional` 누락 금지.
10. **이슈 키(`PROJ-123`)는 영구 보존.** `IssueKeyRedirect` 테이블로 옛 키 유지.

### 코드 품질
11. **`any` 타입 사용 금지** (TypeScript). 모르면 `unknown` 사용.
12. **`!!` null assertion 금지** (Kotlin/TS). 명시적 null 체크.
13. **빈 catch 블록 금지.** 최소한 로그 남김.
14. **테스트 없는 새 기능 커밋 금지.**
15. **`console.log` / `println` 디버깅 코드 커밋 금지.** 진짜 로깅은 `logger` 사용.

### 외부 의존성
16. **신규 npm/maven 의존성 추가 시 Maxi 확인 필수.** Skills에 명시된 라이브러리만 자동 사용.
17. **`localStorage`에 토큰 저장 금지.** `sessionStorage` 또는 HttpOnly Cookie.
18. **CDN에서 임의 스크립트 로드 금지.** 의존성은 npm으로.

## 코드 스타일

### Kotlin (백엔드)
- `ktlint` 표준 준수
- 데이터 클래스는 immutable (모든 필드 `val`)
- nullable은 명시적 (`?`), non-null 기본
- 함수 30줄 이내, 파일 300줄 이내
- 모든 public 함수에 KDoc

### TypeScript (프론트엔드)
- ESLint + Prettier 표준
- `interface` 우선 (`type`은 union/intersection에만)
- 컴포넌트는 named export
- 함수 30줄 이내, 컴포넌트 200줄 이내
- 모든 public 함수에 JSDoc

### 공통
- 매직 넘버 금지 (상수로 추출)
- 명확한 변수명 (`u` 대신 `user`)
- Early return 권장
- 주석은 "왜"를 설명 (코드가 "무엇"을 말함)

## 자주 쓰는 명령어

```bash
# 백엔드
./gradlew test                      # 단위 + 통합 테스트
./gradlew :backend:bootRun          # 로컬 실행
./gradlew flywayMigrate             # DB 마이그레이션
./gradlew ktlintCheck               # 린트

# 프론트엔드
pnpm dev                            # 개발 서버
pnpm test                           # Vitest
pnpm test:e2e                       # Playwright
pnpm lint                           # ESLint
pnpm typecheck                      # tsc --noEmit

# 인프라
docker-compose up -d                # 로컬 전체 환경
docker-compose -f infra/docker-compose.dev.yml up postgres redis minio

# 통합 CI 검증
pnpm verify                         # 모든 체크 (lint + typecheck + test + build)
```

## 폴더 가이드

| 폴더 | 책임 |
|---|---|
| `backend/modules/{module}/` | 바운디드 컨텍스트별 (issue-tracking, agile-planning 등) |
| `backend/modules/{module}/api/` | REST 컨트롤러 |
| `backend/modules/{module}/domain/` | 도메인 엔티티 + 비즈니스 로직 |
| `backend/modules/{module}/infrastructure/` | DB, 외부 통합 어댑터 |
| `backend/shared/` | 모든 모듈 공통 코드 |
| `apps/web/src/routes/` | TanStack Router 라우트 |
| `apps/web/src/features/` | 기능별 컴포넌트/훅/스토어 |
| `packages/ui/` | shadcn/ui 컴포넌트 |
| `packages/api-client/` | OpenAPI 자동 생성 + ky 클라이언트 |
| `infra/` | Docker Compose, Nginx, 배포 스크립트 |
| `docs/sdd/` | 설계 문서 (이 파일들과 함께 읽을 것) |
| `docs/adr/` | Architecture Decision Records |
| `.claude/skills/` | 도메인별 가이드 (자동 트리거) |
| `.claude/commands/` | 반복 워크플로우 (`/명령`으로 실행) |

## 작업 흐름

새 기능을 추가할 때:

1. **명세 확인**: `docs/sdd/` 관련 챕터 읽기
2. **Skill 확인**: `.claude/skills/atlas-backend-feature` 또는 `atlas-frontend-component` 자동 로드 확인
3. **테스트 먼저**: 실패하는 테스트 작성 (TDD)
4. **구현**: 테스트 통과시키는 최소 구현
5. **리팩토링**: 통과 상태에서 정리
6. **자가 검토**: 절대 규칙 위반 없는지
7. **Maxi 검토 요청**: PR 또는 직접 보고

새 모듈을 추가할 때:
- `/new-feature <이름>` 명령으로 스캐폴딩

코드 리뷰가 필요할 때:
- `/review-changes` 명령으로 자가 검토

## 컨텍스트 효율

당신(Claude)이 효율적으로 작동하려면:

- **한 번에 한 모듈만** 작업하기. 여러 모듈 동시 수정 시 Maxi에게 확인.
- **긴 명세는 SDD 챕터 링크로** 안내. 전체 복사 금지.
- **Skills를 활용**. SKILL.md의 트리거 조건이 맞으면 활성화됨.
- **모르겠으면 Maxi에게 물어보기.** 추측해서 구현하지 말 것.
- **수정 범위를 최소화.** 요청받은 것만, 추가 "개선" 안 함.

## 자주 하는 실수 (피할 것)

| 실수 | 대신 |
|---|---|
| 라이브러리 임의 도입 | Skills에 명시된 것만, 모르면 Maxi에게 |
| 잘 작동하는 코드를 "더 좋게" 리팩토링 | 요청받은 것만 |
| 테스트 없이 큰 변경 | 테스트 먼저 (TDD) |
| `any` 타입으로 회피 | 정확한 타입 또는 `unknown` |
| try-catch로 오류 숨기기 | 명확한 에러 핸들링 또는 propagate |
| 주석 없는 복잡한 로직 | "왜" 설명 주석 |
| commit 메시지 "fix" 하나 | Conventional Commits (`feat:`, `fix:`, `refactor:`) |
| 인증 우회로 "임시" 테스트 코드 | 테스트 환경에서 별도로 |

## 참고 문서

| 문서 | 용도 |
|---|---|
| `docs/sdd/README.md` | SDD 챕터 목차 |
| `docs/sdd/22-claude-code-env.md` | 개발 환경 전체 명세 |
| `.claude/skills/*/SKILL.md` | 도메인별 작업 가이드 |
| `.claude/commands/*.md` | Slash 명령 정의 |
| `docs/adr/` | 결정 기록 (왜 그렇게 결정했는지) |

## 비상시

- 빌드/테스트가 깨졌는데 원인을 모를 때 → `git status`, `git diff`로 확인 후 Maxi에게 보고
- 보안 의심 코드를 발견했을 때 → 즉시 Maxi에게 보고, 변경 중단
- Maxi의 지시가 위 규칙과 충돌할 때 → 충돌을 명시하고 확인 요청
- 컨텍스트가 부족해서 자신 없을 때 → 추측 말고 Maxi에게 물어보기

---

**이 파일은 프로젝트의 헌법입니다. 모든 작업의 출발점입니다.**
