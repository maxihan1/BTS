# FR-SR-03 D6/D7 — 저장 필터 저장/공유 모달 + 별표(FILTER) UI — 스펙

> slug: fr-sr-03-d6-d7-saved-filter-ui · type: ui · BC: search-export-import
> 백엔드 완료: PR1 #191 + PR2 #193 (검증 완료, BLOCKER 3종 해소·NIT 1건). 본 스펙은 **프론트 view layer 소비만**.
> 직접 기술 스펙(office-hours/design-shotgun 스킵 — 백엔드 동결 계약 + 기존 페이지 통합, 메모리 [[bts-spec-office-hours-mismatch]]).

## 0. 범위 결정 (Maxi 확정, AskUserQuestion 2026-06-27)

- **UI 노출 = `/search` 페이지 통합만**. 전용 `/filters` 관리 페이지 없음.
- **공유 대상 = AUTHENTICATED + PROJECT(이 필터의 projectKey)만**. GROUP 보류(그룹목록 API admin전용 + 프로젝트목록 API 부재 + group UUID 직접입력 UX 불량). 백엔드는 GROUP 지원하나 본 PR UI에서 미노출.
- **별표(FILTER 즐겨찾기) 이번에 활성화**. FR-UX-02 백엔드 기지원, 프론트만 배선.
- 백엔드 변경 0 (순수 프론트). 신규 백엔드 API 없음.

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 현재 검색을 저장
- **Given** `/search`에서 AQL 쿼리를 입력해 검색한 상태(쿼리 비어있지 않음)
- **When** "저장" 버튼 → 모달에서 이름 입력 → 저장
- **Then** 현재 `aqlQuery` + 현재 `projectKey`가 새 저장 필터로 생성(POST), "내 필터" 목록에 즉시 반영, 토스트 성공.

### S2. 내 저장 필터 불러오기
- **Given** 저장한 필터가 있음
- **When** "필터" 드롭다운 → "내 필터"에서 하나 클릭
- **Then** `/search?filterId=<id>`로 이동 → SearchPage가 필터를 조회해 AQL·projectKey를 세팅하고 자동 실행 → 결과 표시.

### S3. 필터 공유 (소유자)
- **Given** 내가 소유한 저장 필터
- **When** 드롭다운 항목의 "공유" → 모달에서 "이 프로젝트 멤버에게"/"모든 로그인 사용자에게" 토글 → 저장
- **Then** PUT으로 shares replace-all 전송(미편집 GROUP share 보존), 토스트 성공.

### S4. 공유받은 필터 사용 (비소유자)
- **Given** 다른 사용자가 PROJECT/AUTHENTICATED로 공유한 필터가 나에게 가시
- **When** "필터" 드롭다운 → "공유받은 필터"에서 클릭
- **Then** S2와 동일하게 로드·실행. **편집/삭제/공유 버튼은 표시 안 됨**(isOwner=false).

### S5. 필터 편집/삭제 (소유자)
- **When** 드롭다운 항목의 "편집"(이름/AQL 수정) 또는 "삭제"
- **Then** PUT(OCC version) / DELETE(확인 후). 목록 갱신. OCC 충돌(409) 시 "다른 곳에서 수정됨, 새로고침" 안내.

### S6. 별표
- **When** 저장 필터 항목의 ★ 토글
- **Then** FR-UX-02 favorites(FILTER, targetId=필터id) 추가/제거. Header ⭐ 드롭다운 "필터" 그룹에 이름과 함께 노출 → 클릭 시 `/search?filterId=<id>` 로드.

## 2. 기능 요구사항 (FR)

> 모든 FR은 **기존 백엔드 계약 소비**. 신규 엔드포인트 없음.

- **FR-1 저장**. `/search` 검색바 영역에 "저장" 액션. 모달: `name`(필수, ≤100자), `aqlQuery`=현재 쿼리, `projectKey`=현재 projectKey. 빈 쿼리면 저장 액션 비활성. → `POST /api/v1/filters`.
- **FR-2 내 필터 목록**. "필터" 드롭다운에 `GET /api/v1/filters`(소유, 배열). 항목 클릭 → `/search?filterId=<id>` 네비게이션.
- **FR-3 공유받은 필터 목록**. 같은 드롭다운 "공유받은 필터" 섹션에 `GET /api/v1/filters/shared?page=0&size=50`(비소유). 클릭 → 동일 로드.
- **FR-4 필터 로드(딥링크)**. `/search?filterId=<id>` 진입 시 SearchPage가 `GET /api/v1/filters/{id}`로 해석 → `q`=aqlQuery, `projectKey`=filter.projectKey 세팅 후 검색 실행. 404(비가시/삭제) → 토스트 "필터를 찾을 수 없습니다" + 일반 검색 화면 유지.
- **FR-5 편집**. 소유 항목만 "편집" → 모달(name/aqlQuery 수정, version은 로드된 값). → `PUT /api/v1/filters/{id}`.
- **FR-6 삭제**. 소유 항목만 "삭제"(확인) → `DELETE /api/v1/filters/{id}`(204).
- **FR-7 공유 모달**. 소유 항목만 "공유". 토글 2개:
  - "이 프로젝트(<projectKey>) 멤버에게" ↔ `{shareType:'PROJECT', targetId:<filter.projectKey>}`
  - "모든 로그인 사용자에게" ↔ `{shareType:'AUTHENTICATED', targetId:null}`
  - **★ 보존 규칙**: 제출 shares = (편집 토글 결과) + (응답에 있던 그 외 share, 즉 GROUP 또는 본 PR이 다루지 않는 PROJECT 키 등 그대로 유지). replace-all이므로 미보존 시 데이터 손실. → `PUT /api/v1/filters/{id}` (version 동반).
