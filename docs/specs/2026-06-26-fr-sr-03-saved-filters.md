# FR-SR-03 필터 저장 및 공유 — 스펙

> BC: search-export-import | type: backend | 선행: FR-SR-01·FR-SR-02
> ADR: [docs/decisions/2026-06-26-fr-sr-03-saved-filters.md](../decisions/2026-06-26-fr-sr-03-saved-filters.md)
> 범위: 백엔드 D1~D5 (프론트 D6/D7·즐겨찾기 UI 연동은 후속 PR)

## 개요

사용자가 AQL 검색을 이름 붙여 저장하고, 다른 사용자/그룹/프로젝트와 공유한다. 저장된 필터를 클릭하면 그 AQL을 **조회자(viewer) 권한**으로 재실행한다. 즐겨찾기(별표)는 기존 FR-UX-02 favorites(target_type=FILTER)를 재사용한다(본 PR 백엔드 범위 밖, 별도 컬럼 없음).

## 사용자 시나리오 (Given-When-Then)

### S1. 필터 저장 (PRIVATE)
- Given 로그인 사용자가 프로젝트 ATL에서 AQL `status = Open AND priority = High`를 작성
- When 이름 "내 급한 일"로 저장(공유 없음)
- Then saved_filters에 owner=나, project_key=ATL로 1행 생성, 나만 조회 가능

### S2. 필터 공유 (대상 지정)
- Given 내가 소유한 필터
- When 공유 대상에 PROJECT=ATL, GROUP=<백엔드팀 UUID>, 또는 AUTHENTICATED(로그인 전체) 지정
- Then saved_filter_shares에 대상별 행 생성, 해당 조건을 만족하는 사용자가 조회 가능

### S3. 공유받은 필터 실행
- Given Bob이 ATL 프로젝트 멤버이고, Alice가 ATL에 공유한 필터가 있음
- When Bob이 그 필터를 실행
- Then AQL이 **Bob의 visibility 보안 술어**로 실행됨(Alice 권한 아님). Bob이 못 보는 이슈는 결과에서 제외

### S4. 비소유자 수정 차단
- Given Alice 소유 필터를 Bob이 공유받아 조회 가능
- When Bob이 수정/삭제 시도
- Then 403 (소유자만 수정/삭제)

### S5. 비가시 필터 접근
- Given 나와 공유되지 않은 타인의 PRIVATE 필터
- When 조회/실행 시도
- Then 404 (존재 은닉 — 존재 probe 차단)

### S6. 즐겨찾기 (참고, 본 PR 밖)
- Given 조회 가능한 필터
- When 별표 토글 → 기존 `POST/DELETE /api/v1/favorites`(target_type=FILTER, target_id=필터 UUID)
- Then favorites에 사용자별 행. saved_filters는 무변경

## 기능 요구사항 (FR)

- **FR-1**. 필터 CRUD — 생성/조회(단건·목록)/수정/삭제. 필터 = {name, aqlQuery, projectKey, shares[]}.
- **FR-2**. 저장 시 AQL **구문 검증** — AqlLexer+AqlParser로 파싱, 실패 시 400(저장 거부). search BC가 파서를 소유하므로 재사용.
- **FR-3**. 이름 유니크 — 동일 owner 내 name 중복 금지(409).
- **FR-4**. 공유 모델 — share_type ∈ {PROJECT, GROUP, AUTHENTICATED}. PRIVATE = 공유 행 0개.
- **FR-5**. 가시성 판정 — 다음 OR. (a) owner==viewer, (b) AUTHENTICATED 공유 존재, (c) PROJECT 공유 & viewer가 해당 프로젝트 접근(BROWSE) 가능, (d) GROUP 공유 & viewer가 해당 그룹 소속.
- **FR-6**. 소유권 게이트 — 수정/삭제는 owner만(403). 가시 사용자 목록/조회/실행은 FR-5 충족 시 허용.
- **FR-7**. 목록 조회 — 내가 볼 수 있는 필터(소유 + 공유받음) 페이지네이션. `?projectKey=` 필터, `?ownedOnly=true` 옵션.
- **FR-8**. 실행 — `GET /api/v1/filters/{id}/search?page=&size=`. 저장된 필터를 로드(FR-5 가시성 게이트) 후 그 AQL을 **viewer 권한**으로 재실행. 기존 `IssueSearchPort` 재사용(검색 로직 중복 0). 가시성이 서버측에서 강제됨(프론트 우회 불가). 응답은 `POST /api/v1/search/aql`와 동형 `Page<AqlSearchHit>`.
- **FR-9**. 공유 대상 검증 — 생성/수정 시 PROJECT 대상은 owner가 접근 가능한 프로젝트여야(없거나 접근 불가 시 400/403), GROUP 대상은 실재 그룹이어야(없으면 400). shareType은 enum 3종만 허용(그 외 400), PROJECT/GROUP는 target_id 필수(누락 400), AUTHENTICATED는 target_id 무시.
- **FR-10**. project_key 불변 — 생성 시 고정. PUT은 name/aqlQuery/shares만 변경(project_key 변경 시 AQL·PROJECT 공유 정합 붕괴 방지).

