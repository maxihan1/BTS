# FR-AT-04 D6/D7 — 규칙 충돌 경고 모달 UI + E2E — 스펙

> slug: fr-at-04-d6-d7-conflict-warning-ui · type: ui · BC: automation
> 백엔드 D1~D5 = PR #268 머지 완료. 이 스펙은 순수 프론트(D6) + E2E(D7).
> 경량 진행(Maxi 확정) — office-hours/design-shotgun 생략, 확정 계약 위에서 직접 작성.

## 배경

규칙 저장(생성/수정) 시 백엔드가 프로젝트 규칙 집합을 정적 분석해 충돌 4종을 검출하고, 저장 응답에 `conflicts` 배열로 실어 보낸다(soft WARNING — 저장은 어떤 충돌에도 차단되지 않음). 현재 프론트는 이 필드를 Zod 스키마에서 파싱하지 않아 **조용히 버리고 있다**. 사용자는 규칙을 저장해도 충돌이 있었는지 알 수 없다.

D6은 저장 후 검출된 충돌을 경고 모달로 표시한다. D7은 그 흐름을 E2E로 검증한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (충돌 있는 생성)**: Given 프로젝트에 규칙 A가 있고, When 사용자가 A와 순환(CYCLE)을 이루는 규칙 B를 저장하면, Then 규칙 B는 정상 저장되고(목록에 나타남) 저장 직후 충돌 경고 모달이 뜬다. 모달은 충돌 종류(순환)와 백엔드가 내려준 한국어 설명을 보여준다.
- **S2 (충돌 없는 생성/수정)**: Given 충돌 없는 규칙을, When 저장하면, Then 규칙은 저장되고 경고 모달은 **뜨지 않는다**.
- **S3 (충돌 있는 수정)**: Given 기존 규칙을, When 다른 규칙과 충돌하도록 수정 저장하면, Then 수정은 반영되고 충돌 경고 모달이 뜬다.
- **S4 (모달 닫기)**: Given 충돌 경고 모달이 떠 있고, When 사용자가 "확인"·바깥클릭·Esc로 닫으면, Then 모달이 사라지고 페이지의 conflicts 상태가 비워진다(재오픈 없음 — 충돌은 저장 응답 1회성이라 다시 조회되지 않는다).
- **S5 (다중 충돌)**: Given 한 번의 저장이 여러 충돌을 유발하면, Then 모달은 충돌을 목록으로 모두 나열한다.
- **S6 (WEBHOOK 토큰 + 충돌 동시)**: Given WEBHOOK 트리거 규칙 생성이 토큰 발급과 충돌(예: PERMISSION_MISSING)을 동시에 유발하면, Then 토큰 모달을 **먼저** 보여주고(보안상 1회 노출 우선), 그것을 닫으면 이어서 충돌 경고 모달을 보여준다.

## 기능 요구사항 (FR)

- **FR-1 (Zod 계약 미러)**: `automation-rules.types.ts`에 백엔드 계약 대칭 스키마를 추가한다.
  - `conflictTypeSchema` = `z.enum(['CYCLE','FIELD_CONFLICT','PRIORITY_AMBIGUITY','PERMISSION_MISSING'])`
  - `conflictSeveritySchema` = `z.enum(['WARNING'])`
  - `ruleConflictResponseSchema` = `{ type, severity, ruleIds: z.array(z.string().uuid()), detail: z.string() }`
  - `automationRuleResponseSchema`에 `conflicts: z.array(ruleConflictResponseSchema).optional()` 추가.
    - **optional 이유**: GET(목록/단건)은 `@JsonInclude(NON_NULL)`로 키 자체가 부재. create/patch는 `[]` 포함 항상 존재. optional이면 GET(부재)·create/patch(존재, 빈 배열 포함) 모두 파싱 통과.
  - 추론 타입 `RuleConflict`(프론트) export — 백엔드 도메인명과 겹치지만 프론트 파일 스코프라 무해. 필요 시 `RuleConflictView`로 명명.
