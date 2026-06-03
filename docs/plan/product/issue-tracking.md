<!-- issue-tracking BC — 이슈 코어 29 FR (CRUD/타입/담당자/본문/Resolution/PDF + 컴포넌트/버전 + 첨부/멘션/Watcher + 링크/히스토리/템플릿 + 이동) -->

# issue-tracking BC

**소속 FR**. 29개 (IS 9 + CM 3 + VR 4 + AC 2 + MN 2 + WT 1 + LK 2 + HS 2 + TM 2 + MV 2).
**책임**. 이슈/댓글/첨부/관계/이력/템플릿/이동.
**SDD 참조**. 05장 (데이터 모델), 11장 (API).
**다른 BC와의 경계**. project-workflow의 상태 전이 호출, identity-access의 권한 가드 사용, notification-dashboard 이벤트 발행. **다른 BC import 금지 — 이벤트는 pgmq**.

## §0 진입 조건

- [ ] identity-access §2.1 (`AuthenticationProvider`), §4.2 (`PERMISSION` 가드) 완료
- [ ] project-workflow §1 (FSM PoC) + §2.1 (FR-WF-01) 진입 시 의존 (이슈 상태 전이)
- [ ] notification-dashboard §1 (STOMP PoC), pgmq 트랜잭션 PoC 통과
- [ ] DATA.md §이슈키 영속성 + 소프트 삭제 규칙 숙지
- [ ] §A.3 #5 (이슈 키 prefix) 결정 — DATA.md 가이드

## §1 기술 검증

이 BC 자체의 PoC는 없음. 의존 PoC.
- pgmq 트랜잭션 일관성 → project-workflow §1
- TipTap variant 추상 (issue-body / comment / wiki) → §2.1.4 본문 에디터 작성 시점

## §2 이슈 코어 (FR-IS, 9개)

### §2.1 필수 5개 (CRUD/타입/담당자/본문/Resolution)

#### §2.1.1 FR-IS-01 — 이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `issue/crud`

