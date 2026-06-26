# FR-SR-03 PR2 — 저장 필터 공유 — 스펙

> slug: fr-sr-03-pr2-filter-shares · BC: search-export-import · type: backend
> 선행: PR1(#191) SavedFilter CRUD(PRIVATE) + 실행 완료. ADR `docs/decisions/2026-06-26-fr-sr-03-saved-filters.md`(D2/D3/D4).
> 범위: **백엔드 D1~D5**(공유 모델 + 가시성 + cross-BC 포트 + 403). 프론트 D6/D7(공유 모달 + 별표 UI)는 별도 PR.

## 작업 기준

완제품(production) 품질. PoC/임시 코드 금지. 절대 규칙(`DEVELOPMENT.md §1`) + 테스트 + 보안 + 에러 처리 모두 충족.

## 사용자 시나리오 (Given-When-Then)

- **S1 (공유 생성)**. Given 내가 소유한 필터, When `PUT /api/v1/filters/{id}` 바디에 `shares:[{shareType:"PROJECT",targetId:"PROJ"}]`를 보내면, Then 해당 프로젝트 멤버가 그 필터를 조회/실행할 수 있다.
- **S2 (그룹 공유)**. Given 필터, When `shares:[{shareType:"GROUP",targetId:"<groupId>"}]`로 저장하면, Then 그 그룹 소속 사용자가 필터를 볼 수 있다.
- **S3 (전체 공개)**. Given 필터, When `shares:[{shareType:"AUTHENTICATED"}]`로 저장하면, Then 로그인한 모든 사용자가 필터를 볼 수 있다.
- **S4 (공유받은 목록)**. Given 나에게 공유된 필터들, When `GET /api/v1/filters/shared`, Then 내가 소유하지 않지만 볼 수 있는 필터 목록을 받는다.
- **S5 (공유받은 실행)**. Given 나에게 공유된 필터, When `GET /api/v1/filters/{id}/search`, Then **내 권한으로** 검색이 실행된다(필터 소유자 권한 아님 — 권한 상승 불가).
- **S6 (비소유 수정 차단)**. Given 나에게 공유됐지만 내가 소유하지 않은 필터, When `PUT`/`DELETE`, Then **403 Forbidden**(볼 수는 있으나 수정 불가).
- **S7 (비가시 은닉)**. Given 나에게 공유되지 않은 타인 필터, When 임의 경로 접근, Then **404 Not Found**(존재 은닉, PR1 EC5 유지).
- **S8 (공유 해제)**. Given 공유된 필터, When 소유자가 `PUT` 바디에 `shares:[]`를 보내면, Then 모든 공유가 제거되어 PRIVATE로 돌아간다.

## 기능 요구사항 (FR)

- **FR-1**. 필터 공유 대상은 3종. `PROJECT`(특정 프로젝트 멤버) · `GROUP`(특정 사용자 그룹) · `AUTHENTICATED`(로그인 사용자 전체). PR1 ADR D2.
- **FR-2**. 공유는 **필터 본문 임베드**. `POST`/`PUT /api/v1/filters`의 바디에 `shares` 배열로 표현(Maxi 결정 — 전용 엔드포인트 아님). 교체(replace-all) 의미.
  - `shares == null`(필드 생략 **또는 명시적 null** — JsonNullable 미도입, PUT 전체교체 의미상 둘을 동일 취급) → 기존 공유 **유지**(부분 수정 허용 — name만 바꿀 때 공유 보존).
  - `shares == []` → 모든 공유 **제거**(PRIVATE 복귀).
  - `shares == [..]` → 해당 집합으로 **전체 교체**.
  - replace는 필터 update와 **단일 트랜잭션**. OCC version 충돌 시 롤백되어 공유 미변경(NFR-7).
- **FR-3**. 공유 대상 저장. `saved_filter_shares(filter_id, share_type, target_id)`. `target_id`: PROJECT=project_key, GROUP=group id 문자열, AUTHENTICATED=NULL.
- **FR-4 (가시성 OR 4경로)**. 사용자 V가 필터 F를 볼 수 있다 ⇔
  1. `F.ownerId == V`, 또는
  2. F에 `AUTHENTICATED` 공유 존재, 또는
  3. F에 `PROJECT` 공유가 있고 `target_id ∈ V의 프로젝트 키 집합`, 또는
  4. F에 `GROUP` 공유가 있고 `target_id ∈ V의 그룹 id 집합`.
- **FR-5 (읽기 경로 가시성화)**. `GET /{id}` · `GET /{id}/search`는 PR1의 owner-게이트(`getByIdForOwner`)에서 **가시성 게이트**로 전환. 비가시 → 404.
- **FR-6 (쓰기 경로 소유자 전용 + 403)**. `PUT /{id}` · `DELETE /{id}`(공유 변경 포함)는 소유자만. 가시(공유받음)하나 비소유 → **403** `SavedFilterForbiddenException`(PR1에서 제거한 데드코드 부활). 비가시 → 404(은닉).
- **FR-7 (공유받은 목록)**. 신규 `GET /api/v1/filters/shared` — V가 소유하지 않으면서 볼 수 있는 필터 목록(가시성 4경로 중 2~4). 기존 `GET /api/v1/filters`는 **소유만** 유지(하위호환). **페이지네이션 필수**(`page`/`size`, `/search`와 동일 정책 — size 1..100, page≥0) + **결정적 정렬**(`created_at ASC, id ASC` — AUTHENTICATED 공유가 전 사용자에게 증폭되므로 무제한 반환 금지, C1/NFR-3).
- **FR-8 (실행 안전)**. `GET /{id}/search`는 PR1처럼 `viewerUserId=actor`로 실행 — 공유받은 사용자도 자기 권한 범위 이슈만 본다(권한 상승 0).
- **FR-9 (응답 확장)**. `SavedFilterResponse`에 `shares: [{shareType, targetId}]`, `isOwner`(기존) 포함. 모든 읽기 응답(`GET /filters` 소유목록 · `GET /filters/shared` · `GET /{id}`)이 shares를 담는다 — 서비스 read 메서드가 `SavedFilterWithShares`(filter+shares) 반환, 목록은 `findByFilterIds` 배치 로드(N+1 차단, U4). 별표(favorite)는 본 백엔드 PR 범위 외 — 프론트가 기존 FR-UX-02 favorites API(target_type=FILTER)로 별도 조회.
  - **공유 목록 범위(C2 — 정보 노출 차단)**. 응답 `shares`는 요청자에 따라 달라진다.
    - **소유자**: 전체 공유 대상을 그대로 노출(공유 관리 주체).
    - **비소유 가시 viewer**: **자신이 매칭된 공유만** 노출 — `AUTHENTICATED`(있으면) + viewer 소속 PROJECT(`targetId ∈ viewer 프로젝트 키`) + viewer 소속 GROUP(`targetId ∈ viewer 그룹 id`). 다른 GROUP/PROJECT 대상은 응답에서 제외해 필터의 전체 공유 대상 목록(예: 다른 부서 그룹 존재)이 새지 않게 한다. 적용 경로는 `GET /{id}` · `GET /filters/shared`(비소유 결과 전부). 가시성 OR 술어와 동일 의미를 per-share로 적용하므로 가시 판정을 통과한 viewer는 최소 1개 매칭 공유를 보유한다(빈 목록 불가).
- **FR-10 (cross-BC 멤버십 포트)**. shared-kernel에 신규.
  - `GroupMembershipPort.groupIdsOf(userId: UUID): Set<String>` — identity-access(`UserGroupRepository`) 구현.
  - `ProjectMembershipPort.projectKeysOf(userId: UUID): Set<String>` — identity-access(`ProjectMembershipRepository` + projects 키 매핑) 구현.
  - 단일 가시성 판정/공유목록 모두 "viewer의 멤버십 집합" 조회 후 집합 포함으로 평가(포트 메서드 1개씩으로 충분).

## 비기능 요구사항 (NFR)

- **NFR-1 (fail-closed)**. 멤버십 포트는 **default 구현 금지**. 빈 Bean 부재 시 부팅 실패가 의도된 안전망(`IssueVisibilityPort` 선례). allow-all default 절대 금지(공유 누출). `@Profile`/테스트 컨텍스트 부팅 레시피는 plan에서 확정(`profile-scoped-bean-boot-failure` 회피).
- **NFR-2 (권한 상승 0)**. 실행은 viewer 권한(FR-8).
- **NFR-3 (DoS 방어)**. 필터당 공유 행 상한(`MAX_SHARES_PER_FILTER`, 예 50). `GET /shared`도 적정 상한/정렬.
- **NFR-4 (BC 격리)**. shares는 project_key/group_id를 **문자열**로 보관, cross-BC FK 없음(favorites 선례). 타 BC 접근은 shared-kernel 포트만. `SharedKernelBoundaryArchTest`(원시 타입만) 통과.
- **NFR-5 (멱등 공유)**. `UNIQUE(filter_id, share_type, target_id) NULLS NOT DISTINCT`로 AUTHENTICATED(target NULL) 중복 차단(`pg-null-distinct-on-conflict-idempotency`). 요청 내 중복은 dedupe.
- **NFR-6 (SQL 인젝션 방어)**. jOOQ 바인딩만(문자열 결합 금지).
- **NFR-7 (트랜잭션 원자성)**. 필터 update + 공유 replace는 단일 트랜잭션(부분 적용 금지).

## API 인터페이스 (REST)

| 메서드 | 경로 | 게이트 | 비고 |
|---|---|---|---|
| POST | `/api/v1/filters` | 인증 | 바디에 `shares?` 추가. 생성 + 공유 |
| GET | `/api/v1/filters` | 인증 | **소유만**(불변). 응답에 `shares` 포함 |
| GET | `/api/v1/filters/shared` | 인증 | **신규**. 공유받은(비소유) 목록. `page`/`size`(1..100) + created_at,id 정렬 |
| GET | `/api/v1/filters/{id}` | **가시성** | owner OR 공유. 비가시 404 |
| PUT | `/api/v1/filters/{id}` | **소유자**(403/404) | 바디에 `shares?` 추가. OCC version |
| DELETE | `/api/v1/filters/{id}` | **소유자**(403/404) | 하드 삭제 + shares cascade |
| GET | `/api/v1/filters/{id}/search` | **가시성** | viewer 권한 실행(불변) |

요청 DTO(추가).
```
ShareRequest { shareType: String?, targetId: String? }
SavedFilterCreateRequest { name, aqlQuery, projectKey, shares: List<ShareRequest>? }   // shares 추가
SavedFilterUpdateRequest { name, aqlQuery, version, shares: List<ShareRequest>? }      // shares 추가(null=유지)
```
응답 DTO(추가).
```
SavedFilterResponse { ...기존..., isOwner, shares: List<ShareDto> }
ShareDto { shareType: String, targetId: String? }
```

## 데이터 모델 변경

`V601__saved_filter_shares.sql`(search-export-import) + `init_codegen.sql` 미러(`jooq-init-codegen-mirror`).
```sql
CREATE TABLE saved_filter_shares (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filter_id  UUID        NOT NULL REFERENCES saved_filters(id) ON DELETE CASCADE,  -- 동일 BC, FK 허용
    share_type VARCHAR(20) NOT NULL CHECK (share_type IN ('PROJECT','GROUP','AUTHENTICATED')),
    target_id  VARCHAR(255),  -- PROJECT=project_key, GROUP=group id, AUTHENTICATED=NULL
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_filter_shares UNIQUE NULLS NOT DISTINCT (filter_id, share_type, target_id),
    CONSTRAINT ck_saved_filter_shares_target
        CHECK ((share_type = 'AUTHENTICATED') = (target_id IS NULL))  -- AUTHENTICATED만 NULL
);
CREATE INDEX idx_saved_filter_shares_filter ON saved_filter_shares (filter_id);
CREATE INDEX idx_saved_filter_shares_lookup ON saved_filter_shares (share_type, target_id);  -- /shared 조회
```
- 필터 하드 삭제 시 shares는 FK CASCADE로 동반 삭제(같은 BC라 FK 허용 — `join-table-fk-cascade-testcontainers-cleanup`).
- 하드 삭제(DATA.md §3, saved_filters 선례 일관). 소프트 삭제 불필요(외부 영구 참조 대상 아님).

## 엣지 케이스

- **EC1**. 생성 시 shares 포함 → 필터 + 공유 원자 생성.
- **EC2**. owner가 PUT으로 shares 교체 → 기존 전부 삭제 후 새 집합 삽입(단일 tx).
- **EC3**. AUTHENTICATED 공유 → 모든 로그인 사용자 가시.
- **EC4**. 공유받은(비소유) PUT/DELETE → **403**.
- **EC5**. 비가시 필터 get/search/put/delete → **404**(존재 은닉, PR1 유지).
- **EC6**. 필터 삭제 → shares cascade 삭제(고아 0).
- **EC7**. 요청 내 동일 공유 중복 → dedupe(멱등). DB는 UNIQUE NULLS NOT DISTINCT 안전망.
- **EC8**. 알 수 없는 shareType → **400**.
- **EC9**. PROJECT/GROUP인데 targetId 누락/blank → **400**. AUTHENTICATED인데 targetId 제공 → **400**(엄격).
- **EC10**. 공유 행 > 상한 → **400**.
- **EC11**. 멤버십 포트 Bean 부재 → **부팅 실패**(런타임 누출 아님, fail-closed).
- **EC12**. viewer가 프로젝트/그룹 0개 → AUTHENTICATED 공유 + 소유만 가시.
- **EC13**. owner가 본인이 멤버 아닌 프로젝트로 공유 → **허용**(MVP, 대상 존재/멤버십 검증 안 함). 누출 위험 분석: 공유받는 측은 실행 시 자기 권한 이슈만 보고(FR-8), 비소유 viewer에게 노출되는 건 필터 메타(name+AQL)와 **자신이 매칭된 공유 대상뿐**(전체 공유 대상 목록은 비노출 — EC18/C2). 향후 대상 존재·멤버십 검증은 후속.
- **EC18 (공유 대상 목록 비노출 — C2)**. 비소유 가시 viewer는 필터의 **전체 공유 대상 목록을 보지 못한다**. 응답 `shares`는 viewer가 매칭된 공유(AUTHENTICATED + 자신의 PROJECT/GROUP 대상)만 담고, 다른 GROUP UUID·다른 PROJECT 키는 제외한다(정보 노출 차단, 적대적 리뷰 C2 반영). 소유자만 전체 공유 대상을 본다. FR-9 "공유 목록 범위" 참조. (이전 "전체 공유 대상을 전 viewer에게 노출 — 저위험 수용" 문구는 본 EC로 정정됨.)
- **EC14**. `GET /shared`는 소유 필터 제외(소유는 `GET /filters`).
- **EC15**. owner는 공유 유무와 무관하게 항상 자기 필터 가시(자기공유 noop). **owner 단축경로** — 소유 확인 먼저, 비소유일 때만 멤버십 포트 호출(불필요 cross-BC 쿼리 회피, C6).
- **EC16 (대상 소프트삭제/삭제)**. 공유 대상 프로젝트 소프트삭제 → 어댑터가 `deleted_at IS NULL`로 제외 → viewer 키 집합에서 빠져 **매칭 안 됨**(fail-closed, 안전). 그룹 삭제도 동일. **잔존 share 행은 무해 잔류**(CASCADE 대상 아님, 누출 없음 — 매칭자 0) → 소유자 replace로만 정리. spec 동작으로 명문화.
- **EC17 (비로그인)**. AUTHENTICATED 공유라도 비로그인 요청은 actor 추출 단계에서 401 — 인증 사용자 전체이지 익명 아님.
- **EC8 매핑 주의**. shares 검증 실패(EC8~EC10)는 반드시 **400**으로 매핑. `ShareType.from(unknown)`이 catch-all(`Exception`→500)에 걸리지 않도록 **`IllegalArgumentException`**(도메인 일관, handleIllegalArgument 400)으로 통일(C3). enum `valueOf`/`NoSuchElement` 직접 노출 금지.
- **단일 가시성 술어(B3)**. 가시성 OR 4경로는 **repo SQL 술어 한 곳**(`findVisibleById` + `findSharedWith`가 동일 WHERE 조각 공유)으로 funnel. Kotlin/SQL 이중 평가 금지(`IssueVisibilityPort` "단일 source of truth" 도그마). 단건 가시 ⇔ 목록 포함 parity 테스트로 비대칭 차단.

## 제약 조건

- search-export-import BC 한정(공유 모델·CRUD). cross-BC는 shared-kernel 포트만(identity-access 구현은 같은 PR — `IssueVisibilityPort`/RecipientResolver 선례, BC 격리 예외 명문).
- 프론트(공유 모달 + 별표 UI)·favorites 연동은 본 PR 범위 외(D6/D7 별도 PR).
- FR 총수 변경 없음(FR-SR-03 기존 카운트). product D단계 체크박스 D1~D5는 본 PR 머지 시 `[x]` 동기화, D6/D7은 `[ ]` 유지.

## 측정 가능한 완료 기준

1. `V601` 마이그레이션 + init_codegen 미러 + jOOQ 생성 통과.
2. shared-kernel 포트 2종 + identity-access 구현 + `SharedKernelBoundaryArchTest` 통과.
3. 가시성 4경로 + 403(EC4)/404(EC5) 통합 테스트(Testcontainers) green.
4. 공유 replace(EC2)/cascade(EC6)/멱등(EC7)/검증(EC8~EC10) 테스트 green.
5. `GET /shared` 정확성(EC14, viewer 멤버십별) 테스트 green.
6. ktlint + **detekt 별도 실행**(부트스트랩 모듈 ktlint-only 함정) green.
7. `bash scripts/verify-master-plan.sh` 통과(FR 동기화).
8. 기존 PR1 테스트 회귀 0(owner 경로 동작 보존).

## 구현 단계 미해결(plan에서 확정)

- **U1 (해소)**. `target_id`는 ADR D2대로 **project_key 유지**. `ProjectMembershipPort.projectKeysOf(userId)` 구현은 `project_memberships m JOIN projects p ON m.project_id=p.id WHERE m.user_id=:u AND p.deleted_at IS NULL`로 키를 직접 반환 — identity-access의 **`ProjectDirectory` read-only cross-BC 선례**(같은 DB, projects 읽기 전용, 분리배포 시 SPI 교체)를 그대로 따른다. projects 테이블은 issue-tracking 소유이나 read-only 접근은 확립된 허용 패턴(BC 격리 위반 아님). id-vs-key deviation 불필요.
- **U2 (해소)**. GROUP `target_id` = **UserGroup.id(UUID) 문자열**. `GroupMembershipPort.groupIdsOf(userId): Set<String>`.
- **U3 (Task 9에서 해소)**. 멤버십 포트 fail-closed Bean의 통합테스트 부팅 레시피 — search 단독 컨텍스트는 identity-access 미로드라 **stub 포트 빈**(MembershipPortTestConfig, userId별 다른 집합 반환) 사용(`no-cross-bc-deployment-assembly` test-assembled 표준). 실 어댑터는 T4/T5 identity-access 모듈테스트로 검증.
- **U4 (해소)**. **분리 조회 + 서비스 조합** 채택(SavedFilter 도메인 불변, 표면 최소 변경). read 메서드는 `SavedFilterWithShares(filter, shares: List<SavedFilterShare>)` 반환. 목록은 `findByFilterIds(Set<UUID>)` **배치 로드**로 N+1 차단. 응답 변환은 `SavedFilterResponse.from(filter, shares, actorId)`로 시그니처 확장.

## Brainstorming Check

✅ 통과 (1 iteration, 코드 기반 sanity check). 점검 항목.
- API 형태(임베드) + shared 목록(전용 엔드포인트) → Maxi 결정 반영.
- U1(project_key↔id)·U2(group UUID) → 코드(`ProjectDirectory`/`UserGroup`)로 phantom 차단·해소.
- 가시성 404(은닉, EC5) vs 403(가시-비소유, EC4) 분기 명확화.
- 멱등(NULLS NOT DISTINCT, EC7) + CASCADE(EC6) + fail-closed 포트(EC11/NFR-1) 커버.
- EC13(미멤버 프로젝트 공유) 저위험 수용 명시 — 적대적 리뷰에서 재검토 대상으로 표시. **결과**: 적대적 리뷰 C2에서 "비소유 viewer가 전체 공유 대상 목록을 봄"이 정보 노출로 지적됨 → 비소유 viewer 응답을 매칭 공유만으로 제한(FR-9/EC18), 소유자만 전체 노출로 정정.
