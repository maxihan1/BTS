<!-- ADR: 이슈 담당자 사용자 조회/검증 — shared-kernel UserLookupPort + identity-access 실 adapter + 사용자 목록 엔드포인트 -->

# ADR — issue-assignee-user-lookup-port

**일자**. 2026-06-01
**상태**. Accepted
**관련 PR**. `backend/fr-is-03-reporter-assignee-watchers`
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

FR-IS-03 은 이슈에 **Assignee(담당자) 1명**을 지정하는 기능을 도입한다. Reporter(보고자)는 이슈를 생성한 인증 행위자 본인이라 별도 실재 검증이 불필요했으나(`reporter_id` 는 BC 격리로 FK 없이 저장, actor 를 그대로 신뢰), Assignee 는 **행위자가 아닌 다른 사용자**를 지정한다. 따라서 두 가지 cross-BC 관심사가 새로 생긴다.

1. **검증** — 지정된 assignee UUID 가 실재하는 활성 사용자인가.
2. **선택 UI** — 프론트 담당자 셀렉터가 "고를 사용자 목록"을 어디서 얻는가(현재 `GET .../whoami`=본인만 존재).

두 관심사 모두 사용자 데이터의 정본인 **identity-access BC** 소관이다. issue-tracking 에서 `users` 테이블을 직접 참조하면 BC 격리 원칙(CLAUDE.md §핵심 패턴)을 위반한다.

### 고려한 옵션 (검증)

- **옵션 A — 검증 없이 UUID 저장(trust).** 존재하지 않는 사용자가 assignee 로 저장될 수 있음. 데이터 무결성 책임 모호.
- **옵션 B — identity-access `users` 직접 조회.** BC 경계 침범. Gradle 모듈 의존 역전.
- **옵션 C — shared-kernel 포트 + provider 구현(WorkflowTransitionPort 선례).** ✅ 채택.

## 결정

### 1. 검증 — `UserLookupPort` (shared-kernel 정의, identity-access 구현)

기존 `WorkflowTransitionPort`(shared-kernel 정의, project-workflow 가 `WorkflowTransitionAdapter` 로 구현, issue-tracking 소비)와 **동일 패턴**.

```kotlin
// backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/user/UserLookupPort.kt
package com.bts.shared.user

interface UserLookupPort {
    /** 주어진 사용자 UUID 가 실재하는지(users 테이블 행 존재). */
    fun exists(userId: java.util.UUID): Boolean
}
```

- **정의**: shared-kernel (모든 모듈이 의존, 순환 회피). UUID 사용 — shared-kernel 은 issue-tracking 의 `ActorId` VO 를 알지 못함.
- **구현**: identity-access 가 `UserLookupAdapter`(in-process `users` 조회) 제공. **현 `users` 스키마에 `is_active`/`deleted_at` 컬럼 없음**(V001 — id/username/email/display_name) → "실재" = 행 존재로 정의. 비활성/탈퇴 판정은 해당 컬럼 도입 후속 FR. identity-access 는 jOOQ 미사용이므로 기존 `UserRepository` 의 `NamedParameterJdbcTemplate` + SQL 상수 패턴으로 `SELECT EXISTS(...)`. identity-access → shared-kernel 의존 추가.
- **소비**: `IssueApplicationService` 가 `userLookupPort.exists(assignee.value)` 호출. false → `AssigneeNotFoundException` → 422.
- **issue-tracking 격리 테스트**: identity-access 가 classpath 에 없으므로 test double(MockK `@Bean` in `@TestConfiguration`) 사용. issue-tracking 에는 production `@SpringBootApplication` 이 없어(앱 조립은 별도 후속) `@Profile` 운영 stub 은 불필요 — 실 검증은 identity-access 단위/통합 + 향후 E2E 에서 확인.

### 2. 선택 UI — `GET /api/v1/users` (identity-access)

- identity-access 웹 계층에 활성 사용자 목록/검색 엔드포인트 추가. 응답: `{ id, username, displayName, email }`.
- **인증 필수**(사용자 디렉터리 노출 = PII). security-engineer 검토 대상.
- 프론트 담당자 셀렉터가 이 엔드포인트를 직접 호출(프론트 → identity-access). issue-tracking 응답은 `assigneeId`(UUID)만 노출하고, 프론트가 사용자 목록으로 id→이름 매핑. issue-tracking 이 사용자 이름을 알 필요 없음(BC 격리 보존).

## BC 격리 예외 명시

본 PR 은 **issue-tracking + identity-access 2개 BC** 를 건드린다("한 PR = 한 BC" 의 정당한 예외). 사유.
- assignee 기능의 완결(저장+검증+선택 UI)에는 사용자 데이터 정본인 identity-access 의 read 노출이 필수.
- 별도 PR 분리 시 머지 순서 의존 + 프론트 셀렉터가 빈 목록으로 동작 불가.
- 선례: PR #13(옵션 C, frontend + same-BC view layer), PR #36(IssueTypeUsagePort cross-BC SPI).

## 결과

### 긍정
- **BC 경계 보존** — issue-tracking 이 identity-access 내부 구조를 모름. shared-kernel interface 만 의존.
- **실 검증** — 운영에 stub 잔존 없음(identity-access 가 실 adapter 제공).
- **기존 패턴 일관** — WorkflowTransitionPort 와 동일 shared-kernel port-adapter.

### 부정 / 위험
- **2-BC PR** — 리뷰 범위 증가. wave 분해로 완화(eng-review 에서 PR 분할 여부 점검).
- **사용자 목록 PII** — `GET /api/v1/users` 인증 가드 필수. security-engineer 검토.
- **격리 테스트 test double** — issue-tracking 통합 테스트가 실 adapter 대신 스텁 → 실 검증 경로는 전체 앱 컨텍스트/E2E 에서 확인.

## 관련

- `docs/adr/2026-05-22-issue-permission-resolver-port.md` — port-adapter 원형
- `backend/modules/shared-kernel/.../WorkflowTransitionPort.kt` — shared-kernel cross-BC 포트 선례
- `docs/plan/product/issue-tracking.md §2.1.3` — FR-IS-03
- `docs/plans/2026-06-01-fr-is-03-reporter-assignee-watchers.md` §도메인 정리
