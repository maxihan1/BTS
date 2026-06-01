<!-- FR-PM-01 멤버 관리 UI(D6) + E2E(D7) + projectIdOrKey 백엔드 슬라이스 기술 스펙 -->

# FR-PM-01 프로젝트 멤버 관리 — D6 프론트 UI + D7 E2E + projectIdOrKey 슬라이스 (스펙)

> FR: FR-PM-01 | BC: identity-access (+ apps/web 프론트, cross-BC read: issue-tracking projects)
> 선행: 백엔드 D1~D5 (PR #48), ADR [2026-06-01-project-member-projectidorkey](../decisions/2026-06-01-project-member-projectidorkey.md)
> 작성: 2026-06-01 (직접 기술 스펙 — 정의된 FR + 결정 완료, office-hours 부적합)

## 0. 범위

세 묶음. **① 백엔드 슬라이스**(멤버 API가 projectKey도 수용, security-engineer) → **② 프론트 UI**(frontend-engineer) → **③ E2E**(qa-engineer).

범위 밖(이연). 부트스트랩/생성자-자동admin UI(프로젝트 생성 FR), 전체 페이지네이션(typeahead 50건 상한으로 충분), 멤버 일괄 추가/CSV.

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 멤버 목록 조회**. Given PROJECT_ADMIN 또는 MEMBER가 `/projects/ATLAS/settings/members` 진입. When 페이지 로드. Then 멤버 목록이 사용자 **표시 이름**(displayName, 없으면 username)과 **역할 배지**(관리자/멤버)로 렌더된다. userId 원시 UUID는 노출하지 않는다.
- **S2 멤버 추가**. Given PROJECT_ADMIN. When "멤버 추가" → 다이얼로그에서 이름/이메일로 검색(typeahead) → 사용자 선택 → 역할(관리자/멤버) 선택 → 추가. Then 목록에 새 멤버가 나타나고 성공 토스트. 이미 멤버면 `membership_already_exists`(409) → "이미 멤버입니다" 토스트.
- **S3 역할 변경**. Given PROJECT_ADMIN. When 멤버 행의 역할 Select를 변경. Then 역할 배지가 갱신되고 성공 토스트.
- **S4 멤버 제거**. Given PROJECT_ADMIN. When 멤버 행의 "제거" → 확인. Then 목록에서 사라지고 성공 토스트.
- **S5 마지막 관리자 보호**. Given 관리자가 1명뿐. When 그 관리자를 제거/강등 시도. Then `last_admin_protected`(409) → "마지막 관리자는 제거하거나 강등할 수 없습니다" 토스트, 목록은 롤백되어 변화 없음.
- **S6 권한 없음 / 비멤버**. Given 비멤버(또는 MEMBER가 변경 시도). When 페이지 진입 시 GET이 404 → "접근 권한이 없습니다" 안내 화면. MEMBER가 변경 액션 시도 시 `not_project_admin`(403) → "프로젝트 관리자만 멤버를 변경할 수 있습니다" 토스트(액션 컨트롤은 MEMBER에게 비표시가 1차 방어).

## 2. 기능 요구사항 (FR)

### 백엔드 슬라이스
- **FR-B1** 멤버 API path 변수 `{projectId}` → `{projectIdOrKey}`. 값이 UUID 형식이면 id로, 아니면 `projects.key`로 해석(`ProjectDirectory.resolveKeyToId`). 4개 엔드포인트(POST/GET/PATCH/DELETE) 전부 적용.
- **FR-B2** key 해석 실패(미존재/soft-deleted)는 기존 `ProjectNotFound`(404 존재숨김)와 동일 응답. 정보노출 방어 일관(비멤버 404와 구분 불가).
- **FR-B3** UUID 경로 기존 동작 회귀 없음(기존 통합테스트 그린 유지).
- **FR-B4** `ProjectMemberResponse`에 `displayName:String?` + `username:String` 추가(Jira식 — 역할 응답에 이름 inline). GET 목록은 `project_memberships m JOIN users u ON m.user_id = u.id`로 조인(같은 BC, FK CASCADE라 orphan 없음). POST/PATCH 단건 응답도 동일 필드 포함. email은 미포함(목록 표시에 불필요, PII 최소화). 기존 필드(projectId/userId/role/createdAt/updatedAt)는 유지 → 순수 추가, UUID 경로 회귀 없음.

### 프론트
- **FR-F1** 라우트 `/projects/$projectKey/settings/members`(requireAuth). RouteAdapter가 projectKey 추출 → props로 Page에 전달(라우터 비의존 단위 테스트 가능).
- **FR-F2** `GET /api/v1/projects/{projectKey}/members` → `{members:[…]}`를 Zod로 파싱(백엔드 DTO와 1:1, displayName/username 포함). 각 member는 응답의 `displayName ?? username`으로 표시 — 별도 id→이름 변환 호출 불필요(결정 C).
- **FR-F3** `GET /api/v1/users?query=` 디렉토리 클라이언트는 **추가 다이얼로그 typeahead 검색 전용**(이름/이메일로 후보 검색). 목록 이름 변환에는 쓰지 않음.
- **FR-F4** 멤버 추가(POST 201) — body `{userId, role}`. X-XSRF-TOKEN 헤더 포함. 성공 시 invalidate + 토스트.
- **FR-F5** 역할 변경(PATCH `/{userId}` 200) — body `{role}`. 낙관적 업데이트 + 실패 시 롤백.
- **FR-F6** 멤버 제거(DELETE `/{userId}` 204) — 확인 후 실행. 성공 시 invalidate + 토스트.
- **FR-F7** 에러코드→한국어 메시지 매핑(아래 §6). 라벨은 `i18n/project-member-labels.ts`에 정의(E2E 셀렉터 정본).
- **FR-F8** 역할은 enum Select(관리자/멤버)로만 입력 → `invalid_role` 실질 발생 차단(방어적 매핑은 유지).
- **FR-F9** 액션 컨트롤(추가/역할변경/제거)은 현재 사용자가 PROJECT_ADMIN일 때만 노출. 본인 역할 강등/본인 제거 시에도 마지막 관리자 보호는 서버 권위.

### E2E
- **FR-E1** S1~S6 시나리오를 Playwright로 검증. MSW stateful 핸들러 위에서 PATCH/POST/DELETE 후 GET refetch가 화면에 반영(가짜 그린 방지, `msw-mutation-stateful-refetch` 교훈).

## 3. 비기능 요구사항 (NFR)

- **NFR-1** Zod 응답 스키마는 실 백엔드 DTO와 1:1(invent 금지, `frontend-zod-backend-dto-contract-gap` 교훈). 변경 시 grep 전수 + tsc 동반.
- **NFR-2** 접근성 — Select/Dialog/Button에 aria-label, 라벨은 i18n 파일.
- **NFR-3** PII(이메일/이름) 콘솔 로그 금지(DEVELOPMENT.md §1.2).
- **NFR-4** 단위(vitest) + E2E(Playwright) 그린. typecheck/lint 그린. 백엔드 슬라이스는 detekt/통합테스트 그린.

## 4. API 인터페이스 (프론트가 소비)

| 메서드 | 경로 | body | 성공 | 비고 |
|---|---|---|---|---|
| GET | `/api/v1/projects/{projectKey}/members` | - | 200 `{members:[ProjectMemberResponse]}` | 비멤버 404 |
| POST | `/api/v1/projects/{projectKey}/members` | `{userId:UUID, role}` | 201 `ProjectMemberResponse` | 중복 409 |
| PATCH | `/api/v1/projects/{projectKey}/members/{userId}` | `{role}` | 200 `ProjectMemberResponse` | |
| DELETE | `/api/v1/projects/{projectKey}/members/{userId}` | - | 204 | |
| GET | `/api/v1/users?query=` | - | 200 `[{id,username,displayName?,email?}]` | 추가 typeahead 전용, 최대 50건 |

`ProjectMemberResponse = { projectId:UUID, userId:UUID, role:"PROJECT_ADMIN"|"MEMBER", displayName:string|null, username:string, createdAt:ISO, updatedAt:ISO }` (displayName/username은 FR-B4로 추가).

## 5. 데이터 모델 변경

없음(마이그레이션 0건). 백엔드 슬라이스는 읽기 전용 조회만 추가. ① `SELECT id FROM projects WHERE key=:key AND deleted_at IS NULL`(projectKey 해석), ② 멤버 목록/단건 조회 시 `users` 테이블 조인으로 displayName/username 동봉(같은 BC).

## 6. 에러코드 → UI 매핑

| 에러코드 | HTTP | UI 처리 |
|---|---|---|
| `project_not_found` | 404 | 페이지 레벨 "접근 권한이 없습니다" 안내 화면(존재숨김 — 프로젝트 없음/비멤버 구분 안 함) |
| `not_project_admin` | 403 | "프로젝트 관리자만 멤버를 변경할 수 있습니다" 토스트 |
| `last_admin_protected` | 409 | "마지막 관리자는 제거하거나 강등할 수 없습니다" 토스트 + 롤백 |
| `membership_already_exists` | 409 | "이미 멤버입니다" 토스트 |
| `user_not_found` | 404 | "사용자를 찾을 수 없습니다" 토스트 |
| `member_not_found` | 404 | "이미 제거된 멤버입니다" 토스트 + 목록 invalidate |
| `invalid_role` | 422 | (enum Select라 미발생) 방어적 "역할 값이 올바르지 않습니다" |
| `unauthorized` | 401 | client.ts 기존 401 인터셉터 경로(재로그인) |

## 7. 엣지 케이스

- **EC-1** displayName이 null(외부 IdP 미제공) → username으로 폴백 표시(응답에 username 항상 포함).
- **EC-2** (해소됨) 결정 C로 멤버 응답이 displayName/username을 직접 동봉 → 50건 검색 상한과 무관하게 모든 멤버 이름 정확. id→이름 변환 호출 없음.
- **EC-3** 본인을 마지막 관리자 상태에서 강등/제거 → S5 동일(서버 409).
- **EC-4** typeahead 검색 결과 0건 → "검색 결과 없음".
- **EC-5** 이미 멤버인 사용자가 검색 결과에 노출 → 선택 시 409로 안내(또는 결과에서 이미-멤버 표시; 1차는 409 토스트).
- **EC-6** 동시성 — 낙관적 업데이트 중 다른 변경이 끼면 onSettled invalidate가 서버 권위로 수렴.

## 8. 측정 가능한 완료 기준

1. 백엔드: 멤버 API가 projectKey/UUID 둘 다 정상 처리(통합테스트), 기존 UUID 경로 회귀 0.
2. 프론트: S1~S6 단위 테스트 + Playwright E2E 그린.
3. Zod 스키마 ↔ 백엔드 DTO 필드 1:1 검증(grep 대조).
4. typecheck/lint/detekt 그린, MSW stateful refetch로 변경 후 화면 반영 확인.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회). 발견 gap 2건 해소.
- **gap1 (해소)** "내가 PROJECT_ADMIN인가" 판단 근거 — `WhoamiResponse.userId`(authStore 보유)를 멤버 목록에서 찾아 역할 확인. 검증 완료(코드 실재).
- **gap2 (Maxi 결정 → 해소)** 멤버 목록 userId→이름 변환 불가(`/users`는 검색만, id 조회 불가, 50건 상한) → 결정 C(멤버 응답에 displayName/username 동봉, Jira식 같은-BC 조인). ADR 반영.
- 잔여 BLOCKER 0. 구현 착수 가능.
