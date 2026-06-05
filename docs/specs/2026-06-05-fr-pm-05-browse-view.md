# FR-PM-05 — 이슈 접근 권한 (Browse, View) — 스펙

> slug: fr-pm-05-browse-view
> 작성: 2026-06-05
> BC: identity-access (소유) + issue-tracking (서비스 계층 스코프) + apps/web (404 UI)
> 관련 ADR: [2026-06-05-issue-browse-view-permission](../decisions/2026-06-05-issue-browse-view-permission.md)
> 관련 SDD: [12. 권한 모델](../sdd/12-permissions.md) §12.3 이슈 접근
> 선행: FR-PM-02 [issue-permission-scheme-model](../decisions/2026-06-02-issue-permission-scheme-model.md)

## 배경 — 현재 상태 (ground-truth)

- `IssuePermission` enum 6종(VIEW/CREATE/UPDATE/TRANSITION/SOFT_DELETE/HARD_DELETE). **단일 VIEW**가 목록·단건 모두 표현(shared-kernel).
- prod `IdentityAccessIssuePermissionResolver`: VIEW를 `toCodeOrNull() = null`로 매핑 → "프로젝트 멤버면 무조건 통과"(임시 정책). KDoc이 "FR-PM-05에서 매트릭스 이관" 예약.
- `IssueApplicationService.listIssues` → `assertPermission(actor, VIEW, IssueScope.Project)`. `findByKey` → `assertPermission(actor, VIEW, IssueScope.Issue)` **후** 조회. 미인가 시 `IssueAccessDeniedException`(403) → 그 다음 `IssueNotFoundException`(404). 존재 probe 노출.
- 기본 권한 스킴 매트릭스 8행(PROJECT_ADMIN 6 + MEMBER 2). 최신 identity-access 마이그레이션 = V013.
- issue-tracking 컨트롤러 전체가 `SYSTEM_ACTOR_UUID` 하드코딩(실 인증 주체 미결선, BC 전체 공통 부채). **본 FR 범위 밖**(후속).

## 사용자 시나리오 (Given-When-Then)

### S1. 멤버가 자기 프로젝트 이슈 목록을 본다
- Given: alice가 ATLAS 프로젝트의 MEMBER이고 기본 스킴에 BROWSE_PROJECT 보유.
- When: `GET /api/v1/issues?projectKey=ATLAS` 호출.
- Then: 200 + ATLAS 이슈 페이지 반환.

### S2. 비멤버가 남의 프로젝트 이슈 목록을 본다
- Given: bob이 ATLAS의 멤버가 아니다.
- When: `GET /api/v1/issues?projectKey=ATLAS`.
- Then: 403 (BROWSE_PROJECT 미인가 — 프로젝트 단위 거부).

### S3. 멤버가 자기 프로젝트의 이슈 단건을 본다
- Given: alice가 ATLAS MEMBER, VIEW_ISSUE 보유.
- When: `GET /api/v1/issues/ATLAS-1`.
- Then: 200 + 이슈 상세.

### S4. 비멤버가 남의 이슈 단건을 본다 (존재 숨김)
- Given: bob이 ATLAS 비멤버. ATLAS-1은 실재.
- When: `GET /api/v1/issues/ATLAS-1`.
- Then: **404** (미존재와 동일 응답 — 존재 probe 차단). 403 아님.

### S5. 존재하지 않는 이슈 단건
- Given: ATLAS-9999 미존재.
- When: `GET /api/v1/issues/ATLAS-9999` (인가된 alice).
- Then: 404 (S4와 동일 응답 → 인가자/비인가자가 구분 불가).

### S6. 프론트 — 권한 없는 이슈 상세 진입
- Given: bob이 URL로 `/issues/ATLAS-1` 직접 진입(비멤버).
- When: 상세 페이지가 `GET /api/v1/issues/ATLAS-1` → 404.
- Then: "이슈를 찾을 수 없습니다" not-found 화면(에러 토스트 아님). 미존재 이슈와 동일 UX.

## 기능 요구사항 (FR)