- **FR-2 (저장 성공 시 충돌 표출)**: `AutomationRuleFormDialog`의 `onValid`에서 저장 성공 후, 응답 conflicts가 **비어있지 않으면**(`length > 0`) 신규 `onConflicts?.(conflicts)` 콜백으로 1회 전달한다. webhookToken 콜백 선례와 동형.
  - create: `response.rule.conflicts`
  - update: `updateRule.mutateAsync(...)` 반환값의 `.conflicts` (**현재 반환값을 안 받으므로 받도록 수정**)
  - conflicts가 `undefined`이거나 빈 배열이면 콜백 미호출(S2).
- **FR-3 (경고 모달 컴포넌트)**: 신규 `RuleConflictWarningModal` 컴포넌트. `WebhookTokenModal` 패턴 그대로.
  - props: `conflicts: RuleConflict[] | null`(null이면 렌더 안 함) + `onClose: () => void`.
  - amber/warning 톤(WebhookTokenModal의 `text-amber-800 dark:text-amber-200` 선례), `role="alert"`.
  - 각 충돌 1건: 종류 배지(한국어 라벨) + 백엔드 `detail` 메시지 렌더. `ruleIds`는 보조 정보(개수 또는 축약 표기)로, 이름 조회는 하지 않는다(detail이 이미 설명 담당 — 스코프 최소화).
  - 종류→한국어 라벨 매핑: CYCLE=순환 참조, FIELD_CONFLICT=필드 충돌, PRIORITY_AMBIGUITY=우선순위 모호, PERMISSION_MISSING=권한 부족.
- **FR-4 (페이지 배선)**: `projects.$projectKey.settings.automation.tsx`에 `conflicts` 페이지 상태(`RuleConflict[] | null`) 추가. FormDialog의 `onConflicts`로 세팅, `RuleConflictWarningModal` 조립, onClose 시 null 복귀. WebhookTokenModal 조립과 대칭.
- **FR-5 (토큰+충돌 순차)**: WEBHOOK 생성이 토큰과 충돌을 동시 유발하면 토큰 모달 우선, 닫힌 뒤 충돌 모달. 페이지가 두 상태를 보유하되, 충돌 모달은 `webhookToken === null`일 때만 렌더(토큰이 살아있는 동안 대기).
- **FR-6 (MSW 충돌 시나리오)**: `automation-rule-handlers.ts`의 create/patch 핸들러가 시나리오 플래그(localStorage 토글, `SCENARIO_KEY.WITH_CONFLICTS` 선례 [[e2e-msw-scenario-toggle-localstorage-flag]])에 따라 결정적 conflicts 배열을 응답에 실을 수 있게 한다. 기본(플래그 없음)은 `conflicts: []`(생성/수정 계약 준수, GET은 미포함 유지). store 저장 rule에는 conflicts를 넣지 않는다(GET 오염 방지).
- **FR-7 (D7 E2E)**: `automation-conflict-warning.spec.ts`(신규) — S1(충돌 저장→모달 표시), S2(충돌 없는 저장→모달 미표시), S4(닫기), S5(다중 충돌 나열) 검증. 기존 automation-rules.spec.ts 회귀 동반 실행([[ui-pr-defer-e2e-regression-latent]]).

## 비기능 요구사항 (NFR)

- **NFR-1 (비차단)**: 모달은 순수 정보성. 저장은 이미 성공한 상태이며 모달이 저장을 되돌리지 않는다.
- **NFR-2 (접근성)**: `role="dialog"`/`aria-modal`, 경고 본문 `role="alert"`. WebhookTokenModal·기존 automation 모달 선례.
- **NFR-3 (i18n)**: automation BC는 i18n 미도입 — 고정 한국어(FormDialog `labels` 선례).
- **NFR-4 (계약 정합)**: Zod 스키마 필드명·enum 값이 백엔드 `RuleConflictResponse`/`ConflictType`/`ConflictSeverity`와 1:1 ([[frontend-zod-backend-dto-contract-gap]]).

