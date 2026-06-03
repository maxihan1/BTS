# FR-VR-01 버전 생성 + 시작일/릴리즈 예정일 (백엔드 D1~D5) — 스펙

> slug: fr-vr-01-versions-backend
> 작성: 2026-06-03 (office-hours 스킵 — 정의된 백엔드 FR, 메모리 bts-spec-office-hours-mismatch)
> 도메인 결정: ADR 2026-06-03-version-model-and-permission-deferral.md
> BC: issue-tracking | 범위: 백엔드 D1~D5 (D6 UI / D7 E2E 후속 PR)
> 선례: FR-CM-01 백엔드 스펙(2026-06-02-fr-cm-01-components-backend.md) 동형 답습

## 배경 사실 (조사 결과)

- 버전 = 릴리스 단위(glossary "버전 Version — fix/affects 관계로 이슈에 연결"). Affects/Fix 연결은 FR-VR-03, 상태는 FR-VR-02 별도 FR.
- projects 테이블은 issue-tracking 소유(V001). 프로젝트 스코프 경로 규약 `/api/v1/projects/{projectIdOrKey}/...`. 컴포넌트(V009)가 동형 적용.
- 컴포넌트(FR-CM-01, PR #59)가 `com.bts.issue.component` 패키지에 도메인/리포지토리/애플리케이션/웹 + AlwaysAllowComponentPermissionResolver + ProjectLookup(in-BC projects 조회) 구조 확립 → 버전은 `com.bts.issue.version`으로 동형.
- DTO 규약: data class + Jakarta Validation(@NotBlank/@Size), `toAppRequest()`, `Response.from(domain)`, `DataResponse{data}` 래퍼.
- 에러 규약: feature별 `@RestControllerAdvice` ExceptionHandler + ErrorCodes 상수(RFC 7807 ProblemDetail + errorCode 확장 필드).
- 권한: 컴포넌트 쓰기 경로가 `AlwaysAllowComponentPermissionResolver`(비prod) 패턴. 실 enforcement는 후속 FR(FR-PM-03)이 prod 리졸버로 채움.

## 권한 모델 결정 (ADR 요약)

버전 CRUD 권한은 `VersionPermissionResolver` 포트로 추상화하고 실 판정은 **FR-PM-03 이연**.
- FR-VR-01 범위: 인증 필수(JWT/PAT) + 프로젝트 존재 + AlwaysAllow 리졸버(비prod) + !prod fallback 빈 + 부팅 가드.
- FR-PM-03 범위: prod 실판정 구현(permission_schemes 매트릭스 기반) + @PreAuthorize 상당 enforcement.
- 근거: 컴포넌트(FR-CM-01→FR-PM-03)와 동형, 재작업 0. Phase 1(운영 배포 전)이라 임시 포스처 수용.

## 핵심 차이 (vs FR-CM-01 컴포넌트)

- 컴포넌트의 `lead_user_id`(identity-access users 참조) **없음** → 버전은 cross-BC 사용자 참조 부재 → **UserLookupPort 불필요, 422 LEAD_NOT_FOUND 없음**.
- 대신 날짜 두 필드 `start_date`/`release_date`(둘 다 nullable, 순서 미강제 — Jira 기본, Maxi 결정).
- 리드 전용 `/lead` 서브리소스 자리에 **날짜 전용 `/dates` 서브리소스**(두 날짜 함께 제출, 2-state each: null=클리어/날짜=설정). Maxi 결정 2026-06-03.
- status(Unreleased/Released/Archived)는 **FR-VR-02 이연** — 이번 컬럼/필드 없음.

## 사용자 시나리오 (Given-When-Then)

### S1 — 버전 생성 (날짜 지정)
- Given: 인증된 사용자, 존재하는 프로젝트 ATLAS
- When: `POST /api/v1/projects/ATLAS/versions { name: "v1.0", description: "첫 릴리스", startDate: "2026-06-01", releaseDate: "2026-09-01" }`
- Then: 201 + 생성된 버전(id, projectId, name, description, startDate, releaseDate, 타임스탬프)

### S2 — 버전 생성 (날짜 없이)
- Given: 인증된 사용자, 존재하는 프로젝트
- When: `POST .../versions { name: "v2.0" }` (날짜 생략)
- Then: 201 + startDate=null, releaseDate=null. 날짜는 선택값(Jira 동일).

### S2b — 버전 생성 (역순 날짜)
- Given: 인증된 사용자, 존재하는 프로젝트
- When: `POST .../versions { name: "v3.0", startDate: "2026-09-01", releaseDate: "2026-06-01" }` (시작일 > 릴리즈일)
- Then: 201. **순서 미강제**(Maxi 결정) — 역순도 허용.

### S3 — 목록 조회
- Given: 프로젝트에 활성 버전 N개 + 소프트 삭제 1개
- When: `GET /api/v1/projects/ATLAS/versions`
- Then: 200 + 활성 N개만(`deleted_at IS NULL`). name 정렬.

### S4 — 수정 (이름/설명)
- Given: 기존 버전
- When: `PATCH .../versions/{id} { name: "v1.0.1", description: "..." }`
- Then: 200 + 변경 반영. 결합 PATCH는 name/description만. 문자열 sentinel 규약(필드 생략/null=무변경, non-null은 해당 값으로 저장 — 빈 문자열이면 빈 설명) — UpdateComponentRequest 선례 동형.

### S4b — 날짜 지정·해제 (전용 서브리소스)
- Given: 기존 버전
- When: `PATCH .../versions/{id}/dates { startDate: <날짜 또는 null>, releaseDate: <날짜 또는 null> }`
- Then: 200 + 두 날짜 반영. **두 키 항상 존재(2-state each)** — null=해제, 날짜=설정. 컴포넌트 `/lead` 선례 동형 — 결합 PATCH의 생략 vs null 모호성 원천 제거. 한쪽만 변경 시 다른 쪽 현재값도 함께 전송.

### S5 — 소프트 삭제
- Given: 기존 버전
- When: `DELETE .../versions/{id}`
- Then: 204. `deleted_at` 설정. 목록·단건에서 제외. 같은 이름 재생성 가능(활성 기준 유일).

### S6 — 존재하지 않는 프로젝트
- Given: 미존재 projectIdOrKey
- When: 임의 CRUD
- Then: 404 `PROJECT_NOT_FOUND`.

### S7 — 존재하지 않는 버전
- Given: 존재 프로젝트, 미존재/삭제된 versionId
- When: GET/PATCH/DELETE
- Then: 404 `VERSION_NOT_FOUND`.

### S8 — 이름 중복
- Given: 프로젝트에 활성 "v1.0" 이미 존재
- When: `POST .../versions { name: "v1.0" }`
- Then: 409 `VERSION_NAME_DUPLICATE`. (활성 기준 — 삭제된 동명은 충돌 아님.)

### S9 — 미인증
- Given: 인증 토큰 없음
- When: 임의 CRUD
- Then: 401.

## 기능 요구사항 (FR)

- **FR-1 도메인(D1)**: `Version` Aggregate Root — id(UUID), projectId, name, description?, startDate?(LocalDate), releaseDate?(LocalDate). 불변식: name 비어있지 않음, name 길이 ≤255, 정규화(trim). 날짜 순서 미강제. `rename`/`changeDescription`/`changeDates`/`softDelete` 도메인 메서드. (컴포넌트 `changeLead` 자리에 `changeDates(startDate, releaseDate)`.)
- **FR-2 데이터(D3)**: `versions` 테이블 — `id UUID PK`, `project_id UUID NOT NULL FK→projects(id)`, `name VARCHAR(255) NOT NULL`, `description TEXT NULL`, `start_date DATE NULL`, `release_date DATE NULL`, `created_at`/`updated_at`/`deleted_at`. 부분 유니크 인덱스 `(project_id, name) WHERE deleted_at IS NULL`. FK 인덱스 `idx_versions_project_id`. jOOQ init_codegen 미러(메모리 jooq-init-codegen-mirror).
- **FR-3 CRUD API(D4)**: POST(201)/GET 목록(200)/GET 단건(200)/PATCH name·description(200)/PATCH /dates 전용 서브리소스(200)/DELETE(204). DataResponse 래퍼. **날짜는 전용 `/dates` 서브리소스(컴포넌트 `/lead` 선례)** — 결합 PATCH 3-state 모호성 회피. 결합 PATCH는 name/description만 문자열 sentinel.
- **FR-4 검증(D4)**: 프로젝트 존재(404), 버전 존재+소속(404), 이름 중복(409, 활성 기준). 도메인 정규화는 service가 도메인 메서드 경유(메모리 patch-merge-domain-bypass — DTO 검증은 1차방어만). 날짜는 형식 검증(ISO-8601 date)만, 값 제약 없음(순서 미강제).
- **FR-4b 프로젝트 조회(D3/D4)**: 컴포넌트가 추가한 `ProjectLookup`(in-BC projects 존재 + projectKey→id 해석) **재사용**. issue-tracking 소유라 자체 repository(in-BC, BC 위반 아님). 동시 생성 race는 부분 유니크 인덱스 제약 위반 catch→409(pre-check만으로 TOCTOU 미방지).
- **FR-5 권한 리졸버(D4)**: `VersionPermissionResolver` 포트 + AlwaysAllow(비prod) 구현 + !prod fallback 빈 + 부팅 가드(메모리 profile-scoped-bean-boot-failure). **인증 자체(401)는 SecurityConfig 필터가 보장**. actor 추출 + 실 권한판정은 FR-PM-03 이연(AlwaysAllow는 actor 무시) — ComponentController 선례 동형.
- **FR-6 테스트(D5)**: MockK 단위(도메인 불변식/service 분기) + Testcontainers 통합(CRUD happy + 404×2/409 + soft delete 제외 + 부분 유니크 + 역순 날짜 허용 + 날짜 해제). TDD red→green→refactor.

### 범위 밖 (후속)
- 버전 상태(FR-VR-02), Affects/Fix 연결(FR-VR-03), 릴리즈 노트(FR-VR-04), 프론트 UI(D6), E2E(D7).
- prod 권한 실판정(FR-PM-03).

## 비기능 요구사항 (NFR)

- **NFR-1 BC 격리**: 버전은 cross-BC 사용자 참조 없음(리드 부재). projects는 자기 BC 소유라 실 FK. identity-access 직접 import 금지(ArchUnit).
- **NFR-2 트랜잭션**: CRUD는 ApplicationService `@Transactional`. 컨트롤러는 트랜잭션 경계 미보유(컴포넌트/IssueType 규약).
- **NFR-3 소프트 삭제**: DELETE는 항상 WHERE + deleted_at (절대 규칙, DATA.md §3).
- **NFR-4 정규화 단일 진입**: 이름 trim/검증은 도메인 Aggregate에서. service가 도메인 메서드 호출(불변식 우회 금지).

## API 인터페이스 (REST)

```
POST   /api/v1/projects/{projectIdOrKey}/versions           → 201 {data:{id,projectId,name,description,startDate,releaseDate,createdAt,updatedAt}}
GET    /api/v1/projects/{projectIdOrKey}/versions           → 200 {data:[...]}  (활성만, name 정렬)
GET    /api/v1/projects/{projectIdOrKey}/versions/{id}      → 200 {data:{...}}
PATCH  /api/v1/projects/{projectIdOrKey}/versions/{id}      → 200 {data:{...}}  (name/description, 문자열 sentinel)
PATCH  /api/v1/projects/{projectIdOrKey}/versions/{id}/dates → 200 {data:{...}}  (날짜 지정/해제 전용)
DELETE /api/v1/projects/{projectIdOrKey}/versions/{id}      → 204
Authorization: Bearer <JWT 또는 PAT>
```

요청 바디:
- POST `{ name: String(필수,1~255), description: String?(≤1000), startDate: LocalDate?, releaseDate: LocalDate? }`
- PATCH (결합) `{ name?: String, description?: String? }` — name/description 생략·null=무변경, non-null은 해당 값으로 저장(빈 문자열이면 빈 설명) (UpdateComponentRequest sentinel 규약).
- PATCH /dates `{ startDate: LocalDate?, releaseDate: LocalDate? }` — 두 키 항상 존재(2-state each), null=해제, 날짜=설정.

에러 코드:
- 401 (미인증)
- 404 `PROJECT_NOT_FOUND` / `VERSION_NOT_FOUND`
- 409 `VERSION_NAME_DUPLICATE` (활성 기준)
- 400 (Jakarta Validation — name 공백/날짜 형식 등)

projectIdOrKey 해석: ProjectLookup(컴포넌트 선례 재사용)으로 key→id 또는 UUID 직접. 미존재 404.

## 데이터 모델 변경

- 신규 마이그레이션: `versions` 테이블 + 부분 유니크 인덱스 + FK 인덱스. issue-tracking 네임스페이스(V010, BC prefix 정책 ADR).
- init_codegen.sql 미러(jOOQ 상수 생성, 메모리 jooq-init-codegen-mirror).
- projects.id FK(같은 BC). 사용자 참조 없음.

## 엣지 케이스

- EC-1: 날짜 변경은 전용 `PATCH .../versions/{id}/dates { startDate: LocalDate?, releaseDate: LocalDate? }` — 두 키 항상 존재(null=해제/날짜=설정), 결합 PATCH의 생략 vs null 모호성 원천 제거. 컴포넌트 `/lead` 선례 동형.
- EC-2: 이름 중복은 활성 기준 — 소프트 삭제된 동명 버전은 충돌 아님(부분 유니크 인덱스).
- EC-3: 역순 날짜(startDate > releaseDate) 허용 — 순서 미강제(Maxi 결정). 날짜 형식 오류(non-ISO)는 400.
- EC-4: projectIdOrKey가 소프트 삭제된 프로젝트 → 404 PROJECT_NOT_FOUND(활성만).
- EC-5: 다른 프로젝트의 versionId로 접근 → 404 VERSION_NOT_FOUND(프로젝트 소속 검증).
- EC-6: jOOQ init_codegen 미러 누락 시 repository 컴파일 불가(V005 선례).
- EC-7: VersionPermissionResolver가 @Profile prod 단독이면 비prod 통합테스트 부팅 실패 → !prod fallback 필수(profile-scoped-bean-boot-failure).

## 제약 조건

- BC: issue-tracking 단일. cross-BC 포트 사용 없음(리드 부재로 UserLookupPort도 불필요). identity-access 직접 import 금지(ArchUnit).
- 새 외부 의존성 없음.
- 소프트 삭제·트랜잭션 경계·이름 정규화 단일진입 절대 규칙 준수.

## 측정 가능한 완료 기준

- [ ] 도메인 단위테스트: Version 불변식(빈 이름 거부/trim/rename/changeDescription/changeDates/softDelete + 역순 날짜 허용).
- [ ] 통합테스트(Testcontainers): S1~S9 — 생성(날짜有/無/역순)/목록(삭제 제외)/수정(name·desc 3-state)/날짜 지정·해제/소프트삭제/404×2/409/401.
- [ ] 부분 유니크 인덱스 검증: 활성 동명 거부 + 삭제 후 동명 재생성 허용.
- [ ] ktlint(신규 파일, 모듈 ktlintFormat 금지) + detekt 통과.
- [ ] init_codegen 미러 → repository 컴파일 OK.

## Brainstorming Check

✅ 통과 (직접 적대적 sanity check, office-hours 스킵 — 정의된 백엔드 FR + 도메인 grill 완료, FR-CM-01 동형).

발견·반영:
1. **날짜 PATCH 3-state 모호성(EC-1, S4b)** — nullable 날짜의 생략(무변경)/null(해제) 구분 불가 → 컴포넌트 `/lead` 선례 따라 전용 `/dates` 서브리소스(두 키 항상 존재, 2-state each). Maxi 결정.
2. **날짜 순서(S2b/EC-3)** — startDate ≤ releaseDate 미강제(Jira 기본, Maxi 결정). 역순 허용, 형식 오류만 400.
3. **사용자 참조 부재** — 컴포넌트 대비 lead_user_id 없음 → UserLookupPort/422 LEAD_NOT_FOUND 제거. cross-BC 포트 0.
4. **status 이연** — FR-VR-02 D3(versions.status) 소관, 이번 컬럼 없음.
5. **프로젝트 조회 재사용(FR-4b)** — 컴포넌트가 추가한 ProjectLookup(in-BC) 재사용. 동시 생성 race는 부분 유니크 제약 catch→409.
6. **actor 추출 경계(FR-5)** — 인증(401)은 Security 필터, actor+실판정은 FR-PM-03 이연(ComponentController 선례).
7. **jOOQ init_codegen 미러(EC-6)** + **profile-scoped bean 부팅 가드(EC-7)** 메모리 교훈 선반영.

미해소 결정 없음 — 전부 선례/메모리/Maxi 결정 기반으로 스펙에 흡수.