- **FR-8 별표(FILTER)**. `FAVORITE_TARGET_TYPES`에 `FILTER` 추가, 저장필터 항목에 `FavoriteButton(targetType='FILTER', targetId=필터id)` 재사용. `FavoritesMenu`(Header ⭐)에 FILTER 그룹 메타 추가 — 라벨=필터 이름(아래 §5 해석), 클릭 경로=`/search?filterId=<id>`.
- **FR-9 에러 처리**. 백엔드 ProblemDetail 봉투(errorCode) 분기: 400 `SEARCH_VALIDATION_FAILED`/`SEARCH_SYNTAX_ERROR`(입력 오류 인라인), 403 `SEARCH_FILTER_FORBIDDEN`(비소유 쓰기 — UI 게이팅으로 사실상 차단, 방어적 토스트), 404 `SEARCH_FILTER_NOT_FOUND`, 409 `SEARCH_FILTER_NAME_CONFLICT`(이름 중복)·`SEARCH_FILTER_CONFLICT`(OCC), 401 `SEARCH_UNAUTHENTICATED`.

## 3. 비기능 요구사항 (NFR)

- **계약 정합**: 프론트 Zod 스키마는 `SavedFilterDtos.kt` 실측과 1:1. `@JsonInclude` 부재 → null 명시 포함이므로 `targetId`/`createdAt`/`updatedAt`는 `.nullable()`, `.default()` 금지(메모리 [[frontend-zod-backend-dto-contract-gap]]). 저장필터 응답은 **`{data:}` 래퍼 없음**(favorites와 다름).
- **api 관례**: search BC는 Bearer 토큰 → CSRF skip. 기존 `api/search.ts`처럼 `apiGet`/`apiPost`/`apiFetch` 재사용(메모리 [[frontend-api-convention-per-bc]]).
- **TanStack Query**: queryKey `['saved-filters','owned']`, `['saved-filters','shared',page]`, `['saved-filter', id]`. mutation 후 `['saved-filters']` 접두 invalidate. 별표는 기존 `['favorites']` 키 재사용.
- **상태 보존**: 검색 결과 렌더링은 기존 SearchPage 로직 재사용(추가 렌더 경로 신설 금지).
- **i18n**: 라벨 단일 출처 `src/i18n/saved-filter-labels.ts`(신규). 콜론 문장종결 금지(ko.test 패턴).
- **접근성**: 모달은 기존 Radix Dialog 패턴(Title/aria). 드롭다운은 Radix DropdownMenu.

## 4. 프론트 데이터 모델 (Zod / TS — 신규 `api/saved-filters.ts`)

```ts
// 백엔드 SavedFilterDtos.kt 1:1
shareTypeEnum = z.enum(['PROJECT','GROUP','AUTHENTICATED'])   // GROUP은 수신 가능(보존용), UI는 PROJECT/AUTHENTICATED만 편집
shareDtoSchema = z.object({ shareType: shareTypeEnum, targetId: z.string().nullable() })
savedFilterSchema = z.object({
  id: z.string().uuid(), ownerId: z.string().uuid(),
  name: z.string(), aqlQuery: z.string(), projectKey: z.string(),
  createdAt: z.string().nullable(), updatedAt: z.string().nullable(),
  version: z.number().int(), isOwner: z.boolean(),
  shares: z.array(shareDtoSchema),
})
// 요청
createReq = { name, aqlQuery, projectKey, shares?: ShareReq[] }
updateReq = { name, aqlQuery, version, shares?: ShareReq[] }   // shares null=유지 / []=제거 / [..]=교체
shareReq  = { shareType: string, targetId: string | null }
```

