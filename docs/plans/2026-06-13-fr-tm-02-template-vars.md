# FR-TM-02 — 템플릿 변수 (작성자/일자/프로젝트)

> slug: fr-tm-02-template-vars
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-13

## Brief

FR-TM-02 템플릿 변수 (작성자/일자/프로젝트) — 이슈 템플릿 본문 내 변수 치환.
선행 FR-TM-01(이슈 본문 템플릿, PR #125/#127 완료) 위에 얹는 확장.
이슈 템플릿 본문에 {{작성자}}, {{일자}}, {{프로젝트}} 같은 변수를 두면 이슈 생성 시 실제 값으로 치환.

Plan slug(product): issue/template-vars
classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- BC: issue-tracking
- 영향 엔티티: IssueTemplate(기존, **변경 없음** — content는 리터럴+변수 텍스트를 그대로 저장), Issue(생성 시 description 주입 경로)
- 신규 도메인 개념:
  - `TemplateVariable` — 치환 가능 변수 종류 enum (closed set 3종)
  - `TemplateVariableSubstitutor` — 템플릿 content + 변수 바인딩 → 치환 텍스트 (순수 함수/object, MentionParser 동형)
- 새 용어(glossary 갱신 대기, Maxi 승인 후): "템플릿 변수(Template Variable)" — 템플릿 본문에 `{{author}}` 형태로 넣어 이슈 생성 시 실제 값으로 치환되는 자리표시자. "치환(substitution)".
- 변수 모델 (Maxi 확정 2026-06-13):
  - 구문 `{{name}}` 이중 중괄호 + **영문 토큰**
  - closed set 3종: `{{author}}`=reporter display_name(UserLookupPort), `{{date}}`=생성일자 `yyyy-MM-dd`(clock), `{{project}}`=**프로젝트 키**(request.projectKey)
  - 치환 시점 = **createIssue 서버 안전망(resolveDescription)에서만**. request.description non-blank이면 치환 안 함. resolve 엔드포인트 치환은 범위 외(프리필 소비자 부재)
  - 미정의 토큰·display_name 미조회 시 **리터럴 유지**(fail-safe, 이슈 생성 절대 차단 안 함)
- 데이터 모델: **신규 스키마 없음** (issue_templates.content 활용, D3 = 활용)
- cross-BC: `UserLookupPort.findDisplayNamesByIds`(기존, FR-MN-01/HS-01 도입) 재사용. 신규 포트 0
- 기존 결정 충돌: 없음. FR-TM-01 ADR이 "FR-TM-02(변수 치환)는 후속"으로 예고 → 정합
- 관련 ADR: [docs/adr/2026-06-13-issue-template-variable-substitution.md](../adr/2026-06-13-issue-template-variable-substitution.md) (생성됨), 선행 [docs/adr/2026-06-12-issue-template-model-and-application.md](../adr/2026-06-12-issue-template-model-and-application.md)

## 스펙

전체 스펙. [docs/specs/2026-06-13-fr-tm-02-template-vars.md](../specs/2026-06-13-fr-tm-02-template-vars.md)

핵심 시나리오 3줄 요약.
- 템플릿 content의 `{{author}}`/`{{date}}`/`{{project}}`를 이슈 생성 시 reporter display_name·생성일(yyyy-MM-dd)·projectKey로 치환
- 치환은 createIssue 서버 안전망(템플릿 주입 분기)에서만 — 사용자가 description 직접 입력하면 치환 안 함
- 미정의 토큰·display_name 미조회는 리터럴 유지(fail-safe), 단일 패스(삽입값 재치환 안 함), 신규 스키마·엔드포인트·생성자 변경 0

office-hours 스킵(정의된 FR, 메모리 bts-spec-office-hours-mismatch). 기술 스펙 직접 작성.

## Brainstorming Check

✅ 통과 (1회). Maxi 결정 필요 gap 없음. 삽입값 재치환 방지(단일 패스)·`{{author}}` 조건부 조회·생성자 무변경(회귀 안전) 반영.

## Plan

> 범위: 백엔드 D1~D5. 신규 스키마·엔드포인트·생성자 변경 0. cross-BC 기존 UserLookupPort만.

### Task 1. TemplateVariable enum + TemplateVariableSubstitutor 순수 치환기

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/domain/TemplateVariable.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/domain/TemplateVariableSubstitutor.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/template/domain/TemplateVariableSubstitutorTest.kt`]
- depends-on: []

**RED**.
- 파일: `.../template/domain/TemplateVariableSubstitutorTest.kt`
- 테스트(kotest DescribeSpec 또는 JUnit, 기존 컨벤션 따름):
  - `{{author}} 단독` → 바인딩 값으로 치환
  - `{{author}}/{{date}}/{{project}} 3종 동시` → 각각 치환
  - `같은 토큰 다중 등장` → 전부 치환
  - `미정의 토큰 {{foo}}` → 리터럴 유지
  - `바인딩에 없는 변수(예: author 미바인딩)` → 해당 토큰 리터럴 유지(fail-safe)
  - `단일 패스` — author 바인딩 값이 "{{date}}" 문자열이어도 date로 재치환 안 됨
  - `토큰 없는 content` → 원문 그대로(no-op)
  - `{{ author }}`(내부 공백)·`{{Author}}`(대문자) → 미치환(리터럴)
- 실패 메시지(예상): `TemplateVariable`/`TemplateVariableSubstitutor` 클래스 없음

**GREEN**.
- `TemplateVariable.kt` — enum 3종 `AUTHOR("author")`, `DATE("date")`, `PROJECT("project")`. 각 `val token: String get() = "{{$key}}"` 노출(앱 계층 contains 검사용).
- `TemplateVariableSubstitutor.kt` — `object`(MentionParser 동형). `substitute(content: String, bindings: Map<TemplateVariable, String>): String`.
  - 정규식 `\{\{(author|date|project)\}\}` 한 번의 `replace { matchResult -> ... }` 단일 패스.
  - 매치된 변수명이 enum으로 인식되고 bindings에 있으면 그 값으로, 없으면 매치 원문(리터럴) 반환.
  - replacement은 삽입 값을 재스캔하지 않음(Regex.replace 특성 = 단일 패스).

**REFACTOR**.
- 정규식을 `private val` 상수로 추출 + KDoc(L1 한글 헤더 주석, fail-safe 정책 명시). detekt MaxLineLength/주석 규칙 준수.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*TemplateVariableSubstitutorTest"`

### Task 2. createIssue 서버 안전망에 변수 치환 배선

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTemplateVariableTest.kt`]
- depends-on: [1]

**RED**.
- 파일: `.../application/IssueApplicationServiceTemplateVariableTest.kt` (신규 — 기존 `IssueApplicationServiceTemplateApplyTest`는 회귀 보존용으로 손대지 않음)
- 테스트(기존 TemplateApplyTest 구조·stub 재사용, 고정 clock):
  - 시나리오1: 템플릿 `"보고자: {{author}}\n생성일: {{date}}\n프로젝트: {{project}}"` + description null + display_name "김앨리스" → 치환된 description
  - 시나리오3: `"{{author}} {{foo}}"` → `"김앨리스 {{foo}}"`(미정의 리터럴)
  - 시나리오4: display_name 빈 맵 → `{{author}}` 리터럴 유지 + 이슈 생성 성공
  - `{{author}} 미포함` 템플릿 → `userLookupPort.findDisplayNamesByIds` 미호출(verify exactly 0)
- 실패 메시지(예상): description에 `{{author}}` 등 토큰 잔존(치환 미배선)

**GREEN**.
- `resolveDescription` 시그니처에 `reporterId: ActorId`, `projectKey: String` 추가(private 메서드 — 공개 API 무변경). 호출부(createIssue) `request.reporterId`, `request.projectKey` 전달.
- 템플릿 분기에서만 치환:
  ```
  val template = issueTemplateRepository?.findActiveContentByProjectAndType(...) ?: return null
  return substituteTemplateVariables(template, reporterId, projectKey)
  ```
  사용자 입력(non-blank) 분기는 치환하지 않음(시나리오2 보존).
- `substituteTemplateVariables` private helper — bindings 수집:
  - `DATE` → `LocalDate.now(clock)` `yyyy-MM-dd`(DateTimeFormatter.ISO_LOCAL_DATE)
  - `PROJECT` → projectKey
  - `AUTHOR` → content가 `TemplateVariable.AUTHOR.token` 포함할 때만 `userLookupPort.findDisplayNamesByIds(setOf(reporterId.value))[reporterId.value]`. 결과 없으면 bindings에 미포함(→ 리터럴 유지).
  - `TemplateVariableSubstitutor.substitute(template, bindings)` 호출.

**REFACTOR**.
- helper KDoc + 상수(포맷터) 추출. ktlint/detekt 그린. 기존 `IssueApplicationServiceTemplateApplyTest` 5케이스 회귀 확인.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*IssueApplicationServiceTemplateVariableTest" --tests "*IssueApplicationServiceTemplateApplyTest"`

## Plan 메타

- task 수: 2
- 예상 시간: task × 3분 = 약 6분(직렬 기준). depends-on [1]이라 단일 직렬 체인(병렬 wave 1개 = Task1 → Task2)
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 병렬 dispatch: Task2가 Task1의 substitutor API 의존 → 직렬. 파일 겹침 없음
- 추가 검증: ktlint, detekt(issue-tracking 모듈), 기존 TemplateApplyTest 회귀
- D3(데이터 모델)·신규 마이그레이션 없음 / D6·D7(프론트·E2E) 후속 PR

## 리뷰 결과

### eng 집중 독립 리뷰 (2026-06-13) — backend (autoplan 대신, 메모리 bts-review-plan-autoplan-overkill)

- ✅ **호출자 안전성 확인(코드 실측)**: `resolveDescription`은 private 메서드, 호출자는 createIssue(L208) **단 하나**. reporterId/projectKey 파라미터 추가가 다른 경로(cloneIssue 등) 미영향. 공개 API 무변경.
- ✅ **회귀 안전**: 생성자 무변경(clock·userLookupPort 기주입). 기존 `IssueApplicationServiceTemplateApplyTest` 5케이스는 content에 변수 토큰 없어(case a "## 재현 방법…") 치환 no-op → 그대로 통과. 신규 테스트는 별도 파일이라 파일 겹침 0.
- ✅ **단일 패스 정합**: Kotlin `Regex.replace(input){…}`는 replacement 출력을 재스캔하지 않음 → 삽입값(author="{{date}}")이 재치환 안 됨. 시나리오6이 이를 실제로 검증하므로 회귀 가드 성립.
- ✅ **절대 규칙**: 시각 의존 `LocalDate.now(clock)` 주입 clock 사용. 치환은 순수 함수(포트 0). BC 격리 — 신규 cross-BC 포트/gradle 의존 0.
- ⚠️ 주의(BLOCKER 아님):
  1. `TemplateVariableSubstitutorTest`/신규 통합 테스트는 모듈 기존 컨벤션(kotest DescribeSpec) 따를 것.
  2. enum `token` expr body가 detekt 라인길이와 충돌하면 블록 body로(메모리 ktlint-detekt-linelength-and-baseline-traps).
  3. fail-safe 핵심: display_name 미조회 시 bindings에서 누락 → `{{author}}` 리터럴 유지. 빈 문자열 치환 금지(시나리오4가 강제).
- **BLOCKER: 없음**