- **FR1.** `IssuePermission`에 `BROWSE` 추가. 목록 조회는 BROWSE, 단건 조회는 VIEW로 검증.
- **FR2.** prod resolver `toCodeOrNull()`: `BROWSE → BROWSE_PROJECT`, `VIEW → VIEW_ISSUE` 매핑. 두 권한 모두 `role_permissions` 매트릭스로 판정(멤버 통과 임시 정책 종료).
- **FR3.** `listIssues`: `assertPermission(actor, BROWSE, IssueScope.Project(projectKey))`. 미인가 → `IssueAccessDeniedException`(403).
- **FR4.** **단건 VIEW_ISSUE 미인가 → 404 일관 정책.** 단일 이슈를 VIEW로 게이트하는 모든 읽기 경로가 미인가 시 `IssueNotFoundException`(404)을 던진다(존재 숨김). `IssueAccessDeniedException`(403) 던지지 않음. 적용 대상: `findByKey`(GET /issues/{key}), `availableTransitions`(GET /issues/{key}/transitions), `cloneIssue` 소스 검증(line 198), getPdf(findByKey 경유). 전용 가드 헬퍼(`assertViewIssueOrNotFound`류)로 통일 — 일부만 403이면 엔드포인트 간 probe 재개방. 권한 검증은 조회보다 먼저(미존재와 동일 코드 경로 수렴).
- **FR5.** V014 마이그레이션: 기본 스킴에 BROWSE_PROJECT + VIEW_ISSUE를 PROJECT_ADMIN·MEMBER 양 역할에 시드(+4행). FR-PM-02 V008 시드 패턴 동형.
- **FR6.** 프론트: 이슈 상세 라우트가 404 응답을 not-found 화면으로 처리(권한 없는 이슈 = 미존재 이슈와 동일 UX).

## 비기능 요구사항 (NFR)

- **NFR1. 검증 = prod 프로파일 Testcontainers 통합테스트.** non-prod AlwaysAllow stub은 전 권한 통과로 마스킹하므로, 거부 경로는 `@ActiveProfiles("prod")` 통합테스트가 ground-truth(메모리 `issue-scope-global-prod-hard-deny`, `best-effort-loop-permission-exception-nonprod-mask`).
- **NFR2. 정보 누출 0.** 404 응답 body는 미존재 이슈와 동일(이슈 메타/권한 사유 미포함). 메모리 `fr-pm-04-guard-exception-message-http-leak` 동형.
- **NFR3. 마이그레이션 카운트 정합.** `PermissionSchemaMigrationTest` 8→12 갱신 + 신규 코드 시드 검증 테스트 추가. 메모리 `fr-pm-permission-seed-migration-test-coupling`.

## API 인터페이스 (REST) — 변경 없음, 동작만 변경

| 엔드포인트 | 변경 |
|---|---|
| `GET /api/v1/issues?projectKey=` | BROWSE_PROJECT 매트릭스 판정. 미인가 403. |
| `GET /api/v1/issues/{key}` | VIEW_ISSUE 매트릭스 판정. 미인가 **404**(기존 403→404). |

요청/응답 DTO 변경 없음.

## 데이터 모델 변경

- **V014__browse_view_issue_permissions.sql** — 기본 스킴(`is_default = TRUE`)의 PROJECT_ADMIN·MEMBER에 `BROWSE_PROJECT`, `VIEW_ISSUE` 시드. V008/V013 INSERT 패턴 복제(scheme_id 서브쿼리 + ON CONFLICT DO NOTHING 멱등).
- jOOQ 코드 생성 영향: `role_permissions` 컬럼 불변(행만 추가) → init_codegen 미러 **불요**(메모리 `jooq-init-codegen-mirror`는 컬럼 추가 시만 해당).

## 엣지 케이스

