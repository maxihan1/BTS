<!-- FR-AT-06 D6/D7 YAML GitOps UI 스펙 — 툴바 내보내기/가져오기(Dialog), 백엔드 #272 계약 위 순수 프론트 -->

# FR-AT-06 D6/D7 — 자동화 규칙 YAML GitOps UI 스펙

> 날짜. 2026-07-15 | BC. automation | type. ui | 선행. #272(백엔드 D1~D5) | 배치 결정. 상단 툴바 버튼 2개 + 가져오기 Dialog(Maxi 확정)

## 개요

자동화 규칙(트리거·조건·액션)을 YAML 파일로 내보내고, YAML 파일을 올려 일괄 반영하는 GitOps UI. 진입점은 automation 설정 페이지(`/projects/$projectKey/settings/automation`) 상단 툴바의 **"YAML 내보내기"** / **"YAML 가져오기"** 버튼 2개. 내보내기는 클릭 즉시 파일 다운로드, 가져오기는 Radix Dialog에서 파일 선택 → 2단계 확인 → 적용 → 결과 표시.

**순수 프론트엔드.** 백엔드(엔드포인트·DTO·권한·에러코드·상한)는 #272로 완결. 신규 백엔드/스키마/마이그레이션/ADR 0.

**Maxi 확정 3건** (bts-spec 단계).
1. **배치** — 상단 툴바 버튼 2개(라우터 변경 0, #271 선례 동형).
2. **가져오기 확인** — 파일 선택 → 인라인 2단계 확인 → 적용(#271 replay 확인 동형). 백엔드에 dry-run이 없어 "적용=확정"이므로 확인이 유일한 안전망.
3. **토큰 노출** — 결과 Dialog 내 목록 + 복사 버튼(중첩 모달 회피).

## 사용자 시나리오 (Given-When-Then)

### S1. YAML 내보내기 (성공)

- **Given**. `MANAGE_AUTOMATION` 권한이 있고 프로젝트에 규칙 N개(활성+비활성)가 있다.
- **When**. 툴바 "YAML 내보내기" 클릭.
- **Then**. `automation-rules-{projectKey}.yaml` 이 다운로드되고 성공 토스트가 뜬다. 파일명은 서버 `Content-Disposition` 에서 파싱한다(프론트 조립 금지 — `search.ts` 관례).

### S2. 내보내기 — 규칙 0개

- **Given**. 프로젝트에 규칙이 없다.
- **When**. "YAML 내보내기" 클릭.
- **Then**. 버튼은 **활성 유지**. `rules: []` 인 유효한 YAML(version/projectKey 포함)이 다운로드된다 — "규칙 없음"도 GitOps의 정당한 선언 상태이고, 손 작성 시작용 스캐폴드로 쓸 수 있다.

### S3. 내보내기 — 권한 없음 (403)

- **When**. 권한 없는 사용자가 "YAML 내보내기" 클릭.
- **Then**. 에러 토스트 "권한이 없습니다." (`AUTOMATION_ACCESS_DENIED`). 기존 automation UI의 403 fail-closed 관례와 동일 — 버튼 사전 게이팅은 하지 않는다(§제약 조건 3).

### S4. YAML 가져오기 (성공)

- **Given**. 유효한 GitOps YAML 파일(v1)을 가지고 있다.
- **When**. "YAML 가져오기" → Dialog → 파일 선택 → "적용" → 인라인 확인("적용하면 기존 규칙이 덮어쓰일 수 있습니다") → "확인".
- **Then**. Dialog가 결과로 전환 — **생성 N · 갱신 M · 총 T**. 규칙 목록이 갱신된다(invalidate). `conflicts` 가 있으면 경고 영역이, 새 WEBHOOK 규칙이 있으면 토큰 목록이 함께 표시된다.

### S5. 가져오기 — YAML 형식/스키마 버전 오류 (400 `AUTOMATION_IMPORT_INVALID`, EC1)

- **When**. 깨진 YAML 또는 `version: 2` 파일을 적용.
- **Then**. **Dialog를 유지한 채** 에러 영역에 서버 `detail` 을 표시("YAML 형식이 올바르지 않습니다" 등). 토스트가 아니라 Dialog 내 표시 — 사용자가 파일을 고쳐 곧바로 재시도하는 흐름이기 때문. 파일 선택 상태는 유지해 재선택 부담을 줄인다.

### S6. 가져오기 — 특정 규칙에서 실패 (400 + `failedIndex`)

- **When**. 3번째 규칙의 조건식이 화이트리스트를 위반한 파일을 적용.
- **Then**. "**3번째 규칙**에서 실패했습니다. `<서버 detail>`" + "**적용된 변경이 없습니다(전량 취소)**" 를 함께 표시. `failedIndex` 는 0-based이므로 **표시할 때 +1**. atomic fail-closed라 부분 적용이 없다는 사실을 명시해야 사용자가 안심하고 재시도한다.

### S7. 가져오기 — 크기 상한 초과 (413 `AUTOMATION_IMPORT_TOO_LARGE`)

- **When-a**. 1MiB 초과 파일 선택.
- **Then-a**. **클라이언트가 선제 차단** — 서버로 보내지 않고 "파일이 너무 큽니다(최대 1MiB)" 표시. 즉시 피드백 + 무의미한 업로드 회피.
- **When-b**. 1MiB 이하지만 규칙이 500개 초과인 파일 적용.
- **Then-b**. 프론트는 YAML을 파싱하지 않으므로 서버 413에 의존. 응답 `detail` 을 그대로 표시.

### S8. 가져오기 — projectKey 불일치 (400, EC2)

- **When**. 다른 프로젝트에서 내보낸 YAML(`projectKey: OTHER`)을 현재 프로젝트에 적용.
- **Then**. 에러 영역에 서버 `detail` + **안내 문구**. "다른 프로젝트의 규칙을 복사하려면 YAML에서 `id:` 줄을 제거하세요." (round-trip 시맨틱 — 살아있는 타 프로젝트 id 재사용은 백엔드가 거부).

### S9. 가져오기 — 동시 수정 충돌 (409 `AUTOMATION_RULE_VERSION_CONFLICT`)

- **When**. 적용 도중 다른 사용자가 같은 규칙을 먼저 수정.
- **Then**. "다른 사용자가 먼저 수정했습니다. 최신 상태를 내보낸 뒤 다시 시도하세요." + 전량 취소 명시.

### S10. 가져오기 — 새 WEBHOOK 규칙 토큰 1회 노출

- **Given**. YAML에 `trigger.type: WEBHOOK` 인 **신규**(id 미존재) 규칙이 있다.
- **When**. 적용 성공.
- **Then**. 결과 Dialog에 토큰 목록(규칙명 · 토큰 · 복사 버튼)이 **경고 배지**와 함께 표시된다. Dialog를 닫으려 하면 **2단계 확인**("토큰은 다시 볼 수 없습니다. 닫을까요?"). 갱신된 WEBHOOK 규칙은 토큰을 재발급하지 않아 목록에 없다.

### S11. 가져오기 — 미인증 (401)

- **Then**. `apiFetch` 의 자동 refresh + 1회 retry 경로를 그대로 탄다(신규 처리 없음). 최종 실패 시 기존 세션 만료 흐름.

## 기능 요구사항 (FR)

| # | 요구사항 |
|---|---|
| FR1 | automation 설정 페이지 툴바에 "YAML 내보내기" 버튼. 클릭 → `GET .../rules/export` → blob + `Content-Disposition` 파일명 파싱 → `triggerBlobDownload` (`lib/download.ts:16` **기존 헬퍼 재사용, 신규 금지**). |
| FR2 | 같은 툴바에 "YAML 가져오기" 버튼. 클릭 → 신규 `AutomationYamlImportDialog` (Radix Dialog). |
| FR3 | Dialog는 `<Input type="file" accept=".yaml,.yml">` 로 파일 1개 선택(`ImportMappingWizard.tsx:374` 관례). 미선택 시 "적용" disabled. |
| FR4 | "적용" → 인라인 2단계 확인 → `POST .../rules/import`. 파일은 `File.text()` 로 **원문 문자열**로 읽어 `Content-Type: application/yaml` 로 전송. multipart 금지(백엔드 `consumes` 미허용 → 415). |
| FR5 | 클라이언트 선제 크기 검증 — `file.size > 1_048_576` 이면 요청 없이 에러 표시(백엔드 `MAX_IMPORT_BYTES` 미러). |
| FR6 | 성공 시 결과 표시 — 생성 `created` · 갱신 `updated` · 총 `total`. |
| FR7 | `conflicts` 가 비어있지 않으면 결과 안에 **인라인** 경고(`type`·`severity`·`detail`). 중첩 모달 회피(기존 `RuleConflictWarningModal` 을 위에 띄우지 않음). |
| FR8 | `webhookTokens` 가 있으면 결과 안에 토큰 목록(규칙명·토큰·복사) + 경고 배지 + **닫기 2단계 확인**. |
| FR9 | 성공 시 규칙 목록 쿼리 invalidate → 새/갱신 규칙 즉시 반영. |
| FR10 | 에러코드별 한국어 메시지 매핑(`extractAutomationRuleErrorCode` 관례 재사용). `failedIndex` 는 **+1** 해 "N번째 규칙" 으로 표시. 모든 실패 문구에 "적용된 변경 없음(전량 취소)" 명시. |
| FR11 | **공유 인프라** — `api/client.ts` 가 문자열 body를 `JSON.stringify` 하지 않고 그대로 전달하도록 확장(§API 인터페이스 참조). |

## 비기능 요구사항 (NFR)

| # | 요구사항 |
|---|---|
| NFR1 | **인증 다운로드**. STATELESS JWT라 `<a href download>` 순수 네비게이션은 401. `apiFetch` → `blob()` → objectURL 경로 필수([[avatar-auth-image-cachebust]] 동형). |
| NFR2 | **objectURL 누수 0**. `triggerBlobDownload` 의 `finally` revoke 경로를 그대로 사용(자체 구현 금지). |
| NFR3 | **토큰 비영속**. `webhookTokens` 는 React 상태(메모리)로만 보유. localStorage/sessionStorage/URL/로그 기록 금지. Dialog 종료 시 소멸. |
| NFR4 | **Zod 방어**. `webhookTokens`·`conflicts` 는 `@JsonInclude(NON_NULL)` 이라 키가 생략될 수 있음 → `.optional()`. 누락 시 정상 응답에서 조용히 ZodError([[frontend-zod-backend-dto-contract-gap]]). |
| NFR5 | **client.ts 무회귀**. 기존 145개 `apiFetch` 호출자(전부 객체 리터럴·FormData·타입 DTO 변수, 문자열 body 0건 — 전수 확인함)의 동작 불변. |

## API 인터페이스 (REST) — #272 확정, 프론트는 소비만

```
GET  /api/v1/projects/{projectKey}/automation/rules/export
     → 200 application/yaml;charset=UTF-8
       Content-Disposition: attachment; filename="automation-rules-{projectKey}.yaml"
       body: YAML 텍스트

POST /api/v1/projects/{projectKey}/automation/rules/import
     Content-Type: application/yaml   ← 화이트리스트 4종 중 택1
     body: YAML 원문 텍스트 (JSON/multipart 아님)
     → 200 AutomationImportResponse
```

### DTO → Zod 계약 (신규 스키마는 `api/automation-rules.types.ts` 에 추가)

```ts
// AutomationImportResponse
{
  created: number
  updated: number
  total: number
  ruleIds: string[]                         // UUID, 입력 순서 보존
  webhookTokens?: ImportedWebhookToken[]    // .optional() — 새 WEBHOOK 규칙 없으면 키 생략
  conflicts?: RuleConflictResponse[]        // .optional() — 기존 ruleConflictResponseSchema 재사용
}

// ImportedWebhookTokenResponse
{ ruleId: string, name: string, token: string }
```

- `ruleConflictResponseSchema` 는 `automation-rules.types.ts:57` **기존 스키마 재사용**(신규 정의 금지).
- 명명은 기존 관례 — camelCase + `Schema` 접미사, 타입은 `z.infer` PascalCase 파생.

### 에러 코드 → UI 문구

| HTTP | errorCode | UI |
|---|---|---|
| 403 | `AUTOMATION_ACCESS_DENIED` | 토스트/에러영역 "권한이 없습니다." |
| 400 | `AUTOMATION_IMPORT_INVALID` | Dialog 에러영역 + 서버 `detail`. `failedIndex` 있으면 "N번째 규칙에서 실패" (+1) |
| 413 | `AUTOMATION_IMPORT_TOO_LARGE` | Dialog 에러영역 + 서버 `detail` |
| 409 | `AUTOMATION_RULE_VERSION_CONFLICT` | "다른 사용자가 먼저 수정했습니다…" |
| 400 | `AUTOMATION_MALFORMED_REQUEST` | 일반 실패 문구 |

### client.ts 변경 (FR11 — 유일한 공유 인프라 변경)

**문제**. `client.ts:107` 이 FormData가 아닌 모든 body를 `JSON.stringify` 한다 → YAML 문자열이 따옴표로 감싸인 JSON 문자열이 되어 백엔드 파싱 실패. `client.ts:93` 의 Content-Type 자동 설정은 `!headers.has('content-type')` 가드가 있어 **이미 덮어쓰기 가능**(변경 불요).

**변경**. FormData pass-through 분기를 문자열까지 확장.

```ts
// 현재
const isFormData = body instanceof FormData
body: body !== undefined ? (isFormData ? body : JSON.stringify(body)) : undefined,

// 변경 후 — 직렬화 없이 그대로 보낼 body 타입을 확장
const isRawBody = body instanceof FormData || typeof body === 'string'
body: body !== undefined ? (isRawBody ? body : JSON.stringify(body)) : undefined,
```

- Content-Type 자동 JSON 설정 조건도 `!isRawBody` 로 맞춘다(문자열 body에 JSON 기본값이 붙지 않도록). 단 호출자가 명시 헤더를 주는 게 정식 경로.
- **KDoc 갱신** 필수(`client.ts:73-79` 의 "body 있으면 Content-Type: application/json 자동 설정" 문구가 반쪽 진실이 됨).
- **대안 기각 근거**. (a) multipart → 백엔드 `consumes` 미허용, 415. (b) raw `fetch` 직접 호출 → 401 자동 refresh·Authorization·credentials·XSRF 전부 상실, `imports.ts:84-93` 주석이 "raw fetch 금지" 로 명시한 관례 위반. (c) 백엔드에 multipart 추가 → 순수 프론트 범위 파괴 + #272가 `@RequestBody` 를 의도 선택.

## 데이터 모델 변경

**없음.** 신규 마이그레이션 0, 백엔드 변경 0.

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| EC1 | 파일 미선택 상태 "적용" | 버튼 disabled |
| EC2 | 빈 파일(0바이트) | 서버 400 → Dialog 에러영역 |
| EC3 | 1MiB 초과 | 클라 선제 차단(S7-a), 요청 미발사 |
| EC4 | 규칙 500개 초과 | 서버 413 의존(프론트 파싱 안 함) |
| EC5 | 적용 진행 중 재클릭 | `isPending` 으로 버튼 disabled |
| EC6 | 내보내기 진행 중 재클릭 | `isPending` 으로 버튼 disabled |
| EC7 | 토큰 노출 중 Dialog 닫기 | 2단계 확인(S10) |
| EC8 | 결과 표시 후 다시 가져오기 | Dialog 재오픈 시 상태 초기화(파일·결과·에러) |
| EC9 | `.yaml` 아닌 확장자 선택 | `accept` 는 힌트일 뿐 강제 아님 → 서버 400에 위임(프론트 확장자 검증은 하지 않음, 손 작성 파일 배제 위험) |
| EC10 | `conflicts: []`(빈 배열) | 경고 영역 렌더 안 함(`length > 0` 조건) |
| EC11 | `webhookTokens` 키 생략 | `.optional()` → `undefined` → 토큰 영역 렌더 안 함 |

## 제약 조건

1. **순수 프론트**. `apps/web/**` 만 변경. 백엔드/DB/마이그레이션 0.
2. **BC 격리**. automation BC UI만. 다른 BC 컴포넌트 수정 금지. 단 `api/client.ts` 는 전 BC 공유 인프라 — FR11로 최소 변경(현재 진행 중인 다른 PR 없음을 확인, 충돌 위험 0).
3. **권한 게이팅 범위 밖**. `projectPermissionsSchema` (`api/project-permissions.ts:21-32`)에 `MANAGE_AUTOMATION` 키가 **부재**하고, automation UI에 게이팅 선례가 **0건**이다. 사전 게이팅하려면 백엔드 권한 요약 API 확장이 선행돼야 하므로([[ui-permission-gating-needs-summary-api-exposure]]) 본 PR 범위 밖. 기존 관례대로 **런타임 403 fail-closed**. → 후속 FR 후보로 기록.
4. **YAML 파싱 금지**. 프론트는 YAML을 파싱하지 않는다(js-yaml 등 신규 의존성 도입 금지). 원문 문자열을 그대로 전달하고 검증은 전부 백엔드 도메인 파서에 위임.
5. **MSW 등록 2곳**. 신규 핸들러는 `mocks/handlers.ts` 의 import(≈69) + 전역 배열 스프레드(≈147) **양쪽** 추가([[msw-global-handler-registration-gap]]).
6. **MSW 단일 인스턴스**. 지역 `setupServer` 금지, `@/test/server` 사용([[msw-dual-setupserver-double-dispatch]], #271 T3 발견).

## 측정 가능한 완료 기준

- [ ] `pnpm lint` · `pnpm typecheck`(CI는 `tsconfig.app.json`) 0 에러
- [ ] `pnpm test` — 신규 단위 테스트 통과 + **기존 회귀 0**(#271 기준 6933 테스트)
- [ ] `pnpm build` 성공
- [ ] E2E(D7) 신규 시나리오 통과 — S1(내보내기) · S4(가져오기 성공) · S6(failedIndex 실패) · S10(토큰 1회 노출)
- [ ] `client.ts` 변경 후 기존 145개 호출자 회귀 0(단위 테스트로 객체 body JSON 직렬화 유지 검증)
- [ ] `docs/plan/product/automation.md` D6/D7 체크박스 마킹 + automation BC 5/7 → **6/7** 반영
- [ ] `node scripts/build-dashboard.mjs` 재생성([[dashboard-regen-after-fr-marking]])

## Brainstorming Check

(← /bts-spec Phase B 채움)
