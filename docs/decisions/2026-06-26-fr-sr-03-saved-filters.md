<!-- FR-SR-03 저장된 필터 데이터 모델 + 공유 모델 + 즐겨찾기 재사용 + BC 첫 영속성 부트스트랩 결정 ADR -->

# ADR — FR-SR-03 필터 저장 및 공유: 데이터 모델 · 공유 · 즐겨찾기 · BC 영속성 부트스트랩

- 날짜: 2026-06-26
- 상태: 채택 (Accepted)
- 관련 FR: FR-SR-03
- 관련 PR: #191
- 선행 ADR: [2026-06-25-fr-sr-02-aql-parser-and-bc.md](2026-06-25-fr-sr-02-aql-parser-and-bc.md) · [2026-06-24-fr-ux-02-favorites.md](2026-06-24-fr-ux-02-favorites.md)

## 맥락 (Context)

FR-SR-03은 AQL 검색을 "저장된 필터(SavedFilter)"로 보관하고 다른 사용자와 공유하는 기능이다. SDD 10.3은 다음 모델을 명세한다.

```kotlin
data class SavedFilter(
    val id: Long, val ownerId: Long, val name: String, val aql: String,
    val shareScope: ShareScope,        // PRIVATE / PROJECT / ORG
    val shareTargetIds: List<Long>, val isFavorite: Boolean,
)
```

product 문서(§2.3 D3)의 데이터 모델은 `saved_filters(owner_id, name, query, visibility)`로, SDD 모델과 어긋난다(`isFavorite`·`shareTargetIds` 누락). 코드베이스 조사 결과 SDD 모델 그대로는 현 아키텍처와 충돌하며, 두 모델 모두 일부 수정이 필요하다.