- **EC1. 미인증 요청(401).** SecurityFilterChain이 인증 단계에서 처리(`AuthenticationException → 401`, `IssueExceptionHandler` 기존). 단 컨트롤러 actor 하드코딩이 남아있어 per-user 401/404의 HTTP 종단 결선은 actor-wiring 후속에 완성. 본 FR의 종단 검증은 서비스 계층(명시 actor) + resolver 통합.
- **EC2. findByKey 권한검증 순서.** assertPermission이 조회보다 먼저(현 구조 유지) → 미존재·미인가가 동일 404로 수렴. 인가자만 미존재 시에도 404(S5) → 비인가자와 구분 불가.
- **EC3. 단건 VIEW 읽기 경로 전수.** 현재 VIEW+IssueScope.Issue 검증 지점 3곳 — `cloneIssue` 소스(line 198), `findByKey`(line 266), `availableTransitions`(line 383). FR4 404 정책을 3곳 모두 적용(일부 누락 시 probe 재개방). plan에서 가드 헬퍼 도입 + 3곳 치환 task로 명시. (mutation의 UPDATE/SOFT_DELETE/TRANSITION 검증은 403 유지 — 미인가 mutation은 존재 숨김 대상 아님.)
- **EC4. IssueScope.Global 미사용.** 본 FR은 Project/Issue 스코프만. Global 신규 결선 없음(prod 하드거부 함정 회피).
- **EC5. 병렬 FR-CM-03 충돌.** FR-CM-03(draft PR #84)이 `IssueApplicationService.kt`(createIssue 경로)·`IssueController.kt`를 수정 중. 본 FR은 같은 파일의 `listIssues`/`findByKey` 메서드만 수정 → 메서드 분리라 auto-merge 가능성 높으나, 머지 직전 충돌 확인 필수. **머지 순서 — 먼저 머지된 쪽 기준 rebase**(메모리 `parallel-fr-overlapping-frontend-infra-collision`).
- **EC6. V번호 충돌.** identity-access V014가 본 FR 사용분. 다른 병렬 브랜치가 V014 선점 시 머지 직전 `git fetch + ls`로 재확인, 충돌 시 git mv + 문서 동기화(메모리 `migration-vnumber-concurrent-branch-collision`).

## 제약 조건

- 한 PR = 한 BC 원칙의 의식적 예외: identity-access(소유) + issue-tracking 서비스 계층 스코프 변경. 포트(`IssuePermissionResolver`)가 shared-kernel에 있어 경계 위반 아님(FR-PM-02 선례 동형).
- per-issue 보안 수준(비공개 이슈 차등)은 본 FR 범위 밖 → FR-PM-06.
- 컨트롤러 CurrentActor 결선은 본 FR 범위 밖 → issue-tracking BC 전체 actor-wiring 후속.

## 측정 가능한 완료 기준

1. `IssuePermission.BROWSE` 추가, `when` exhaustive 갱신(컴파일 강제).
2. prod resolver가 BROWSE_PROJECT/VIEW_ISSUE를 매트릭스로 판정. "멤버면 통과" 임시 KDoc 제거.
3. V014 시드 후 `PermissionSchemaMigrationTest` 12행 단언 통과 + BROWSE_PROJECT/VIEW_ISSUE 시드 검증 통과.
4. prod 통합테스트: S1(200)/S2(403)/S3(200)/S4(404)/S5(404) + availableTransitions 미인가 404 전부 통과.
5. 프론트: 권한 없는 이슈 상세 진입 시 not-found 화면(E2E S6).
6. 4모듈 test + ktlint(`ktlintMainSourceSetCheck`+`ktlintTestSourceSetCheck`) + detekt 그린, 회귀 0.

## 잔여 위험 (문서화)

- **mutation 미인가 403(존재 노출 잔여).** 비멤버가 미인가 이슈에 PATCH/DELETE/TRANSITION 시 403(FR-PM-02 계약 유지). GET은 404인데 mutation은 403이라 결정적 probe로 존재 추론 가능. 본 FR은 읽기 접근 범위라 mutation 403→404 전환은 제외(FR-PM-02 계약 변경 + FR-CM-03 충돌 확대 회피). per-issue 보안 수준(FR-PM-06)에서 통합 재검토 후보.

## Brainstorming Check

✅ 통과 (자체 적대적 sanity check 1회). 발견·반영:
- **갭1**: 단건 VIEW 읽기 경로가 findByKey 외 availableTransitions·clone 소스에도 존재 → 404 정책 누락 시 엔드포인트 간 probe 재개방 → FR4를 "단건 VIEW 전 경로 일관 404"로 보강(EC3 전수 명시).
- **갭2**: plan D4 "jOOQ Condition 빌더"는 프로젝트 단위 all-or-nothing + 크로스프로젝트 검색 엔드포인트 부재 상황에서 투기적 인프라 → 본 FR은 프로젝트 인가 게이트 수준으로 한정, per-issue 술어는 FR-PM-06/검색(SDD 10) 위임(ADR D4, §2 단순성).
- **갭3**: mutation 403 잔여 probe → 범위 밖 명시(잔여 위험 문서화).
