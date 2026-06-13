# 이슈 템플릿 변수 치환 모델 (FR-TM-02)

> 상태: Accepted
> 날짜: 2026-06-13
> BC: issue-tracking
> 관련 FR: FR-TM-02 (템플릿 변수 — 작성자/일자/프로젝트)
> 관련: [[2026-06-12-issue-template-model-and-application]] (FR-TM-01 — "변수 치환은 후속, 1차는 리터럴 텍스트"로 예고), domain/issue-tracking.md (IssueTemplate / Issue 엔티티), UserLookupPort(작성자 display_name 조회)

## 맥락

FR-TM-01 은 이슈 본문 템플릿(`issue_templates.content`)을 리터럴 마크다운으로 저장하고, 이슈 생성 시
`description` 이 null/blank 이면 서버 안전망(`IssueApplicationService.resolveDescription`)이 템플릿 content
를 주입한다(옵션 C). FR-TM-01 ADR §1 이 "FR-TM-02(변수 치환)는 후속 — 1차는 리터럴 텍스트"로 명시 예고했다.

FR-TM-02 는 템플릿 content 안의 변수 토큰(`{{author}}` 등)을 이슈 생성 시점의 실제 값으로 치환한다.

조사로 드러난 제약.
- 템플릿 content 가 실제 이슈 description 으로 materialize 되는 경로는 현재 **createIssue 서버 안전망 단 하나**다.
  FR-TM-01 D6 는 생성폼 프리필 UI 를 분리(미구현)했고(ADR 옵션 C deviation), `GET /resolve` 엔드포인트는
  존재하나 프론트 생성 폼이 프리필로 소비하지 않는다.
- `IssueApplicationService` 는 이미 `clock`(일자) 과 `UserLookupPort`(작성자 display_name) 를 주입받는다.
  `findDisplayNamesByIds(ids): Map<UUID,String>` 가 FR-MN-01/FR-HS-01 에서 도입되어 재사용 가능하다.
- issue-tracking 은 `projectKey`(요청에 존재) 와 `projectId`(ProjectLookup) 만 알고, 프로젝트 **이름**은 보유하지 않는다.

## 결정

### 1. 변수 구문 — `{{name}}` 이중 중괄호 + 영문 토큰

- 토큰 형식. `{{author}}`, `{{date}}`, `{{project}}` (Handlebars/Mustache 관례, 영문 식별자).
- 한글 토큰(`{{작성자}}`)·단일 중괄호(`${...}`)는 기각. 영문은 파서 정규식 단순, 코드 식별자·다국어 확장 안전.

### 2. 변수 집합 — closed set 3종

| 토큰 | 치환 값 | 출처 |
|---|---|---|
| `{{author}}` | 이슈 reporter 의 display_name | `UserLookupPort.findDisplayNamesByIds(setOf(reporterId))` |
| `{{date}}` | 이슈 생성 일자 `yyyy-MM-dd` (ISO local date, 시스템 기본 영역) | `clock` |
| `{{project}}` | 프로젝트 **키** (예 `BTS`) | `request.projectKey` |

- FR 제목("작성자/일자/프로젝트")대로 정확히 3종. 확장 가능 레지스트리 없음(Simplicity First).
- `{{project}}` 는 **키**로 확정 — 추가 조회 0, 항상 가용, 고유·불변. 프로젝트 이름은 issue-tracking 미보유라 기각.
- `{{author}}` 는 reporter(작성자) 기준. display_name 미조회 시 fail-safe(아래 §4).

### 3. 치환 시점 — createIssue 서버 안전망에서만

- 치환은 `resolveDescription` 안전망이 템플릿 content 를 주입하는 지점에서 1회 수행한다.
- `request.description` 이 non-blank(사용자 직접 입력/프리필 제출)이면 치환하지 않는다 — 사용자 입력은 그대로 존중.
- `GET /resolve` 엔드포인트 치환은 범위 외(현재 프리필 소비자 없음 = speculative). 프리필 UI 도입 시 그 PR 이 담당.

### 4. 미정의 토큰 / fail-safe

- 정의되지 않은 토큰(`{{foo}}`)은 **리터럴 그대로 유지** — 사용자가 의도한 텍스트일 수 있으므로 에러/제거하지 않는다.
- `{{author}}` display_name 미조회(빈 맵) 시 토큰을 빈 문자열로 치환하지 않고 **리터럴 유지**(fail-safe, 미정의 토큰과 동일 정책). 이슈 생성은 절대 막지 않는다.

## 대안 (기각)

- **한글 토큰** — 사내 한국어 사용자에 직관적이나 파서 유니코드 처리·자동완성·문서 비용 증가. 영문이 관례·안전. 기각.
- **확장 가능 변수 레지스트리** — FR 범위(3종)를 넘는 speculative 추상화. 기각.
- **프로젝트 이름 치환** — issue-tracking 미보유 → cross-BC/repo 확장 필요. 키로 충분. 기각.
- **resolve 엔드포인트도 치환** — 현재 프리필 소비자 부재. speculative. 프리필 UI PR 로 위임. 기각.

## 적용 범위 (이번 PR — 백엔드 D1~D5)

순수 치환 함수(TemplateVariableSubstitutor) · 변수 종류 enum · `resolveDescription` 안전망 배선
(작성자명·일자·프로젝트키 바인딩 수집 후 치환) · 백엔드 테스트. 신규 마이그레이션 없음(content 활용).
프론트 변수 자동완성(D6) · E2E(D7)는 후속.
