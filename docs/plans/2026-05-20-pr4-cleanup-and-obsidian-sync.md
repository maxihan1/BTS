# chore 정리 묶음 — PR #4 잔여 + CONTRIBUTING + CLAUDE.md 설명 룰

> slug. pr4-cleanup-and-obsidian-sync
> type. chore (classify 수동 override — 원본은 `auth` 였음)
> agent. backend-engineer (대표, T1 은 security-engineer)
> primary BC. identity-access
> 생성. 2026-05-20

## Brief

사용자 원문. "chroe 정리 묶음 - pr #4 잔여 + obsidian 동기화 + contributing" + 추가 "CLAUDE.md 에 한국어 쉬운 설명 지침 추가".

체크포인트 (`20260520-185752-stored-password-credential-shipped.md`) 의 잔여 작업 #4 단일 묶음. **두 번째 wave 병렬 dispatch dogfood**. Obsidian 3 task 는 단방향 룰 때문에 본 PR 범위 밖 (별도 자동화 PR 로 분리).

**최종 PR 범위** (5 task).

| # | task | files | agent | TDD |
|---|---|---|---|---|
| T1 | escapeForLdapFilter 공백 처리 제거 (CONCERN-3) | LdapProvider.kt + Test | security-engineer | yes |
| T2 | INSERT...RETURNING + UPSERT 패턴 — ExternalAccountRepository | ExternalAccountRepository.kt + Test | backend-engineer | yes |
| T3 | classify-task slug 한국어 50자 컷 | classify-task.ts + .test.ts | backend-engineer | yes |
| T4 | CONTRIBUTING.md 신규 (Testcontainers Docker Desktop) | CONTRIBUTING.md | backend-engineer | no |
| T5 | CLAUDE.md 사용자 커뮤니케이션 스타일 섹션 추가 | CLAUDE.md | backend-engineer | no |

**분류 수동 override 이유**. classify-task 가 LDAP / INSERT 키워드로 `type=auth` 단정 + 한국어 slug 미컷. 두 가지 모두 본 PR 의 task 3 / task 1~2 가 해결할 대상이라 self-referential. 게이트 1 전 사용자 결정으로 `type=chore` + ASCII slug 강제.

**Obsidian 3 task 분리 결정**. `Maxi_wiki/BTS/_index/glossary/domain/identity-access` 갱신은 단방향 동기화 룰 (Obsidian → Repo 없음) 위반 위험. 별도 PR 로 `sync-obsidian.ts` 자동화 확장.

## 도메인 정리 (← /bts-domain 채움)

**SKIPPED — Fast-track**. `bts-domain` SKILL.md 명시: `type ∈ {bugfix, chore}` 전체 스킵.

T1, T2 의 LDAP/Repository 영역은 identity-access BC 내부. 신규 도메인 용어 없음. T3~T5 는 비-BC 작업.

## 스펙 (← /bts-spec Phase A 채움)

**SKIPPED — Fast-track**. `bts-spec` SKILL.md 명시: `type ∈ {bugfix, chore}` 전체 스킵.

각 task 의 acceptance criteria 는 Plan 섹션 내 RED phase 기술에 흡수.

## Brainstorming Check (← /bts-spec Phase B 채움)

스킵 (Phase A 스킵에 종속).

## Plan

### Task 1. escapeForLdapFilter 공백 처리 제거 (CONCERN-3)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapProvider.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/LdapProviderUnitTest.kt`]
- depends-on: []

**컨텍스트**. 현재 `LdapProvider.escapeForLdapFilter` (라인 199~205) 가 RFC 4515 외 공백을 `\\00` 으로 escape. 합법 사용자명 (예. "John Doe") 이 LDAP 검색 필터에서 매칭 실패. 보안 영향 없음 (false-positive).

**RED**.
- 파일. `LdapProviderUnitTest.kt`
- 테스트.
  ```kotlin
  @Test
  fun `escapeForLdapFilter 는 공백을 escape 하지 않는다`() {
      // given: 공백 포함 username (RFC 4515 외)
      val input = "John Doe"

      // when: 필터 escape
      val escaped = LdapProvider.escapeForLdapFilterForTest(input)  // visible-for-test or 직접 호출

      // then: 공백은 그대로 보존
      assertThat(escaped).isEqualTo("John Doe")
  }
  ```
