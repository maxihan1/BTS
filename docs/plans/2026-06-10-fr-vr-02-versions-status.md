# FR-VR-02 — 버전 상태 (Unreleased/Released/Archived)

> slug: fr-vr-02-versions-status
> type: feature
> agent: backend-engineer (주력) + db-engineer + frontend-engineer + qa-engineer
> 생성: 2026-06-10

## Brief

FR-VR-02 — 버전 상태 (Unreleased/Released/Archived). issue-tracking BC, §3.2.2, 우선순위 필수.
선행 FR-VR-01(버전 생성, versions 테이블 + Version Aggregate, PR #67/#68) 완료.

명세 D단계 (product/issue-tracking.md §3.2.2):
- D1. 도메인
- D2. 명세 — 상태 전이 규칙
- D3. 데이터 모델 — versions.status
- D4. 백엔드 — 상태 전이 API + 가드
- D5. 백엔드 테스트
- D6. 프론트 UI
- D7. E2E

classify: E2E/UI 키워드로 qa 오판 → 풀스택 feature로 교정 (backend-engineer 주력).

## 도메인 정리

- **BC**: issue-tracking (단일 BC, 격리 유지)
- **영향 엔티티**: Version Aggregate (FR-VR-01 기존) — `status`/`releasedAt` 필드 추가, 전이 메서드 신설
- **새 도메인 개념**: `VersionStatus` enum (UNRELEASED/RELEASED/ARCHIVED) + 전이 동사 4종(release/unrelease/archive/unarchive)
- **권한**: `VersionPermission.UPDATE` 재사용 (새 enum 없음, enum KDoc이 이미 "released 등" 예고)
- **actorId**: `SYSTEM_ACTOR_UUID` placeholder 유지 (FR-PM-03 패턴, Component/Version 컨트롤러 동일)

### 전이 규칙 (Maxi 결정 2026-06-10 — Jira 정석)

```
UNRELEASED ──release──▶ RELEASED      (released_at = now())
UNRELEASED ◀─unrelease─ RELEASED      (released_at = null)
UNRELEASED ──archive──▶ ARCHIVED      (released_at 유지)
RELEASED   ──archive──▶ ARCHIVED      (released_at 유지)
ARCHIVED   ─unarchive─▶ UNRELEASED    (released_at = null)
```
- ARCHIVED → RELEASED 직행 없음. self-transition 거부(409).
- ARCHIVED는 읽기 전용 — rename/changeDescription/changeDates/delete 거부(409). unarchive만 허용.
- released_at 시각은 Clock 주입(결정론적). 기존 softDelete의 Instant.now()는 본 FR 범위 밖 — 미변경.

### 데이터 모델 (SDD §05 명세 준수)

- V016 마이그레이션 — `versions.status VARCHAR(20) NOT NULL DEFAULT 'UNRELEASED'` (CHECK IN 3종)
  + `versions.released_at TIMESTAMPTZ NULL`. init_codegen.sql 미러 필수.

### API (D5 — spec에서 형식 확정)

- `PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/status` — body `{ "status": "RELEASED" }`.
  FR-VR-01 `/dates` 서브리소스 패턴 동형.

### 기존 결정 충돌 / 관련 ADR

- 기존 결정 충돌: 없음 (FR-VR-01 deferral ADR D5가 이 작업을 예고).
- 관련 ADR: [docs/adr/2026-06-10-version-status-and-transitions.md](../adr/2026-06-10-version-status-and-transitions.md) (생성됨)
- 선행 ADR: docs/adr/2026-06-03-version-model-and-permission-deferral.md (D5에서 이연)
- glossary 추가 대기: "버전 상태"(VersionStatus) — Maxi 승인 후 Obsidian 반영 (게이트 1에서 확인)

## 스펙

전체 스펙. [docs/specs/2026-06-10-fr-vr-02-versions-status.md](../specs/2026-06-10-fr-vr-02-versions-status.md)

핵심 시나리오 요약.
- 버전 상태 UNRELEASED/RELEASED/ARCHIVED + 전이 5종(release/unrelease/archive/unarchive), self·그래프외 전이는 409.
- `PATCH .../versions/{id}/status` body `{status}` — VersionPermission.UPDATE 재사용, released_at 자동(Clock).
- ARCHIVED는 읽기 전용(rename/changeDates/delete 409), unarchive만 허용. VersionResponse에 status/releasedAt 추가.

## Brainstorming Check

✅ 통과 (self-review 1회, 치명적 누락 없음). plan 보강점 3건.
- VersionResponse 확장의 Zod/MSW 전수 파급 → grep 검증 task.
- ARCHIVED 읽기전용은 도메인에서 강제(softDelete 가드 포함).
- V016 번호 머지 직전 재확인.

## Plan

> 경로 약어. IT = `backend/modules/issue-tracking/src`, FE = `apps/web/src`.

### Task 1. 도메인 — VersionStatus enum + 전이 메서드 + ARCHIVED 가드

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/version/domain/VersionStatus.kt`, `IT/main/kotlin/com/bts/issue/version/domain/Version.kt`, `IT/main/kotlin/com/bts/issue/version/domain/VersionExceptions.kt`, `IT/test/kotlin/com/bts/issue/version/domain/VersionTest.kt`]
- depends-on: []

**RED**: `VersionTest.kt` 에 전이 테스트 추가.
```kotlin
@Test fun `release transitions UNRELEASED to RELEASED and sets releasedAt`() { ... }   // Clock 고정
@Test fun `unrelease transitions RELEASED to UNRELEASED and clears releasedAt`() { ... }
@Test fun `archive keeps releasedAt when from RELEASED`() { ... }
@Test fun `archive from UNRELEASED keeps releasedAt null`() { ... }
@Test fun `unarchive transitions ARCHIVED to UNRELEASED and clears releasedAt`() { ... }
@Test fun `release on RELEASED throws VersionTransitionNotAllowedException`() { ... }   // self
@Test fun `release on ARCHIVED throws (graph-absent)`() { ... }
@Test fun `rename on ARCHIVED throws VersionTransitionNotAllowedException`() { ... }
@Test fun `changeDates on ARCHIVED throws`() { ... }
@Test fun `softDelete on ARCHIVED throws`() { ... }
@Test fun `create starts as UNRELEASED with releasedAt null`() { ... }
```
실패 예상: `VersionStatus` 미존재, `release/unrelease/archive/unarchive` 메서드 없음.

**GREEN**:
- `VersionStatus.kt`(신규) — `enum class VersionStatus { UNRELEASED, RELEASED, ARCHIVED }` + L1 한글 주석.
- `Version.kt` — `status: VersionStatus`(기본 UNRELEASED), `releasedAt: Instant?` 필드 추가. `create()` 기본값 status=UNRELEASED/releasedAt=null. 전이 메서드 4종:
  - `release(now: Instant)`: status==UNRELEASED 아니면 throw. copy(status=RELEASED, releasedAt=now).
  - `unrelease()`: status==RELEASED 아니면 throw. copy(status=UNRELEASED, releasedAt=null).
  - `archive()`: status in (UNRELEASED, RELEASED) 아니면 throw. copy(status=ARCHIVED) — releasedAt 유지.
  - `unarchive()`: status==ARCHIVED 아니면 throw. copy(status=UNRELEASED, releasedAt=null).
  - `rename/changeDescription/changeDates`: 진입부에 `requireNotArchived()` 가드.
  - `softDelete()`: 기존 `check(deletedAt==null)` 다음에 ARCHIVED 거부 가드 추가(기존 Instant.now()는 미변경).
  - 거부는 `VersionTransitionNotAllowedException` 던짐(IllegalState 아님).
- `VersionExceptions.kt` — `VersionTransitionNotAllowedException(message)` 를 `VersionDomainException` sealed 서브클래스로 추가(같은 파일).

**REFACTOR**: 전이 가능 여부를 private 헬퍼/when으로 정리. released_at 불변식 KDoc 명시.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*VersionTest"`

---

### Task 2. DB — V016 status/released_at 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`IT/main/resources/db/migration/issue-tracking/V016__version_status.sql`, `IT/main/resources/db/codegen/init_codegen.sql`, `IT/test/kotlin/com/bts/issue/version/migration/VersionsMigrationTest.kt`]
- depends-on: []

**RED**: `VersionsMigrationTest.kt` 에 컬럼/제약/기본값 검증 추가.
```kotlin
@Test fun `versions has status column with default UNRELEASED`() { ... }
@Test fun `versions has released_at nullable timestamptz`() { ... }
@Test fun `ck_versions_status rejects invalid status`() { ... }   // INSERT 'FOO' → 위반
```
실패 예상: status/released_at 컬럼 없음.

**GREEN**:
- `V016__version_status.sql`(신규) — spec §데이터 모델 DDL(ADD COLUMN status NOT NULL DEFAULT 'UNRELEASED' + released_at + CHECK ck_versions_status). L1 한글 주석 + COMMENT ON COLUMN.
- `init_codegen.sql` — versions 테이블 정의에 동일 컬럼 2개 + CHECK 미러(메모리 jooq-init-codegen-mirror).
- 머지 직전 V016 번호 재확인(메모리 migration-vnumber-concurrent-branch-collision).

**REFACTOR**: COMMENT 문구 정리.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*VersionsMigrationTest"` (jOOQ 재생성 포함 빌드).

---

### Task 3. 영속 + 서비스 — Repository status/released_at + changeStatus + ARCHIVED 거부

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/version/repository/VersionRepository.kt`, `IT/test/kotlin/com/bts/issue/version/repository/VersionRepositoryTest.kt`, `IT/main/kotlin/com/bts/issue/version/application/VersionApplicationService.kt`, `IT/test/kotlin/com/bts/issue/version/application/VersionApplicationServiceTest.kt`]
- depends-on: [1, 2]

**RED**:
- `VersionRepositoryTest.kt` — insert/update가 status/released_at 왕복(round-trip) 보존, findById/findByProject가 두 필드 매핑.
- `VersionApplicationServiceTest.kt` — `changeStatus(actor, project, id, RELEASED)` 호출 시 도메인 전이 경유 + repo.update. 불허 전이 시 `VersionTransitionNotAllowedException`. ARCHIVED 버전 update/changeDates/delete 시 예외 전파. Clock 고정으로 releasedAt 검증.

**GREEN**:
- `VersionRepository.kt` — insert/update SQL에 status/released_at 컬럼 추가, record→domain 매핑에 두 필드 추가(jOOQ 생성 컬럼 사용).
- `VersionApplicationService.kt` — `changeStatus(actorId, projectIdOrKey, versionId, target: VersionStatus): Version` 추가. 흐름: resolveProject → assertPermission(UPDATE) → findActiveVersion → 도메인 전이 메서드(target별 release/unrelease/archive/unarchive, Clock 주입) → repo.update. `Clock` 생성자 주입(기본 `Clock.systemUTC()` 빈). update/changeDates/delete는 도메인 가드가 ARCHIVED를 거부하므로 별도 코드 불필요(예외만 전파 — 단 delete는 findActiveVersion 후 도메인 softDelete 경유로 변경하거나 status 사전 체크).

**REFACTOR**: target→전이 메서드 매핑을 private when 헬퍼로. Clock 빈은 기존 설정 재사용 확인.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*VersionRepositoryTest" --tests "*VersionApplicationServiceTest"`

---

### Task 4. 웹 — PATCH /{id}/status + DTO + Handler 409 + VersionResponse 확장

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/version/web/VersionController.kt`, `IT/main/kotlin/com/bts/issue/version/web/dto/ChangeVersionStatusRequest.kt`, `IT/main/kotlin/com/bts/issue/version/web/dto/VersionResponse.kt`, `IT/main/kotlin/com/bts/issue/version/web/VersionExceptionHandler.kt`, `IT/main/kotlin/com/bts/issue/version/web/VersionErrorCodes.kt`, `IT/test/kotlin/com/bts/issue/version/web/VersionControllerIntegrationTest.kt`]
- depends-on: [3]

**RED**: `VersionControllerIntegrationTest.kt` —
```
PATCH /status RELEASED → 200, body.status=RELEASED, releasedAt!=null   (S1)
PATCH /status UNRELEASED (from RELEASED) → 200, releasedAt=null         (S2)
PATCH /status ARCHIVED → 200                                           (S3)
PATCH /status RELEASED (from ARCHIVED) → 409 VERSION_TRANSITION_NOT_ALLOWED  (S5)
PATCH /{id} (rename) on ARCHIVED → 409                                 (S6)
PATCH /status "FOO" → 400 VALIDATION_FAILED                            (EC3)
GET 목록/단건 → status/releasedAt 포함                                  (S7)
```

**GREEN**:
- `ChangeVersionStatusRequest.kt`(신규) — `data class ChangeVersionStatusRequest(@field:NotNull val status: VersionStatus)`. L1 한글 주석.
- `VersionResponse.kt` — `status: VersionStatus`, `releasedAt: Instant?` 필드 추가 + `from()` 매핑.
- `VersionController.kt` — `@PatchMapping("/{id}/status")` changeStatus 핸들러(SYSTEM_ACTOR_UUID placeholder 유지).
- `VersionErrorCodes.kt` — `VERSION_TRANSITION_NOT_ALLOWED = "VERSION_TRANSITION_NOT_ALLOWED"` 추가.
- `VersionExceptionHandler.kt` — `@ExceptionHandler(VersionTransitionNotAllowedException)` → 409 매핑.

**REFACTOR**: KDoc 엔드포인트 표 갱신.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*VersionControllerIntegrationTest"` + 모듈 전체 `:backend:modules:issue-tracking:test` + `ktlintCheck detekt`.

---

### Task 5. 프론트 UI — 상태 뱃지 + 전이 버튼 + ARCHIVED 비활성화

**메타**.
- agent: `frontend-engineer`
- files: [`FE/api/versions.types.ts`, `FE/api/versions.ts`, `FE/api/versions.test.ts`, `FE/hooks/use-versions.ts`, `FE/hooks/__tests__/use-versions.test.tsx`, `FE/components/version/VersionRow.tsx`, `FE/components/version/VersionRow.test.tsx`, `FE/components/version/VersionList.tsx`, `FE/i18n/version-labels.ts`, `FE/i18n/version-labels.test.ts`, `FE/mocks/version-handlers.ts`, `FE/mocks/version-handlers.test.ts`]
- depends-on: [4]

**RED**: VersionRow.test — 상태 뱃지 렌더(UNRELEASED/RELEASED/ARCHIVED), 전이 버튼 노출(상태별), ARCHIVED 행은 수정/삭제 비활성화. use-versions.test — useChangeVersionStatus 훅 mutation. versions.test — changeStatus api PATCH /status 호출.

**GREEN**:
- `versions.types.ts` — Zod 스키마에 `status: z.enum(['UNRELEASED','RELEASED','ARCHIVED'])`, `releasedAt: z.string().nullable()` 추가(backend VersionResponse와 정확히 일치 — 메모리 frontend-zod-backend-dto-contract-gap, grep 검증). `ChangeVersionStatusRequest` 타입.
- `versions.ts` — `changeVersionStatus(projectKey, id, status)` PATCH 호출(CSRF 수동 — 메모리 frontend-api-convention-per-bc, 같은 BC 선례 grep).
- `use-versions.ts` — `useChangeVersionStatus` mutation(invalidate-only refetch — 메모리 mutation-setquerydata-partial-response-flicker).
- `VersionRow.tsx` — 상태 뱃지 + 전이 액션 버튼(현재 상태에서 가능한 전이만), ARCHIVED면 수정/삭제 disabled.
- `version-labels.ts` — 상태/전이 한글 라벨.
- `version-handlers.ts` — MSW status 전이 핸들러(stateful, 브라우저 시드 가능 — 메모리 msw-derived-behavior-shared-store-e2e). 기존 핸들러 응답에 status/releasedAt 추가.

**REFACTOR**: 전이 버튼 매핑을 상태→액션 맵으로.

**검증**: `cd apps/web && pnpm typecheck && pnpm test -- version && pnpm lint`

---

### Task 6. E2E — 버전 상태 전이 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/version-status.spec.ts`, `FE/mocks/version-handlers.ts`]
- depends-on: [5]

**RED/GREEN**: `version-status.spec.ts`(신규) — 릴리스→보관→보관해제 happy path + 불허 전이 거부 + ARCHIVED 행 수정/삭제 비활성화 확인. MSW 상태 store 브라우저 시드(메모리 e2e-msw-scenario-toggle-localstorage-flag, worktree-stale-base-rebase-and-e2e-msw-traps). 행 컨테이너 한정 셀렉터(메모리 playwright-getbyrole-exact-strict-mode). 기존 version-management E2E 회귀 동반 실행(메모리 ui-pr-defer-e2e-regression-latent).

**검증**: `cd apps/web && pnpm test:e2e -- version`

---

## Plan 메타

- task 수: 6
- 예상 wave: 5 (W1: T1+T2 병렬 / W2: T3 / W3: T4 / W4: T5 / W5: T6). 백엔드 레이어 의존성으로 대부분 직렬.
- TDD 강제: yes (RED→GREEN→REFACTOR, test 커밋 선행)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산.
- 추가 검증: ktlint/detekt(백엔드), typecheck/vitest/playwright(프론트).
- BC: issue-tracking 단일. permission enum/seed 변경 없음(verify-master-plan 카운트 영향 없음).

## 리뷰 결과 (← /bts-review-plan 채움)
