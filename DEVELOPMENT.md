# DEVELOPMENT.md

> **BTS 개발 헌법 §2 — 절대 규칙 + 코드 스타일.**
> `CLAUDE.md`가 진입점, 이 파일은 위반 시 빌드/리뷰가 차단되는 19개 절대 규칙 + 스타일.
> 관련. `DATA.md` (데이터/DB 규칙), Obsidian `Maxi_wiki/BTS/learnings.md` (과거 사고 회귀 방지).

## §1. 절대 규칙 (NEVER) — 19개

다음 규칙은 어떤 경우에도 위반하지 않는다. PR 단위 코드 리뷰(`/bts-codereview`)에서 인라인 가이드로 첨부되어 자동 검증.

### §1.1 보안 (6개)

1. **DB에 평문 비밀번호/토큰/API 키 저장 금지.** 패스워드는 Argon2id, 토큰은 KMS 암호화.
2. **로그에 PII/비밀번호/토큰 출력 금지.** Pino/Logback 마스킹 필수.
3. **SQL 문자열 결합 금지.** jOOQ 또는 prepared statement만.
4. **인증 없는 엔드포인트 추가 금지.** Spring Security 필터 우회 금지.
5. **CSRF 토큰 검증 비활성화 금지.**
6. **검증되지 않은 사용자 입력으로 외부 명령 실행 금지** (RCE 방지).

### §1.2 데이터 무결성 (4개)

7. **`DELETE`는 항상 `WHERE` + 소프트 삭제 우선.** 하드 삭제는 ADR 필수.
8. **PostgreSQL 마이그레이션은 Flyway만.** 수동 ALTER TABLE 금지.
9. **트랜잭션 경계 명시.** `@Transactional` 누락 시 코드리뷰 BLOCKER.
10. **이슈 키(`PROJ-123`)는 영구 보존.** 이동/삭제 시 `IssueKeyRedirect`로 옛 키 유지.

자세히. `DATA.md`.

### §1.3 코드 품질 (6개)

11. **`any` 타입 금지** (TypeScript). 모르면 `unknown`.
12. **`!!` null assertion 금지** (Kotlin/TS). 명시적 null 체크.
13. **빈 catch 블록 금지.** 최소한 로그.
14. **테스트 없는 새 기능 커밋 금지.** TDD red→green→refactor 강제 (`/bts-impl`).
15. **`console.log` / `println` 디버깅 코드 커밋 금지.** Pino/Logback 사용.
16. **PoC / 프로토타입 / 임시 코드 금지.** 모든 작업은 완제품(production) 기준으로 작성한다. Phase 0 / Phase 1 단계 표기는 도입 시점 표시일 뿐, 작업 품질 수준이 아니다. "일단 동작만" / "나중에 리팩토링" 금지. Maxi가 "PoC 수준으로"라고 명시한 경우에만 예외. 자세히. `CLAUDE.md §작업 기준 — 완제품`.

### §1.4 외부 의존성 (3개)

17. **신규 npm/maven 의존성 추가 시 Maxi 확인 필수.** SDD/Skills에 명시된 라이브러리만 자동 사용.
18. **토큰 `localStorage` 저장 금지.** `sessionStorage` 또는 HttpOnly Cookie.
19. **CDN에서 임의 스크립트 로드 금지.** 의존성은 npm으로.

## §2. 코드 스타일

### §2.1 Kotlin (백엔드)

- `ktlint` 표준 준수
- 데이터 클래스는 immutable (모든 필드 `val`)
- nullable은 명시적 (`?`), non-null 기본
- 함수 30줄 이내, 파일 300줄 이내 (Claude 컨텍스트 효율)
- 모든 public 함수에 KDoc

```kotlin
/**
 * 이슈 키를 발급하고 IssueKeyRedirect 매핑을 생성한다.
 * @throws IssueKeyExhaustedException 프로젝트의 키 시퀀스가 한계에 도달했을 때
 */
fun issueKey(project: Project): IssueKey { ... }
```

### §2.2 TypeScript (프론트엔드)

- ESLint + Prettier 표준
- `tsconfig.json`. `strict: true` + `noUncheckedIndexedAccess: true`
- `interface` 우선 (`type`은 union/intersection 전용)
- 컴포넌트는 named export
- 함수 30줄 이내, 컴포넌트 200줄 이내
- 모든 public 함수에 JSDoc