- 실패 메시지 (예상). `Expected "John Doe" but was "John\\00Doe"`.
- 참고. 함수가 `private` 이므로 visible-for-test alias 추가 또는 reflection. **권장**. `internal` 로 가시성 완화 (Kotlin module-private) + 회귀 가드 테스트 직접 호출.

**GREEN**.
- 파일. `LdapProvider.kt` 라인 205 한 줄 삭제.
- diff.
  ```diff
       .replace(")", "\\29")
  -    .replace(" ", "\\00")
   }
  ```
- 동시에 함수 가시성 `private` → `internal` (테스트 직접 호출 위해). 또는 internal 별도 wrapper 추가.

**REFACTOR**.
- 함수 KDoc 갱신. "RFC 4515 규정 문자: \ * ( ) \0" 기존 라인을 유지하고, 공백 제외 사유 1줄 추가 (false-positive 회피).
- ADR 갱신 없음 (보안 영향 없는 fix). PR 본문에 CONCERN-3 해소 명시.

**검증**.
- `./gradlew :backend:identity-access:test --tests "*LdapProviderUnitTest*"` 통과.
- 통합 테스트 (`LdapProviderIntegrationTest`) 회귀 없음 — 기존 OpenLDAP Testcontainers 시나리오가 공백 username 미사용이라 영향 없을 예상.

---

### Task 2. INSERT...RETURNING + UPSERT 패턴 — ExternalAccountRepository

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/ExternalAccountRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/ExternalAccountRepositoryTest.kt`]
- depends-on: []

**컨텍스트**. PR #6 의 `StoredPasswordCredentialRepository` 가 단일 SQL UPSERT (`ON CONFLICT DO UPDATE`) + `INSERT ... RETURNING` + `getObject(UUID)` 패턴으로 SAVE-2/3 부채 선반영. 본 task 는 PR #4 의 `ExternalAccountRepository` 본체에 동일 패턴 적용.

**현재 상태 확인**. `provisionUser`, `updateLastLoginAt`, `incrementFailedAttempts`, `markLockedUntil` 메서드들의 현재 구현 (SELECT-then-UPDATE 또는 다중 statement) 검토 후 단일 SQL 패턴 마이그레이션 가능 여부 판정 — implementer 가 RED 작성 전 진행.

**RED**.
- 파일. `ExternalAccountRepositoryTest.kt`
- 테스트. (이미 통과 중인 회귀 가드를 강화. 새 시나리오는 동시성 회귀)
  ```kotlin
  @Test
  fun `provisionUser 는 단일 SQL 로 row 를 반환한다 (회귀 가드)`() {
      // given: provider 가 등록된 상태
      val providerId = setupProvider()

      // when: provisionUser 호출
      val account = repo.provisionUser(providerId, "uid=alice,...", "alice@x", "Alice", null, emptyList())

      // then: id 가 UUID 타입 + 조회 가능
      assertThat(account.id).isInstanceOf(UUID::class.java)
      assertThat(repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,...")).isNotNull
  }

  @Test
  fun `provisionUser 두 번 호출 시 같은 row 반환 (UPSERT)`() {
      // given: 한 번 provisioned 상태
      val providerId = setupProvider()
      val first = repo.provisionUser(providerId, "uid=alice,...", "alice@x", "Alice", null, emptyList())

      // when: 같은 externalSubject 로 재호출
      val second = repo.provisionUser(providerId, "uid=alice,...", "alice@x", "Alice", null, emptyList())

      // then: 같은 id (UPSERT 동작)
      assertThat(second.id).isEqualTo(first.id)
  }
  ```
- 실패 예상. 현재 구현이 SELECT-then-INSERT 분기일 경우 두 번째 테스트가 INSERT 충돌 또는 다른 ID 반환으로 실패.

**GREEN**.
- 파일. `ExternalAccountRepository.kt`
- 변경. 다음 메서드들을 단일 SQL UPSERT/INSERT...RETURNING 패턴으로 마이그레이션.
  - `provisionUser` — `INSERT ... ON CONFLICT (provider_id, external_subject) DO UPDATE SET ... RETURNING *`
  - `updateLastLoginAt` — 이미 UPDATE 단일이면 변경 없음
  - `incrementFailedAttempts` — `UPDATE ... SET failed_attempts = failed_attempts + 1 ... RETURNING ...` 단일 SQL
  - `markLockedUntil` — UPDATE 단일 유지
- UUID 추출. `rs.getObject("id", UUID::class.java)` 사용 (NEVER `UUID.fromString(rs.getString(...))`).

**REFACTOR**.
- SQL 상수 추출 (`companion object` 의 `SQL_PROVISION`, `SQL_INCREMENT_ATTEMPTS` 등).
- ResultSet → ExternalAccount 매핑 함수 1개로 통합.

**검증**.
- `./gradlew :backend:identity-access:test --tests "*ExternalAccountRepositoryTest*"` 통과 (기존 + 신규 회귀 가드).
- `LdapProviderIntegrationTest` 도 그대로 통과 (Repository 본체 변경 → Integration 시나리오 회귀 없는지 확인).

---

### Task 3. classify-task slug 한국어 50자 컷

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/classify-task.ts`, `scripts/workflow/classify-task.test.ts`]
- depends-on: []

