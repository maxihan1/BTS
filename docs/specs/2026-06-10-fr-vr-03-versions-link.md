<!-- FR-VR-03 Affects/Fix Version 연결 기술 스펙 — 이슈↔버전 다대다, 컴포넌트(FR-CM-02) 동형 -->
# FR-VR-03 — Affects/Fix Version 연결 — 스펙

> slug: fr-vr-03-versions-link | BC: issue-tracking | 작성: 2026-06-10
> product 정본: `docs/plan/product/issue-tracking.md §3.2.3`
> 동형 선례: 이슈↔컴포넌트 연결 (FR-CM-02/03)
> 도메인 정리: `docs/plans/2026-06-10-fr-vr-03-versions-link.md §도메인 정리`

## 개요

이슈를 버전(Version)에 두 가지 의미로 다대다(N:M) 연결한다.

- **Affects Version (영향 버전)** — 이 이슈/버그가 발견된·영향을 받는 버전. 예. "이 버그는 v1.2에서 발생".
- **Fix Version (수정 버전)** — 이 이슈가 수정될·수정된 목표 버전. 예. "v1.3에서 고침".

한 이슈가 여러 affects/fix 버전을 가질 수 있고, 한 버전이 여러 이슈에 연결될 수 있다. affects와 fix는 **독립** 관계 — 같은 버전이 한 이슈의 affects이면서 fix일 수 있다(드물지만 허용).

## 사용자 시나리오 (Given-When-Then)

### S1. Fix Version 연결 (정상)
- **Given** 프로젝트 PROJ에 버전 v1.3(UNRELEASED)이 있고, 이슈 PROJ-10이 존재
- **When** 사용자가 `PATCH /api/v1/issues/PROJ-10/fix-versions { versionIds: [v1.3], expectedVersion: 0 }` 호출
- **Then** 200 OK, 응답의 `fixVersionIds`에 v1.3 포함, 이슈 version은 1로 증가

### S2. Affects Version 연결 (정상)
- **Given** 프로젝트 PROJ에 버전 v1.2(RELEASED)가 있고, 이슈 PROJ-10이 존재
- **When** `PATCH /api/v1/issues/PROJ-10/affects-versions { versionIds: [v1.2], expectedVersion: 0 }`
- **Then** 200 OK, 응답의 `affectsVersionIds`에 v1.2 포함

### S3. 전체 교체 (replace 의미)
- **Given** 이슈 PROJ-10의 fixVersions = [v1.3]
- **When** `PATCH .../fix-versions { versionIds: [v1.4], expectedVersion: 1 }`
- **Then** fixVersions = [v1.4] (v1.3 연결 해제됨). 부분 추가/삭제 아님 — 명시 목록으로 전체 교체.

### S4. 전체 해제 (빈 목록)
- **Given** 이슈 PROJ-10의 affectsVersions = [v1.2]
- **When** `PATCH .../affects-versions { versionIds: [], expectedVersion: 2 }`
- **Then** affectsVersions = [] (전부 해제), 200 OK

### S5. ARCHIVED 버전 연결 (API 허용)
- **Given** 버전 v1.0이 ARCHIVED 상태(삭제 안 됨)
- **When** `PATCH .../affects-versions { versionIds: [v1.0], expectedVersion: N }`
- **Then** 200 OK — ARCHIVED 버전도 연결 가능 (과거 보관 릴리스에서 발견된 버그 기록). 프론트 셀렉터는 ARCHIVED를 기본 숨기지만 API는 허용.

### S6. 타 프로젝트 버전 (거부)
- **Given** 이슈 PROJ-10(프로젝트 PROJ), 버전 vX는 프로젝트 OTHER 소속
- **When** `PATCH .../fix-versions { versionIds: [vX], expectedVersion: N }`
- **Then** 422 Unprocessable Entity, error code `ISSUE_LINKED_VERSION_NOT_FOUND` — 이슈 프로젝트에 속하지 않는 버전.

### S7. 소프트 삭제된 버전 (거부)
- **Given** 버전 vDel이 소프트 삭제됨(deleted_at != null)
- **When** 연결 시도
- **Then** 422 `ISSUE_LINKED_VERSION_NOT_FOUND` — findById가 deleted_at IS NULL만 조회하므로 검증 실패.

### S8. 낙관적 잠금 충돌
- **Given** 이슈 PROJ-10의 현재 version = 3
- **When** `PATCH .../fix-versions { versionIds: [...], expectedVersion: 2 }` (stale)
- **Then** 409 Conflict, error code 기존 `ISSUE_VERSION_CONFLICT` 재사용 (changeComponents 동일).

### S9. 존재하지 않는 이슈
- **When** `PATCH /api/v1/issues/NOPE-1/fix-versions ...`
- **Then** 404 Not Found (IssueNotFoundException), 권한 검사 이후 findByKey 단계.