```typescript
/**
 * 이슈 키 입력 검증. PROJ-123 형식만 통과.
 * @returns 검증된 IssueKey 또는 null
 */
export const issueKey = (input: string): IssueKey | null => { ... };
```

### §2.3 공통

- 매직 넘버 금지 (상수로 추출, 의도 명확화)
- 명확한 변수명 (`u` 대신 `user`)
- Early return 권장 (가독성)
- 주석은 **"왜"**를 설명 (코드가 "무엇"을 말함)
- **첫 줄 한국어 파일 헤더 주석** 필수 (각 새 소스 파일)

```kotlin
// 이슈 키 발급 + IssueKeyRedirect 매핑 관리 서비스
```

```typescript
// 이슈 디테일 페이지의 메인 컨테이너 컴포넌트
```

## §3. 자주 하는 실수 (피할 것)

| 실수 | 대신 |
|---|---|
| 라이브러리 임의 도입 | SDD/Skills 명시 라이브러리만 (§1.16) |
| 잘 작동하는 코드를 "더 좋게" 리팩토링 | 요청받은 것만 |
| 테스트 없이 큰 변경 | TDD 강제 (§1.14) |
| `any` 타입 회피 | `unknown` 또는 정확한 타입 |
| try-catch로 오류 숨김 | 명시적 처리 또는 propagate |
| 주석 없는 복잡한 로직 | "왜" 설명 주석 |
| 커밋 메시지 "fix" 하나 | Conventional Commits (`feat:`, `fix:`, `refactor:`, `chore:`, `test:`) |
| 인증 우회 "임시" 테스트 코드 | 테스트 환경에서 별도로 (`@TestProfile`) |
| 한 PR에 여러 BC 변경 | BC당 PR 분리 (모듈 단위 작업) |

## §4. 커밋 / PR 규칙

### Conventional Commits

| Prefix | 용도 |
|---|---|
| `feat:` | 새 기능 (TDD red→green→refactor 한 사이클 = 한 커밋) |
| `test:` | TDD red 단계 (실패 테스트) |
| `fix:` | 버그 수정 |
| `refactor:` | 동작 변경 없는 정리 |
| `chore:` | 빌드/설정/문서 |
| `docs:` | 문서만 |

### TDD 강제 커밋 패턴

```
test: <slug> red — <테스트가 검증하는 시나리오>
feat: <slug> green — <최소 구현>
refactor: <slug> — <정리 요약>
```

`/bts-impl`의 spec-compliance-verifier가 git log를 확인. `test:` 커밋이 `feat:` 커밋보다 먼저 없으면 BLOCKED.

### PR 규칙

- 한 PR = 한 BC + 한 plan
- PR 제목 = 첫 커밋 제목과 동일 (Conventional Commits)
- 라벨. `bc:<context>`, `type:<feature|fix|...>`, 필요 시 `learning:<topic>` (머지 시 learnings.md auto-append)
- Draft PR 자동 개설 (`/bts-start`이 worktree 생성 직후)

## §5. 도구 표준

| 영역 | 도구 | 비고 |
|---|---|---|
| Kotlin 빌드 | Gradle (`./gradlew`) | Kotlin DSL |
| 백엔드 린트 | ktlint | 빌드 통합 |
| 백엔드 정적 분석 | Detekt | 커스텀 룰 (트랜잭션 누락 검출) |
| 프론트 패키지 | pnpm (모노레포) | npm/yarn 금지 |
| 프론트 빌드 | Vite | |
| 프론트 린트 | ESLint + Prettier | |
| 타입 체크 | `tsc --noEmit` | strict + noUncheckedIndexedAccess |
| 백엔드 테스트 | JUnit 5 + Testcontainers + MockK | |
| 프론트 테스트 | Vitest + Testing Library | |
| E2E | Playwright | qa-engineer 담당 |

## §6. 환경 변수

- 접두사. `BTS_*` (모든 환경 변수)
- 형식. SCREAMING_SNAKE_CASE
- 접근 경로
  - 백엔드. Spring `@Value` 또는 `@ConfigurationProperties` (전역 객체)
  - 프론트. `import.meta.env.BTS_*` (Vite)
- **직접 `process.env.*` 접근 금지** — `src/lib/env.ts` 또는 백엔드 설정 객체 경유
- 비밀값은 `.env` (gitignored), 공통값은 `application.yml`

## §7. 변경 이력

- 2026-05-19. 초안. 기존 `CLAUDE.md`에서 절대 규칙 + 스타일 + 자주 하는 실수를 분리.
