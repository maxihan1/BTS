# FR-CM-01 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드 (백엔드 D1~D5) — 스펙

> slug: fr-cm-01-components-backend
> 작성: 2026-06-02 (office-hours 스킵 — 정의된 백엔드 FR, 메모리 bts-spec-office-hours-mismatch)
> 도메인 결정: ADR 2026-06-02-component-model-and-permission-deferral.md
> BC: issue-tracking | 범위: 백엔드 D1~D5 (D6 UI / D7 E2E 후속 PR)

## 배경 사실 (조사 결과)

- 컴포넌트 = 프로젝트 내 하위 영역 분류(glossary). 이슈를 컴포넌트로 묶음. FR-CM-02(이슈↔컴포넌트 다대다)는 별도 FR.
- projects 테이블은 issue-tracking 소유(V001). `id UUID PK`, `key`(^[A-Z][A-Z0-9]{1,9}$), soft delete `deleted_at`.
- 프로젝트 스코프 경로 규약: `/api/v1/projects/{projectIdOrKey}/members` (ProjectMember, FR-PM-01). 컴포넌트도 동형.
- 422 검증 선례: `AssigneeNotFoundException → 422 ASSIGNEE_NOT_FOUND`, `UserLookupPort.exists`(shared-kernel)로 사용자 실재 검증(FR-IS-03).
- DTO 규약: data class + Jakarta Validation(@NotBlank/@Size), `toAppRequest()`, `Response.from(domain)`, `DataResponse{data}` 래퍼.
- 에러 규약: feature별 `@RestControllerAdvice` ExceptionHandler + ErrorCodes 상수(RFC 7807 ProblemDetail + errorCode 확장 필드).
- 권한: IssueController 쓰기 경로는 `AlwaysAllowIssuePermissionResolver`(비prod) 패턴. 실 enforcement는 FR-PM-02가 prod 리졸버로 채움.

## 권한 모델 결정 (ADR 요약)

컴포넌트 CRUD 권한은 `ComponentPermissionResolver` 포트로 추상화하고 실 판정은 **FR-PM-03 이연**.
- FR-CM-01 범위: 인증 필수(JWT/PAT) + 프로젝트 존재 + 리드 검증 + AlwaysAllow 리졸버(비prod) + !prod fallback 빈 + 부팅 가드.
- FR-PM-03 범위: prod 실판정 구현(permission_schemes 매트릭스 기반) + @PreAuthorize 상당 enforcement.
- 근거: 이슈 권한(FR-IS-01→FR-PM-02)과 동형, 재작업 0. Phase 1(운영 배포 전)이라 임시 포스처 수용.

## 사용자 시나리오 (Given-When-Then)

### S1 — 컴포넌트 생성 (리드 지정)
- Given: 인증된 사용자, 존재하는 프로젝트 ATLAS, 실재 사용자 alice
- When: `POST /api/v1/projects/ATLAS/components { name: "결제", description: "결제 도메인", leadUserId: <alice UUID> }`
- Then: 201 + 생성된 컴포넌트(id, projectId, name, description, leadUserId, 타임스탬프)

### S2 — 컴포넌트 생성 (리드 없이)
- Given: 인증된 사용자, 존재하는 프로젝트
- When: `POST .../components { name: "백엔드" }` (leadUserId 생략)
- Then: 201 + leadUserId=null. 리드는 선택값(Jira 동일).

### S3 — 목록 조회
- Given: 프로젝트에 활성 컴포넌트 N개 + 소프트 삭제 1개
- When: `GET /api/v1/projects/ATLAS/components`
- Then: 200 + 활성 N개만(`deleted_at IS NULL`). name 정렬.

### S4 — 수정 (이름/설명)
- Given: 기존 컴포넌트
- When: `PATCH .../components/{id} { name: "결제V2", description: "..." }`
- Then: 200 + 변경 반영. 결합 PATCH는 name/description만. 문자열 sentinel 규약(필드 생략/null=무변경, description은 ""=클리어) — UpdateIssueRequest 선례 동형.