## 비기능 요구사항 (NFR)

- **NFR-1 (보안·우회불가)**. 실행 시 AQL은 항상 viewer의 visibility 보안 술어와 AND 결합(FR-SR-02 패턴). 공유 필터로 권한 상승 불가.
- **NFR-2 (fail-closed)**. 가시성/멤버십 포트는 default 구현 없음(빈 Bean 부재 시 부팅 실패). allow-all default 금지(공유 누출). IssueVisibilityPort 선례.
- **NFR-3 (DoS)**. aqlQuery ≤ 2000자(기존 MAX_QUERY_LENGTH), name ≤ 100자, 목록 size ≤ 100.
- **NFR-4 (동시성)**. 수정은 OCC(version 컬럼). 충돌 시 409.
- **NFR-5 (BC 격리)**. cross-BC(그룹/프로젝트 멤버십)는 shared-kernel 포트만. saved_filters/saved_filter_shares 외 타 BC 테이블 직접 접근 금지(ArchUnit).
- **NFR-6 (성능)**. owner_id·project_key·shares(share_type,target_id) 인덱스. 목록 가시성 판정이 N+1 없이 동작.

## API 인터페이스 (REST) — `/api/v1/filters`

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | `/api/v1/filters` | 생성 | 인증 + projectKey BROWSE |
| GET | `/api/v1/filters` | 내가 보는 목록(페이지) | 인증 |
| GET | `/api/v1/filters/{id}` | 단건 | FR-5 가시성(else 404) |
| PUT | `/api/v1/filters/{id}` | 수정(name/aql/shares) | owner(else 403/404) |
| DELETE | `/api/v1/filters/{id}` | 삭제(하드) | owner(else 403/404) |
| GET | `/api/v1/filters/{id}/search` | 저장 필터 실행(viewer 권한) | FR-5 가시성(else 404) |

- 요청/응답 DTO: `SavedFilterRequest{name, aqlQuery, projectKey, shares:[{shareType, targetId}]}`(PUT은 projectKey 무시 — FR-10), `SavedFilterResponse{id, ownerId, name, aqlQuery, projectKey, shares[], createdAt, updatedAt, version, isOwner}`.
- 이름 중복은 unique 위반을 409로 변환 — Spring `DuplicateKeyException` + jOOQ-native SQLState 23505 **양 경로 catch**(메모리 jooq-exception-translator-409-dependency, 동시 생성 race 가짜그린 방지).
- actor 추출은 SearchController 패턴 재사용(SecurityContextHolder→UUID, 리소스 조회보다 먼저, probe 차단). 미인증 401.
- 에러: 400(구문/검증), 401(미인증), 403(비소유 수정), 404(비가시), 409(이름중복·OCC).

## 데이터 모델 변경 (V600~)

```sql
-- V600__saved_filters.sql (search-export-import 첫 마이그레이션)
CREATE TABLE saved_filters (
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL,        -- identity-access users.id, FK 없음(BC 격리)
    name        VARCHAR(100) NOT NULL,
    aql_query   TEXT         NOT NULL,
    project_key VARCHAR(50)  NOT NULL,        -- 실행 컨텍스트(AQL 본문 project 미지원)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_saved_filters_owner_name UNIQUE (owner_id, name)
);
CREATE INDEX idx_saved_filters_owner ON saved_filters (owner_id);
CREATE INDEX idx_saved_filters_project ON saved_filters (project_key);

CREATE TABLE saved_filter_shares (
    filter_id  UUID        NOT NULL REFERENCES saved_filters(id) ON DELETE CASCADE,
    share_type VARCHAR(20) NOT NULL,          -- PROJECT / GROUP / AUTHENTICATED
    target_id  VARCHAR(255),                  -- PROJECT=project_key, GROUP=group UUID, AUTHENTICATED=NULL
    CONSTRAINT uq_filter_share UNIQUE NULLS NOT DISTINCT (filter_id, share_type, target_id),
    CONSTRAINT ck_share_type CHECK (share_type IN ('PROJECT','GROUP','AUTHENTICATED'))
);
CREATE INDEX idx_filter_shares_target ON saved_filter_shares (share_type, target_id);
```

- `UNIQUE NULLS NOT DISTINCT`로 AUTHENTICATED(target NULL) 멱등 보장(메모리 pg-null-distinct-on-conflict-idempotency).
- 삭제는 **하드 삭제**(필터는 외부 영구 참조 대상 아님 — 이슈 키와 다름). shares는 CASCADE. favorites(notification, 별도 BC)의 dangling 행은 기존 ISSUE 즐겨찾기와 동일하게 허용(프론트가 detail 404 시 제외).

## 신규 cross-BC 포트 (shared-kernel)