**컨텍스트**. 본 PR 의 분류 시점에 한국어 + 영어 잡종 slug 가 66자로 생성됨. branch/worktree 경로명에 부적합. ASCII slug 강제 + 한국어 입력 시 50자 컷 + 음절 경계 처리.

**RED**.
- 파일. `classify-task.test.ts`
- 테스트.
  ```ts
  it('한국어 100자 입력은 50자 ASCII slug 로 컷팅된다', () => {
      const title = 'chore 정리 묶음 — PR #4 잔여 (escapeForLdapFilter 공백, INSERT...RETURNING, classify-task slug 50자컷) + Obsidian 동기화';
      const { slug } = classify({ title });
      expect(slug.length).toBeLessThanOrEqual(50);
      expect(slug).toMatch(/^[a-z0-9-]+$/);  // ASCII only
  });

  it('한국어 음절 중간에서 컷팅하지 않는다 (UTF-16 surrogate 안전)', () => {
      const title = '가나다라마바사아자차카타파하';  // 13자 한국어
      const { slug } = classify({ title });
      // slug 가 한국어를 포함하지 않거나, 포함하더라도 음절 경계에서 컷
      expect(slug).not.toMatch(/[\uD800-\uDFFF]/);  // lone surrogate 금지
  });
  ```
- 실패 예상. 현재 구현이 한국어를 그대로 slug 에 포함 + 길이 cap 없음.

**GREEN**.
- 파일. `classify-task.ts`
- 변경. slug 생성 로직.
  1. 한국어 → 로마자 변환 시도 안 함 (의미 손실). 대신 ASCII 단어만 추출.
  2. ASCII 단어가 부족하면 type prefix + counter (예. `chore-cleanup-1`) 또는 short hash.
  3. 최종 slug 길이 50자 cap.
- 알고리즘 안.
  ```ts
  const ASCII_SLUG_MAX = 50;
  function buildSlug(title: string): string {
    const ascii = title
      .toLowerCase()
      .replace(/[^a-z0-9\s-]/g, ' ')   // 비-ASCII 제거 (한국어 포함)
      .trim()
      .split(/\s+/)
      .filter(Boolean)
      .join('-');
    if (ascii.length === 0) {
      // 한국어 only 입력 → fallback
      return `task-${shortHash(title)}`;
    }
    return ascii.slice(0, ASCII_SLUG_MAX).replace(/-+$/, '');
  }
  ```

**REFACTOR**.
- `ASCII_SLUG_MAX` 상수 export.
- `shortHash` 헬퍼 함수 분리 (crypto.createHash('sha1') → 6자).