### S10. 권한 없음
- **Given** actor가 PROJ에 대한 ISSUE UPDATE 권한 없음
- **When** 연결 시도
- **Then** 403 Forbidden — `IssuePermission.UPDATE` 재사용 (별도 권한 코드 신설 안 함).

### S11. 중복 versionId 정규화
- **When** `{ versionIds: [v1.3, v1.3], ... }`
- **Then** distinct 정규화 → [v1.3] 한 건만 저장 (도메인 assignXxxVersions에서 처리).

### S12. 이슈 단건 조회 시 노출
- **Given** PROJ-10에 affects=[v1.2], fix=[v1.3]
- **When** `GET /api/v1/issues/PROJ-10`
- **Then** 응답에 `affectsVersionIds: [v1.2]`, `fixVersionIds: [v1.3]` 포함 (목록 경로 `GET /issues`는 빈 목록 — componentIds 동일 정책).

## 기능 요구사항 (FR)

- **FR1.** `PATCH /api/v1/issues/{key}/affects-versions` — affects 버전 목록 전체 교체.
- **FR2.** `PATCH /api/v1/issues/{key}/fix-versions` — fix 버전 목록 전체 교체.
- **FR3.** 두 엔드포인트 모두 `{ versionIds: List<UUID>, expectedVersion: Long }` 바디, expectedVersion 필수(OCC).
- **FR4.** 검증 — 각 versionId가 이슈 프로젝트에 속하고 삭제되지 않았는지 확인. 위반 시 422 `ISSUE_LINKED_VERSION_NOT_FOUND`. ARCHIVED는 통과(허용).
- **FR5.** distinct 정규화 — 중복 versionId 자동 제거.
- **FR6.** 단건 조회(`GET /issues/{key}`)에 `affectsVersionIds`, `fixVersionIds` 노출. 목록 경로는 빈 목록.
- **FR7.** 권한 — `IssuePermission.UPDATE` 재사용 (신규 권한 코드 없음).
- **FR8.** 프론트 — 이슈 상세 화면에 Affects/Fix 버전 셀렉터 2종. 멀티 셀렉트. ARCHIVED 버전은 기본 숨김(이미 연결된 ARCHIVED는 표시).

## 비기능 요구사항 (NFR)

- **NFR1.** 트랜잭션 — 연결 교체 + version bump는 한 트랜잭션 (replaceXxxVersions 내 DELETE+INSERT+UPDATE version).
- **NFR2.** 조인 테이블 FK 인덱스 — 역방향 조회(버전→이슈) 위해 후미 컬럼(version_id) 단독 인덱스 (DATA.md §7, V012 패턴).
- **NFR3.** 동명 예외 회피 — issue-side 예외는 **`IssueLinkedVersionNotFoundException`** (com.bts.issue.domain), 에러코드 **`ISSUE_LINKED_VERSION_NOT_FOUND`** 로 확정. version 패키지의 `VersionNotFoundException`과 구분(메모리 duplicate-exception-name), 동시에 OCC 충돌(`IssueVersionConflictException`/`ISSUE_VERSION_CONFLICT`)의 "version" 의미 중복도 회피. IssueExceptionHandler basePackages(`com.bts.issue.adapter.inbound.rest`)가 IssueController를 커버하므로 422 매핑 정상.
- **NFR4.** init_codegen 미러 — 신규 테이블은 jOOQ 코드 생성용 `init_codegen.sql`에도 반영(메모리 jooq-init-codegen-mirror).

## API 인터페이스 (REST)

```
PATCH /api/v1/issues/{key}/affects-versions
  body: { "versionIds": ["uuid", ...], "expectedVersion": 0 }
  200 → IssueResponse (단건, affectsVersionIds/fixVersionIds 채워짐)
  403 권한 없음 | 404 이슈 없음 | 409 ISSUE_VERSION_CONFLICT | 422 ISSUE_LINKED_VERSION_NOT_FOUND

PATCH /api/v1/issues/{key}/fix-versions
  body: { "versionIds": ["uuid", ...], "expectedVersion": 0 }
  (응답·에러 동일)
```

flow (changeComponents 동형, 자동 담당자 단계만 제외):
1. assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key))
2. existing = repo.findByKey(key) ?: 404
3. normalized = existing.assignAffectsVersions(ids) / assignFixVersions(ids) — distinct
4. validateVersions(ids, existing.projectId) — versionRepository.findById(id, projectId) ?: 422
5. rows = repo.replaceAffectsVersions(key, issueId, ids, expectedVersion) / replaceFixVersions(...)
6. if rows == 0 → 409 IssueVersionConflictException
7. return repo.findByKeyWithType(key).withSingleDetail()

## 데이터 모델 변경

신규 마이그레이션 `V017__issue_version_links.sql` (V012 동형, 두 테이블).