- `GroupMembershipPort`(identity-access 구현) — `groupsOf(userId: UUID): Set<UUID>` (viewer의 소속 그룹). fail-closed.
- 프로젝트 접근 판정 — 기존 권한 인프라(BROWSE resolver / IssueVisibilityPort 계열) 재사용 가능성 우선 조사. 부재 시 신규 포트. **두 메서드 필요**. `canBrowse(userId, projectKey): Boolean`(생성 검증·단건 PROJECT 공유 판정) + `accessibleProjectKeys(userId): Set<String>`(목록 가시성 N+1 회피 — 한 번에 viewer 접근가능 프로젝트키 집합). ※ D-plan에서 기존 포트 grep 후 확정.

### 목록 가시성 SQL (N+1 회피)
```sql
SELECT f.* FROM saved_filters f
WHERE f.owner_id = :me
   OR EXISTS (SELECT 1 FROM saved_filter_shares s WHERE s.filter_id = f.id AND (
        s.share_type = 'AUTHENTICATED'
        OR (s.share_type = 'PROJECT' AND s.target_id = ANY(:myProjectKeys))
        OR (s.share_type = 'GROUP'   AND s.target_id = ANY(:myGroupIds))
   ))
```
`:myProjectKeys`=accessibleProjectKeys(me), `:myGroupIds`=groupsOf(me) 문자열화. 포트 2회 호출 후 단일 쿼리.

## 엣지 케이스

- **EC1** 잘못된 AQL 저장 → 400(파싱 실패).
- **EC2** 동일 owner 이름 중복 → 409.
- **EC3** 빈 name/aql/projectKey → 400. name>100·aql>2000 → 400.
- **EC4** 비소유자 PUT/DELETE(공유받아 가시) → 403.
- **EC5** 비가시 필터 GET/PUT/DELETE → 404(존재 은닉).
- **EC6** 존재하지 않는/접근불가 PROJECT 공유 대상 → 400/403. 존재하지 않는 GROUP → 400.
- **EC7** owner가 BROWSE 불가한 projectKey로 생성 → 403.
- **EC8** AUTHENTICATED 공유 중복 추가 → 멱등(NULLS NOT DISTINCT).
- **EC9** OCC version 불일치 PUT → 409.
- **EC10** 필터 삭제 후 favorites dangling → 허용(프론트 tolerant). saved_filter_shares는 CASCADE 정리.
- **EC11** 공유 필터 실행 시 viewer가 일부 이슈 비가시 → 보안 술어로 자동 제외(권한 상승 없음).
- **EC12** PROJECT 공유했으나 viewer가 그 프로젝트 멤버 아님 → 비가시(404).
- **EC13** 그룹 삭제 후 그 그룹으로 공유된 필터 → 멤버 0명 → owner만 가시(dangling share 무해).
- **EC14** 동시 동일 (owner,name) 생성 race → unique 위반 → 409(dual-catch). 가짜그린 회피 위해 single-thread 통합테스트 + repo isFailure만이 아닌 예외 변환 검증.
- **EC15** 잘못된 shareType 문자열 → 400. PROJECT/GROUP에 target_id 누락 → 400. AUTHENTICATED에 target_id 동봉 → 무시(저장 NULL).
- **EC16** PUT에 projectKey 다른 값 동봉 → 무시(FR-10, 기존 값 유지). 응답엔 기존 project_key.

## 제약 조건

- search-export-import 모듈 **첫 영속성** — build.gradle에 flyway/jOOQ codegen/postgres/Testcontainers 추가(FR-NT-01/FR-BD-01 부트스트랩 선례). 통합 테스트 부팅 신규.
- AQL 본문 `project` 필드 미지원(FR-SR-02 MVP) → project_key를 필터에 박제.
- 공유는 **view 전용**(MVP). edit-share·필터 복제·서버측 /results 실행은 후속.
- DATA.md 버전 범위 표에 search-export-import V600~V699 추가(전수 동기화).

## 측정 가능한 완료 기준

- [ ] V600 마이그레이션 + jOOQ 생성코드 + search BC Testcontainers 부팅 통합테스트 green
- [ ] CRUD 5개 엔드포인트 + 가시성/소유권 게이트 + AQL 구문검증
- [ ] GroupMembershipPort(+identity-access 구현) / 프로젝트 접근 판정 결선, fail-closed
- [ ] 가시성 판정 4경로(owner/AUTHENTICATED/PROJECT/GROUP) 통합테스트
- [ ] EC1~EC13 단위/통합 커버, ArchUnit BC 격리 통과
- [ ] visibility 보안 술어 우회 불가 회귀테스트(공유 필터를 저권한 viewer가 실행 → 결과 제한)

## Brainstorming Check ✅ 통과 (1회 iteration)

자가 적대 검토에서 gap 5건 발견 후 보강. (A) 서버측 실행 엔드포인트 GET /filters/{id}/search 확정 — 가시성 서버측 강제. (B) ProjectAccessPort에 accessibleProjectKeys 추가 — 목록 N+1 회피. (C) project_key 불변(FR-10). (D) 이름중복 409 dual-catch(EC14). (E) shareType/target_id 검증(EC15). Maxi 결정 필요 항목 없음.