**검증**.
- `npx tsx --test scripts/workflow/classify-task.test.ts` 통과.
- 기존 classify-task 통합 시나리오 (PR #1 의 7건 회귀 테스트) 회귀 없음.

---

### Task 4. CONTRIBUTING.md 신규 — Testcontainers Docker Desktop 안내

**메타**.
- agent: `backend-engineer`
- files: [`CONTRIBUTING.md`]
- depends-on: []
- **TDD 비대상** (단순 문서 생성).

**컨텍스트**. PR #4 Testcontainers OpenLDAP 도입 후, Docker Desktop 미설치/미실행 시 통합 테스트가 cryptic 한 메시지로 실패. 신규 컨트리뷰터 (현재는 Maxi 본인만) 가 설정 가이드 필요.

**작업**.
- `CONTRIBUTING.md` 루트에 신규 작성.
- 섹션.
  1. **개발 환경 요구사항**. Node 22+, JDK 21, Docker Desktop.
  2. **Docker Desktop 설정** (macOS 기준). 설치 링크 + 메모리 4GB+ 권장.
  3. **Testcontainers 동작 확인**. `./gradlew :backend:identity-access:test` 첫 실행 시 OpenLDAP 이미지 pull → 5분 정도 걸릴 수 있음 명시.
  4. **흔한 문제 + 해결**. `Cannot connect to the Docker daemon` (Docker Desktop 미실행), 메모리 부족, 컨테이너 정리 (`docker system prune`).
  5. **참고**. SDD 문서 위치 (`docs/sdd/README.md`), CLAUDE.md 진입점.
- 분량 약 80~120 줄 (가이드성, 코드 블록 위주).

**검증**.
- `markdownlint CONTRIBUTING.md` (있으면) 통과. 없으면 manual review.
- BTS root README 가 있다면 CONTRIBUTING 링크 추가 검토 (별도 task 아님, 본 task 범위 한정).

---

### Task 5. CLAUDE.md 사용자 커뮤니케이션 스타일 섹션 추가

**메타**.
- agent: `backend-engineer`
- files: [`CLAUDE.md`]
- depends-on: []
- **TDD 비대상** (단순 문서 갱신).

**컨텍스트**. 사용자 피드백 — Claude 가 작업 내용을 설명할 때 도메인/코드 용어를 그대로 쓰거나 추상화된 어휘로 말해서 이해 어려운 경우 많음. 글로벌 `~/.claude/CLAUDE.md` 에 비슷한 룰 있으나 BTS 컨텍스트에서 잘 작동 안 함. BTS 프로젝트 CLAUDE.md 에 명시적 룰 추가.

**작업**.
- `CLAUDE.md` 의 `## 컨텍스트 효율` 섹션 다음, `## 비상시` 섹션 앞에 신규 섹션 추가.
- 신규 섹션 제목. `## 사용자 커뮤니케이션 스타일`
- 본문 (한국어 룰).
  - 사용자는 개발 전문 지식이 깊지 않음. 도메인 용어 (BC, JPA, AOP, UPSERT, slug, worktree, idempotent 등) 첫 등장 시 한 줄 비유/풀이 동반.
  - 코드 변경 설명은 **무엇을 / 왜** 둘 다 풀어 쓰기. "라인 205 삭제" 보다 "공백을 잘못 처리하는 한 줄을 지워서 정상 사용자명이 통과하게" 같은 형태.
  - 약어/영어 그대로 쓰지 말 것 — "PR" 처럼 자주 쓰는 약어는 한 번만 풀이 후 사용. 처음 보는 영어 (Testcontainers, Flyway 등) 는 도구 한 줄 설명.
  - 막힐 때 / 결정 갈림길에서는 옵션 2~3개 + 한 줄 trade-off 제시 (이미 글로벌 룰).
  - 콜론으로 문장 끝내지 않음 (글로벌 §5 재확인).
  - **이미 글로벌 `~/.claude/CLAUDE.md` §Explanation Style 과 일관**. BTS 에서는 추가로 BTS 고유 용어 (BC, worktree per 작업, wave dispatch 등) 도 풀이 대상.

**검증**.
- 섹션 추가 후 CLAUDE.md 가 여전히 200 줄 이내 (현재 약 80줄, 큰 문제 없음).
- 글로벌 CLAUDE.md 와 중복되거나 충돌하는 룰 없음.

---

## Plan 메타

- task 수. 5
- 예상 시간. task × 3~5분 = 약 15~25분 직렬, 단일 wave 5-병렬 시 약 5~7분.
- TDD 강제. T1, T2, T3 (3건). T4, T5 는 단순 문서 task — TDD 비대상.
- 병렬 dispatch. 5 task 모두 의존성/파일 겹침 없음 → **단일 wave 5-병렬 (두 번째 dogfood)**.
- 검증 도구. ktlint + detekt (backend 변경 시), vitest/node:test (TS), markdown lint (있으면).
- wave 후 controller chore commit 후보. `./gradlew :backend:identity-access:ktlintFormat` 부수 변경 (PR #6 learning #2 패턴).

## 리뷰 결과 (← /bts-review-plan 채움)

(`/bts-review-plan` 단계에서 채움)