## API 인터페이스

신규 API 없음. 기존 create/patch 응답에 이미 존재하는 `conflicts` 필드 소비만 한다.
- POST `.../automation/rules` → `{ rule: { ..., conflicts: RuleConflictResponse[] }, webhookToken }`
- PATCH `.../automation/rules/{id}` → `{ ..., conflicts: RuleConflictResponse[] }`
- GET 계열 → `conflicts` 키 부재(불변).

## 데이터 모델 변경

없음(프론트 Zod 스키마 확장만, 백엔드/DB 무변경).

## 엣지 케이스

- **E1**: conflicts 부재(GET) 또는 빈 배열 → 모달 미표시(S2, FR-2).
- **E2**: 저장 실패(400/403/409/500) → 응답에 conflicts 없음 → 모달 미표시. 기존 에러 처리(submitError/토스트) 그대로.
- **E3**: WEBHOOK 토큰 + 충돌 동시 → 순차(FR-5). 토큰 모달이 열려있는 동안 충돌 모달 대기.
- **E4**: 다중 충돌 → 목록 렌더(S5).
- **E5 (react-usestate-stale)**: 충돌 모달을 연속 저장으로 다시 띄울 때 이전 conflicts가 잔존하지 않도록, null→새 배열 세팅 시 항상 최신 응답값으로 교체. WebhookTokenModal이 null 복귀로 언마운트에 준하는 것과 동일.
- **E6**: `ruleIds`가 방금 저장한 규칙 자신을 포함할 수 있음(CYCLE은 self 포함 가능) — id만 보조 표기하므로 문제없음. 이름 조회 안 함.
- **E7 (409 후 재시도)**: OCC 409는 폼을 닫고 목록 refetch([[form-occ-409-parent-usestate-staleness]]) — 이때 저장 자체가 실패라 conflicts 없음(E2와 동일 경로).

## 제약 조건

- 신규 백엔드 호출·엔드포인트·테이블 없음.
- automation BC 프론트 관례 준수: bare DTO, `{data}` 봉투 없음, 고정 한국어, mocks/<name>-handlers.
- MSW 신규 handler 없음(기존 create/patch 핸들러 확장) — 전역 등록 갭([[msw-global-handler-registration-gap]]) 해당 없으나, SCENARIO_KEY 신규 상수는 fixtures에 추가.

## 측정 가능한 완료 기준

- [ ] `automation-rules.types.ts`에 conflicts 스키마 3종 추가, 기존 GET/create/patch 파싱 회귀 없음(단위 테스트).
- [ ] `RuleConflictWarningModal` 단위 테스트: null 미렌더 / 단건 / 다중 / 종류 라벨 / detail 렌더.
- [ ] FormDialog 단위 테스트: 충돌 있는 create/patch → onConflicts 호출 / 빈 배열·부재 → 미호출.
- [ ] 페이지 단위 테스트: onConflicts → 모달 표시 / onClose → null 복귀 / 토큰+충돌 순차.
- [ ] E2E `automation-conflict-warning.spec.ts` S1·S2·S4·S5 green + 기존 automation E2E 회귀 green.
- [ ] pnpm verify(lint+typecheck+test+build) green, CI 3잡 green.

## Brainstorming Check

✅ 통과 (1회, gap 없음). 점검 요지.
- API 레이어 무변경 확인(Zod 스키마 확장만) — 스코프 축소.
- `.optional()` 정확성 확인(백엔드 null 미전송, GET 부재/create·patch 배열).
- `AutomationRule` optional 필드 추가의 하위호환 확인.
- **게이트1 확인 대상 1건**: WEBHOOK 토큰+충돌 동시 순차 표시(S6/E3). 기본값=토큰 우선, Maxi 확인.
