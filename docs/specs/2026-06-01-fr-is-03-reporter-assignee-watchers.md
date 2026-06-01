# FR-IS-03 담당자 (Reporter 1 / Assignee 1) — 스펙

> slug: fr-is-03-reporter-assignee-watchers
> BC: issue-tracking
> 범위: Reporter(기존 재사용) + Assignee(신규). **Watcher 는 FR-WT-01 로 분리.**
> 작성: 2026-06-01

## 배경 / 범위 경계

- **Reporter(보고자)** — 이미 구현됨. `Issue.reporterId: ActorId`(UUID), 생성 시 설정, `IssueResponse.reporterId` 노출. 이번 PR 에서 **보고자 재지정은 범위 밖**(생성 시 고정, 변경 UI 없음). 별도 필요 시 후속.
- **Assignee(담당자)** — 신규. 이슈당 0~1명. 미할당(null) 허용. 본 PR 의 핵심 작업.
- **Watcher(워처)** — FR-WT-01(§4.3.1)로 분리. 본 PR 범위 밖.

## 사용자 시나리오 (Given-When-Then)

### S1 — 담당자 지정
- **Given** 담당자가 없는(미할당) 이슈 `ATL-1`(version=1)
- **When** 사용자가 `PATCH /api/v1/issues/ATL-1/assignee` `{assigneeId: <bob-uuid>, expectedVersion: 1}` 호출
- **Then** assignee=bob 으로 저장, version=2, 200 + 갱신된 IssueResponse(assigneeId=bob)

### S2 — 담당자 해제 (미할당)
- **Given** 담당자가 bob 인 이슈 `ATL-1`(version=2)
- **When** `PATCH .../assignee` `{assigneeId: null, expectedVersion: 2}`
- **Then** assignee=null, version=3, 200 + IssueResponse(assigneeId=null)

### S3 — 담당자 변경(다른 사용자로)
- **Given** assignee=bob, version=3
- **When** `{assigneeId: <carol-uuid>, expectedVersion: 3}`
- **Then** assignee=carol, version=4, 200

### S4 — 존재하지 않는 사용자 지정 → 거부
- **Given** 이슈 `ATL-1` 존재
- **When** `{assigneeId: <unknown-uuid>, expectedVersion: N}` (UserLookupPort.exists=false)
- **Then** 422 + errorCode `ASSIGNEE_NOT_FOUND`, 이슈 변경 없음(version 유지)

### S5 — 낙관적 잠금 충돌
- **Given** 이슈 version=2 인데 다른 사용자가 먼저 수정해 version=3
- **When** `{assigneeId: <bob>, expectedVersion: 2}`
- **Then** 409 + errorCode `VERSION_CONFLICT`(기존 패턴)

### S6 — 존재하지 않는/삭제된 이슈
- **When** `PATCH /api/v1/issues/NOPE-1/assignee`
- **Then** 404 + errorCode `ISSUE_NOT_FOUND`

### S7 — 응답에 assignee 노출 (조회)
- **Given** assignee=bob 인 이슈
- **When** `GET /api/v1/issues/ATL-1`
- **Then** IssueResponse 에 `assigneeId: <bob-uuid>` 포함 (미할당 시 null)

## 기능 요구사항 (FR)

- **FR-1** 이슈는 0~1명의 Assignee 를 가진다. 미할당이 기본값(생성 시 null).
- **FR-2** `PATCH /api/v1/issues/{key}/assignee` 로 담당자를 지정/변경/해제한다.
- **FR-3** assignee 지정(non-null) 시 `UserLookupPort.exists(assigneeId)` 로 사용자 실재를 검증한다. false 면 `ASSIGNEE_NOT_FOUND`(422). null(해제)은 검증 스킵.
- **FR-4** 담당자 변경은 낙관적 잠금(version)을 적용한다. expectedVersion 불일치 시 409 `VERSION_CONFLICT`.
- **FR-5** `IssueResponse` 에 `assigneeId: UUID?` 필드를 추가한다(단건 GET·목록·생성·PATCH 응답 모두).
- **FR-6** 담당자 지정/변경/해제는 도메인 메서드(`Issue.assignTo` / `Issue.unassign`)를 거쳐 version+1, updatedAt 갱신을 보장한다(PATCH→repository 직행 도메인 우회 금지, 메모리 `patch-merge-도메인-우회`).
- **FR-7** 권한은 기존 `IssuePermissionResolver` 의 `UPDATE` 로 가드한다(현 AlwaysAllow stub).

## 비기능 요구사항 (NFR)

- **NFR-1** 담당자 변경은 단일 트랜잭션(이슈 UPDATE + version 증가). 부분 반영 없음.
- **NFR-2** cross-BC 사용자 검증은 `UserLookupPort` interface 만 의존. identity-access 직접 import 금지(ArchUnit 가드 적용 대상).
- **NFR-3** 에러 코드는 대문자 스네이크(`ASSIGNEE_NOT_FOUND`/`VERSION_CONFLICT`/`ISSUE_NOT_FOUND`). 프론트와 정합(메모리 `frontend-zod-backend-dto-contract-gap`).

## API 인터페이스 (REST)

### 담당자 변경
```
PATCH /api/v1/issues/{key}/assignee
Body: { "assigneeId": "<uuid>" | null, "expectedVersion": <long> }
200 → IssueResponse (assigneeId 반영)
404 ISSUE_NOT_FOUND / 409 VERSION_CONFLICT / 422 ASSIGNEE_NOT_FOUND / 400 VALIDATION_FAILED(expectedVersion 누락 등)
```

