# FR-TM-02 템플릿 변수 (작성자/일자/프로젝트) — 스펙

> slug: fr-tm-02-template-vars | BC: issue-tracking | 작성: 2026-06-13
> 선행: FR-TM-01(이슈 본문 템플릿, PR #125/#127) | ADR: docs/adr/2026-06-13-issue-template-variable-substitution.md
> 범위: 백엔드 D1~D5. 프론트 변수 자동완성(D6)·E2E(D7)는 후속.

## 배경

FR-TM-01 은 이슈 본문 템플릿을 리터럴 텍스트로 저장하고, 이슈 생성 시 `description` 이 null/blank 이면
서버 안전망(`IssueApplicationService.resolveDescription`)이 템플릿 content 를 주입한다. FR-TM-02 는
템플릿 content 안의 변수 토큰을 이슈 생성 시점의 실제 값으로 치환한다.

## 사용자 시나리오 (Given-When-Then)

1. **변수 치환 happy path**
   - Given (BTS, Bug) 템플릿 content = `"보고자: {{author}}\n생성일: {{date}}\n프로젝트: {{project}}\n\n## 재현 방법"`
   - When 앨리스(display_name "김앨리스")가 BTS 의 Bug 이슈를 `description` 없이 생성
   - Then 생성 이슈 description = `"보고자: 김앨리스\n생성일: 2026-06-13\n프로젝트: BTS\n\n## 재현 방법"`

2. **사용자 입력 우선 (안전망 미발동 = 치환 없음)**
   - Given 위 템플릿 존재
   - When 앨리스가 `description = "직접 작성한 설명 {{author}}"` 로 생성
   - Then description = `"직접 작성한 설명 {{author}}"` 그대로 — 사용자 입력은 치환하지 않음(`{{author}}` 도 리터럴)

3. **미정의 토큰 리터럴 유지**
   - Given 템플릿 content = `"작성자 {{author}}, 미지원 {{foo}}"`
   - When 앨리스가 description 없이 생성
   - Then description = `"작성자 김앨리스, 미지원 {{foo}}"` — `{{foo}}` 는 정의되지 않아 그대로 둔다

4. **display_name 미조회 fail-safe**
   - Given 템플릿 content = `"{{author}}"`, reporter 의 display_name 을 UserLookupPort 가 반환하지 못함(빈 맵)
   - When 생성
   - Then description = `"{{author}}"` 리터럴 유지, **이슈 생성은 성공**(치환 실패가 생성을 막지 않음)

5. **템플릿 없음 (기존 동작 보존)**
   - Given (BTS, Task) 활성 템플릿 없음
   - When description 없이 Task 생성
   - Then description = null (FR-TM-01 동작 그대로)

6. **치환 값이 토큰을 포함해도 재치환 안 함(단일 패스)**
   - Given 템플릿 content = `"{{author}}"`, reporter display_name 이 우연히 `"{{date}}"` 라는 문자열
   - When 생성
   - Then description = `"{{date}}"` (삽입된 값은 다시 스캔하지 않음 — 토큰 주입 방지)

## 기능 요구사항 (FR)

- **FR-TM-02-1**. 변수 토큰 집합은 closed set 3종 — `{{author}}`, `{{date}}`, `{{project}}` (영문 소문자, 이중 중괄호, 대소문자 구분).
- **FR-TM-02-2**. 치환은 `resolveDescription` 안전망이 **템플릿 content 를 주입하는 분기에서만** 수행한다.
- **FR-TM-02-3**. `request.description` 이 non-blank 이면 안전망·치환 모두 미발동 — 사용자 입력 원문 보존.
- **FR-TM-02-4**. 정의되지 않은 토큰(`{{foo}}`) 및 값 미조회(display_name 빈 맵)는 **리터럴 유지**. 치환은 이슈 생성을 절대 차단하지 않는다.
- **FR-TM-02-5**. 변수 값 — `{{author}}` = reporter(`request.reporterId`)의 display_name, `{{date}}` = 생성 일자 `LocalDate.now(clock)` `yyyy-MM-dd`, `{{project}}` = `request.projectKey`.
- **FR-TM-02-6**. 치환은 **단일 패스**(한 번의 정규식 스캔, replacement 함수). 삽입된 값은 재스캔하지 않는다.

## 비기능 요구사항 (NFR)

- **성능**. 치환은 순수 문자열 연산. display_name 조회(`UserLookupPort.findDisplayNamesByIds`)는 **템플릿 content 에 `{{author}}` 가 실제 포함될 때만** 1회 호출(불필요 쿼리 회피). 안전망 자체가 description blank 일 때만 발동.
- **보안**. display_name 은 멘션(FR-MN-01)·이력(FR-HS-01)에서 이미 노출되는 공개 정보. 추가 노출 표면 없음. 템플릿 resolve READ 는 기존대로 미게이트(프로젝트 조회 권한으로 충족).
- **절대 규칙**. 시각 의존은 주입된 `clock` 사용(메모리 authcontroller-revokesession-timebomb). 치환 로직은 순수 함수로 단위 테스트.

## API 인터페이스 (REST)

- **신규 엔드포인트 없음.** `POST /api/v1/issues`(createIssue) 의 내부 description 결정 로직만 확장.
- `GET /api/v1/projects/{key}/issue-templates/resolve` 는 **변경 없음**(치환하지 않은 원문 반환 — 프리필 소비자 부재, 범위 외).

## 데이터 모델 변경

- **없음.** `issue_templates.content` 를 그대로 활용. 마이그레이션 0건.

## 엣지 케이스

- 한 토큰이 본문에 여러 번 등장 → 모두 치환(전역).
- `{{ author }}`(중괄호 내부 공백), `{{Author}}`(대문자) → **미정의 토큰으로 간주, 리터럴 유지**. 정확히 `{{author}}`/`{{date}}`/`{{project}}` 만 치환(자동완성 UI 가 정확 토큰 삽입).
- 템플릿 content 에 변수 토큰이 하나도 없음 → 치환 no-op, content 원문 그대로.
- `{{date}}` 시간대 → `clock` 의 ZoneId 기준 `LocalDate`(시스템 기본). 테스트는 고정 clock(UTC).
- 클론(cloneIssue)은 원본 description 복사로 안전망·치환 경로를 타지 않음(기존 FR-IS-06 동작 보존).

## 제약 조건

- **BC 격리**. issue-tracking 내부만. cross-BC 는 기존 `UserLookupPort` 만(신규 포트 0, 신규 gradle 의존 0).
- 치환은 사용자 입력(`request.description` non-blank)에는 절대 적용하지 않는다 — 안전망 분기 한정.

## 측정 가능한 완료 기준

- **단위(순수 함수)** — `TemplateVariableSubstitutor`: 각 변수 단독 치환 / 3종 동시 / 다중 등장 / 미정의 토큰 잔존 / 빈 바인딩 시 리터럴 / 단일 패스(삽입값 재치환 안 함) / 토큰 없는 content no-op.
- **단위(안전망 배선)** — createIssue: 시나리오 1·3·4 검증(치환된 description), 시나리오 2(non-blank 미치환)·5(템플릿 없음 null) 회귀 보존.
- **회귀** — 기존 `IssueApplicationServiceTemplateApplyTest` 5케이스 그대로 통과. `{{author}}` 미포함 시 UserLookupPort 미호출 검증.
- 백엔드 전체 `./gradlew :backend:modules:issue-tracking:test` + ktlint + detekt 그린.

## Brainstorming Check

✅ 통과 (1회). Maxi 결정 필요 gap 없음. 자체 점검에서 (a) 삽입값 재치환 방지 → 단일 패스(FR-TM-02-6/시나리오6), (b) `{{author}}` 미포함 시 display_name 미조회(NFR 성능), (c) 생성자 무변경(clock·userLookupPort 기주입 → 기존 mock 35개 회귀 안전) 세 항목을 스펙에 반영.
