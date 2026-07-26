<!-- issue-tracking BC — 이슈 코어 37 FR (CRUD/타입/담당자/본문/Resolution/PDF/커스텀필드 + 컴포넌트/버전 + 첨부/멘션/댓글/Watcher + 링크/히스토리/템플릿 + 이동 + 프로젝트 관리) -->

# issue-tracking BC

**소속 FR**. 37개 (IS 10 + CM 4 + VR 4 + AC 2 + MN 2 + CO 2 + WT 1 + LK 2 + HS 2 + TM 2 + MV 2 + PJ 4).
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

## §2 이슈 코어 (FR-IS, 10개)

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

- [x] D1. 도메인 (책임. backend-engineer) (완료. PR #62 — Resolution 엔티티(IssueType 패턴 동형, 표준 5종 불변 FIXED/WONT_FIX/DUPLICATE/CANNOT_REPRODUCE/DONE + key 슬러그 검증). 옵션 A(워크플로우 게이트) 채택 — 선행 FR-WF-03(validator 런타임 결선) 머지 후 보류 해제)
- [x] D2. 명세 — 종료 상태 진입 시 Resolution 필수 (책임. backend-engineer) (완료. PR #62 — DONE 카테고리 전이 시 RequiredField(resolution) validator로 필수 강제. 거부=409 TRANSITION_NOT_ALLOWED(422 아님, 기존 전이거부 계약 재사용). 재오픈(DONE→비DONE) 시 resolution_id clear)
- [x] D3. 데이터 모델 — `resolutions`, `issues.resolution_id` (책임. db-engineer) (완료. PR #62 — V011(V010은 versions 선점) resolutions 테이블 + 표준 5종 seed(결정적 Zod v4 UUID) + issues.resolution_id(FK 미적용, BC 격리) + init_codegen 미러)
- [x] D4. 백엔드 — 상태 전이 가드 (Resolution 미설정 시 reject) (책임. backend-engineer) (완료. PR #62 — production 워크플로우 YAML(software-default 등) DONE 전이에 RequiredField(resolution) 시드(YamlSeedService, FR-WF-03 결선 활용 — 마이그레이션 아님). transitionIssue가 issueFields에 resolution 전달 + raw jOOQ applyTransition으로 resolution_id 영속 + 존재성 검증(위조 UUID→404 RESOLUTION_NOT_FOUND). 일괄 전이(Q4)도 payload resolutionId 전달)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (완료. PR #62 — 마이그레이션/엔티티/repo/엔드포인트/전이 영속·clear·존재성/일괄 Testcontainers 통합 + 실 WorkflowEngine 결선 end-to-end(IssueTransitionValidatorEndToEndIntegrationTest — REST DONE 전이 resolution 누락→409 전체스택). 3모듈 test+detekt 그린)
- [x] D6. 프론트 UI — 종료 모달 (책임. designer → frontend-engineer) (완료. PR #62 — resolutions API/useResolutions/MSW + 단건 종료 모달(toCategory==='DONE' 트리거, 기존 resolution pre-fill=done→closed 정확성 요건, 미선택 시 확인 비활성) + 일괄 전이 resolution 드롭다운(Q4) + 상세 메타 resolution 표시. vitest 997+typecheck+eslint 그린)
- [x] D7. E2E (책임. qa-engineer) (완료. PR #62 — issue-resolution.spec.ts S1(단건 종료 happy+resolution 표시)/S2(미선택 거부)/S4(재오픈 clear)/S5(일괄 종료). MSW stateful(전이 결과 영속). 전체 E2E 80 passed/1 skipped 회귀 0)

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

- [x] D1. 도메인 (책임. backend-engineer) (완료. PR #79 — 라벨=free-form 텍스트 태그 모델 확정(Jira 정합), 신규 엔티티 0. 기존 Issue.labels(TEXT[]) + normalizeLabels 도메인검증 재사용. ADR `2026-06-04-issue-label-freeform-tag-model`)
- [x] D2. 명세 — prefix 매칭 + 사용 빈도 정렬 (책임. backend-engineer) (완료. PR #79 — prefix 대소문자무시(ILIKE) + 사용 빈도순(COUNT DISTINCT 이슈 수, 동률 시 라벨 알파벳 ASC tiebreak) 최대 10. q 빈값→전체 인기 top-10, 매칭0→빈배열200. ILIKE 와일드카드(%/_/\) ESCAPE 이스케이프)
- [x] D3. 데이터 모델 — 기존 `issues.labels TEXT[]` 활용 (책임. backend-engineer) (완료. PR #79 — **정규화 테이블(labels/issue_labels) 미도입**. 기존 `issues.labels TEXT[]` + `ix_issues_labels_gin` 그대로 사용. 마이그레이션/init_codegen 변경 0. plan 원표기 "labels, issue_labels"는 SDD 05 정본(labels TEXT[])과 drift였음을 ADR로 정정)
- [x] D4. 백엔드 — `GET /api/v1/labels?q=<prefix>` (책임. backend-engineer) (완료. PR #79 — LabelController→LabelApplicationService→IssueRepository.findLabelsByPrefix. raw SQL(dsl.fetch, DATA.md §5 예외 — prefix/limit 바인드파라미터, UNNEST+COUNT DISTINCT 집계, deleted_at 제외). **권한 가드는 코드리뷰 B1(prod resolver가 IssueScope.Global 하드거부→전 사용자 403, non-prod AlwaysAllow 마스킹) 발견으로 제거→인증 공통 접근, 권한 정교화는 FR-PM-05 위임**)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (완료. PR #79 — repository 통합(Testcontainers — 빈도순/삭제이슈 제외/ILIKE 이스케이프 리터럴매칭/동률 tiebreak) + service 단위(MockK q정규화) + controller 슬라이스(@WebMvcTest). 모듈 전체 test 그린, 신규 빈 컨텍스트 부팅 OK)
- [x] D6. 프론트 UI — cmdk 콤보박스 (책임. frontend-engineer) (완료. PR #79 — cmdk(1.1.1, 게이트1 승인) `LabelAutocompleteInput`이 기존 `IssueLabelsEdit`의 plain input을 **in-place 교체**(칩/검증/저장 경로 보존, 신규 컴포넌트 중복 회피). 키보드 네비(↓↑Enter) 자체구현, free-form 신규라벨 입력 허용. api/labels+use-labels(react-query)+use-debounce 재사용+MSW label-handlers. dead code command.tsx 정리(C1))
- [x] D7. E2E (책임. qa-engineer) (완료. PR #79 — `label-autocomplete.spec.ts` 3시나리오(S1 자동완성happy→칩→저장 / S2 free-form 신규라벨 / S3 빈포커스 인기라벨). MSW 이슈 PATCH stateful로 저장 후 반영 일관. 화살표키 네비 단위테스트 추가(C2). 전체 프론트 1184 통과)

### §2.3 정리 2개 (클론, PDF)

#### §2.3.1 FR-IS-06 — 이슈 클론 (옵션. 첨부/Watcher/댓글 포함)

**우선순위**. 중간 | **선행**. §2.1.1, §4.2.1, §4.3.1 | **Plan slug**. `issue/clone`

- [x] D1. 도메인 — CloneOptions (책임. backend-engineer) — Issue 애그리거트 재사용, 신규 엔티티 없음. ADR `2026-06-02-issue-clone-semantics`.
- [x] D2. 명세 — 무엇이 복사되고 무엇이 새로 시작되는지 (책임. backend-engineer) — `docs/specs/2026-06-02-issue-clone.md`. 복사(summary/description/type/priority/labels/env/impact/assignee) vs 새로 시작(key/reporter/상태/version/시각).
- [x] D3. 데이터 모델 — (활용만) (책임. db-engineer) — 신규 테이블/마이그레이션 없음. 기존 `issues` INSERT.
- [x] D4. 백엔드 — `POST /api/v1/issues/{key}/clone` (책임. backend-engineer) — `CloneIssueRequest(includeAssignee, summaryOverride)`. 같은 프로젝트 한정. 권한 VIEW(원본)+CREATE(프로젝트).
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — application 단위(MockK) + 컨트롤러 슬라이스(@WebMvc) + 런타임 통합(Testcontainers).
- [x] D6. 프론트 UI — 옵션 다이얼로그 (책임. designer → frontend-engineer) — PR #78. 메타패널 클론 버튼 + `CloneIssueDialog`(includeAssignee 체크박스 기본 true + summaryOverride 입력 ≤255) + `useCloneIssue`(성공 시 새 이슈 navigate + 토스트, 404/403/400 분기) + `cloneIssueHandler`(stateful MSW). 클론 버튼 권한 게이트는 프론트 권한 API에 CREATE 미노출로 서버 403+토스트 처리(FR-PM 후속).
- [x] D7. E2E (책임. qa-engineer) — PR #78. `issue-clone.spec.ts` 4시나리오(happy 새이슈 이동/includeAssignee 토글/취소/maxLength 255). data-testid+dialog 컨테이너 한정 strict mode 안전. 전체 E2E 94 통과/회귀 0.

> **이연 (deferred)**. FR 제목의 "옵션: 첨부/Watcher/댓글 포함"은 해당 하위 시스템(Attachment/Watcher/IssueComment)이 미구현이라 이번 범위에서 제외. 해당 기능 도입 후 `CloneOptions` 확장으로 충족 (ADR §3). 다른 프로젝트로의 클론은 FR-MV(이슈 이동)와 함께 다룸 (ADR §4).

#### §2.3.2 FR-IS-08 — 이슈 인쇄 + PDF 출력

**우선순위**. 중간 | **선행**. §2.1.1 | **Plan slug**. `issue/pdf-export`

- [x] D1. 도메인 (책임. backend-engineer) — PR #71 (읽기 전용 출력, 신규 엔티티 0, 기존 MarkdownRenderer sanitized HTML 재사용)
- [x] D2. 명세 — PDF 레이아웃 + 페이지 헤더/푸터 (책임. backend-engineer) — PR #71 (메타 표 + @page 헤더/푸터, docs/specs/2026-06-03-fr-is-08-pdf.md)
- [x] D3. 데이터 모델 — (활용만) (책임. db-engineer) — PR #71 (변경 0)
- [x] D4. 백엔드 — `openhtmltopdf` + `pdfbox`. `GET /api/v1/issues/{key}/pdf` (책임. backend-engineer) — PR #71 (openhtmltopdf-pdfbox:1.0.10, NanumGothic OFL 폰트 번들, IssuePdfTemplate→IssuePdfRenderer→controller. NFR1 descriptionHtml만 신뢰)
- [x] D5. 백엔드 테스트 — PDF 바이너리 검증 (책임. backend-engineer) — PR #71 (단위+통합. %PDF- 시그니처, PDFTextStripper 한글 추출, XSS sanitize, 404)
- [x] D6. 프론트 UI — 인쇄 버튼 + 다운로드 (책임. designer → frontend-engineer) — PR #74 (이슈 상세 breadcrumb 우측 PDF 다운로드 버튼. downloadIssuePdf(apiFetch→blob, 코드베이스 첫 바이너리 다운로드) + triggerBlobDownload 헬퍼(createObjectURL→앵커 click→revokeObjectURL finally 누수방지) + MSW 핸들러. 로딩 disabled+sonner 에러토스트, i18n issueDetailStrings. "인쇄"는 서버 PDF 다운로드로 해석(브라우저 print 별도 미추가). 부분 mock으로 기존 30+ 라우트 테스트 생존)
- [x] D7. E2E (책임. qa-engineer) — PR #74 (issue-pdf.spec.ts — waitForEvent('download') 선셋업→버튼 클릭→suggestedFilename ATLAS-1.pdf 검증. 전체 E2E 90 passed/1 skip 회귀 0)

### §2.4 인프라 1개 (커스텀 필드)

#### §2.4.1 FR-IS-10 — 커스텀 필드 인프라 (프로젝트별 정의 + JSONB 값 저장)

**우선순위**. 높음 | **선행**. §2.1.1 | **Plan slug**. `fr-is-10-custom-fields`

> **선후행**. FR-IS-10(이 작업, 커스텀 필드) → FR-PM-07(필드 수준 권한, 코어+커스텀 필드 대상). 필드 권한이 커스텀 필드를 대상으로 포함하므로 FR-IS-10이 선행.
> **스펙**. `docs/specs/2026-06-08-fr-is-10-custom-fields.md`. **ADR**. `docs/decisions/2026-06-08-custom-fields-model.md`.

- [x] D1. 도메인 — FieldType enum(10종) + CustomFieldDefinition + CustomFieldOption + 예외 (책임. backend-engineer) — PR #96
- [x] D2. 명세 — 정의 CRUD API 계약 + 이슈 JSONB 값 검증 규칙 (E1~E11) (책임. backend-engineer) — PR #96
- [x] D3. 데이터 모델 — V015 마이그레이션: `custom_field_definitions` + `custom_field_options` + `issues.custom_fields JSONB` + GIN 인덱스 + init_codegen 미러 (책임. db-engineer) — PR #96
- [x] D4. 백엔드 — `/api/v1/projects/{key}/custom-fields` CRUD + 이슈 Create/Update/Response customFields 통합 + CustomFieldValueValidator (책임. backend-engineer + security-engineer 권한 결선 T1/T6) — PR #96
- [x] D5. 백엔드 테스트 — 도메인 단위(MockK) + Testcontainers 통합(마이그레이션/정의CRUD권한/값 왕복/E1~E11 검증/소프트삭제 후 값 보존) (책임. backend-engineer + qa-engineer) — PR #96
- [x] D6. 프론트 UI — 커스텀 필드 관리 페이지 + 이슈 폼 동적 렌더링 (책임. designer → frontend-engineer) — PR #98 (settings.custom-fields 관리 페이지 + FieldType 10종 위젯(네이티브 input) + 이슈 생성폼/상세 메타패널 통합. cross-BC 예외: MyProjectPermissionController에 MANAGE_CUSTOM_FIELDS 노출. E-6 값 비우기 null 정규화, E-3 required 클라 경고)
- [x] D7. E2E — 정의 CRUD + 이슈 값 입력/검증 시나리오 (책임. qa-engineer) — PR #98 (custom-fields.spec.ts 8 시나리오: 정의 CRUD/권한 disabled/값 입력/required·403)

## §3 컴포넌트 / 버전 (7개)

### §3.1 컴포넌트 (FR-CM, 4개)

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

- [x] D1. 도메인 (책임. backend-engineer) — PR #81 (Issue.componentIds + assignComponents/clearComponents distinct 정규화)
- [x] D2. 명세 (책임. backend-engineer) — PR #81 (ADR docs/adr/2026-06-04-issue-component-assignment-model.md: 정규화 조인테이블 + 전체교체 set + 이슈 편집권)
- [x] D3. 데이터 모델 — `issue_components` 다대다 (책임. db-engineer) — V012, PR #81 (복합 PK + FK ON DELETE CASCADE(관계테이블, prod 소프트삭제 미발화) + init_codegen 미러)
- [x] D4. 백엔드 — 이슈 PATCH 확장 (책임. backend-engineer) — PR #81 (`PATCH /api/v1/issues/{key}/components` 전용 서브리소스, IssuePermission.UPDATE+IssueScope.Issue, 같은프로젝트+활성 검증 422 IssueComponentNotFoundException, 낙관락 409, 도메인 경유. componentIds는 단건 응답에만 노출)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #81 (도메인 단위 + Testcontainers 통합 S1~S9 + prod 권한 + 멱등 + 읽기 활성필터, 969 그린)
- [x] D6. 프론트 UI — 다중 셀렉터 (책임. designer → frontend-engineer) — PR #81 (ComponentMultiSelect 순수 presentational + IssueMetaPanel 배선 + route가 mutation 소유 + useChangeComponents invalidate-only + issueResponseSchema 계약 정렬)
- [x] D7. E2E (책임. qa-engineer) — PR #81 (issue-components.spec.ts 할당/부분교체/전부해제 happy path)

#### §3.1.3 FR-CM-03 — 컴포넌트별 기본 담당자 자동 할당

**우선순위**. 높음 | **선행**. §3.1.1, §2.1.3 | **Plan slug**. `issue/components-default-assignee`

- [x] D1. 도메인 (책임. backend-engineer) — PR #84 (DefaultAssigneeResolver 순수 함수 + Issue.create componentIds 수용. ADR docs/adr/2026-06-05-component-default-assignee-auto-assignment.md)
- [x] D2. 명세 — 다중 컴포넌트 시 우선순위 규칙 (책임. backend-engineer) — PR #84 (기본 담당자=컴포넌트 leadUserId 재사용, 미할당일 때만, 다중이면 리드 보유 컴포넌트 이름 사전순 첫 번째, silent. 단일 전환 검토 후 다중 유지)
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer) — PR #84 (마이그레이션 없음, components.lead_user_id + issue_components 재사용)
- [x] D4. 백엔드 — 이슈 생성/컴포넌트 변경 trigger (책임. backend-engineer) — PR #84 (createIssue에 componentIds 입력 추가+생성 전용 no-bump insertComponents+생성시 자동배정, changeComponents 자동배정 no-bump setAssignee로 version +1 유지. 도메인 assignTo 경유)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #84 (도메인 단위 + Testcontainers 통합 생성/변경 S1~S6 + version 정확성(생성=1·변경 +1) + 422 검증, 989 그린)
- [x] D6. 프론트 UI — 자동 표시 (책임. frontend-engineer) — PR #84 (생성 폼 ComponentMultiSelect 재사용+useComponents enabled 확장 lazy 로드, 담당자 표시는 FR-IS-03 기존 UI 재사용+invalidate-only refetch, MSW 자동배정 백엔드 규칙 미러)
- [x] D7. E2E (책임. qa-engineer) — PR #84 (issue-component-default-assignee.spec.ts: 컴포넌트 시드→자동배정 담당자 이름 검증 + 사전순 tiebreak. 자동배정 ground-truth는 백엔드 통합테스트)

> **FR-CM-03 완료 (2026-06-05, PR #84)**. 컴포넌트 리드 자동 배정(생성+변경 trigger, 미할당일 때만, 다중이면 이름 사전순 첫 리드, silent). **후속 FR 예정** — 컴포넌트 리드 없을 때 프로젝트 리드 폴백(2순위). admin(다수·권한)≠lead(단일·담당)이라 project_memberships 단일 PROJECT_LEAD 지정 + cross-BC 포트로 별도 설계. **머지 주의** — FR-PM-05(PR #85)와 IssueApplicationService.kt 동시 수정(권한 vs 자동배정 다른 영역), 머지 후 컴파일+테스트 검증 통과.

#### §3.1.4 FR-CM-04 — 컴포넌트 리드 부재 시 프로젝트 리드 폴백 (2순위)

**우선순위**. 중간 | **선행**. §3.1.3, §4.1(FR-PM-01) | **Plan slug**. `fr-cm-04-project-lead-fallback`

> **배경 (2026-06-05, FR-CM-03 PR #84 후속)**. FR-CM-03 자동 배정은 컴포넌트 리드(1순위)만 사용하고, 리드가 없으면 미할당으로 남긴다. Jira식으로 컴포넌트 리드가 없을 때 **프로젝트 리드**를 2순위 폴백으로 쓰자는 요구(Maxi). 단 BTS엔 "프로젝트 리드"(단일) 개념이 없었다 — `projects` 테이블에 lead 필드 없음, FR-PM-01의 `PROJECT_ADMIN`은 **다수·권한** 개념이라 자동배정 대상(단일·업무 책임)과 다르다(admin≠lead).
>
> **결정 (2026-06-06, 구현 PR — ADR docs/adr/2026-06-06-project-lead-default-assignee-fallback.md)**. 프로젝트 리드를 **`projects.lead_user_id` 컬럼**(옵션 B, in-BC)으로 표현한다. issue-tracking 소유 테이블이라 **cross-BC 포트 불필요** — `components.lead_user_id`(FR-CM-03) 동형. 대안이던 project_memberships 단일 `PROJECT_LEAD` 역할(옵션 A)은 cross-BC 포트 + 단일 UNIQUE 제약 비용으로 기각. 범위는 폴백 로직 + 지정/해제 API. 지정 UI(D6)·E2E(D7)는 후속.

- [x] D1. 도메인 — 폴백 체인(컴포넌트 리드 → 프로젝트 리드 → 미할당) 규칙 (책임. backend-engineer) — current!=null·클론 제외
- [x] D2. 명세 — 프로젝트 리드 지정 모델 결정 → **옵션 B `projects.lead_user_id` 컬럼**(in-BC), admin≠lead (책임. backend-engineer)
- [x] D3. 데이터 모델 — `projects.lead_user_id` 마이그레이션(V013) + init_codegen 미러. cross-BC 포트 없음(in-BC) (책임. backend-engineer)
- [x] D4. 백엔드 — 프로젝트 리드 지정/해제 API(PATCH /api/v1/projects/{idOrKey}/lead) + DefaultAssigneeResolver 폴백 확장 + IssueApplicationService 주입 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 폴백 우선순위(resolver 단위) + 리드 실존(UserLookupPort, 422) + 생성/변경 통합(Testcontainers) (책임. backend-engineer)
- [x] D6. 프론트 UI — 프로젝트 설정에 리드 지정 (책임. designer → frontend-engineer) (완료. PR #91 — 신규 라우트 `/projects/$projectKey/settings/project-lead`(RouteAdapter+Page, workflow-scheme 동형) + 백엔드 조회 GET `/api/v1/projects/{idOrKey}/lead`(same-BC view layer, readOnly, 무권한 READ) + api/project-lead+useProjectLead/useChangeProjectLead+ProjectLeadSelect(ComponentLeadSelect 동형)+MSW stateful. 권한 fail-closed(MANAGE_COMPONENTS) + CSRF + errorCode. 적대적 리뷰 #1(삭제된 리드→"미지정" 둔갑) 수정 — leadUserId 있는데 조회 실패 시 "알 수 없는 사용자 (uuid 앞8자)"+해제버튼 표시. 컴포넌트 리드 동형 UX 개선(#2~#4)·D7 E2E는 후속)
- [x] D7. E2E (책임. qa-engineer) (완료. PR #92 — `project-lead.spec.ts` 5시나리오(S1 지정/S2 해제/S3 권한disabled bob/S4 삭제리드 "알 수 없는 사용자"/S5 폴백 자동배정=리드 없는 컴포넌트 이슈 생성→프로젝트 리드 김앨리스 자동배정). MSW createIssueHandler 폴백 미러 확장(getStoredProjectLead getter, 컴포넌트 리드→프로젝트 리드 폴백, 브라우저 시드 X-MSW-Seed-ProjectLead). 구현 src 무수정(mocks/e2e만). E2E 9/9(신규5+회귀4) 통과. 코드리뷰 CONCERN1(미사용 dead code) 제거)

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

- [x] D1. 도메인 (책임. backend-engineer) — PR #105 (`VersionStatus` enum 3종 + `Version.status`/`releasedAt` 필드 + release/unrelease/archive/unarchive 전이 메서드 + assertNotArchived 가드. 도메인은 `Instant` 수신, Clock은 서비스 주입)
- [x] D2. 명세 — 상태 전이 규칙 (책임. backend-engineer) — PR #105 (ADR docs/adr/2026-06-10-version-status-and-transitions.md, Jira 정석 그래프 UNRELEASED⇄RELEASED·둘다→ARCHIVED·ARCHIVED→UNRELEASED, self/그래프외 409, ARCHIVED 읽기전용, released_at 자동)
- [x] D3. 데이터 모델 — `versions.status` (책임. db-engineer) — PR #105 (V016 status VARCHAR(20) NOT NULL DEFAULT 'UNRELEASED' + released_at TIMESTAMPTZ + ck_versions_status CHECK, init_codegen 미러)
- [x] D4. 백엔드 — 상태 전이 API + 가드 (책임. backend-engineer) — PR #105 (PATCH /{id}/status changeStatus + applyTransition when, VersionPermission.UPDATE 재사용, 409 VERSION_TRANSITION_NOT_ALLOWED, delete ARCHIVED 명시 차단(repo 직행 우회 방지))
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #105 (도메인 전이/거부 + repo status/released_at 왕복 + service changeStatus + 통합 S1~S7 + 마이그레이션, 136 그린)
- [x] D6. 프론트 UI (책임. designer → frontend-engineer) — PR #105 (VersionRow 상태 뱃지 + TRANSITION_ACTIONS 전이 버튼 + ARCHIVED 수정/삭제 disabled, Zod status/releasedAt(NON_NULL .nullable().optional()), useChangeVersionStatus invalidate-only, MSW stateful 전이그래프)
- [x] D7. E2E (책임. qa-engineer) — PR #105 (version-status.spec.ts 4시나리오 + 기존 version-management 5 회귀. session-fixtures loginAsAlice FR-AU-07 2단계 hot-fix. issue/workflow fixture 1단계 회귀는 별도 PR)

#### §3.2.3 FR-VR-03 — Affects/Fix Version 연결

**우선순위**. 필수 | **선행**. §3.2.1 | **Plan slug**. `issue/versions-link`

- [x] D1. 도메인 — Affects vs Fix 의미 (책임. backend-engineer) — PR #107 (Issue 애그리거트에 `affectsVersionIds`/`fixVersionIds` + assign/clear, componentIds 동형. 생성 시점 미지원 — PATCH 교체만)
- [x] D2. 명세 (책임. backend-engineer) — PR #107 (이슈↔컴포넌트(FR-CM-02/03) 동형. 전체 교체+OCC, 타 프로젝트/삭제 버전 422, ARCHIVED는 API 허용·UI 숨김. 에러코드 `ISSUE_LINKED_VERSION_NOT_FOUND`)
- [x] D3. 데이터 모델 — `issue_affects_versions`, `issue_fix_versions` (책임. db-engineer) — PR #107 (V017, 복합 PK + version_id 인덱스 + FK CASCADE, 소프트삭제 없음, init_codegen 미러)
- [x] D4. 백엔드 (책임. backend-engineer) — PR #107 (PATCH /{key}/affects-versions·/fix-versions 2종, IssueResponse 노출(withSingleDetail 독립 쿼리 2개), IssuePermission.UPDATE 재사용. versionRepository non-null 주입 — fail-open 차단)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #107 (도메인/repo/service + 통합 S1~S12(errorCode 바디 단언) + MVC + 마이그레이션, 전체 모듈 1415 그린)
- [x] D6. 프론트 UI — 버전 셀렉터 2종 (책임. frontend-engineer) — PR #107 (VersionMultiSelect variant affects/fix, ARCHIVED 드롭다운 숨김(연결분은 표시), issues.$key 라우트 배선, Zod optional+default, MSW stateful)
- [x] D7. E2E (책임. qa-engineer) — PR #107 (issue-versions-link.spec.ts S0~S5 happy path 6/6. 기존 issue-components 로그인은 FR-AU-07 1단계 회귀로 별도 PR — session-fixtures 2단계 loginAsAlice 사용)

#### §3.2.4 FR-VR-04 — 버전 릴리즈 노트 자동 생성

**우선순위**. 중간 | **선행**. §3.2.1, §3.2.3 | **Plan slug**. `issue/versions-release-notes`

- [x] D1. 도메인 (책임. backend-engineer) — PR #110 (조회 전용 — 영속 안 함. ReleaseNoteIssue 입력 모델 + ReleaseNotes 출력 모델, 신규 엔티티/테이블 없음)
- [x] D2. 명세 — Markdown 템플릿 (책임. backend-engineer) — PR #110 (타입별 그룹핑 hierarchy_level 순+그룹 내 키 순, resolution 표시, 0건 안내, summary 줄바꿈 sanitize. ReleaseNotesGenerator 순수 함수)
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer) — PR #110 (신규 테이블 없음. IssueRepository.findFixVersionIssuesForReleaseNotes 역방향 조회(단일 JOIN, cartesian 안전) + ProjectLookupRepository.findProjectKeyById)
- [x] D4. 백엔드 — `GET /api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes` (책임. backend-engineer) — PR #110 (별도 ReleaseNotesController, DataResponse 래핑, READ 게이트 없음(getById 정책), VersionExceptionHandler basePackage 스코프 커버, Clock 주입 generatedAt)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #110 (Generator 단위 + Service mockk + Repository 통합 + Controller 통합 S1~S7(errorCode 바디 단언), Clock 고정 결정론, WebMvcConfigurer로 Instant ISO 직렬화)
- [x] D6. 프론트 UI — 미리보기 + 복사 (책임. designer → frontend-engineer) — PR #110 (ReleaseNotesDialog radix Dialog + 클립보드 복사 graceful, VersionRow "릴리즈 노트" 버튼, Zod↔DTO 1:1, useReleaseNotes lazy fetch)
- [x] D7. E2E (책임. qa-engineer) — PR #110 (version-release-notes.spec.ts 3 happy path(열림/복사/닫기) + 기존 version E2E 9 회귀, 클립보드 권한 grant, 한글 markdown Base64 헤더)

## §4 첨부 / 멘션 / 댓글 / Watcher (7개)

### §4.1 멘션 (FR-MN, 2개)

#### §4.1.1 FR-MN-01 — 본문/댓글 @멘션 + 즉시 알림

**우선순위**. 필수 | **선행**. §2.1.4, §4.3.1 | **Plan slug**. `issue/mentions`

> **범위(2026-06-11, PR #114, Maxi 옵션 A 확정)**. 백엔드 발행분(D1~D5)만 PR #114로 완료 — **본문(description) 멘션 추출 → `IssueMentioned`(issue.mentioned) pgmq 발행**까지. **댓글 멘션은 댓글 기능 부재로 제외**(댓글 FR 도입 시 sourceField="comment"로 확장), **그룹 멘션(@team)은 FR-PM-09 user_groups 소비 별도 단위로 제외**.
>
> **D6/D7 완료(2026-06-27, PR #197, Maxi 옵션 A 확정)**. deferred 사유였던 알림 전달(FR-NT)·Inbox(FR-UX-03) 인프라가 완성되어 진행. **D6 강조는 백엔드 마크업** — `MarkdownRenderer`가 flexmark 인라인 확장으로 `@username`→`<span class="mention">` 마크업(코드/링크 자동 제외) + 정화 allowlist `span[class=mention]` 추가, 프론트는 `.mention` CSS만(실존 검증 없는 형식 기반 강조). 멘션→알림→Inbox 파이프라인은 백엔드 기존 완성분 재사용. **FR-MN-01 전체 완료.**

- [x] D1. 도메인 — Mention 이벤트 (책임. backend-engineer) (완료. PR #114 — `IssueMentioned`(issue.mentioned) sealed subtype + 직렬화 라운드트립)
- [x] D2. 명세 — `@username` 파싱 규칙 (책임. backend-engineer) (완료. PR #114 — `MentionParser` object: lookbehind 이메일/`@@` 회피·영숫자 경계·코드스팬(인라인 한 줄 한정)/펜스블록 제거·dedup)
- [x] D3. 데이터 모델 — (notification 이벤트 발행만) (책임. db-engineer) (완료. PR #114 — 마이그레이션 없음, 기존 `q_issue_events` pgmq 큐 재사용)
- [x] D4. 백엔드 — 본문 저장 시 mention 추출 → pgmq 이벤트 (책임. backend-engineer) (완료. PR #114 — `updateIssue`에서 description 변경 시 diff기반 신규멘션만·자기제외·UUID정렬·cap 50, cross-BC `UserLookupPort.findIdsByUsernames`(대소문자 무시) 해석, 같은 트랜잭션 outbox. 댓글은 제외)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (완료. PR #114 — MentionParser 단위·UserLookupAdapter 통합(대소문자/과다매칭)·updateIssue 멘션 단위(S1~S5+cap)·pgmq enqueue Testcontainers 통합)
- [x] D6. 프론트 UI — 멘션 렌더링 (강조) (책임. backend-engineer → frontend-engineer) (완료. PR #197 — 옵션 A 백엔드 마크업. `MentionExtension`(flexmark 인라인 확장)이 `@username`→`<span class="mention">`, 코드스팬/코드블록/링크/이메일/`@@` 제외(AST 노드 분리+`\G` 앵커로 텍스트 유실 차단), 멘션 정규식은 `MentionParser.MENTION_PATTERN` 공유. `SANITIZE_POLICY`에 `span[class=mention]` 정확일치 허용. 프론트 `.mention` CSS(primary text+accent bg, 유채색 신설 0). 형식 기반 강조(실존 검증 없음))
- [x] D7. E2E — 멘션 → Inbox 도착 (책임. qa-engineer) (완료. PR #197 — `issue-mention-render.spec.ts` S1~S6(강조/코드·이메일 제외/멘션→Inbox 도착/분별시드/후행마침표/선행구두점). MSW 멘션 파생(description PATCH→Inbox 공유 store, 백엔드 형식 미러). ground-truth는 백엔드 통합테스트)

#### §4.1.2 FR-MN-02 — 멘션 자동완성

**우선순위**. 높음 | **선행**. §4.1.1 | **Plan slug**. `issue/mentions-autocomplete`

> **구현 deviation (#158, Maxi 확정 2026-06-17)**. **프론트 전용·백엔드 신규 0**. D4 `GET /api/v1/users/autocomplete?q=` → **기존 `GET /api/v1/users?query=` 재사용**(FR-IS-03 Task 4 / ADR `2026-06-01-issue-assignee-user-lookup-port` §2, 담당자 셀렉터와 동일 엔드포인트). 매칭은 명세 "prefix" → **substring(ILIKE `%q%`) 유지**(Slack/GitHub식 관대 매칭). cross-BC 포트 불요(프론트가 identity-access 직접 호출). D6 "TipTap 확장" → **마크다운 textarea 기반 `@` typeahead**(TipTap 미도입, 의존성 0). 적용 위치는 `IssueDescription` 편집모드 Write 탭 textarea 단일(이슈 생성폼 description textarea·댓글 기능 부재로 후속). classify 조정 type=ui·agent=frontend-engineer. **전체 완료(프론트 #158, 백엔드 변경 0).**

- [x] D1. 도메인 — 신규 엔티티 0, 기존 User 읽기. cross-BC 경계 해소(순수 identity-access) (책임. frontend-engineer) — PR #158
- [x] D2. 명세 — **deviation. substring 재사용**(prefix 아님), 트리거 경계/쿼리 추출/IME 규칙 (책임. frontend-engineer) — PR #158
- [x] D3. 데이터 모델 — (users 활용) 읽기만, 마이그레이션 0 (책임. —) — PR #158
- [x] D4. 백엔드 — **deviation. 신규 0**(기존 `GET /api/v1/users?query=` 재사용) (책임. —) — PR #158
- [x] D5. 백엔드 테스트 — **해당 없음**(백엔드 변경 0, 기존 UsersController 테스트 커버) (책임. —) — PR #158
- [x] D6. 프론트 UI — **deviation. 마크다운 textarea `@` typeahead**(useMentionAutocomplete 훅 + MentionDropdown docked listbox, fetchUsers 재사용). caret splice·키보드 네비·IME 보류 (책임. frontend-engineer) — PR #158
- [x] D7. E2E — issue-mention-autocomplete.spec.ts 4시나리오(클릭/키보드 선택·Escape·저장), 실 브라우저 caret 검증 (책임. qa-engineer) — PR #158

### §4.2 첨부 (FR-AC, 2개)

#### §4.2.1 FR-AC-01 — 첨부 업로드 (최대 100MB/파일)

**우선순위**. 필수 | **선행**. §2.1.1, MinIO 인프라 | **Plan slug**. `issue/attachments`

> **구현 deviation (#145, ADR `2026-06-15-fr-ac-01-attachment-storage`, Maxi 확정)**. D4를 Presigned URL → **서버 경유 멀티파트 스트리밍**으로 변경(권한 일원화·고아객체 회피). scope를 업로드만 → **업로드+다운로드+목록+삭제**로 확장(실사용 완결). 삭제=하드삭제(DATA.md §3 동기화). 권한은 신규 권한 대신 `IssuePermission.UPDATE/VIEW` 재사용(enum 무변경). MIME 화이트리스트(#149)·ClamAV 바이러스 스캔(#150, 저장 전 동기·fail-closed·의존성0)은 후속으로 적용 완료(D2 종료). 프론트 D6 표기 "react-dropzone + 직접 PUT"은 deviation으로 **네이티브 HTML5 파일선택(의존성0) + 서버경유 멀티파트**로 변경(Maxi 확정 2026-06-15). **전체 완료(백엔드 #145 + 프론트/E2E #146).**

- [x] D1. 도메인 — Attachment (책임. backend-engineer)
- [x] D2. 명세 — 크기 제한(100MB) 적용 / **MIME 화이트리스트 적용(#149 — Content-Type+확장자 독립 allowlist, 광범위 화이트리스트, 위반 415)** / **바이러스 스캔(ClamAV) 적용(#150 — 저장 전 동기 스캔·fail-closed·raw clamd INSTREAM 의존성0·감염 422 ISSUE_ATTACHMENT_INFECTED/데몬미가용 503 ISSUE_ATTACHMENT_SCAN_UNAVAILABLE·실 clamd EICAR 통합테스트)** (다운로드 Content-Disposition: attachment + nosniff(#148)로 인라인 실행 차단) (책임. backend-engineer + security-engineer)
- [x] D3. 데이터 모델 — `issue_attachments(storage_key)` V023 (UUID PK/FK, 하드삭제) (책임. db-engineer)
- [x] D4. 백엔드 — 서버 경유 스트리밍 `POST/GET/DELETE /api/v1/issues/{key}/attachments` (업로드/다운로드/목록/삭제) (책임. backend-engineer + security-engineer)
- [x] D5. 백엔드 테스트 — Testcontainers MinIO (책임. backend-engineer)
- [x] D6. 프론트 UI — 네이티브 HTML5 파일선택(드롭존) + 서버경유 멀티파트 업로드/목록/다운로드/삭제 (책임. frontend-engineer) (완료. PR #146 — apiFetch FormData 확장(JSON 호출 불변), AttachmentSection 본문하단 배선(canUpdate 게이팅), triggerBlobDownload 재사용, 삭제 인라인확인(하드삭제 경고). 의존성 0. 적대적리뷰 hot-fix 3건(키보드 Space preventDefault·다중파일 토스트 파일명·FormData retry 가드))
- [x] D7. E2E (책임. qa-engineer) (완료. PR #146 — issue-attachments.spec.ts 6시나리오(S1 업로드/S2 목록/S3 다운로드/S4 삭제/S5 권한게이팅/S7 빈상태). MSW stateful 브라우저 시드(X-MSW-Seed-Attachment), 기존 issue E2E 회귀 0)

#### §4.2.2 FR-AC-02 — 첨부 미리보기 (이미지/PDF/동영상)

**우선순위**. 높음 | **선행**. §4.2.1 | **Plan slug**. `issue/attachments-preview` (실제 `fr-ac-02-preview`)

> **구현 deviation (#147, Maxi 확정 2026-06-15)**. D4를 Presigned GET URL → **기존 download 엔드포인트 blob 재사용**(`URL.createObjectURL`)으로 변경 → **백엔드 신규 0**(D4/D5 해당 없음). FR-AC-01이 이미 presigned 폐기·서버경유 일관. D6 "react-pdf + video.js"를 **네이티브 HTML5(img/iframe/video) + 의존성 0**으로 변경(FR-AC-01 "의존성 0" 기조, DEVELOPMENT.md §17). 보안=미리보기 MIME 화이트리스트(image 4종+pdf+mp4/webm, SVG/HTML 제외) 1차 + iframe MIME-typed 렌더 2차. **G3**: PDF iframe sandbox 제거(빈 미리보기 회피, 화이트리스트가 1차 방어). **후속**: 다운로드 응답 `X-Content-Type-Options: nosniff` 헤더는 FR-AC-01 백엔드 결함이라 **별도 후속 PR로 분리**(Maxi 확정). **전체 완료(프론트 전용, 백엔드 변경 0).**

- [x] D1. 도메인 — Attachment(FR-AC-01) 재활용, 신규 엔티티 0 (책임. frontend-engineer) — PR #147
- [x] D2. 명세 — MIME별 렌더러(image→img, pdf→iframe, video→video) + 미리보기 화이트리스트 (책임. frontend-engineer) — PR #147
- [x] D3. 데이터 모델 — (활용) `issue_attachments.content_type` 읽기, 변경 0 (책임. —) — PR #147
- [x] D4. 백엔드 — **deviation. 신규 0**(기존 `GET /api/v1/issues/{key}/attachments/{id}` blob 재사용) (책임. —) — PR #147
- [x] D5. 백엔드 테스트 — **해당 없음**(백엔드 변경 0) (책임. —) — PR #147
- [x] D6. 프론트 UI — **deviation. 네이티브 HTML5**(img/iframe/video) + radix Dialog 미리보기 모달, objectURL 생명주기 (책임. frontend-engineer) — PR #147
- [x] D7. E2E — issue-attachment-preview.spec.ts 6시나리오(이미지/PDF/동영상/화이트리스트밖/닫기), MSW 실바이트, FR-AC-01 회귀 0 (책임. qa-engineer) — PR #147

### §4.3 Watcher (FR-WT, 1개)

#### §4.3.1 FR-WT-01 — Watcher 추가/제거 + 자동 Watcher

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/watchers`

- [x] D1. 도메인 — Watcher(issue,user) 관계, user_id FK 미적용(BC 격리, assignee_id 선례) (책임. backend-engineer) — PR #151
- [x] D2. 명세 — **deviation. 타인 추가 가능(Jira식, Maxi 확정)** + Reporter/Assignee 자동 Watcher. 자동 watcher 3진입점(createIssue·changeAssignee·changeComponents 자동재배정), 재배정 시 이전 assignee 유지·unassign 무변경. cloneIssue 자동watch 제외(ADR 이연) (책임. backend-engineer) — PR #151
- [x] D3. 데이터 모델 — `issue_watchers(issue_id, user_id, created_at)` 복합PK 멱등 + issue_id FK ON DELETE CASCADE, V024 + init_codegen 미러 (책임. db-engineer) — PR #151
- [x] D4. 백엔드 — **deviation. `GET` 추가** + `POST/DELETE /api/v1/issues/{key}/watchers`. 권한 분기(본인=VIEW/타인=UPDATE), 타인 추가 UserLookupPort 검증(422), 멱등(ON CONFLICT) (책임. backend-engineer) — PR #151
- [x] D5. 백엔드 테스트 — repository 8 + service 21 + controller 11 + 자동watcher 8 + 마이그레이션 스키마 (책임. backend-engineer) — PR #151
- [x] D6. 프론트 UI — Watch 버튼(지켜보기/지켜보는 중 토글) + 카운트 + 감시자 명단(본인 "(나)") (책임. designer → frontend-engineer) — PR #152 (메타패널 WatchersSection 자체-훅, 4상태·긴목록 상한 8·aria-pressed·invalidate-only. FR-7 담당자/컴포넌트 변경 시 자동 watcher 라이브 갱신. watcher userId/displayName z.string 완화(whoami 공간 정합·빈 displayName 허용). 적대리뷰 P1/P2/P3(로딩윈도우 토글가드·mutation 에러토스트·빈displayName ZodError) 수정)
- [x] D7. E2E (책임. qa-engineer) — PR #152 (issue-watchers.spec.ts 3종: 초기 빈상태/watch(나 표시·카운트+1)/unwatch(카운트-1). 컨테이너 한정 셀렉터, 전체 E2E 108 통과 회귀 0)

### §4.4 댓글 (FR-CO, 2개)

> **왜 신규 FR 인가.** 댓글 백엔드는 `FR-IM-01` PR3(#224)의 **부산물**이다 — 외부 시스템에서 댓글을
> 가져오려면 Comment 도메인이 필요했다. 그래서 `CommentController` 는 `@GetMapping` 하나뿐이고
> 쓰기 REST 가 **0건**이며 프론트도 0건이었다. **REST 미노출 = 기능 없음.** `FR-MN-01`·`FR-IS-06`·
> `FR-HS-01` 이 *"댓글 FR 도입 시"* 로 명시 이연하며 기다리고 있었다.
> ADR [2026-07-27-fr-co-comment-feature](../../decisions/2026-07-27-fr-co-comment-feature.md).

#### §4.4.1 FR-CO-01 — 이슈 댓글 작성 + 목록 조회

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `fr-co-01`

- [x] D1. 도메인 — 기존 `Comment`(Issue 종속 child entity, Worklog 동형) 재사용. `comment/domain/CommentExceptions.kt` 신설(`CommentBodyBlankException`·`CommentBodyTooLongException`, `RuntimeException` 직접 상속 = 형제 관례) (책임. backend-engineer) — PR #315
- [x] D2. 명세 — 시나리오 10 · FR 22 · NFR 7 · 엣지 21 · 완료기준 14. **★D4 저작자 강제** — `create` 에서 `authorId` 파라미터를 제거해 위조를 컴파일 수준에서 차단, Import 는 `createImported` 로 분리. **★D7 검증 위치** — 본문 상한(32,000자)을 **서비스**에 둔다(생산자가 REST·automation 둘이고 `TemplateRenderer` 에 길이 제한이 없어 컨트롤러 검증은 automation 이 우회) (책임. backend-engineer) — PR #315
- [x] D3. 데이터 모델 — **마이그레이션 0건.** `V035__comments.sql` 이 부분 인덱스까지 이미 보유 (책임. db-engineer) — PR #315
- [x] D4. 백엔드 — `POST /api/v1/issues/{key}/comments` (201). `AddCommentRequest(body)` **저작자 필드 없음**. 권한 `IssuePermission.UPDATE` + `IssueScope.Issue` 고정(보안등급 우회 차단, 기존 유지). 아카이브 프로젝트 차단(기존 `archiveGuard`). `IssueCommented` 발행 유지(outbox, `MANDATORY`). `CommentExceptionHandler` 400 매핑 2종. **`bodyHtml` 렌더링을 `CommentView.of` 로 단일 지점화** (책임. backend-engineer) — PR #315
- [x] D5. 백엔드 테스트 — 서비스 14(경계 32000/32001 양쪽·공백·`createImported` 원본보존/상한면제) + 컨트롤러 12(GET 4 무회귀 + POST 8) + `IssueImportAdapterTest` 60 무회귀 + automation 어댑터 14. 모듈 전체 **306 클래스 3,128 tests 0 fail 0 skip**. **뮤테이션 봉인 2회** — 길이 검증 무력화 시 CO-2 red · actor 자기 제외 무력화 시 10건 red(역할 8종 + e2e) (책임. backend-engineer) — PR #315
- [ ] D6. 프론트 UI — 이슈 상세 활동 영역 4번째 "댓글" 탭 + `CommentSection`(`WorklogSection` 미러). 기본 활성 탭은 **이력 유지**(D8) (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §4.4.2 FR-CO-02 — 댓글 수정 + 삭제 (소프트)

**우선순위**. 필수 | **선행**. §4.4.1 | **Plan slug**. (미착수)

> **분할 근거 (Maxi 확정 D1).** 작성과 수정·삭제의 위험 성격이 다르다. 작성은 `create()` 재사용으로
> 얇고 실질 위험이 알림 입력 분포 하나인데, 수정·삭제는 도메인 확장(`Comment` 전 필드 `val` → 본문
> 변경) + repo 쓰기 메서드 신설 + **"누가 남의 댓글을 지울 수 있나" 정책 결정**이 붙는다.
>
> **선례가 예고하는 것.** Worklog 는 수정·삭제를 **작성자 한정**(`existing.authorId != actor` → 403)으로
> 두고 관리자 우회를 두지 않았다. 이 대칭을 따를지 모더레이션을 도입할지는 CO-02 자기 ADR 에서 결정한다.

- [ ] D1. 도메인 — `Comment` 본문 변경 허용(전 필드 `val` 해제) + `updatedAt` 갱신 (책임. backend-engineer)
- [ ] D2. 명세 — 모더레이션 정책 결정 (작성자 한정 vs PROJECT_ADMIN 우회) (책임. backend-engineer)
- [ ] D3. 데이터 모델 — 마이그레이션 0건 예상 (`deleted_at` 기존) (책임. db-engineer)
- [ ] D4. 백엔드 — `PATCH`/`DELETE /api/v1/issues/{key}/comments/{commentId}` + repo `update`/`softDelete` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 수정/삭제 버튼 · 인라인 편집 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §5 링크 / 히스토리 / 템플릿 (6개)

### §5.1 히스토리 (FR-HS, 2개)

#### §5.1.1 FR-HS-01 — 이슈 변경 이력 (필드/댓글/첨부/전이)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/history`

- [x] D1. 도메인 — IssueChangeGroup/IssueChangeItem/IssueChangeDetector (책임. backend-engineer) — PR #115 (Jira식 변경 그룹+항목. ADR 2026-06-11-issue-change-history-model)
- [x] D2. 명세 — 전 필드 + 생명주기 기록, no-op 이력0, 부분 라벨 박제 (책임. backend-engineer) — PR #115 (8개 변경 진입점. 값=원시ID, 라벨=변경 당시 표시명 박제. assignee/securityLevel cross-BC 표시명은 기록 시점 박제(PR #120 보강, 완전 Jira식). status는 project-workflow BC 소관으로 label=null 유지. 조회 UI는 FR-HS-02)
- [x] D3. 데이터 모델 — `issue_change_group` + `issue_change_item`(field/from·to_value/from·to_label) 2테이블, append-only (책임. db-engineer) — PR #115 (**deviation**: 계획의 단일 `issue_history` → Jira식 2테이블. V018, FK는 item→group만(issues FK 없음=이력 보존), init_codegen 미러)
- [x] D4. 백엔드 — 서비스 레이어 동기 기록 (IssueHistoryRecorder facade) (책임. backend-engineer) — PR #115 (**deviation**: 계획의 pgmq consumer → 이슈 변경과 **같은 트랜잭션** 동기 기록. recordChange private=self-invocation 회피, 자동배정 2차변경 캡처)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #115 (디텍터 22 + 라벨리졸버 18 + repository 5 + recorder + 서비스배선 + e2e 10 시나리오, 보안등급 단독변경 회귀 포함)
- [x] D6. 프론트 UI — 해당 없음 (FR-HS-01은 기록 전용, 조회 UI는 §5.1.2 FR-HS-02 소관) (책임. —) — PR #115
- [x] D7. E2E — 백엔드 e2e 통합테스트 (책임. backend-engineer) — PR #115 (IssueChangeHistoryE2EIntegrationTest: 다필드/no-op/소프트삭제 보존/라벨박제/트랜잭션/자동배정 2차변경. 프론트 없어 Playwright 해당없음)

#### §5.1.2 FR-HS-02 — 히스토리 조회 UI

**우선순위**. 필수 | **선행**. §5.1.1 | **Plan slug**. `issue/history-ui`

- [x] D1. 도메인 — FR-HS-01 변경 모델(IssueChangeGroup/Item) 재활용, 조회 전용 `IssueChangelogService` 추가 (책임. backend-engineer) — PR #122 (생성자 폭발반경 0 위해 신규 @Service 분리, VIEW 가드는 IssueApplicationService.findByKey 재사용)
- [x] D2. 명세 — 페이징(page/size, 최신순) (책임. backend-engineer) — PR #122 (**deviation**: 계획의 "필드 필터"는 범위 외 — 페이징만 제공. 권한별 보안등급 필드 마스킹은 응답단 처리. 소프트삭제·미존재·권한없음 모두 404로 존재 probe 차단)
- [x] D3. 데이터 모델 — (활용) FR-HS-01 `issue_change_group`+`issue_change_item` 읽기 전용 조회 (책임. db-engineer) — PR #122 (`findByIssuePaged`+`countByIssue` 추가, 조인 읽기 시 부모 deleted_at 필터)
- [x] D4. 백엔드 — `GET /api/v1/issues/{key}/changelog` 페이징 조회 (책임. backend-engineer) — PR #122 (**deviation**: 계획·SDD §11-api-design의 `/{key}/history` → `/{key}/changelog`. changelog 서비스/프론트 명명 일관 위해 채택(spec/plan 리뷰 확정). 단건조회와 동일 VIEW 가드 404, actor 표시명 UserLookupPort graceful 해석)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #122 (IssueChangelogServiceTest + IssueChangelogControllerIntegrationTest + JdbcIssueChangeHistoryRepositoryIntegrationTest, 권한/페이징/필드마스킹 회귀)
- [x] D6. 프론트 UI — 타임라인 형식 (책임. designer → frontend-engineer) — PR #122 (상세페이지 통합, 필드/값 라벨 한글 해석, 더보기 페이징+에러 재시도, InvalidDate 방어, refetchOnWindowFocus)
- [x] D7. E2E (책임. qa-engineer) — PR #122 (issue-changelog.spec.ts 6 시나리오: 타임라인 렌더·페이징·권한·빈상태)

### §5.2 템플릿 (FR-TM, 2개)

#### §5.2.1 FR-TM-01 — 프로젝트+타입별 본문 템플릿

**우선순위**. 필수 | **선행**. §2.1.1, §2.1.4 | **Plan slug**. `issue/templates`

- [x] D1. 도메인 — IssueTemplate (책임. backend-engineer) — PR #125
- [x] D2. 명세 — 적용방식 옵션 C(프론트 프리필+서버 안전망), (project,type)당 1개 (책임. backend-engineer) — PR #125 (ADR `2026-06-12-issue-template-model-and-application`)
- [x] D3. 데이터 모델 — `issue_templates(project_id, issue_type_id, content)` (프로젝트+타입당 1개, 활성 UNIQUE) (책임. db-engineer) — PR #125
- [x] D4. 백엔드 — CRUD API + 이슈 생성 시 적용(서버 안전망) (책임. backend-engineer) — PR #125 (cross-BC: identity-access MANAGE_TEMPLATES resolver/시드)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #125
- [x] D6. 프론트 UI — 템플릿 관리 페이지 + 생성 시 자동 적용 (책임. frontend-engineer) — PR #127 (**deviation**: Maxi 확정 옵션 B = 관리 페이지 중심. 템플릿 관리 CRUD(`/projects/{key}/settings/issue-templates`, 커스텀필드 동형) + `MANAGE_TEMPLATES` 권한 게이팅/노출. **생성폼 프리필 UI는 분리** — "생성 시 자동 적용"은 머지된 서버 안전망(D4 #125)이 description blank 시 주입으로 담당. 설정 nav는 기존 7종과 동형 직접 URL 진입)
- [x] D7. E2E (책임. qa-engineer) — PR #127 (issue-templates.spec.ts 7 시나리오: 생성·중복409·수정·삭제·권한게이팅)

#### §5.2.2 FR-TM-02 — 템플릿 변수 (작성자/일자/프로젝트)

**우선순위**. 높음 | **선행**. §5.2.1 | **Plan slug**. `issue/template-vars`

- [x] D1. 도메인 — TemplateVariable enum(closed set 3종) + TemplateVariableSubstitutor (책임. backend-engineer) — PR #132 (ADR `2026-06-13-issue-template-variable-substitution`)
- [x] D2. 명세 — 변수 종류({{author}}/{{date}}/{{project}} 영문) + 치환 시점(createIssue 서버 안전망에서만, 사용자 입력 비치환) (책임. backend-engineer) — PR #132
- [x] D3. 데이터 모델 — (활용. issue_templates.content 그대로, 신규 스키마 0) (책임. db-engineer) — PR #132
- [x] D4. 백엔드 — 변수 치환 엔진(단일 패스 정규식, fail-safe 리터럴 유지) + resolveDescription 배선 (책임. backend-engineer) — PR #132 (author=reporter display_name(UserLookupPort 재사용)·date=clock yyyy-MM-dd·project=projectKey)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #132 (Substitutor 10 + 안전망 통합 4 + 기존 회귀 5 보존)
- [x] D6. 프론트 UI — 변수 자동완성 (책임. frontend-engineer) — PR #133 (옵션 A. 본문 편집기 `IssueTemplateFormDialog` create/edit 두 모드에 `[+작성자][+일자][+프로젝트]` 삽입 버튼 + "사용 가능 변수" 도움말. `TemplateContentField` 공유 컴포넌트(삽입버튼+도움말+caret 삽입). 토큰 상수는 백엔드 `TemplateVariable` enum 수동 미러+드리프트 가드 테스트. RHF setValue 단일출처. 리뷰 hot-fix F2(실브라우저 selectionStart=0 prepend→`document.activeElement` 분기+버튼 mousedown preventDefault로 caret 보존, 미포커스 끝 append)·F1(rAF detached 가드)·F3(`{...registration}` prop 보존)·F4(IME 조합 중 끝 append))
- [x] D7. E2E (책임. qa-engineer) — PR #133 (issue-template-variables.spec.ts 6 시나리오: E1 작성자/일자/프로젝트 커서삽입·E2 create→store→refetch→edit 라운드트립(토큰 전송 입증)·E3 도움말·E4 미포커스 끝 append(F2 회귀). 기존 issue-templates.spec.ts 7 회귀 동반 통과)

### §5.3 링크 (FR-LK, 2개)

#### §5.3.1 FR-LK-01 — 링크 (blocks/relates/duplicates/clones/parent-child)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/links`

- [x] D1. 도메인 — LinkType (4종 blocks/relates/duplicates/clones) (책임. backend-engineer) — PR #135
- [x] D2. 명세 — 양방향성(역방향 라벨 계산) + cycle 검출 (책임. backend-engineer) — PR #135
- [x] D3. 데이터 모델 — `issue_links(source_id, target_id, link_type)` UUID FK + `issues.parent_id` (책임. db-engineer, V021) — PR #135
- [x] D4. 백엔드 — `POST/GET/DELETE /api/v1/issues/{key}/links` + `PATCH /api/v1/issues/{key}/parent` (책임. backend-engineer) — PR #135
- [x] D5. 백엔드 테스트 — cycle 케이스(blocks 전이·부모 조상) (책임. backend-engineer) — PR #135
- [x] D6. 프론트 UI — 링크 추가/제거 패널 + parent-child set/clear (책임. frontend-engineer) — PR #136
- [x] D7. E2E (책임. qa-engineer) — PR #136

> **deviation (PR #135, ADR `2026-06-13-issue-link-vs-parent-child-separation`)**. 링크와 parent-child를 **별개 메커니즘**으로 분리(Maxi 확정 B). `issue_links`는 `link_type ∈ {blocks,relates,duplicates,clones}` 4종(SDD §5.7 일치), parent-child는 `issues.parent_id` 구조적 계층(SDD §5.8). 따라서 D4가 `PATCH /parent` 엔드포인트를 추가(원 표기는 `/links`만). SDD §5.7 `source_id/target_id`는 BIGINT 표기였으나 실제 `issues.id`가 UUID라 **UUID FK**로 구현. parent-child는 구조 불변식(단일 부모·acyclic·self 금지)만 강제, hierarchy_level 위계는 후속(FR-IS-02 parent_id 강제 이연). 이력·알림·권한 게이팅은 후속 PR (프론트 D6/D7은 PR #136 완료 — 대상 이슈 키 직접 입력 + IssueResponse.parent 단건 노출).

#### §5.3.2 FR-LK-02 — 링크 그래프 시각화

**우선순위**. 중간 | **선행**. §5.3.1 | **Plan slug**. `issue/links-graph`

- [x] D1. 도메인 (책임. backend-engineer) — PR #138 (그래프 노드/엣지 읽기 모델, 신규 엔티티 0)
- [x] D2. 명세 — 노드/엣지 표현 (책임. backend-engineer) — PR #138 (깊이 제한 BFS, edge.type 대문자 link 4종+PARENT)
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer) — PR #138 (신규 스키마 없음, issue_links+parent_id 읽기 쿼리만)
- [x] D4. 백엔드 — `GET /api/v1/issues/{key}/graph` (책임. backend-engineer) — PR #138 (?depth=1..3 기본 2, 노드 상한 100+truncated)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #138 (repo 통합/service 단위/controller HTTP 통합, 400 INVALID_DEPTH 스코프 검증)
- [x] D6. 프론트 UI — mermaid flowchart 시각화 (책임. frontend-engineer) — PR #142 (ADR mermaid 채택, lazy 펼침+depth 1~3+노드 클릭 내비게이션+a11y, 새 의존성 0)
- [x] D7. E2E (책임. qa-engineer) — PR #142 (펼치기/depth 전환/빈/truncated/노드 클릭·키보드 이동 6 시나리오, mermaid flowchart 실렌더 결함 3건 적발·hot-fix)

## §6 이슈 이동 (FR-MV, 2개)

### §6.1.1 FR-MV-01 — 프로젝트 간 이슈 이동

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/move`

> **PR #153 (2026-06-16)** — **단건 이동** 완료: Jira식 매핑 마법사(`POST /move/preview`+`POST /move` 2단계), cross-BC SPI `WorkflowStateCatalog`(대상 워크플로우 상태목록), 옛 키 308 redirect(체인 순회), id 보존+종속데이터(컴포넌트/버전/커스텀필드) 서버측 매핑검증, resolution clear(비DONE). 자식(서브태스크) 있으면 422 거부. ADR `2026-06-16-issue-move-semantics`.
>
> **PR #154 (2026-06-16)** — **서브태스크 동반 이동(노드별 매핑)** 완료: 부모+직접 자식(1레벨) 동반 이동, 노드별 매핑(컴포넌트/버전/커스텀필드/상태, 각 노드 `issueTypeKey`로 상태 조회), 자식 `parent_id` 보존(루트만 detach), 노드별 OCC, (루트+자식) id 오름차순 비관락 + 락-후 재조회로 TOCTOU 시간창 제거. EC16(`SUBTASK_HAS_OWN_SUBTASKS` 다단계 거부)·EC15 재정의(`INCOMPLETE_SUBTASK_MAPPING`). 자식 없으면 단건 회귀 보존. ADR §후속 결정. 백엔드(D1·D2·D4·D5) 완료.
>
> **PR #155 (2026-06-16)** — **프론트 이동 마법사 UI + E2E** 완료 → **FR-MV-01 전체 종료**. radix-ui Dialog 마법사(대상 키 입력→preview 노드별 매핑→실행), 단건+서브태스크 노드별 매핑(상태 select·컴포넌트/버전 매핑/제거·필수필드), Zod 백엔드 1:1 미러, 옛 키 308 redirect → 새 키 SPA navigate(replace). **view layer 보강(BC 예외)**: `WorkflowStateView.isDone`(targetStateIsDone 출처) + `MovePreview/SubtaskPreviewNode.version`(OCC 출처). E2E 단건+서브태스크 동반(옛 키 redirect는 MSW opaque 308 한계로 단위+백엔드통합 대체).

- [x] D1. 도메인 — IssueMoveOperation (책임. backend-engineer) — PR #153 (단건 검증) + #154 (서브태스크 동반 노드별 검증)
- [x] D2. 명세 — 새 이슈 키 생성 + 옛 키 redirect (DATA.md §이슈키 영속성) (책임. backend-engineer + Maxi) — PR #153 (단건 spec/ADR) + #154 (동반 노드별 spec/ADR §후속결정)
- [x] D3. 데이터 모델 — `issue_key_redirects(old_key, new_key, moved_at)` (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/issues/{key}/move` 트랜잭션 (책임. backend-engineer) — PR #153 (단건 preview/move) + #154 (서브태스크 동반 노드별 이동)
- [x] D5. 백엔드 테스트 — 옛 키로 조회 시 308 redirect (책임. backend-engineer) — PR #153 (308 체인+EC 전수 통합테스트) + #154 (동반 ST1~7 + HTTP 슬라이스 CI)
- [x] D6. 프론트 UI — 이동 다이얼로그 (책임. designer → frontend-engineer) — PR #155 (radix-ui 마법사, 노드별 매핑)
- [x] D7. E2E — 이동 + **옛 키 redirect E2E** (FR-IS-01 D7에서 이관, 2026-05-30) (책임. qa-engineer) — PR #155 (단건+서브태스크 E2E, 옛키 redirect는 단위+백엔드통합 대체)

### §6.1.2 FR-MV-02 — 이동 시 히스토리 보존 + 링크 유지

**우선순위**. 필수 | **선행**. §6.1.1, §5.1.1, §5.3.1 | **Plan slug**. `issue/move-preserve`

> **PR #157 (2026-06-17)** — **전체 종료**. FR-MV-01의 id 보존 in-place UPDATE 위에서 (1) 보존 invariant 명시 검증 + (2) 이동 이벤트 이력 기록. 보존은 구조적으로 이미 성립(id 불변 → issue_links/issue_watchers/issue_attachments/issue_change_group 자동 보존)이라 **신규 DB 컬럼 0**. 이동 기록은 `IssueChangeDetector.SCALAR_FIELD_EXTRACTORS`에 `key` 1줄 추가로, 이미 흐르는 `historyRecorder.record(before, after)`가 이동(key 변경)을 `issue_change_item`에 남기게 함(순수 이동도 no-op 회피). 프론트는 changelog i18n 라벨 "프로젝트 이동" 1줄. **vacuous 차단**: 보존 테스트 populated 선단언 + 실 recorder 통합테스트(`IssueMoveHistoryIntegrationTest`)가 mock 없이 issue_change_item 영속 단언(코드리뷰 mutation test로 입증). E2E 옛 키 308 redirect는 MSW opaque 한계로 단위+백엔드통합 대체. **issue_change_group.issue_key는 이동 그룹에 한해 새 키 박제**(from=옛키·to=새키로 이동 추론, 기존 이력 그룹은 옛 키 유지).

- [x] D1. 도메인 (책임. backend-engineer) — PR #157
- [x] D2. 명세 — 히스토리/링크/Watcher/첨부 보존 (책임. backend-engineer) — PR #157 (워처/링크 권한 무관 전부 보존, 권한 필터링은 알림 발송 시점 책임)
- [x] D3. 데이터 모델 — (활용. id 보존 + key만 변경) (책임. db-engineer)
- [x] D4. 백엔드 — 이동 시 모든 FK 보존 검증 (책임. backend-engineer) — PR #157 (detector key 추적으로 이동 이력 기록, 신규 컬럼 0)
- [x] D5. 백엔드 테스트 — 이동 전후 invariant 비교 (책임. backend-engineer) — PR #157 (INV1/INV2 행 보존 populated 선단언 + 실 recorder 통합 T3-1~3)
- [x] D6. 프론트 UI — 이동 후 페이지 자동 갱신 (책임. frontend-engineer) — PR #157 (FR-MV-01 새 키 navigate 기존 + changelog "프로젝트 이동" 라벨)
- [x] D7. E2E (책임. qa-engineer) — PR #157 (이동 마법사 흐름 + changelog 합성 렌더 단언, 옛 키 redirect는 단위+백엔드통합 대체)

## §7 프로젝트 관리 (FR-PJ, 4개)

### §7.1 FR-PJ-01 — 프로젝트 생성

**우선순위**. 필수 | **선행**. identity-access §4.10 FR-PM-10(`CREATE_PROJECT` 전역 권한) | **Plan slug**. `issue/project-create`

프로젝트 생성 — 키 검증(형식·유일성) + 생성자 자동 `PROJECT_ADMIN` 멤버십 부여. `CREATE_PROJECT` 전역 권한(FR-PM-10, `global_permission_grants` 또는 SYSTEM_ADMIN) 보유자만 호출 가능. `POST /api/v1/projects`.

- [x] D1. 도메인 (책임. backend-engineer) — PR #282
- [x] D2. 명세 (책임. backend-engineer + Maxi) — PR #282
- [x] D3. 데이터 모델 — (활용. `projects`, `project_memberships` 기존 테이블) (책임. db-engineer) — PR #282
- [x] D4. 백엔드 — `POST /api/v1/projects` (책임. backend-engineer) — PR #282
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #282
- [x] D6. 프론트 UI — 프로젝트 생성 폼 (책임. designer → frontend-engineer) — PR #300
- [x] D7. E2E (책임. qa-engineer) — PR #300

### §7.2 FR-PJ-02 — 프로젝트 목록/조회

**우선순위**. 필수 | **선행**. §7.1 | **Plan slug**. `issue/project-list`

프로젝트 목록/조회 — 멤버십 기반 권한 필터링, 아카이브 프로젝트 기본 제외. `GET /api/v1/projects`(목록), `GET /api/v1/projects/{projectIdOrKey}`(단건, BROWSE).

- [x] D1. 도메인 (책임. backend-engineer) — PR #283
- [x] D2. 명세 (책임. backend-engineer + Maxi) — PR #283
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer) — PR #283
- [x] D4. 백엔드 — `GET /api/v1/projects` + `GET /api/v1/projects/{projectIdOrKey}` (책임. backend-engineer) — PR #283
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #283
- [x] D6. 프론트 UI — 프로젝트 목록/상세 화면 (책임. designer → frontend-engineer) — PR #300
- [x] D7. E2E (책임. qa-engineer) — PR #300

### §7.3 FR-PJ-03 — 프로젝트 설정 변경 (`name`)

**우선순위**. 필수 | **선행**. §7.1 | **Plan slug**. `issue/project-settings`

프로젝트 설정 변경 — `name`만. `lead_user_id`는 기존 `PATCH /api/v1/projects/{projectIdOrKey}/lead`(FR-CM-04)가, `require_2fa`는 기존 별도 엔드포인트가 담당(D13, 중복 통로 방지 — spec §4.2 확정). `PATCH /api/v1/projects/{projectIdOrKey}`(PROJECT_ADMIN).

- [x] D1. 도메인 (책임. backend-engineer) — PR #283
- [x] D2. 명세 (책임. backend-engineer + Maxi) — PR #283
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer) — PR #283
- [x] D4. 백엔드 — `PATCH /api/v1/projects/{projectIdOrKey}` (책임. backend-engineer) — PR #283
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #283
- [x] D6. 프론트 UI — 프로젝트 설정 폼 (책임. designer → frontend-engineer) — PR #300
- [x] D7. E2E (책임. qa-engineer) — PR #300

### §7.4 FR-PJ-04 — 프로젝트 아카이브/해제

**우선순위**. 필수 | **선행**. §7.1 | **Plan slug**. `issue/project-archive`

프로젝트 아카이브/해제 — 아카이브된 프로젝트는 읽기 전용 잠금(쓰기 경로 차단). `POST /api/v1/projects/{projectIdOrKey}/archive`, `POST /api/v1/projects/{projectIdOrKey}/unarchive`(PROJECT_ADMIN).

- [x] D1. 도메인 (책임. backend-engineer) — PR #285
- [x] D2. 명세 (책임. backend-engineer + Maxi) — PR #285
- [x] D3. 데이터 모델 — `projects.archived_at`(신규 컬럼) (책임. db-engineer) — PR #285
- [x] D4. 백엔드 — `POST .../archive` + `POST .../unarchive` + 쓰기 경로 잠금 (책임. backend-engineer) — PR #285
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #285
- [x] D6. 프론트 UI — 아카이브 토글 (책임. designer → frontend-engineer) — PR #300
- [x] D7. E2E (책임. qa-engineer) — PR #300

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

- [ ] §2~§7 (37 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] DATA.md §이슈키 영속성 자가 점검
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "issue-tracking BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "issue-tracking BC 완료"