API 함수: `fetchOwnedFilters()`(GET) · `fetchSharedFilters(page,size)`(GET /shared) · `fetchFilter(id)`(GET /{id}) · `createFilter(req)`(POST) · `updateFilter(id,req)`(PUT) · `deleteFilter(id)`(DELETE).

## 5. 설계 포인트 (plan에서 구체화)

- **로드 메커니즘 = 딥링크 `/search?filterId=<id>`**. 전용 실행 엔드포인트(`GET /{id}/search`)는 미사용 — 기존 SearchPage가 AQL을 그대로 실행하므로 viewer=현재 사용자(권한상승 0 동일) + AQL 편집 투명성 + 코드 최소. router의 `/search` validateSearch에 `filterId?` 추가.
- **Header ⭐ FILTER 라벨**: favorites 응답엔 필터 이름이 없음(targetId=UUID만). `FavoritesMenu`가 FILTER 항목별로 `fetchFilter(id)`(React Query 캐시, 소수 N) 조회해 이름 표시. 404(삭제됨) → 항목 숨김 또는 흐리게. 본 PR은 **숨김** 채택(stale 즐겨찾기 비표시).
- **공유 모달 GROUP 보존**: 로드된 `filter.shares`에서 PROJECT(자기 projectKey)·AUTHENTICATED만 토글에 바인딩, 나머지(GROUP·타 projectKey)는 그대로 보관했다가 제출 배열에 합침.
- **드롭다운 vs 모달 분리**: "필터" 드롭다운(목록/로드/별표/소유자 액션 진입) + SaveFilterDialog(생성/편집) + ShareFilterDialog(공유). 3 컴포넌트.

## 6. 엣지 케이스

- **EC1** 빈 AQL에서 저장 시도 → 저장 버튼 비활성(클라 차단).
- **EC2** 이름 중복 저장/편집 → 409 `SEARCH_FILTER_NAME_CONFLICT` → 모달 인라인 "이미 사용 중인 이름입니다".
- **EC3** 편집 중 OCC 충돌 → 409 `SEARCH_FILTER_CONFLICT` → "다른 곳에서 수정됨, 새로고침 후 다시" + 목록 invalidate.
- **EC4** 공유 편집 시 기존 GROUP share 보존(미보존 = 데이터 손실 회귀). → 보존 단위 테스트 필수.
- **EC5** 비소유 필터: 편집/삭제/공유/별표 중 별표는 허용(공유받은 필터도 즐겨찾기 가능), 편집/삭제/공유 버튼은 미표시. 방어적으로 PUT/DELETE 직접 호출 시 403 토스트.
- **EC6** 딥링크 `filterId` 404(삭제/비가시) → 토스트 + 일반 검색 유지(앱 안 깨짐).
- **EC7** 공유받은 필터 목록 페이지네이션: size=50로 1페이지만 로드(MVP). 50 초과 시 "더 보기"는 후속(스펙 미포함, 드롭다운 단순 목록).
- **EC8** AQL 검증 실패한 필터 로드 시: 백엔드가 저장 시점에 AQL 검증하므로 저장된 필터는 유효. 단 필드 미지원 등 런타임 오류는 기존 SearchPage 에러 처리에 위임.
- **EC9** 별표 토글 실패(네트워크) → 기존 FavoriteButton 토스트 처리 재사용.

## 7. 측정 가능한 완료 기준

- D6: 저장/불러오기/편집/삭제/공유(AUTHENTICATED+PROJECT)/별표 6개 흐름이 `/search`에서 동작. 단위 테스트(api Zod 계약 + 컴포넌트 + GROUP 보존 EC4)·`pnpm typecheck`·`pnpm lint` 그린.
- D7: E2E(MSW) — 저장→목록→불러오기 실행, 공유→공유받은측 가시, 별표→Header 노출, 비소유 편집버튼 부재, OCC 충돌 안내. 기존 search/favorites E2E 회귀 0.
- `pnpm verify`(lint+typecheck+test+build) 그린.

## Brainstorming Check

✅ 통과 (1 iteration, 적대적 자체 검토). Maxi 결정 필요 gap 0. plan 흡수 항목.
- 저장 대상 쿼리 = 검색 입력창 현재 값(제출 쿼리). 빈 값 → 저장 비활성(EC1).
- 빈 목록 상태("저장된 필터 없음") · 클라 길이검증(name≤100/aqlQuery≤2000/projectKey≤50, 백엔드 400 선제 차단).
- Header ⭐ FILTER 이름 조회 N요청 React Query 캐시 흡수 · 삭제필터(404) 숨김(§5).
- D7 MSW 저장필터 store = userId별 소유 + 가시성(shared) 시뮬레이션 · 별표 store 재사용.
- 기존 search/favorites E2E 회귀 0 확인 필수(메모리 [[ui-pr-defer-e2e-regression-latent]]).
