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