- **search-export-import BC는 영속성 계층이 전혀 없다.** FR-SR-02(PR #189)에서 신설됐으나 현재 AQL 렉서/파서 + 검색 컨트롤러(`POST /api/v1/search/aql`)만 있고 issue-tracking에 포트(`IssueSearchPort`)로 위임한다. DB 의존성(flyway·jOOQ·postgres·Testcontainers)이 build.gradle에 없다. SavedFilter는 이 모듈의 **첫 영속 엔티티**다.
- **즐겨찾기는 이미 범용 시스템이 있다.** FR-UX-02(PR #184/#185)가 notification BC에 `favorites(user_id, target_type, target_id)` 테이블 + `FILTER` 타입을 만들어 두었다(당시 "FILTER 후속" = 저장된 필터 생기면 활성화 예약). 즐겨찾기는 본질적으로 (사용자 ↔ 대상) 다대다 관계다.
- **사용자 ID는 UUID다.** SDD의 `Long` 표기와 달리 BTS 전 모듈이 `users.id` = UUID를 쓴다(`UserLookupPort`, `favorites.user_id UUID`).
- **그룹/프로젝트 멤버십 cross-BC 포트가 없다.** 그룹 멤버십은 identity-access 내부(`JdbcUserGroupRepository`)에만 있고, 프로젝트 멤버십 판정도 shared-kernel에 노출 포트가 없다(`UserLookupPort`만 존재). 대상 지정 공유의 가시성 판정에 신규 포트가 필요하다.

## 결정 (Decision)

### D1. 즐겨찾기 — 기존 FR-UX-02 favorites 재사용 (SDD `isFavorite` deviation)

`saved_filters`에 `is_favorite` 컬럼을 두지 **않는다.** 저장된 필터의 즐겨찾기는 기존 범용 즐겨찾기(`favorites`, target_type=`FILTER`, target_id=필터 ID 문자열)를 재사용한다.

근거. 즐겨찾기는 사용자별 북마크다(Jira Cloud 동일 — 필터 응답의 `favourite`는 요청자 기준 계산값, `favouritedCount` 별도). 공유된 필터를 N명이 각자 별표하면 단일 `is_favorite` 컬럼으로는 "누가 별표했는지" 표현 불가 — 본질적으로 `(user_id, filter_id)` 다대다다. `favorites(user_id, target_type, target_id)`가 정확히 이 모델이며 `FILTER` 타입이 이미 예약돼 있다. SDD 10.3의 `isFavorite: Boolean` 인라인 표기는 본 ADR로 superseded한다(공유 시 모델 붕괴). product D3 스키마(컬럼 없음)와도 정합한다.

### D2. 공유 모델 — 대상 지정 공유 (Jira 파리티), `saved_filter_shares` 조인 테이블

SDD `shareScope`(단일 enum) + product `visibility`(단일 값)는 "특정 프로젝트/그룹 지정 공유"(Jira 핵심)를 표현하지 못한다. 별도 조인 테이블로 다대다 공유를 모델링한다.

- `saved_filters(id, owner_id UUID, name, aql_query, project_key, created_at, updated_at, version)` — 필터 본체. PRIVATE = 공유 행 0개(owner-only).
- `saved_filter_shares(filter_id, share_type, target_id)` — 공유 대상.
  - `share_type` ∈ `PROJECT`(특정 프로젝트 멤버) · `GROUP`(특정 사용자 그룹) · `AUTHENTICATED`(로그인 사용자 전체 = "공개"/ORG).
  - `target_id`: PROJECT=project_key 문자열, GROUP=group id 문자열, AUTHENTICATED=null/sentinel.
- 공유는 **view 전용**(MVP). 소유자만 수정/삭제. 공유받은 사용자는 조회/실행/복제 가능. edit-share는 후속.

`project_key` 포함 근거. AQL MVP는 본문 `project` 필드를 지원하지 않고(`IssueSearchQuery.projectKey` 별도 전달 — FR-SR-02 ADR D3), 검색 실행에 projectKey가 필수다. 따라서 저장된 필터는 실행 컨텍스트인 project_key를 박제해야 재실행 가능하다.

### D3. 가시성 판정 — 신규 cross-BC 멤버십 포트

"이 사용자가 이 필터를 볼 수 있는가"는 다음 OR로 판정한다.

1. `owner_id == viewer` (소유자), 또는
2. 공유 행 존재: `AUTHENTICATED`(로그인 사용자 전체 무조건 통과), 또는 `PROJECT`이고 viewer가 해당 프로젝트 멤버, 또는 `GROUP`이고 viewer가 해당 그룹 소속.

이를 위해 shared-kernel에 신규 포트.

- `GroupMembershipPort`(identity-access 구현) — `groupsOf(userId): Set<String>` 또는 `isMember(userId, groupId)`.
- 프로젝트 멤버십 판정 — 기존 권한 인프라(`IssueVisibilityPort`/permission resolver) 재사용 가능성 우선 조사, 부재 시 `ProjectMembershipPort` 신규.

**fail-closed 원칙**(`IssueVisibilityPort` 선례). 멤버십 포트는 default 구현 없이, 빈 Bean 부재 시 부팅 실패가 의도된 안전망. allow-all default 금지(공유 누출). 단 viewer 본인 소유 + AUTHENTICATED 공유는 포트 없이 판정 가능.

### D4. BC 영속성 부트스트랩 — search-export-import 첫 DB 도입

search-export-import build.gradle에 flyway·jOOQ codegen·postgres·Testcontainers를 추가한다(notification FR-NT-01 / agile-planning FR-BD-01 부트스트랩 선례). 마이그레이션 버전 범위는 DATA.md 할당표에 **V600~V699**를 신규 부여한다(automation V300 예약·notification V400·agile V500 다음 가용 범위). 첫 마이그레이션 `V600__saved_filters.sql`.

owner_id·target_id는 FK 없이 UUID/문자열 보관(BC 격리 — favorites 선례). 소프트 삭제 여부는 spec에서 확정(이슈 키 영속성과 달리 필터는 외부 영구 참조 대상 아님 → 하드 삭제 후보).

### D5. SR-01/02와의 관계 — 무변경

`GET /api/v1/issues?filter=`(FR-SR-01)·`POST /api/v1/search/aql`(FR-SR-02)는 그대로 둔다. SavedFilter는 AQL 문자열을 보관·재실행하는 별도 CRUD(`/api/v1/filters`)이며, 실행은 기존 `IssueSearchPort`를 재사용한다(검색 로직 중복 0).

## 결과 (Consequences)

- search-export-import가 첫 영속성을 갖는다 — build.gradle 확장 + 통합 테스트 부팅(Testcontainers) + jOOQ 생성 코드 모듈 추가. 향후 Export/Import FR도 이 인프라 위에 얹힌다.
- (PR2) `saved_filter_shares`는 하드삭제(조인테이블 성격 — replace=delete-then-insert + 필터 삭제 시 FK CASCADE). DEVELOPMENT.md §1.2 규칙7 하드삭제 ADR 커버.
- shared-kernel에 멤버십 포트(+identity-access 구현) 추가 → `SharedKernelBoundaryArchTest` 통과 필요(원시 타입만).
- DATA.md 버전 범위 표에 search-export-import V600~ 추가(전수 동기화). fr-index FR-SR-03 BC 매핑 무변경(카운트 영향 0).
- SDD 10.3 모델(`isFavorite`·`shareTargetIds: List<Long>`·`ShareScope` 단일 enum·`Long` id)은 본 ADR로 정정 — SDD 갱신 시 deviation 주석 반영.
- 범위 큼(BC 부트스트랩 + CRUD + 공유 + 신규 포트). **백엔드 D1~D5 / 프론트 D6~D7 분리**(FR-SR-01/02 선례), 필요 시 백엔드도 PR 분할(부트스트랩+CRUD+PRIVATE → 공유) 검토 — plan에서 확정.

## 대안 (Rejected)

- **SDD `isFavorite` 인라인 컬럼.** 기각 — 공유 필터를 다수가 별표할 때 모델 붕괴(사용자별 아님). 기존 favorites가 정확한 모델.
- **`shareScope` 단일 enum(SDD) / `visibility` 단일 값(product).** 기각 — "특정 프로젝트/그룹 지정 공유"(Jira 핵심, Maxi 확정 범위) 표현 불가. 조인 테이블 필요.
- **issue-tracking 모듈에 saved_filters 두기(SR-01 물리 위치 선례).** 기각 — SavedFilter는 issue 테이블·jOOQ 접근 불필요한 순수 메타데이터다. search BC 자체 영속성을 갖는 게 책임 정합상 옳고, BC가 영구히 DB 없이 남는 것을 회피한다.
- **그룹/프로젝트 멤버십을 search 모듈이 직접 조회.** 기각 — BC 격리 위반(ArchUnit). cross-BC는 shared-kernel 포트 경유.