전용 서브리소스 엔드포인트로 merge-patch 3-state 모호성 회피 — 본문이 항상 assigneeId 를 실어 null=해제, 값=지정으로 명확.

### 조회 (기존 엔드포인트 응답 확장)
```
GET /api/v1/issues/{key}     → IssueResponse.assigneeId 포함
GET /api/v1/issues (목록)     → 각 항목 assigneeId 포함
POST /api/v1/issues          → 생성 응답 assigneeId=null
```

## 사용자 목록 (담당자 셀렉터 재료 — identity-access)

브레인스토밍에서 발견: 담당자를 UI 에서 고르려면 "사용자 목록"이 필요한데 현재 `whoami`(본인)만 있음. Maxi 결정 — 풀스택 + 사용자 목록 조회 추가.

- **FR-8** identity-access 에 `GET /api/v1/users`(활성 사용자 목록/검색) 추가. 응답 `{ id, username, displayName, email }`. 인증 필수(PII, security-engineer 검토).
- **FR-9** 프론트 담당자 셀렉터는 이 엔드포인트로 목록을 채우고, `assigneeId`(UUID)를 id→displayName 매핑으로 표시. issue-tracking 응답은 assigneeId 만 노출(BC 격리 보존, 이름 비포함).
- 자세한 cross-BC 배선(UserLookupPort = shared-kernel, identity-access 구현): [docs/adr/2026-06-01-issue-assignee-user-lookup-port.md](../adr/2026-06-01-issue-assignee-user-lookup-port.md).

## 데이터 모델 변경

- `issues.assignee_id UUID NULL` 추가 (reporter_id 와 동일하게 **FK 미적용**, BC 격리. 컬럼 코멘트로 명시).
- **`init_codegen.sql` 미러 필수** — jOOQ ISSUES.ASSIGNEE_ID 상수 생성용(Flyway PG16 우회 구조, V005/V006 선례, 메모리 `jooq-init_codegen-미러`).
- 마이그레이션 버전: `V007__issue_assignee.sql`.
- 인덱스: assignee 기준 조회(내 담당 이슈)는 후속 FR(목록 필터)에서 필요 시 추가. 본 PR 은 단일 컬럼만(과설계 회피).

## 엣지 케이스

- **EC-1** 같은 사용자로 재지정(idempotent) → 값 동일해도 version+1, 200 (다른 PATCH 와 일관). 별도 no-op 분기 없음.
- **EC-2** assigneeId=null 해제 시 UserLookupPort 호출 안 함(불필요 cross-BC 호출 회피).
- **EC-3** expectedVersion 누락 → 400 VALIDATION_FAILED (Jakarta `@NotNull`).
- **EC-4** assignee 를 reporter 와 동일인으로 지정 → 허용(보고자=담당자 자가할당 정상).
- **EC-5** 소프트 삭제된 이슈 → 404 ISSUE_NOT_FOUND(`deleted_at IS NULL` 필터, 기존 findByKey 패턴).
- **EC-6** 잘못된 UUID 형식의 assigneeId → 400 VALIDATION_FAILED(역직렬화 단계).

## 제약 조건

- 보고자 재지정 범위 밖(생성 시 고정).
- 담당자 변경 이력(IssueHistory) 기록은 FR-HS-01 로 위임(FR-IS-04 가 history 를 위임한 선례와 일관).
- 자동 Watcher 등록(담당자 지정 시 watcher 추가)은 FR-WT-01 범위.

## 프론트 캐시 정책 (회귀 방지)

- 담당자 변경 mutation 은 **invalidate-only**(setQueryData 로 PATCH 응답 통째 교체 금지). 메모리 `mutation-setquerydata-부분응답-플리커` — PATCH 응답이 descriptionHtml 등 파생 필드를 null 로 덮어 플리커. 단건 GET refetch 가 전 필드 정확히 채움.
- `assigneeId` 를 `issueResponseSchema`(Zod)에 추가 시 산재한 인라인 IssueResponse mock 전수 보강 필요(메모리 `zod-스키마-강화-mock-파급`). `grep -rl reporterId` 로 mock 식별 + `tsc --noEmit` 동반.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견된 핵심 gap = "담당자 셀렉터의 사용자 목록 출처 부재" → Maxi 결정으로 identity-access `GET /api/v1/users` 추가(FR-8/9)로 보강. 부수 gap 2건(프론트 invalidate-only, Zod mock 파급)은 메모리 선례 기반 spec 반영.

## 측정 가능한 완료 기준

- [ ] `issues.assignee_id` 컬럼 + init_codegen 미러 + V007 마이그레이션 통합 테스트(Testcontainers)
- [ ] `Issue.assignTo(ActorId)` / `Issue.unassign()` 도메인 메서드 + 단위 테스트
- [ ] `UserLookupPort` + `AlwaysExistsUserLookup` stub(`@Profile("!prod")`) + ArchUnit BC 격리 가드
- [ ] `PATCH /assignee` 엔드포인트 — S1~S6 통합 테스트(MockK + Testcontainers), assignee 검증 false 케이스 포함
- [ ] `IssueResponse.assigneeId` 노출 — 단건/목록/생성 모두
- [ ] 프론트: 담당자 셀렉터(미할당 포함) + assigneeId Zod 계약 동기 + E2E(D6/D7)
