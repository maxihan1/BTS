# FR-IM-02 PR-B — Import 사용자 매핑 (전 작성자 필드) — 스펙

> BC. search-export-import (+ shared-kernel 커맨드 · issue-tracking 어댑터)
> 선행. PR-A(#230 필드 매핑). 도메인·아키텍처는 승인된 ADR `2026-07-03-fr-im-02-import-mapping.md` 상속.
> 범위. **백엔드 전용**. 프론트 매핑 마법사는 D6/D7(후속).

## 배경 한 줄

필드 매핑(PR-A)만으로는 소스 파일의 사용자 식별자(이메일/이름)가 BTS 사용자와 정확히 매칭되지 않는 대량 마이그레이션이 많다. PR-B는 사용자가 소스 작성자 식별자를 BTS 사용자로 **명시 매핑**하게 해, 이메일 자동해석이 놓치는 사용자를 정확히 배정한다. 대상은 **전 작성자 필드**(reporter/assignee + 댓글/worklog/첨부/changelog 작성자).

## 사용자 시나리오 (Given-When-Then)

### S1. distinct 작성자 수집 (2차 분석)
- **Given** analyze로 AWAITING_MAPPING 상태가 된 job과, 사용자가 확정하려는 필드 매핑(reporter→…, assignee→… 포함)이 있다.
- **When** `POST /imports/{jobId}/mapping/users`에 필드 매핑을 담아 호출한다.
- **Then** 서버가 persist된 원본 파일을 필드 매핑 기준으로 **전량 스캔**해, 작성자 필드에서 발견된 **distinct 소스 식별자**(정규화형) 목록을 반환한다. 각 식별자에 대해 `resolveByEmails` 자동해석 결과(추천 BTS 사용자 UUID + display_name)를 함께 담는다(이메일형만 추천, 미해석은 null).

### S2. 사용자 매핑 확정 (필드 매핑과 동시)
- **Given** 사용자가 수집된 식별자마다 BTS 사용자를 확정(또는 미매핑 유지)했다.
- **When** `POST /imports/{jobId}/mapping`(confirm)에 필드 매핑 + **사용자 매핑 목록**을 담아 호출한다.
- **Then** 사용자 매핑을 검증(대상 사용자 실재·중복 없음) 후, PR-A와 **같은 CAS-우선 트랜잭션** 안에서 `import_user_mappings`를 저장하고 job을 PENDING으로 전이·enqueue한다.

### S3. 워커 실행 — userId 우선 해석
- **Given** 확정된 사용자 매핑이 있는 job을 워커가 처리한다.
- **When** 프로세서가 각 행을 커맨드로 변환한다.
- **Then** 프로세서가 `import_user_mappings`를 **1회 로드**해, 각 행의 작성자 식별자를 매핑으로 해석한 UUID를 커맨드의 `reporterUserId`/`assigneeUserId` 및 각 동반 VO의 `authorUserId`에 세팅한다. 어댑터는 **userId가 있으면 그것으로 배정**하고, 없으면(미매핑) 기존 이메일 해석 → requester 폴백을 그대로 수행한다.

### S4. 하위호환 — 즉시 경로 + 미매핑
- **Given** 기존 즉시-업로드(`POST /api/v1/imports`) 또는 사용자 매핑 없이 confirm한 job.
- **When** 워커가 처리한다.
- **Then** 커맨드 userId 필드는 전부 null → 어댑터는 PR-A/FR-IM-01과 **완전히 동일**하게 동작(회귀 0).

## 기능 요구사항 (FR)

- **FR1**. 새 엔드포인트 `POST /api/v1/imports/{jobId}/mapping/users` — 필드 매핑을 받아 distinct 작성자 식별자 + 자동해석 추천을 반환. 소유확인·AWAITING_MAPPING 상태 검사를 검증보다 먼저(auth-extraction-before-resource-lookup).
- **FR2**. distinct 수집 대상 = 전 작성자 필드. **CSV**: reporter/assignee 컬럼(필드 매핑 기준) + 댓글 작성자(`date;author;body`). **JSON**: reporter/assignee + comment/worklog/attachment/changelog authorEmail(canonical 경로).
- **FR3**. 소스 식별자 정규화 = `trim().lowercase()`. **수집·저장·프로세서 행별 해석 삼자 동일**(F2 비대칭 방지). PK `(import_job_id, source_identifier)`가 대소문자 변형 중복을 물리 차단.
- **FR4**. confirm(`POST /imports/{jobId}/mapping`) 요청에 optional `userMappings: [{sourceIdentifier, targetUserId}]` 추가. 생략 시 사용자 매핑 없음(하위호환).
- **FR5**. confirm 시 사용자 매핑 검증 — targetUserId(non-null)는 실재 사용자여야(`UserLookupPort.findDisplayNamesByIds` 배치 실재확인). 미실재 → 422 `IMPORT_USER_MAPPING_INVALID`. 중복 sourceIdentifier → 422. targetUserId=null 허용(미매핑=폴백 유지).
- **FR6**. `import_user_mappings` 저장은 confirm의 CAS 트랜잭션 안(transitionToPending CAS → 필드매핑 saveAll → **사용자매핑 saveAll** → enqueue). 반복/동시 confirm 시 단일 저장·enqueue(중복 차단).
- **FR7**. shared-kernel `IssueImportCommand`에 `reporterUserId`/`assigneeUserId`(nullable), 동반 VO 4종(`ImportComment`/`ImportWorklog`/`ImportAttachment`/`ImportChangeGroup`)에 `authorUserId`(nullable) 추가. 포트 시그니처 불변.
- **FR8**. issue-tracking `IssueImportAdapter` — reporter/assignee/author 해석에 userId 우선 분기. `resolveAuthorId(userId, email, resolvedEmails, requester) = userId ?: (resolvedEmails[email.lower()] ?: requester)`. changelog는 기존 비대칭 폴백 유지(`userId ?: resolvedEmails[email]`, requester 폴백 없음).
- **FR9**. 프로세서가 사용자 매핑을 1회 로드 → 행별 in-memory 해석(추가 DB 왕복 없음). 매핑 없는 job은 로드 스킵(빈 맵).
- **FR10**. 미매핑 작성자 경고(`isAuthorUnmatched`)는 userId-aware — userId 있으면 matched로 간주.

## 비기능 요구사항 (NFR)

- **NFR1 (성능)**. distinct 수집은 파일 전량 스캔이나 **distinct set 크기**(팀 규모)로 메모리 bounded, 스트리밍 파서 재사용(전 컬럼 사전수집 회피 — ADR D5). 파일 크기는 기존 업로드 상한이 이미 제한.
- **NFR2 (보안/격리)**. `import_user_mappings.target_user_id`는 cross-BC(users=identity-access)라 **FK 미적용**(favorites 선례), 앱 계층 `UserLookupPort` 실재확인으로 무결성 보장. 소유확인 없는 요청자에 job 존재·상태 미노출(404 우선).
- **NFR3 (하위호환)**. 즉시 경로·미매핑 confirm은 회귀 0. 커맨드 userId 필드 전부 nullable 기본 null.
- **NFR4 (search-export-import 최초 UserLookupPort 사용)**. import BC가 처음으로 `UserLookupPort`를 주입받는다. production 구현체는 identity-access `UserLookupAdapter`. **모듈 테스트는 fake/stub 빈 필요**(shared-kernel default는 emptyMap → 자동해석 0건이라 테스트는 명시 fake로 실동작 검증).

## API 인터페이스 (REST)

```
POST /api/v1/imports/{jobId}/mapping/users        # 신규 — distinct 작성자 수집 + 추천
  req  { fieldMappings: [{sourceField, targetField}] }
  res 200 { users: [{ sourceIdentifier, suggestedUserId?, suggestedDisplayName? }] }
  401 미인증 · 404 미존재/타인 · 409 상태충돌(AWAITING_MAPPING 아님)

POST /api/v1/imports/{jobId}/mapping              # 확장 — userMappings 추가
  req  { fieldMappings: [...], userMappings?: [{sourceIdentifier, targetUserId?}], dryRun? }
  res 200 ImportJobResponse(status=PENDING)
  422 IMPORT_MAPPING_INVALID(필드) / IMPORT_USER_MAPPING_INVALID(사용자) · 409 상태충돌
```

> **결정됨 (Maxi 2026-07-04)**. 사용자 매핑 전용 validate 엔드포인트는 **신설하지 않는다** — confirm에 검증 폴딩(YAGNI, 마법사 D6/D7 후속). 신규 엔드포인트는 collect 1개뿐.

## 데이터 모델 변경

- **V607** `import_user_mappings(import_job_id UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE, source_identifier TEXT NOT NULL, target_user_id UUID NULL, PRIMARY KEY (import_job_id, source_identifier))`. init_codegen 미러. **머지 직전 V번호 재확인**(migration-vnumber-concurrent-branch-collision).
- shared-kernel 커맨드/VO userId 필드 추가(비-DB).

## 엣지 케이스

- **E1**. 소스 식별자가 이메일 아님(이름/계정ID) → 자동해석 null, 사용자가 수동 매핑. 저장·해석은 정규화형 문자열로 동일 처리.
- **E2**. 같은 targetUserId에 여러 sourceIdentifier 매핑 → 허용(여러 소스 별칭이 한 사용자). DUPLICATE는 sourceIdentifier(키) 기준만.
- **E3**. targetUserId=null(미매핑 확정) → 저장 허용하되 프로세서는 non-null만 사용(null=폴백). 없는 키와 동일 동작.
- **E4**. confirm의 userMappings에 수집 안 된 sourceIdentifier 포함 → 무해(프로세서가 해당 식별자를 안 만나면 미사용). UNKNOWN_SOURCE 강제 검증은 재수집 비용이라 스킵(경고도 생략).
- **E5**. 매핑된 사용자가 대상 프로젝트 권한 없음 → PR-B 범위 아님(어댑터가 requesterUserId actor로 생성, 배정만 해당 user). 기존 FR-IM-01 정책 불변.
- **E6**. CSV 다중 대소문자 변형 작성자(`bob@x`, `Bob@X`) → 정규화로 1건 수집·저장(F2 회피). PK 중복 방지.
- **E7**. confirm 후 재-collect 호출 → 상태가 PENDING이라 409(AWAITING_MAPPING 아님). PR-A validate와 동일.

## 제약 조건

- 한 트랜잭션 원자성(CAS 우선) 유지 — 사용자매핑 saveAll을 필드매핑 saveAll과 같은 구간에.
- MinIO I/O(전량 스캔)는 `@Transactional` 밖(DB 커넥션 미점유 — PR-A/FR-AC-01 정책).
- search-export-import 백엔드 CI 없음 → detekt/ktlint 명시 `--rerun-tasks`(backend-detekt-lint-debt-unmasked).

## 측정 가능한 완료 기준

- [ ] `POST /{jobId}/mapping/users`가 CSV(임의 헤더+댓글작성자)·JSON(6종 작성자) 파일에서 distinct 식별자+추천 정확 반환.
- [ ] confirm이 userMappings를 CAS 트랜잭션에 저장, targetUserId 미실재 시 422.
- [ ] 워커가 매핑된 작성자를 정확한 userId로 배정(reporter/assignee/댓글/worklog/첨부/changelog 각각).
- [ ] 미매핑/즉시경로 회귀 0(기존 import 통합테스트 그린).
- [ ] `import_user_mappings.target_user_id` 실 insert round-trip 검증(F1 누수 방지).
- [ ] 정규화 삼자(수집·저장·해석) 동일 회귀 가드 테스트.
- [ ] 모듈 전체 test 그린 + detekt/ktlint(--rerun-tasks) + verify-master-plan.

## Brainstorming Check

자기-비평 1회(PR-A 방식). 수정가능 갭은 plan 흡수, 1건은 게이트 1 fork.

**검증된 가정**.
- 파서 스트리밍 메서드 실재 확인 — `parseCsv(input, fieldMapping, onRow)`·`parseJson(input, onRow)`. collect가 전량 스캔에 재사용(신규 파서 메서드 불필요). mapped-mode CSV도 댓글 다중컬럼 수집 불변(PR-A Task 3 확립).
- `UserLookupPort.findDisplayNamesByIds`(실재확인·display_name)·`resolveByEmails`(자동해석) 둘 다 실재 — 신규 포트 메서드 0(35개 fake 파급 없음).

**수정가능 갭(plan 흡수)**.
- G1. collect 추천은 포트 2회 — `resolveByEmails(식별자)→Map<email,uid>` 후 `findDisplayNamesByIds(uid집합)→display`. 이름형 식별자는 resolveByEmails 미매칭(무해).
- G2. JSON collect는 fieldMappings 무시(canonical) — validate의 JSON 스킵과 동형. 명시.
- G3. confirm 방어적 정규화 — 클라이언트가 raw sourceIdentifier 보내도 저장 전 `trim().lowercase()`(F3 삼자 일치 강제).
- G4. 생성자 주입 파급 — `ImportMappingService`에 `ImportUserMappingRepository`+`UserLookupPort` 추가 → 기존 `ImportMappingServiceTest` mockk 생성자 갱신(plan files 포함). 프로세서도 동일.
- G5. 사용자매핑 검증은 트랜잭션 **밖**(UserLookupPort I/O) → 실패 시 422 후 상태변경 0. CAS 트랜잭션은 저장+enqueue만.
- G6. collect 반복호출 idempotent(read-only 전량 스캔). confirm 후 재-collect는 409(E7).

**게이트 1 fork → 결정됨 (Maxi 2026-07-04)**.
- 사용자 매핑 전용 validate 엔드포인트 = **신설 안 함**. confirm 폴딩(검증 confirm 내부). 신규 엔드포인트 collect 1개.

✅ 통과 (자기-비평 1회, fork 1건 Maxi 결정 확정).