### S4b — 리드 지정·해제 (전용 서브리소스)
- Given: 기존 컴포넌트
- When: `PATCH .../components/{id}/lead { leadUserId: <UUID 또는 null> }`
- Then: 200 + 리드 반영. `leadUserId: null` = 리드 해제, UUID = 지정. **FR-IS-03 assignee 전용 서브리소스(`PATCH /issues/{key}/assignee`) 선례 동형** — 결합 PATCH의 3-state 모호성(생략 vs null 구분)을 전용 단일필드 엔드포인트로 제거. 코드베이스에 presence-detection(JsonNullable) 없음을 회피.

### S5 — 소프트 삭제
- Given: 기존 컴포넌트
- When: `DELETE .../components/{id}`
- Then: 204. `deleted_at` 설정. 목록·단건에서 제외. 같은 이름 재생성 가능(활성 기준 유일).

### S6 — 존재하지 않는 프로젝트
- Given: 미존재 projectIdOrKey
- When: 임의 CRUD
- Then: 404 `PROJECT_NOT_FOUND`.

### S7 — 존재하지 않는 컴포넌트
- Given: 존재 프로젝트, 미존재/삭제된 componentId
- When: GET/PATCH/DELETE
- Then: 404 `COMPONENT_NOT_FOUND`.

### S8 — 이름 중복
- Given: 프로젝트에 활성 "결제" 이미 존재
- When: `POST .../components { name: "결제" }`
- Then: 409 `COMPONENT_NAME_DUPLICATE`. (활성 기준 — 삭제된 동명은 충돌 아님.)

### S9 — 실재하지 않는 리드
- Given: 존재하지 않는 leadUserId
- When: 생성/수정에 그 leadUserId 지정
- Then: 422 `COMPONENT_LEAD_NOT_FOUND` (UserLookupPort.exists=false).

### S10 — 미인증
- Given: 인증 토큰 없음
- When: 임의 CRUD
- Then: 401.

## 기능 요구사항 (FR)

- **FR-1 도메인(D1)**: `Component` Aggregate Root — id(UUID), projectId, name, description?, leadUserId?. 불변식: name 비어있지 않음, name 길이 제한, 정규화(trim). `rename`/`changeLead`/`changeDescription`/`softDelete` 도메인 메서드.
- **FR-2 데이터(D3)**: `components` 테이블 — `id UUID PK`, `project_id UUID NOT NULL FK→projects(id)`, `name VARCHAR(255) NOT NULL`, `description TEXT NULL`, `lead_user_id UUID NULL`(FK 없음, BC 격리), `created_at`/`updated_at`/`deleted_at`. 부분 유니크 인덱스 `(project_id, name) WHERE deleted_at IS NULL`. jOOQ init_codegen 미러(메모리 jooq-init-codegen-mirror).
- **FR-3 CRUD API(D4)**: POST(201)/GET 목록(200)/GET 단건(200)/PATCH name·description(200)/PATCH lead 전용 서브리소스(200)/DELETE(204). DataResponse 래퍼. **리드는 전용 `/lead` 서브리소스(FR-IS-03 assignee 선례)** — 결합 PATCH 3-state 모호성 회피. 결합 PATCH는 name/description만 문자열 sentinel.
- **FR-4 검증(D4)**: 프로젝트 존재(404), 컴포넌트 존재+소속(404), 이름 중복(409, 활성 기준), 리드 실재(422, UserLookupPort). 도메인 정규화는 service가 도메인 메서드 경유(메모리 patch-merge-domain-bypass — DTO 검증은 1차방어만).
- **FR-4b 프로젝트 조회(D3/D4, sanity check 발견)**: issue-tracking에 프로젝트 조회(존재 + projectKey→id) 컴포넌트 신규 추가. projects는 issue-tracking 소유라 자체 repository(in-BC, 위반 아님). 현재 issue-tracking엔 프로젝트 단건 조회 컴포넌트 부재 → 신규. 동시 생성 race는 부분 유니크 인덱스 제약 위반 catch→409로 처리(pre-check만으로 TOCTOU 미방지).
- **FR-5 권한 리졸버(D4)**: `ComponentPermissionResolver` 포트 + AlwaysAllow(비prod) 구현 + !prod fallback 빈 + 부팅 가드(메모리 profile-scoped-bean-boot-failure). **인증 자체(401)는 SecurityConfig 필터가 보장**. actor 추출 + 실 권한판정은 FR-PM-03 이연(AlwaysAllow는 actor 무시) — IssueController가 actor를 SYSTEM 하드코딩하고 실추출을 미룬 선례 동형. PAT→userId 추출은 identity-access 서비스 필요(cross-BC)라 FR-PM-03 범위.
- **FR-6 테스트(D5)**: MockK 단위(도메인 불변식/service 분기) + Testcontainers 통합(CRUD happy + 404/409/422 + soft delete 제외 + 부분 유니크). TDD red→green→refactor.