- [x] D1. 도메인 — Issue Aggregate Root, IssueKey VO (책임. backend-engineer + Maxi)
- [x] D2. 명세 — Given/When/Then. 7 엣지 케이스 (중복 키/권한/전이 위반/대용량/동시 편집/소프트 삭제/키 보존) (책임. backend-engineer)
- [x] D3. 데이터 모델 — Flyway. `issues`, `issue_key_redirects`. DATA.md 영속성 (책임. db-engineer)
- [x] D4. 백엔드 — `POST/GET/PATCH/DELETE /api/v1/issues`. `@Transactional`. pgmq 이벤트 발행 동일 트랜잭션 (책임. backend-engineer + security-engineer 가드) (완료. PR #17 비즈니스 로직 + PR #23 codereview CONCERN cleanup — sealed Result port + PATCH partial RFC 7396 + PR #27 transition wiring — WorkflowResolver consumer (issue-tracking ↔ project-workflow via SPI). 전이 런타임 BLOCKER(DEFAULT 워크플로우 미시드 + OPEN/open 케이스 불일치)는 PR #28 hot-fix로 해소 + PR #38 가용전이 SPI/런타임으로 실 Postgres+워크플로우 시드 검증 완료)
- [x] D5. 백엔드 테스트 — MockK 단위 + Testcontainers 통합. TDD red→green→refactor (책임. backend-engineer) (완료. PR #17 단위 + PR #23 ArchUnit 2룰 + Testcontainers singleton base + Kotest property × 3 + PR #24 Flyway namespace 격리로 IssueRepositoryTest `@Disabled` 해제 + NFR-3 2회 연속 BUILD SUCCESSFUL 24s × 2 측정 통과)
- [x] D6. 프론트 UI — `IssueDetail.tsx`. TanStack Query 캐싱 + 낙관적 업데이트 (책임. designer → frontend-engineer) (완료. PR #26 — 목록(issues.index)+생성(issues.new)+상세(issues.$key, 시안2 사이드 메타패널)+요약 인라인 수정(완전 낙관적 onMutate/onError/onSettled+409 토스트)+소프트 삭제. sonner toast 도입. router 3라우트 requireAuth. 상태 전이 UI는 별 slice로 분리 → D6.5(PR #41)에서 완료. 8 TDD task / 173 테스트 통과)
- [x] D6.5. 상태 전이 UI + 전이 E2E — 가용전이 드롭다운(서버 권위 `GET /transitions`) + `POST /transition`(409 errorCode 2종 TRANSITION_NOT_ALLOWED/VERSION_CONFLICT, 422 미설정 분기) + Zod(backend DTO 정합) + MSW stateful + i18n + Playwright E2E 4(happy/가용전이 필터/미설정/종료상태) (책임. backend-engineer→frontend-engineer→qa-engineer) (완료. PR #38 백엔드 가용전이 SPI+런타임 + PR #41 프론트 UI+E2E)
- [x] D7. E2E — FR-IS-01 고유 lifecycle 생성→조회→수정→상태 전이→소프트 삭제 (책임. qa-engineer) (완료. PR #32 E2E-1~4(생성/조회/인라인수정/소프트삭제/목록제외 + 비로그인 가드 + 미존재 키 404 + UI 회귀 가드 3) + PR #41 상태 전이 E2E 4(happy/가용전이 필터/미설정/종료상태). MSW mock 환경. **범위 재정의(2026-05-30)** — 아래 cross-FR/deferred 항목을 각 FR로 이관해 FR-IS-01 고유 E2E만 D7로 한정)

> **D7 잔여 이관 (2026-05-30)**. 아래 3건은 FR-IS-01 고유 범위가 아니라 각 FR/deferred로 이관 — D7 완료 판정에서 제외:
> - **이슈 이동 + 키 redirect E2E** → **FR-MV-01**(§6.1.1 프로젝트 간 이슈 이동)의 E2E 범위. 이미 FR-MV-01 D2(옛 키 redirect 명세)/D5(옛 키 308 redirect 테스트)로 추적 중.
> - **NFR p95 측정 3건 (조회 200ms / 목록 500ms / POST 300ms)** → Deferred(아래 NFR 표). trigger — (a) k6-load-testing + Playwright NFR 계층 도구 + (b) 실 backend 환경, Maxi 선언으로 조정 (PR #21 §F4 패턴). checkbox 아님(표).
> - **권한 모델 E2E** → 실 RBAC 가드 구현(현재 dev/test = `AlwaysAllowIssuePermissionResolver`) 별 FR 후속.

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 단건 조회 | 200ms | ___ |
| 목록 50건 | 500ms | ___ |
| `POST /issues` | 300ms | ___ |

#### §2.1.2 FR-IS-02 — 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/types`

- [x] D1. 도메인 — IssueType.hierarchyLevel + Issue.typeId (PR #36)
- [x] D2. 명세 — Epic-Subtask 계층은 hierarchyLevel 메타데이터로 (parent_id 강제는 후속 FR) (PR #36)
- [x] D3. 데이터 모델 — `issue_types.hierarchy_level` + `issues.type_id` FK + key 부분 unique (V005, PR #36)
- [x] D4. 백엔드 — 커스텀 IssueType CRUD (POST/PATCH/DELETE+reassignTo) + 표준 불변 + RFC 7807 (PR #36)
- [x] D5. 백엔드 테스트 — MockK 단위 + Testcontainers 통합 (PR #36)
- [x] D6. 프론트 UI — 타입 셀렉터 + 아이콘 + 이슈 타입 변경 backend(PATCH typeId + 활성 검증 + OCC) (PR #39)
- [x] D7. E2E — 타입 변경 Playwright 시나리오 (PR #42 — S1 버그→스토리 타입/아이콘 갱신 + S2 표준 5종 셀렉터 옵션. MSW PATCH stateful 오버라이드 영속화로 refetch 롤백 방지. 회귀 E2E 12/12, unit 410/410 그린)

#### §2.1.3 FR-IS-03 — 담당자 (Reporter 1 / Assignee 1 / Watchers N)

**우선순위**. 필수 | **선행**. §2.1.1, §4.3.1 (Watcher) | **Plan slug**. `issue/assignees`

- [x] D1. 도메인 — Reporter(기존 재사용)/Assignee(신규 `Issue.assigneeId: ActorId?` + assignTo/unassign). **Watcher(W)는 FR-WT-01(§4.3.1)로 분리** (PR #49)
- [x] D2. 명세 — 전용 엔드포인트 merge-patch 3-state 회피 + UserLookupPort 사용자 실재 검증(cross-BC) (PR #49)
- [x] D3. 데이터 모델 — `issues.assignee_id`(UUID NULL, FK 미적용 BC격리, V007 + init_codegen 미러). `reporter_id` 기존. **`issue_watchers`는 FR-WT-01로 이관** (PR #49)
- [x] D4. 백엔드 — `PATCH /api/v1/issues/{key}/assignee` + 422 ASSIGNEE_NOT_FOUND + OCC + 도메인 경유 + `GET /api/v1/users`(셀렉터 재료, identity-access, 인증가드) + shared-kernel UserLookupPort (PR #49)
- [x] D5. 백엔드 테스트 — MockK 단위 + Testcontainers 통합(S1~S6) + ArchUnit BC격리 + UserLookupAdapter 통합 (PR #49)
- [x] D6. 프론트 UI — 담당자 셀렉터(GET /api/v1/users 사용) + 현재 담당자 id 조회(`GET /api/v1/users?ids=` 신규, "미지정" 오표시 C1 수정) + invalidate-only mutation + 검색 debounce (PR #51)
- [x] D7. E2E — 담당자 할당/해제 Playwright 2종 (S3 422/409는 UI 트리거 불가로 단위 커버) (PR #51)

#### §2.1.4 FR-IS-04 — 본문(Markdown) + 우선순위/라벨/환경/영향도

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/body`

- [x] D1. 도메인 — Issue Aggregate에 description/priority/labels/environment/impact 5필드 + 라벨 불변식(중복/공백/50자/20개) + priority/impact 이름매핑 (PR #43)
- [x] D2. 명세 — Markdown XSS sanitization (CSRF ADR "서버측 sanitization" 준수, merge-patch 3-state) (PR #43)
- [x] D3. 데이터 모델 — `issues.description`(TEXT, Markdown) + priority SMALLINT(1~5) + labels TEXT[]+GIN + environment TEXT + impact SMALLINT(1~3) (V006, PR #43)
- [x] D4. 백엔드 — flexmark 렌더링 + OWASP Java HTML Sanitizer + PATCH merge-patch 확장 (PR #43)
- [x] D5. 백엔드 테스트 — XSS 17벡터 차단 + merge-patch/backfill/OCC 통합 (MockK + Testcontainers, PR #43)
- [x] D6. 프론트 UI — GitHub 스타일 Write/Preview 본문 에디터(TipTap 폐기, 의존성 0 — 백엔드 Markdown 정본과 정합) + 우선순위/영향도 셀렉터 + 환경/라벨 칩 + Zod 계약 동기 (PR #46)
- [x] D7. E2E — 본문 Write/Preview·우선순위·영향도(미지정 disabled 토글)·라벨·환경 Playwright E2E 5종 + OCC409 skip(issue-edit-conflict 위임). D6 회귀(저장버튼 strict mode) hot-fix 동반. 전체 E2E 54 passed (PR #47)

#### §2.1.5 FR-IS-07 — Resolution 필드 (Fixed/Won't Fix/Duplicate)

**우선순위**. 필수 | **선행**. §2.1.1, project-workflow §2.1 | **Plan slug**. `issue/resolution`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 종료 상태 진입 시 Resolution 필수 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `resolutions`, `issues.resolution_id` (책임. db-engineer)
- [ ] D4. 백엔드 — 상태 전이 가드 (Resolution 미설정 시 reject) (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 종료 모달 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.2 보강 2개 (일괄 편집, 라벨 자동완성)

#### §2.2.1 FR-IS-05 — 이슈 일괄 편집 + 일괄 상태 전이

**우선순위**. 높음 | **선행**. §2.1.1 | **Plan slug**. `issue/bulk-edit`

- [x] D1. 도메인 — BulkOp (책임. backend-engineer)
- [x] D2. 명세 — 트랜잭션 정책, 부분 실패 처리 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (FR-IS-01 활용) (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/issues/bulk-update`. 청크 처리 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 부분 실패 시 트랜잭션 동작 (책임. backend-engineer)
- [x] D6. 프론트 UI — 다중 선택 + 액션 바 (책임. designer → frontend-engineer) (완료. PR #58 — 이슈 목록 체크박스 다중선택(페이지 교차 누적) + 일괄 액션바 + 일괄 편집/전이 Dialog + 결과 패널(폴링 1.5s 종단중지). 전이 대상=가용전이 교집합만(Promise.allSettled N회). 계약 정합(payload operationType union/DataResponse/errorCode4·failureReasonCode7). B1 BLOCKER(체크박스 행 형제구조+키 비포함 aria-label, getByLabel 회귀0) + 적대적리뷰 결함4건(폴링 에러 무한루프/전량실패 빈드롭다운/stale 덮어쓰기/Dialog 잔상) 수정. 9 TDD task. 프론트 854 단위+typecheck+lint+기존 E2E 24 green. FR-PM-02 #57과 issues.index 충돌→NewIssueButton+일괄UI 공존 통합머지)
- [x] D7. E2E — 일괄 편집/전이 + 진행률 폴링 시나리오 + bulk MSW 핸들러 정본 (책임. qa-engineer) (완료. PR #60 — bulk 작업 공용 MSW 핸들러 정본(bulk-operation-handlers.ts, stateful 폴링 2폴내 종단 + partial-fail/reject localStorage 토글) + 단위 11 + E2E 5(S1 편집happy/S2 전이happy 교집합closed/S3 부분실패 성공2실패1/S4 접수실패 토스트/S5 교집합0). 코드리뷰 PASS, CONCERNS 2건(payload union 가드+격리주석) 반영. typecheck/lint/단위 865/전체 E2E 71 passed 1 skipped 회귀0)

#### §2.2.2 FR-IS-09 — 라벨 자동완성

**우선순위**. 높음 | **선행**. §2.1.1 | **Plan slug**. `issue/labels-autocomplete`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — prefix 매칭 + 사용 빈도 정렬 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `labels`, `issue_labels` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/labels?q=<prefix>` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — cmdk 콤보박스 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.3 정리 2개 (클론, PDF)

#### §2.3.1 FR-IS-06 — 이슈 클론 (옵션. 첨부/Watcher/댓글 포함)

**우선순위**. 중간 | **선행**. §2.1.1, §4.2.1, §4.3.1 | **Plan slug**. `issue/clone`

- [ ] D1. 도메인 — CloneOptions (책임. backend-engineer)
- [ ] D2. 명세 — 무엇이 복사되고 무엇이 새로 시작되는지 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용만) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/issues/{key}/clone` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 옵션 다이얼로그 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §2.3.2 FR-IS-08 — 이슈 인쇄 + PDF 출력

**우선순위**. 중간 | **선행**. §2.1.1 | **Plan slug**. `issue/pdf-export`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — PDF 레이아웃 + 페이지 헤더/푸터 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용만) (책임. db-engineer)
- [ ] D4. 백엔드 — `openhtmltopdf` + `pdfbox`. `GET /api/v1/issues/{key}/pdf` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — PDF 바이너리 검증 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 인쇄 버튼 + 다운로드 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 컴포넌트 / 버전 (7개)

### §3.1 컴포넌트 (FR-CM, 3개)

#### §3.1.1 FR-CM-01 — 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/components`

- [x] D1. 도메인 — Component Aggregate (책임. backend-engineer) — PR #59
- [x] D2. 명세 (책임. backend-engineer) — PR #59
- [x] D3. 데이터 모델 — `components(lead_user_id)` (책임. db-engineer) — V009, PR #59
- [x] D4. 백엔드 — CRUD API (책임. backend-engineer + security-engineer) — PR #59 (권한 가드는 ComponentPermissionResolver 포트로 추상화, prod 실판정은 FR-PM-03 이연)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #59
- [x] D6. 프론트 UI — 컴포넌트 관리 페이지 (책임. designer → frontend-engineer) (완료. PR #64 — `/projects/$projectKey/settings/components` 멤버 설정 패턴 재사용(목록 4분기+추가/수정 Dialog+행별 리드 지정/해제+소프트 삭제). issues.ts 관례(공유 ApiError+DataResponse, errorCode는 body.errorCode 헬퍼 추출), mutation invalidate-only+토스트 hook 단일, X-XSRF-TOKEN. 기존 useUsers/useUsersByIds 재사용, ComponentLeadSelect 순수 props. plan리뷰 BLOCKER 3(errorCode/RFC7807 MSW/CSRF)+코드리뷰 BLOCKER 1(수정모드 설명 비우기) 해소. 9 TDD task)
- [x] D7. E2E (책임. qa-engineer) (완료. PR #64 — component-management.spec.ts 5종(S1 목록/S2 생성/S4 수정/S5 리드 지정·해제/S6 삭제). 행 컨테이너 한정 셀렉터로 strict mode 회피. 전체 E2E 76통과/1skip(기존 의도)/회귀 0)

#### §3.1.2 FR-CM-02 — 이슈에 다중 컴포넌트 할당

**우선순위**. 필수 | **선행**. §3.1.1 | **Plan slug**. `issue/components-assign`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_components` 다대다 (책임. db-engineer)
- [ ] D4. 백엔드 — 이슈 PATCH 확장 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 다중 셀렉터 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.1.3 FR-CM-03 — 컴포넌트별 기본 담당자 자동 할당

**우선순위**. 높음 | **선행**. §3.1.1, §2.1.3 | **Plan slug**. `issue/components-default-assignee`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 다중 컴포넌트 시 우선순위 규칙 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 이슈 생성/컴포넌트 변경 trigger (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 자동 표시 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.2 버전 (FR-VR, 4개)

#### §3.2.1 FR-VR-01 — 버전 생성 + 시작일/릴리즈 예정일

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/versions`

- [x] D1. 도메인 — Version Aggregate (책임. backend-engineer) — PR #67
- [x] D2. 명세 (책임. backend-engineer) — PR #67
- [x] D3. 데이터 모델 — `versions` (책임. db-engineer) — V010, PR #67
- [x] D4. 백엔드 — CRUD API (책임. backend-engineer + security-engineer) — PR #67 (권한 가드는 VersionPermissionResolver 포트로 추상화, prod 실판정은 FR-PM-03 이연. /dates 전용 서브리소스, 날짜 순서 미강제. ProjectLookup을 com.bts.issue.project 공용 패키지로 이동)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #67 (도메인 단위 + Testcontainers 통합 S1~S9 + S13 rename 중복 409 + 마이그레이션 + 부팅가드 + ArchUnit. 코드리뷰 P2-1 update rename 23505→409 parity 수정)
- [x] D6. 프론트 UI (책임. designer → frontend-engineer) — PR #68 (`/projects/$projectKey/settings/versions` 라우트, VersionList/Row/FormDialog, /dates 분리호출 + ''→null 정규화, FR-CM-01 동형. 코드리뷰 H3 submitError 자체관리 배선 수정)
- [x] D7. E2E (책임. qa-engineer) — PR #68 (version-management.spec.ts S1/S2/S4/S5/S6 happy path, apps/web/e2e/, 행 컨테이너 한정 셀렉터)

#### §3.2.2 FR-VR-02 — 버전 상태 (Unreleased/Released/Archived)

**우선순위**. 필수 | **선행**. §3.2.1 | **Plan slug**. `issue/versions-status`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 상태 전이 규칙 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `versions.status` (책임. db-engineer)
- [ ] D4. 백엔드 — 상태 전이 API + 가드 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.2.3 FR-VR-03 — Affects/Fix Version 연결

**우선순위**. 필수 | **선행**. §3.2.1 | **Plan slug**. `issue/versions-link`

- [ ] D1. 도메인 — Affects vs Fix 의미 (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_affects_versions`, `issue_fix_versions` (책임. db-engineer)
- [ ] D4. 백엔드 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 버전 셀렉터 2종 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.2.4 FR-VR-04 — 버전 릴리즈 노트 자동 생성

**우선순위**. 중간 | **선행**. §3.2.1, §3.2.3 | **Plan slug**. `issue/versions-release-notes`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — Markdown 템플릿 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/versions/{id}/release-notes` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 미리보기 + 복사 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §4 첨부 / 멘션 / Watcher (5개)

### §4.1 멘션 (FR-MN, 2개)

#### §4.1.1 FR-MN-01 — 본문/댓글 @멘션 + 즉시 알림

**우선순위**. 필수 | **선행**. §2.1.4, §4.3.1 | **Plan slug**. `issue/mentions`

- [ ] D1. 도메인 — Mention 이벤트 (책임. backend-engineer)
- [ ] D2. 명세 — `@username` 파싱 규칙 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (notification 이벤트 발행만) (책임. db-engineer)
- [ ] D4. 백엔드 — 본문/댓글 저장 시 mention 추출 → pgmq 이벤트 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 멘션 렌더링 (강조) (책임. designer → frontend-engineer)
- [ ] D7. E2E — 멘션 → Inbox 도착 (책임. qa-engineer)

#### §4.1.2 FR-MN-02 — 멘션 자동완성

**우선순위**. 높음 | **선행**. §4.1.1 | **Plan slug**. `issue/mentions-autocomplete`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — prefix 매칭 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (users 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/users/autocomplete?q=` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — `@` 트리거 popover (TipTap 확장) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.2 첨부 (FR-AC, 2개)

#### §4.2.1 FR-AC-01 — 첨부 업로드 (최대 100MB/파일)

**우선순위**. 필수 | **선행**. §2.1.1, MinIO 인프라 | **Plan slug**. `issue/attachments`

- [ ] D1. 도메인 — Attachment Aggregate (책임. backend-engineer)
- [ ] D2. 명세 — MIME 화이트리스트 + 크기 제한 + 바이러스 스캔 (ClamAV 후속) (책임. backend-engineer + security-engineer)
- [ ] D3. 데이터 모델 — `attachments(minio_key)` (책임. db-engineer)
- [ ] D4. 백엔드 — Presigned URL `POST /api/v1/issues/{key}/attachments/upload-url` (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 — Testcontainers MinIO (책임. backend-engineer)
- [ ] D6. 프론트 UI — react-dropzone + 직접 PUT (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §4.2.2 FR-AC-02 — 첨부 미리보기 (이미지/PDF/동영상)

**우선순위**. 높음 | **선행**. §4.2.1 | **Plan slug**. `issue/attachments-preview`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — MIME 별 렌더러 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — Presigned GET URL (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — react-pdf + img + video.js (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.3 Watcher (FR-WT, 1개)

#### §4.3.1 FR-WT-01 — Watcher 추가/제거 + 자동 Watcher

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/watchers`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — Reporter/Assignee 자동 Watcher (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_watchers` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST/DELETE /api/v1/issues/{key}/watchers` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Watch 버튼 + 카운트 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §5 링크 / 히스토리 / 템플릿 (6개)

### §5.1 히스토리 (FR-HS, 2개)

#### §5.1.1 FR-HS-01 — 이슈 변경 이력 (필드/댓글/첨부/전이)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/history`

- [ ] D1. 도메인 — IssueHistoryEntry (책임. backend-engineer)
- [ ] D2. 명세 — 무엇을 기록하고 무엇을 기록 안 하는지 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_history(field, old_value, new_value, changed_by)` (책임. db-engineer)
- [ ] D4. 백엔드 — `IssueEventListener` (pgmq consumer) (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — (조회는 §5.1.2) (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §5.1.2 FR-HS-02 — 히스토리 조회 UI

**우선순위**. 필수 | **선행**. §5.1.1 | **Plan slug**. `issue/history-ui`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 페이지네이션, 필드 필터 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/issues/{key}/history` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 타임라인 형식 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §5.2 템플릿 (FR-TM, 2개)

#### §5.2.1 FR-TM-01 — 프로젝트+타입별 본문 템플릿

**우선순위**. 필수 | **선행**. §2.1.1, §2.1.4 | **Plan slug**. `issue/templates`

- [ ] D1. 도메인 — IssueTemplate (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_templates(project_id, type_id, body)` (책임. db-engineer)
- [ ] D4. 백엔드 — CRUD API + 이슈 생성 시 적용 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 템플릿 관리 페이지 + 생성 시 자동 적용 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §5.2.2 FR-TM-02 — 템플릿 변수 (작성자/일자/프로젝트)

**우선순위**. 높음 | **선행**. §5.2.1 | **Plan slug**. `issue/template-vars`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 변수 종류 + 치환 시점 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 변수 치환 엔진 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 변수 자동완성 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §5.3 링크 (FR-LK, 2개)

#### §5.3.1 FR-LK-01 — 링크 (blocks/relates/duplicates/clones/parent-child)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/links`

- [ ] D1. 도메인 — LinkType (책임. backend-engineer)
- [ ] D2. 명세 — 양방향성 + cycle 검출 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_links(src, dst, link_type)` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST/DELETE /api/v1/issues/{key}/links` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — cycle 케이스 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 링크 추가/제거 패널 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §5.3.2 FR-LK-02 — 링크 그래프 시각화

**우선순위**. 중간 | **선행**. §5.3.1 | **Plan slug**. `issue/links-graph`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 노드/엣지 표현 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/issues/{key}/graph` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — SVG 또는 force-directed lib (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §6 이슈 이동 (FR-MV, 2개)

### §6.1.1 FR-MV-01 — 프로젝트 간 이슈 이동

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/move`

- [ ] D1. 도메인 — IssueMoveOperation (책임. backend-engineer)
- [ ] D2. 명세 — 새 이슈 키 생성 + 옛 키 redirect (DATA.md §이슈키 영속성) (책임. backend-engineer + Maxi)
- [x] D3. 데이터 모델 — `issue_key_redirects(old_key, new_key, moved_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/issues/{key}/move` 트랜잭션 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 옛 키로 조회 시 308 redirect (책임. backend-engineer)
- [ ] D6. 프론트 UI — 이동 다이얼로그 (책임. designer → frontend-engineer)
- [ ] D7. E2E — 이동 + **옛 키 redirect E2E** (FR-IS-01 D7에서 이관, 2026-05-30) (책임. qa-engineer)

### §6.1.2 FR-MV-02 — 이동 시 히스토리 보존 + 링크 유지

**우선순위**. 필수 | **선행**. §6.1.1, §5.1.1, §5.3.1 | **Plan slug**. `issue/move-preserve`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 히스토리/링크/Watcher/첨부 보존 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용. id 보존 + key만 변경) (책임. db-engineer)
- [ ] D4. 백엔드 — 이동 시 모든 FK 보존 검증 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 이동 전후 invariant 비교 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 이동 후 페이지 자동 갱신 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR issue-tracking BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 이슈 단건 조회 | 200ms | ___ | k6 |
| 이슈 목록 50건 | 500ms | ___ | k6 |
| 이슈 생성 | 300ms | ___ | k6 (pgmq 이벤트 포함) |
| 이슈 이동 | 1s | ___ | k6 (트랜잭션 + redirect 등록) |
| 첨부 업로드 (100MB) | 30s | ___ | Playwright + MinIO |
| 첨부 미리보기 로딩 | 1s | ___ | Playwright |
| 히스토리 50건 조회 | 500ms | ___ | k6 |
| LCP (이슈 페이지) | 2.5s | ___ | Lighthouse CI |
| INP | 200ms | ___ | Lighthouse CI |
| 메인 번들 (gzip) | 200KB | ___ | bundle-analyzer |
| WCAG 2.1 AA | 0 violations | ___ | axe-core |
| XSS 페이로드 차단율 | 100% | ___ | 보안 페이로드 10종 |
| 이슈 키 영속성 (이동 후 redirect) | 308 | ___ | Playwright |

### BC 완료 조건

- [ ] §2~§6 (29 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] DATA.md §이슈키 영속성 자가 점검
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "issue-tracking BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "issue-tracking BC 완료"