```sql
CREATE TABLE issue_affects_versions (
    issue_id   UUID        NOT NULL REFERENCES issues(id)   ON DELETE CASCADE,
    version_id UUID        NOT NULL REFERENCES versions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, version_id)
);
CREATE INDEX idx_issue_affects_versions_version_id ON issue_affects_versions(version_id);

CREATE TABLE issue_fix_versions (
    issue_id   UUID        NOT NULL REFERENCES issues(id)   ON DELETE CASCADE,
    version_id UUID        NOT NULL REFERENCES versions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, version_id)
);
CREATE INDEX idx_issue_fix_versions_version_id ON issue_fix_versions(version_id);
```

- 소프트 삭제 없음(순수 관계 테이블, V012 정책 동일). 연결 해제 = 행 DELETE.
- ON DELETE CASCADE — prod 소프트 삭제라 발화 안 함, 하드 삭제 경로(테스트 cleanup) 고아 정리용.
- **V번호 충돌 주의** — 현재 issue-tracking 최신 V016. 머지 직전 재확인(메모리 migration-vnumber-concurrent-branch-collision). PR #106은 identity-access 모듈이라 무관.
- **init_codegen.sql 미러** 필수.

도메인 변경 — `Issue`에 신규 필드:
```kotlin
val affectsVersionIds: List<UUID> = emptyList(),
val fixVersionIds: List<UUID> = emptyList(),
// + assignAffectsVersions(ids)/clearAffectsVersions(), assignFixVersions(ids)/clearFixVersions() (componentIds 동형)
```

## 엣지 케이스

- **EC1.** 빈 목록 → 전체 해제 (S4).
- **EC2.** 중복 ID → distinct (S11).
- **EC3.** ARCHIVED 버전 → 허용 (S5).
- **EC4.** 소프트 삭제 버전 → 422 (S7).
- **EC5.** 타 프로젝트 버전 → 422 (S6).
- **EC6.** expectedVersion 누락 → 400 (@NotNull Bean validation).
- **EC7.** affects와 fix에 같은 버전 → 둘 다 허용(독립 테이블).
- **EC8.** 이미 ARCHIVED 버전이 연결된 이슈 조회 → 정상 표시(프론트도 표시, 셀렉터에서만 숨김).

## 제약 조건

- 한 PR = 한 BC (issue-tracking) — BC 격리 준수.
- 신규 권한 코드 없음(IssuePermission.UPDATE 재사용) → FR-PM 권한 시드 카운트 테스트 영향 없음.
- 생성 시점 지정 미지원 (Maxi 결정) — CreateIssueRequest 변경 없음.
- 프론트 셀렉터 — 버전 목록은 기존 `GET /api/v1/projects/{key}/versions` 재사용(있으면). 없으면 D6에서 확인.

## 측정 가능한 완료 기준

- [ ] V017 마이그레이션 + init_codegen 미러, 부팅/마이그레이션 테스트 그린
- [ ] Issue 도메인 affectsVersionIds/fixVersionIds + assign/clear 단위 테스트 (distinct 포함)
- [ ] IssueRepository replaceAffectsVersions/replaceFixVersions + 단건 조회 fetch 왕복 테스트
- [ ] IssueApplicationService changeAffectsVersions/changeFixVersions 단위 + validateVersions 422
- [ ] 통합 테스트 S1~S12 (Testcontainers)
- [ ] IssueController 2 엔드포인트 + IssueResponse 노출 + MVC 테스트
- [ ] 프론트 Affects/Fix 셀렉터 2종 + Zod 스키마 + MSW 핸들러 + 단위 테스트
- [ ] E2E happy path (연결/교체/해제)
- [ ] product 정본 D1~D7 체크 + FR 카운트 동기화 + verify-master-plan 통과

## Brainstorming Check

✅ 통과 (1회, 진짜 gap 없음). 컴포넌트 선례 대조로 점검한 항목:
- **이슈 히스토리 감사** — IssueHistory 미존재 + changeComponents도 history 미기록 → 버전 동형 불필요.
- **PDF 내보내기** — IssuePdfTemplate에 컴포넌트 필드 렌더 없음 → 버전 노출 불필요.
- **이슈 클론** — core-only (componentIds 미복사, ADR 2026-06-02-issue-clone-semantics) → affects/fix 버전도 미복사(Issue.create 기본값 emptyList 처리).
- **소프트 삭제 버전이 연결된 채 조회** — CASCADE는 하드 삭제만 발화하므로 링크 행 잔존. 컴포넌트와 동일 동작. 프론트는 버전 목록(삭제 제외)에서 이름 해소하므로 dangling id는 이름 미표시로 graceful. 별도 필터 미도입(컴포넌트 선례 일치).
- Maxi 결정 2건(생성 시점 미지원 / ARCHIVED 허용)은 도메인 단계에서 확정.