### 범위 밖 (후속)
- 이슈↔컴포넌트 할당(FR-CM-02), 컴포넌트별 기본 담당자(FR-CM-03), 프론트 UI(D6), E2E(D7).
- prod 권한 실판정(FR-PM-03).

## 비기능 요구사항 (NFR)

- **NFR-1 BC 격리**: lead_user_id는 FK 없이 UserLookupPort로 검증. projects는 자기 BC 소유라 실 FK.
- **NFR-2 트랜잭션**: CRUD는 ApplicationService `@Transactional`. 컨트롤러는 트랜잭션 경계 미보유(IssueType 규약).
- **NFR-3 소프트 삭제**: DELETE는 항상 WHERE + deleted_at (절대 규칙, DATA.md §3).
- **NFR-4 정규화 단일 진입**: 이름 trim/검증은 도메인 Aggregate에서. service가 도메인 메서드 호출(불변식 우회 금지).

## API 인터페이스 (REST)

```
POST   /api/v1/projects/{projectIdOrKey}/components          → 201 {data:{id,projectId,name,description,leadUserId,createdAt,updatedAt}}
GET    /api/v1/projects/{projectIdOrKey}/components          → 200 {data:[...]}  (활성만, name 정렬)
GET    /api/v1/projects/{projectIdOrKey}/components/{id}     → 200 {data:{...}}
PATCH  /api/v1/projects/{projectIdOrKey}/components/{id}     → 200 {data:{...}}  (name/description, 문자열 sentinel)
PATCH  /api/v1/projects/{projectIdOrKey}/components/{id}/lead → 200 {data:{...}}  (리드 지정/해제 전용)
DELETE /api/v1/projects/{projectIdOrKey}/components/{id}     → 204
Authorization: Bearer <JWT 또는 PAT>
```

요청 바디:
- POST `{ name: String(필수,1~255), description: String?(≤1000), leadUserId: UUID? }`
- PATCH (결합) `{ name?: String, description?: String? }` — name 생략=무변경, description null/생략=무변경·""=클리어 (UpdateIssueRequest sentinel 규약).
- PATCH /lead `{ leadUserId: UUID? }` — null=해제, UUID=지정. leadUserId 키는 항상 존재(2-state, 모호성 없음).

에러 코드:
- 401 (미인증)
- 404 `PROJECT_NOT_FOUND` / `COMPONENT_NOT_FOUND`
- 409 `COMPONENT_NAME_DUPLICATE` (활성 기준)
- 422 `COMPONENT_LEAD_NOT_FOUND`
- 400 (Jakarta Validation — name 공백 등)

projectIdOrKey 해석: issue-tracking이 projects 소유 → 자체 조회로 key→id 또는 UUID 직접. 미존재 404.

## 데이터 모델 변경

- 신규 마이그레이션: `components` 테이블 + 부분 유니크 인덱스. issue-tracking 네임스페이스(Vxxx, BC prefix 정책 ADR).
- init_codegen.sql 미러(jOOQ 상수 생성, 메모리 jooq-init-codegen-mirror).
- projects.id FK(같은 BC). lead_user_id FK 없음(BC 격리).

