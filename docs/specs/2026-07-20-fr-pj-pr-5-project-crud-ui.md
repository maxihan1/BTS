<!-- FR-PJ PR-5 프론트 UI 스펙 — 프로젝트 생성/목록/설정/아카이브 4화면 + 백엔드 노출 2건(archived·canCreateProject). 마스터 스펙 §9.2 PR-5 상세화 -->
# FR-PJ PR-5 — 프로젝트 생성·목록·설정·아카이브 UI — 스펙

> slug: fr-pj-pr-5-project-crud-ui · type: ui(+backend 2필드) · 2026-07-20
> 마스터 스펙: `docs/specs/2026-07-17-project-management-crud.md` §9.2 PR-5(D6 프론트 UI)
> 백엔드 CRUD/archive는 PR-1~4(#277·#282·#283·#285)로 완비. 이 PR = **읽기 노출 2건 + 프론트 UI + FR-PJ 완료마킹**.

## 0. 스코프 확정 (Maxi 결정, 2026-07-20)

| # | 결정 | 값 |
|---|---|---|
| D1 | 디자인 접근 | **기존 ADS v2 그대로 적용** (design-shotgun 없음 — FR-UX-06가 시스템 확정) |
| D2 | 생성 방식 | **전용 라우트 `/projects/new`** (issues.new 선례) |
| D3 | 설정/아카이브 배치 | **설정 danger zone**(name 편집 + archive/unarchive) + **목록은 보기 전용** |
| D4 | 백엔드 노출 갭 | **둘 다 노출** — `ProjectResponse.archived`(issue-tracking) + `whoami.canCreateProject`(identity-access) |

**한 PR = 한 BC 관례 deviation.** 이 PR은 issue-tracking + identity-access 2개 백엔드 BC + apps/web를 건드린다. UI 기능이 cross-BC 읽기 노출을 필요로 하는 정당한 경우(마스터 스펙 PR-2 D10 2-BC 예외 선례 동형). **PR 본문·게이트1에 근거 명시.** 두 백엔드 변경은 read-only 노출(마이그레이션 0·신규 로직 0).

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 프로젝트 목록 조회 (FR-PJ-02)
```
Given  로그인 사용자
When   /projects 진입
Then   접근 가능한(멤버십) 활성 프로젝트만 name 오름차순 테이블로 표시.
       접근 불가 프로젝트는 목록에 없다(존재 비노출, PJ2-4).
       각 행: key · name. 행 클릭 → /projects/{key}/board 진입.
```

### S2. 아카이브 필터 토글 (FR-PJ-04, 보기 전용)
```
Given  /projects 목록
When   "아카이브 표시" 토글 on
Then   ?archived=true 로 재조회 → 아카이브된 프로젝트만 표시. 기본은 활성만(S5, PJ2-1).
```

### S3. 프로젝트 생성 (FR-PJ-01)
```
Given  canCreateProject=true 사용자
When   "새 프로젝트" → /projects/new → key="ATLAS", name="Atlas Issues" 제출
Then   POST /api/v1/projects → 201 → /projects/{key}/board 로 이동(생성자 자동 admin).
Edge   key 중복 → 409 "이미 사용 중인 키". 형식 위반 → 400/폼 검증. 권한 없음 → 403 안내.
```

### S4. "새 프로젝트" 버튼 게이팅 (FR-PJ-01 / FR-PM-10)
```
Given  로그인 사용자
When   /projects 진입
Then   whoami.canCreateProject=true(=grant OR isSystemAdmin) 일 때만 "새 프로젝트" 버튼 표시.
       false 면 버튼 미표시(하지만 백엔드가 최종 방어 — fail-closed).
```

### S5. 프로젝트 이름 변경 (FR-PJ-03)
```
Given  PROJECT_ADMIN(=ComponentPermission.UPDATE) 사용자, 활성 프로젝트
When   /projects/{key}/settings/details 에서 name 수정 후 저장
Then   PATCH /api/v1/projects/{key} → 204 → 재조회로 반영.
Edge   비-admin → 액션 숨김 + 백엔드 403. 미존재/비멤버 → ProjectNotFoundScreen(404, 존재 비노출).
```

### S6. 아카이브 / 해제 (FR-PJ-04)
```
Given  PROJECT_ADMIN, 설정 danger zone
When   활성 프로젝트에서 "아카이브" 클릭
Then   POST /api/v1/projects/{key}/archive → 200(archivedAt non-null) → 상태 아카이브로 전환.
When   아카이브 프로젝트에서 "아카이브 해제" 클릭
Then   POST .../unarchive → 200(archivedAt null) → 활성 전환. (멱등 200)
Edge   아카이브 상태에서 name 편집 시도 → 백엔드 409(설정 잠금, PJ3-2) → 폼 비활성 + 안내.
```

## 2. 기능 요구사항 (FR)

이 PR은 신규 FR을 만들지 않는다. **기존 FR-PJ-01~04의 UI 표면 + 읽기 노출**을 구현하고 **완료마킹**한다.

### 2.1 백엔드 읽기 노출 (2건, read-only, 마이그레이션 0)
| ID | 내용 | BC / agent |
|---|---|---|
| BE-1 | `ProjectResponse` 에 `archived: Boolean` 추가 = `project.archivedAt != null`. 목록·단건 GET 응답에 포함. `ProjectResponse.from` 매핑만. | issue-tracking / backend-engineer |
| BE-2 | `WhoamiResponse` 에 `canCreateProject: Boolean` 추가 = `hasGlobalPermission(actor, CREATE_PROJECT)`(grant OR isSystemAdmin). whoami 조립 시 resolver 호출. | identity-access / security-engineer |

### 2.2 프론트 화면 (apps/web, frontend-engineer)
| ID | 화면 | 라우트(신설) | 소비 API |
|---|---|---|---|
| FE-1 | 목록 | `projects.index.tsx` → `/projects` | GET /projects?archived |
| FE-2 | 생성 | `projects.new.tsx` → `/projects/new` | POST /projects |
| FE-3 | 설정(일반) | `projects.$projectKey.settings.details.tsx` → `/projects/{key}/settings/details` | GET /{key}, PATCH /{key}, POST /archive·/unarchive, GET /me/project-permissions |
| FE-4 | 사이드바 연동 | `ProjectTree` 설정 그룹에 "일반" 링크 추가(11→12) | — |

### 2.3 API 클라이언트 확장 (apps/web/src/api)
- `api/projects.ts`: `projectSchema` 에 `archived: z.boolean()` 추가(BE-1 계약). 신규 함수 `createProject(key,name)`·`getProject(idOrKey)`·`updateProjectName(idOrKey,name)`·`archiveProject(idOrKey)`·`unarchiveProject(idOrKey)`. `listProjects(archived)` 는 기존 재사용.
- `api/schemas.ts`: `WhoamiResponseSchema` 에 `canCreateProject: z.boolean()` 추가(BE-2 계약). **인라인 whoami mock ~40파일 fanout 주의** → 하위호환 위해 `.optional()`(키 부재 허용, 기존 선례) 여부는 plan에서 결정. **★DTO invent 금지** — 반드시 BE-1/BE-2 먼저(또는 동시) 구현 후 스키마 강화([[frontend-zod-backend-dto-contract-gap]]).
- 훅: `use-projects.ts`(archived 파라미터), `use-project.ts`(단건, 신규), 생성/수정/archive mutation 훅. `use-project-permissions.ts` 재사용(MANAGE_COMPONENTS 게이트).

### 2.4 완료마킹 (전수 동기화 8종)
FR-PJ-01~04 를 완료로 마킹. **FR 총수 129 불변**(FR-PJ 는 이미 카운트됨 — 마킹은 진척 표시). `bash scripts/verify-master-plan.sh` 통과 필수. 동기화 대상: fr-index.md · product/*.md(D단계 체크박스·헤더) · README.md · CLAUDE.md · docs/decisions or plans · progress.html(dashboard 재생성) · Obsidian · 자동 메모리.

## 3. 비기능 요구사항 (NFR)
- **디자인**: ADS v2 토큰·PageLayout/PageHeader/Breadcrumb·ui/ 프리미티브만. 하드코딩 색·신규 디자인 변형 금지.
- **접근성 계약**([[frontend-nav-aria-label-e2e-contract]]): 페이지당 `<h1>` 단 하나(PageHeader 소유). 사이드바 h1 금지. `검색` 라벨 중복 금지. 신규 "프로젝트" 라벨은 기존 ProjectTree "프로젝트" substring 함정 주의([[playwright-getbyrole-exact-strict-mode]] — exact).
- **권한 게이팅**: 생성=whoami.canCreateProject. 설정 액션=useProjectPermissions MANAGE_COMPONENTS. **fail-closed** — 백엔드가 최종 방어, 프론트는 UX 편의.
- **에러 처리**: 404/비멤버=ProjectNotFoundScreen(재사용, 존재 비노출). 403=액션 숨김+실패 시 안내. 409=중복key/아카이브잠금 명시 메시지.
- **패턴 계승**: RouteAdapter(useParams) + Page(props) 분리(설정 페이지 단위 테스트 가능, members 선례).

## 4. API 인터페이스 (소비 — 전부 기존, BE-1/BE-2만 확장)
| Method | Path | 권한 | 응답 |
|---|---|---|---|
| GET | `/api/v1/projects?archived` | 인증(멤버십 필터) | `{data:[{id,key,name,archived}]}` (BE-1) |
| GET | `/api/v1/projects/{idOrKey}` | BROWSE | `{data:{id,key,name,archived}}` (BE-1) |
| POST | `/api/v1/projects` | CREATE_PROJECT | 201 `{data:{...}}` / 409 중복 |
| PATCH | `/api/v1/projects/{idOrKey}` | PROJECT_ADMIN | **204 No Content**(재조회) |
| POST | `/api/v1/projects/{idOrKey}/archive` | PROJECT_ADMIN | 200 `{data:{archivedAt}}` 멱등 |
| POST | `/api/v1/projects/{idOrKey}/unarchive` | PROJECT_ADMIN | 200 `{data:{archivedAt:null}}` 멱등 |
| GET | `/api/v1/users/me/whoami` | 인증 | + `canCreateProject` (BE-2) |
| GET | `/api/v1/users/me/project-permissions?projectKey` | 인증 | `{permissions:{MANAGE_COMPONENTS,...}}` (기존) |

## 5. 데이터 모델 변경
**없음.** `projects.archived_at`(V037, PR-4)·`global_permission_grants`(V036, PR-1) 이미 존재. 마이그레이션 0.

## 6. 엣지 케이스
- EC-1 목록 빈 상태: 접근 가능 프로젝트 0 → empty-state. canCreateProject면 "새 프로젝트" CTA.
- EC-2 생성 key 형식: 영문 대문자+숫자(glossary). 프론트 검증 + 백엔드 400/409 이중.
- EC-3 아카이브 프로젝트 name 편집: 백엔드 409 → 폼 비활성+안내(로드 시 archived로 선반영).
- EC-4 생성 직후 이동: 201 응답의 key로 `/projects/{key}/board`. (초기 이슈 생성은 R6 백필로 정상, PR-2 T13 hot-fix.)
- EC-5 canCreateProject=false가 /projects/new 직접 URL 진입: 페이지가 403 안내 or 라우트 가드. **fail-closed** — POST가 최종 방어.
- EC-6 whoami canCreateProject 키 부재(구 클라이언트/mock): `.optional()` 하위호환 → 부재 시 false 취급(버튼 숨김, 안전).
- EC-7 archived 필터 토글 후 생성: 활성 목록으로 복귀 후 반영.

## 7. 제약 조건
- **BE 먼저, 스키마 강화 나중** — DTO invent 금지. BE-1/BE-2 구현 전 프론트 스키마에 필드 넣으면 실서버 응답과 불일치.
- **whoami mock fanout** — `canCreateProject` required 강화 시 ~40파일 인라인 mock 깨짐 → `.optional()` 또는 공통 fixture 갱신([[zod-schema-strengthen-inline-mock-fanout]]).
- **E2E 계약 보존** — 라벨 4종·h1 단일·role=dialog. 신규 화면은 추가라 파손면 좁으나 exact 매칭 확인.
- **BC 격리** — 백엔드 2 BC 변경은 각자 모듈 내. cross-BC import 0. security-engineer가 BE-2(권한 노출) 검토.

## 8. 측정 가능한 완료 기준 (DoD)
1. FE-1~4 화면 구현 + 각 vitest 단위 테스트(TDD red→green). RouteAdapter/Page 분리.
2. BE-1(archived)·BE-2(canCreateProject) 백엔드 테스트(TDD) + 프론트 Zod 계약 일치.
3. 권한 게이팅: canCreateProject 버튼 게이팅 + MANAGE_COMPONENTS 설정 액션 게이팅 유닛 검증.
4. **E2E**(happy-path 최소): 목록 표시·생성 성공·아카이브 토글·설정 name변경·아카이브/해제. **[[ui-pr-defer-e2e-regression-latent]] 근거로 PR-5에 포함**(마스터 스펙 D7=PR-6은 FR-PM-10 관리화면 E2E) — CI에 e2e 잡 없어 로컬 필수([[frontend-ci-10min-timeout-nonrequired]]).
5. FR-PJ-01~04 완료마킹 전수 동기화 + `verify-master-plan.sh` PASS(129/129).
6. typecheck 0 · lint 0 error · 전체 vitest green · 백엔드 :modules:app 조립 부팅(2 BC 변경이라 [[prod-assembly-boot-verification-required]]).

## Brainstorming Check

적대적 자체 검토(2026-07-20). 실질 갭 3건 발견·해소. **G2·G3은 네비 설계 결정 — 게이트1 Maxi 검토 대상(권장안으로 선반영, veto 가능).**

- **G1 (구현 디테일·해소)** — 라우팅은 파일기반 자동생성이 아니라 **수동 `router.ts`**(`createRoute` + PR10 `_shell` pathless 레이아웃). 신규 3라우트(`/projects`·`/projects/new`·`/projects/{key}/settings/details`)를 **전부 `shellRoute` 밑에 등록**해야 크롬(사이드바)이 붙는다. login 제외 인증 라우트는 전부 `_shell` 자식(PR10 계약). router.ts 라우트카운트 주석 정합도 갱신([[tanstack-pathless-layout-router-test-blind]]).
- **G2 (진입점·권장 해소)** — `/projects` 목록으로 가는 nav 링크가 현재 없음(ProjectTree는 프로젝트명→`/board`만). **권장**: 사이드바 ProjectTree "프로젝트" 섹션에 "**모든 프로젝트**" 진입 링크 추가(또는 섹션 헤더를 `/projects` 링크로). **★신규 "프로젝트" 라벨 substring 함정**([[playwright-getbyrole-exact-strict-mode]]·[[frontend-nav-aria-label-e2e-contract]]) — exact 매칭·기존 ProjectTree "프로젝트" 라벨과 충돌 회피. 라우트 이동이므로 nav+Link(Tabs 금지).
- **G3 (해제 경로·권장 해소)** — 사이드바는 활성 프로젝트만(useProjects archived=false) → 아카이브 프로젝트의 settings/details(해제 버튼)가 사이드바로 도달 불가. **권장**: `/projects?archived=true` 목록에서 **아카이브 행 클릭 → `/projects/{key}/settings/details`**(활성 행은 `/board`). "목록은 보기만"(D3·인라인 버튼 없음)과 양립 — 행 클릭은 네비지 액션 아님. 해제는 settings danger zone에서 수행.
- **검증된 비-갭** — 생성 라우트 `new` vs `$projectKey` 정적세그먼트 우선(issues.new 선례 동형)·PATCH 204 후 invalidate(setQueryData 금지 [[mutation-setquerydata-partial-response-flicker]])·프로젝트 key 불변(설정=name만)·ProjectNotFoundScreen 재사용.

✅ Phase B 통과 (자체 적대검토 1회, 갭 3건 해소·G2/G3 게이트1 확인 플래그).