## 엣지 케이스

- EC-1: 리드 변경은 전용 `PATCH .../components/{id}/lead { leadUserId: UUID? }` — leadUserId 키 항상 존재(null=해제/UUID=지정), 결합 PATCH의 생략 vs null 모호성 원천 제거. FR-IS-03 assignee 서브리소스 선례 동형(eng 리뷰 B1 반영, 결합 PATCH로는 UUID 해제/무변경 구분 불가).
- EC-2: 이름 중복은 활성 기준 — 소프트 삭제된 동명 컴포넌트는 충돌 아님(부분 유니크 인덱스).
- EC-3: 리드 검증 — leadUserId 생략/null은 검증 스킵, non-null만 UserLookupPort.exists. 도메인 우회 금지(patch-merge-domain-bypass).
- EC-4: projectIdOrKey가 소프트 삭제된 프로젝트 → 404 PROJECT_NOT_FOUND(활성만).
- EC-5: 다른 프로젝트의 componentId로 접근 → 404 COMPONENT_NOT_FOUND(프로젝트 소속 검증).
- EC-6: jOOQ init_codegen 미러 누락 시 repository 컴파일 불가(V005 선례).
- EC-7: ComponentPermissionResolver가 @Profile prod 단독이면 비prod 통합테스트 부팅 실패 → !prod fallback 필수(profile-scoped-bean-boot-failure).

## 제약 조건

- BC: issue-tracking 단일. lead 검증만 cross-BC 포트(UserLookupPort, shared-kernel). identity-access 직접 import 금지(ArchUnit).
- 새 외부 의존성 없음.
- 소프트 삭제·트랜잭션 경계·이름 정규화 단일진입 절대 규칙 준수.

## 측정 가능한 완료 기준

- [ ] 도메인 단위테스트: Component 불변식(빈 이름 거부/trim/rename/changeLead/softDelete).
- [ ] 통합테스트(Testcontainers): S1~S10 — 생성(리드有/無)/목록(삭제 제외)/수정(3-state)/소프트삭제/404×2/409/422/401.
- [ ] 부분 유니크 인덱스 검증: 활성 동명 거부 + 삭제 후 동명 재생성 허용.
- [ ] ktlint(신규 파일, 모듈 ktlintFormat 금지) + detekt 통과.
- [ ] init_codegen 미러 → repository 컴파일 OK.

## Brainstorming Check

✅ 통과 (직접 적대적 sanity check, office-hours 스킵 — 정의된 백엔드 FR + 도메인 grill 완료).

발견·반영:
1. **프로젝트 조회 부재(Gap 1)** — issue-tracking에 projectKey→id 해석/존재 검증 컴포넌트 없음 → FR-4b로 신규 추가 명시(in-BC, projects 소유). 동시 생성 race는 부분 유니크 제약 catch→409.
2. **actor 추출 경계(Gap 2)** — PAT actor 추출은 cross-BC라 FR-CM-01 불가 → 인증(401)은 Security 필터, actor+실판정은 FR-PM-03 이연(IssueController SYSTEM 하드코딩 선례). FR-5에 반영.
3. **3-state PATCH 모호성(EC-1)** — leadUserId 생략(무변경)/null(해제) 구분, FR-IS-03 assignee 전용 PATCH 선례.
4. **이름 중복 활성 기준(EC-2/S8)** — 부분 유니크 인덱스 `WHERE deleted_at IS NULL`, 삭제된 동명 재생성 허용.
5. **도메인 우회 차단(NFR-4)** — service가 도메인 정규화 메서드 경유(patch-merge-domain-bypass), DTO 검증은 1차방어.
6. **jOOQ init_codegen 미러(EC-6)** + **profile-scoped bean 부팅 가드(EC-7)** 메모리 교훈 선반영.

미해소 결정 없음 — 전부 선례/메모리 기반으로 스펙에 흡수.
