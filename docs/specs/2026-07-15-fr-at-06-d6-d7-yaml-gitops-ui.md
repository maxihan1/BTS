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
- **When**. "YAML 가져오기" → Dialog → 파일 선택 → "적용" → 인라인 확인("적용하면 기존 룰이 덮어쓰일 수 있습니다") → **"확정"**(라벨은 같은 BC의 위험 액션 확정 선례 `RuleExecutionTraceRow.tsx:26` `replayConfirmButton: '확정'` 과 통일 — plan-design-review 반영).
- **Then**. Dialog가 결과로 전환 — **생성 N · 갱신 M · 총 T**. 규칙 목록이 갱신된다(invalidate). `conflicts` 가 있으면 경고 영역이, 새 WEBHOOK 규칙이 있으면 토큰 목록이 함께 표시된다.

### S5. 가져오기 — 400 `AUTOMATION_IMPORT_INVALID` (`failedIndex` 없음)

깨진 YAML · `version: 2` · YAML `projectKey` 불일치가 **모두 이 케이스로 합류**한다.

- **When**. 깨진 YAML, 또는 `version: 2`, 또는 다른 프로젝트의 YAML(`projectKey: OTHER`)을 적용.
- **Then**. **Dialog를 유지한 채** 에러 영역에 **서버 `detail` 을 그대로** 표시 + "적용된 변경 없음(전량 취소)". 토스트가 아니라 Dialog 내 표시 — 사용자가 파일을 고쳐 곧바로 재시도하는 흐름이기 때문. 파일 선택 상태는 유지해 재선택 부담을 줄인다.

> **★ 사유 3종을 UI에서 분기하지 않는다 (gap 분석 BLOCKER-1 해소).** 백엔드가 `AutomationYamlInvalidException`(EC1)·`AutomationImportProjectKeyMismatchException`(EC2)을 `AutomationRuleController.kt:612-621` 의 **같은 `else` 분기**로 처리한다 — status(400)·`errorCode`·ProblemDetail `type`·`failedIndex` 부재까지 전부 동일하고 **한국어 `detail` 문자열로만 다르다**. 프론트가 이를 구별하려면 메시지 문자열 매칭이 필요한데 이는 [[crossbc-failure-classification-typed-not-name]](실패 분류는 타입으로, 문자열 매칭 금지) 위반이다. 순수 프론트 범위라 백엔드에 판별자를 추가할 수도 없다. → **구별하지 않고 서버 `detail` 을 신뢰**한다(백엔드가 이미 사유별로 구체적인 한국어 문구를 담아 보낸다).

### S6. 가져오기 — 특정 룰에서 실패 (400 + `failedIndex`)

- **When**. 3번째 룰의 조건식이 화이트리스트를 위반한 파일을 적용. (또는 `id` 가 타 프로젝트/삭제된 룰에 귀속된 경우 — `AutomationImportIdConflictException` 도 `AutomationImportCommandException` 으로 래핑돼 이 경로로 온다.)
- **Then**. "**3번째 룰**에서 실패했습니다. `<서버 detail>`" + "**적용된 변경이 없습니다(전량 취소)**" 를 함께 표시. `failedIndex` 는 0-based이므로 **표시할 때 +1**. atomic fail-closed라 부분 적용이 없다는 사실을 명시해야 사용자가 안심하고 재시도한다.

### S7. 가져오기 — 크기 상한 초과 (413 `AUTOMATION_IMPORT_TOO_LARGE`)

- **When-a**. 1MiB 초과 파일 선택.
- **Then-a**. **클라이언트가 선제 차단** — 서버로 보내지 않고 "파일이 너무 큽니다(최대 1MiB)" 표시. 즉시 피드백 + 무의미한 업로드 회피.
- **When-b**. 1MiB 이하지만 규칙이 500개 초과인 파일 적용.
- **Then-b**. 프론트는 YAML을 파싱하지 않으므로 서버 413에 의존. 응답 `detail` 을 그대로 표시.

### S8. 타 프로젝트 복사 안내 — 에러 분기가 아닌 **상시 도움말**

- **Given**. 사용자가 다른 프로젝트의 YAML을 이 프로젝트에 적용하려 한다.
- **When**. 가져오기 Dialog를 연다(에러 발생 여부와 무관).
- **Then**. Dialog에 **항상 보이는** 도움말 문구. "다른 프로젝트의 룰을 복사하려면 YAML에서 `id:` 줄을 제거하세요. 같은 프로젝트에 다시 적용하는 경우에는 그대로 두면 됩니다."

> **★ 왜 에러 조건부가 아니라 상시인가 (gap 분석 BLOCKER-1 + CONCERN-6 동시 해소).** 타 프로젝트 YAML을 적용하는 사용자는 **에러를 두 번 연달아** 만난다. ① `projectKey: OTHER` 그대로 → S5(`failedIndex` 없음). ② `projectKey` 만 고쳐서 재시도 → `id` 가 타 프로젝트 소유라 **S6**(`failedIndex` 있음). 안내가 필요한 시점은 ②인데, ②는 S6 규칙상 "N번째 룰에서 실패"로만 렌더되어 안내가 **사라진다**. 게다가 ①은 S5의 다른 사유(깨진 YAML)와 와이어에서 구별 불가다. → 어느 에러에도 매달지 않고 **Dialog 상시 도움말**로 올린다. 구현이 단순해지고(분기 0), 사용자는 실수하기 **전에** 안내를 본다.

### S9. 가져오기 — 동시 수정 충돌 (409 `AUTOMATION_RULE_VERSION_CONFLICT`)

- **When**. 적용 도중 다른 사용자가 같은 룰을 먼저 수정.
- **Then**. **다른 실패와 동일한 단일 규칙** — 서버 `detail`("다른 변경이 먼저 반영되었습니다. 최신 정보를 다시 불러온 뒤 시도해 주세요.") + "적용된 변경이 없습니다(전량 취소)". 프론트 전용 문구/분기 없음(§API 인터페이스 §에러 참조).

### S10. 가져오기 — 새 WEBHOOK 규칙 토큰 1회 노출

- **Given**. YAML에 `trigger.type: WEBHOOK` 인 **신규**(id 미존재) 규칙이 있다.
- **When**. 적용 성공.
- **Then**. 결과 Dialog에 토큰 목록(**룰 이름** · 토큰 · 복사 버튼)이 `role="alert"` 경고 영역과 함께 표시된다. **토큰 영역은 결과 카운트(생성/갱신/총)와 conflicts 경고보다 시각적으로 먼저 온다** — 놓치면 영구 분실이라 가장 되돌릴 수 없는 정보가 최상단이어야 한다(plan-design-review 반영). Dialog를 닫으려 하면 **2단계 확인**("토큰은 다시 볼 수 없습니다. 닫을까요?"). 갱신된 WEBHOOK 룰은 토큰을 재발급하지 않아 목록에 없다.

### S11. 가져오기 — 미인증 (401)

- **Then**. `apiFetch` 의 자동 refresh + 1회 retry 경로를 그대로 탄다(신규 처리 없음). 최종 실패 시 기존 세션 만료 흐름.

## 기능 요구사항 (FR)

| # | 요구사항 |
|---|---|
| FR0 | **배치 — 파일 단위 확정**(gap 분석 CONCERN-2 해소). 버튼 2개는 `AutomationRuleList.tsx:361-367` 의 **기존 헤더 행**(`flex items-center justify-between`, h2 "자동화 룰" + "룰 추가" 버튼)에 **"룰 추가" 옆으로** 추가한다 — Maxi 확정 시안이 버튼 3개 한 줄이었기 때문. route(`projects.$projectKey.settings.automation.tsx:96-101`)의 `<header>` 에는 버튼을 두지 **않는다**(그 헤더는 h1+설명문 전용). **prop threading** — `AutomationRuleList` 에 `onExportYaml: () => void` · `onImportYaml: () => void` · `isExportingYaml: boolean` prop 추가(기존 `onAddRule`/`onViewHistory` 관례 동형, #271 선례). Dialog 상태와 export mutation은 **페이지가 소유**한다. |
| FR1 | "YAML 내보내기" 버튼 → `GET .../rules/export` → blob + `Content-Disposition` 파일명 파싱 → `triggerBlobDownload` (`lib/download.ts:16` **기존 헬퍼 재사용, 신규 금지**). |
| FR2 | "YAML 가져오기" 버튼 → 신규 `AutomationYamlImportDialog` (Radix Dialog). |
| FR3 | Dialog는 `<Input type="file" accept=".yaml,.yml">` 로 파일 1개 선택(`ImportMappingWizard.tsx:374` 관례). 미선택 시 "적용" disabled. Dialog에 §S8 **상시 도움말** 문구를 항상 표시. |
| FR4 | "적용" → 인라인 2단계 확인 → `POST .../rules/import`. 파일은 `File.text()` 로 **원문 문자열**로 읽어 전송. 헤더는 `{ 'Content-Type': 'application/yaml;charset=UTF-8', 'X-XSRF-TOKEN': readXsrfToken() }` — **XSRF는 형제 mutation 3종(`automation-rules.ts:99,128,149`)과 동일하게 포함**(gap 분석 CONCERN-3). multipart 금지(백엔드 `consumes` 미허용 → 415). |
| FR5 | 클라이언트 선제 크기 검증 — `file.size > 1_048_576` 이면 요청 없이 에러 표시(백엔드 `MAX_IMPORT_BYTES` 미러). |
| FR6 | 성공 시 결과 표시 — 생성 `created` · 갱신 `updated` · 총 `total`. |
| FR7 | `conflicts` 가 비어있지 않으면 결과 안에 **인라인** 경고(`type`·`severity`·`detail`). 중첩 모달 회피(기존 `RuleConflictWarningModal` 을 위에 띄우지 않음). |
| FR8 | `webhookTokens` 가 있으면 결과 안에 토큰 목록(룰 이름·토큰·복사) + 경고 + **닫기 2단계 확인**. **문구/복사 처리는 `WebhookTokenModal.tsx:12-17` 을 그대로 따른다**(gap 분석 CONCERN-4) — 경고 `'이 토큰은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없습니다.'` · `'복사'` / `'복사됨'` / **`'복사에 실패했습니다. 직접 선택해 복사해 주세요.'`**. 컴포넌트 자체 재사용은 하지 않는다(단건 모달 vs 다건 목록). `navigator.clipboard.writeText` reject 경로 필수 처리(EC12). |
| FR9 | 성공 시 `AUTOMATION_RULES_QUERY_KEY(projectKey)` invalidate → 새/갱신 룰 즉시 반영(gap 분석 NIT-9). |
| FR10 | **errorCode 분기 금지** — 서버가 내려준 `detail` 을 그대로 노출한다(§에러 단일 규칙). S5(깨진 YAML)와 S8(projectKey 불일치)은 status·errorCode·ProblemDetail 타입이 **와이어에서 동일**해 `detail` 문자열로만 갈리므로, UI 분기는 문자열 매칭이 되어 금지(BLOCKER-1). `failedIndex` 는 **+1** 해 "N번째 룰" 로 표시. **"적용된 변경 없음(전량 취소)" 은 `ApiError`(서버발 실패)에만 병기한다** — 백엔드 atomic fail-closed 가 보장하는 건 서버가 실패를 보고한 경우뿐이고, 응답 파싱 실패(ZodError)·수신 중 네트워크 단절은 **서버가 이미 커밋한 뒤**라 롤백 단언이 거짓이 된다. 이때 사용자가 S8 안내대로 `id:` 를 지우고 재시도하면 룰이 중복 생성된다. 클라이언트측 실패는 결과 불확실 문구 + 룰 목록 invalidate 로 처리(코드리뷰 CRITICAL-2). |
| FR12 | **UI 문구 용어는 "룰"** (gap 분석 NIT-12). `AutomationRuleList.tsx:22-24` 의 `labels` 정본이 `heading: '자동화 룰'` · `addButton: '룰 추가'` 다. 새 버튼이 같은 행에 놓이므로 "규칙"과 섞이면 안 된다. 버튼 라벨은 `'YAML 내보내기'` / `'YAML 가져오기'`. 문서(스펙/plan) 산문은 FR 제목을 따라 "규칙"을 써도 되지만 **화면 문자열은 전부 "룰"**. |
| FR11 | **공유 인프라** — `api/client.ts` 가 문자열 body를 `JSON.stringify` 하지 않고 그대로 전달하도록 확장(§API 인터페이스 참조). |

## 비기능 요구사항 (NFR)

| # | 요구사항 |
|---|---|
| NFR1 | **인증 다운로드**. STATELESS JWT라 `<a href download>` 순수 네비게이션은 401. `apiFetch` → `blob()` → objectURL 경로 필수([[avatar-auth-image-cachebust]] 동형). |
| NFR2 | **objectURL 누수 0**. `triggerBlobDownload` 의 `finally` revoke 경로를 그대로 사용(자체 구현 금지). |
| NFR3 | **토큰 비영속**. `webhookTokens` 는 React 상태(메모리)로만 보유. localStorage/sessionStorage/URL/로그 기록 금지. Dialog 종료 시 소멸. |
| NFR4 | **Zod 방어**. `webhookTokens` 는 새 WEBHOOK 룰이 없으면 **키가 실제로 생략**된다(`AutomationImportResponse.from` 의 `ifEmpty { null }` + `@JsonInclude(NON_NULL)`) → `.optional()` **필수**. 누락 시 정상 응답에서 조용히 ZodError([[frontend-zod-backend-dto-contract-gap]]). `conflicts` 는 어노테이션은 같지만 **실제로는 항상 배열**(빈 배열이어도 `[]`, `AutomationRuleResponses.kt:293` 이 무조건 `conflicts.map(...)`) — 방어적으로 `.optional()` 을 두되 **생략을 기대하지는 않는다**(gap 분석 NIT-8, plan §도메인 정리와 동일 서술). |
| NFR5 | **client.ts 무회귀**. 기존 145개 `apiFetch` 호출자(전부 객체 리터럴·FormData·타입 DTO 변수, 문자열 body 0건 — 전수 확인함)의 동작 불변. |

## API 인터페이스 (REST) — #272 확정, 프론트는 소비만

```
GET  /api/v1/projects/{projectKey}/automation/rules/export
     → 200 application/yaml;charset=UTF-8
       Content-Disposition: attachment; filename="automation-rules-{projectKey}.yaml"
       body: YAML 텍스트

POST /api/v1/projects/{projectKey}/automation/rules/import
     Content-Type: application/yaml;charset=UTF-8   ← 화이트리스트 4종 중 택1 + charset 명시
     X-XSRF-TOKEN: <readXsrfToken()>                ← 형제 mutation 3종과 동일
     body: YAML 원문 텍스트 (JSON/multipart 아님)
     → 200 AutomationImportResponse
```

> **charset 명시 이유**(gap 분석 NIT-7). export가 `;charset=UTF-8` 을 **일부러** 명시한 것과 대칭 — #272에서 charset 미명시가 `StringHttpMessageConverter` 의 ISO-8859-1 기본값을 타 한글 룰명을 깨뜨린 함정이 있었다([[fr-at-06-yaml-gitops-backend-done]]). 현재 Boot 기본값(UTF-8)에 기대면 동작은 하지만 암묵 의존이다. `consumes` 매칭은 미디어 타입 파라미터를 무시하므로 charset을 붙여도 415가 나지 않는다.

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
**★ 가져오기 실패는 errorCode 분기 없이 단일 규칙**(plan-eng-review §8 반영).

```
표시 = (failedIndex != null ? `${failedIndex + 1}번째 룰에서 실패했습니다. ` : '')
     + (서버 detail ?? '가져오기에 실패했습니다.')      ← fallback
     + ' 적용된 변경이 없습니다(전량 취소).'             ← 항상 병기
```

| HTTP | errorCode | 서버 `detail` (실물) | UI |
|---|---|---|---|
| 400 | `AUTOMATION_IMPORT_INVALID` | "YAML 형식이…" / "지원하지 않는 YAML 스키마 버전…" / projectKey 불일치 / 커맨드 사유 | 단일 규칙. `failedIndex` 있으면 접두(S6), 없으면 `detail` 만(S5). **사유별 분기 금지**(BLOCKER-1) |
| 413 | `AUTOMATION_IMPORT_TOO_LARGE` | "가져오기 요청 본문 크기가 상한(1048576바이트)을 초과…" / "…규칙 수가 상한(500개)을…" | 단일 규칙 |
| 409 | `AUTOMATION_RULE_VERSION_CONFLICT` | **"다른 변경이 먼저 반영되었습니다. 최신 정보를 다시 불러온 뒤 시도해 주세요."** (`AutomationRuleController.kt:546`) | 단일 규칙 — **프론트 하드코딩 금지**(아래 사유) |
| 403 | `AUTOMATION_ACCESS_DENIED` | 권한 문구 | 단일 규칙 |
| 400 | `AUTOMATION_MALFORMED_REQUEST` | 본문 판독 불가 | 단일 규칙 |
| 500 | `AUTOMATION_INTERNAL_ERROR` | (있을 수도/없을 수도) | 단일 규칙 — `detail` 없으면 fallback 문구 |

> **★ 409에 프론트 고정 문구를 두지 않는다** (plan-eng-review §8 해소). 초안은 409만 유일하게 서버 `detail` 대신 프론트 하드코딩 문구를 쓰게 했는데, ① 백엔드가 이미 **사용자에게 그대로 보여줄 수 있는 한국어 안내**를 준다(위 실물 인용) ② 이 예외 하나 때문에 errorCode 기반 분기가 필요해지고, 그 분기를 강제하는 테스트가 없으면 구현자가 놓쳐도 **아무 테스트도 안 깨지는 가짜 그린**이 된다 ③ S5/S8의 BLOCKER-1 해소 원칙("서버 `detail` 을 신뢰")과도 어긋난다. → **분기 자체를 제거**해 전 실패를 한 규칙으로 통일한다. errorCode는 로깅/E2E 식별용으로만 쓴다.

> **내보내기(export)는 Dialog가 없어 토스트**. 403이면 "권한이 없습니다.", 그 외 실패는 "YAML 내보내기에 실패했습니다." (토스트는 짧아야 하므로 서버 `detail` 을 싣지 않는다 — 가져오기와 의도적으로 다름).

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
| EC7 | 토큰 노출 중 Dialog 닫기 | 2단계 확인(S10). **닫기 경로 4종 전부 가로챈다**(gap 분석 CONCERN-5) — X 버튼 · **ESC**(`onEscapeKeyDown` preventDefault) · **오버레이 클릭**(`onPointerDownOutside` preventDefault) · `onOpenChange`. 하나라도 빠지면 토큰이 **영구 분실**된다(NFR3 비영속). |
| EC7-a | **import 응답 대기(in-flight) 중 닫기** | 게이트 조건은 `hasUnackedTokens \|\| isPending`(= `tokenAtRisk`). **서버는 `result` 가 채워지기 전에 이미 커밋하고 토큰을 발급**하므로, `result` 를 기다리는 `hasUnackedTokens` 만으로 가드하면 이 구간이 통째로 무방비다(코드리뷰 CRITICAL-1). 2단계 확인 UI 는 `result` 와 **무관하게** 렌더돼야 한다 — 토큰 목록 안에 중첩하면 in-flight 때 `closeConfirming` 이 세팅돼도 렌더될 곳이 없어 **무음 실패**한다. |
| EC7-b | **토큰 노출 중 파일 재선택** | 파일 input 을 `tokenAtRisk` 로 disabled. 재선택은 결과를 초기화하므로 토큰을 **확인 없이 파기**하는 경로가 된다(코드리뷰 CRITICAL-1 동반). input 을 막으면 "닫기가 유일한 토큰 소멸 경로" 불변식이 구조적으로 성립한다. |
| EC8 | 결과 표시 후 다시 가져오기 | Dialog 재오픈 시 상태 초기화(파일·결과·에러) |
| EC9 | `.yaml` 아닌 확장자 선택 | `accept` 는 힌트일 뿐 강제 아님 → 서버 400에 위임(프론트 확장자 검증은 하지 않음, 손 작성 파일 배제 위험) |
| EC10 | `conflicts: []`(빈 배열) | 경고 영역 렌더 안 함(`length > 0` 조건) |
| EC11 | `webhookTokens` 키 생략 | `.optional()` → `undefined` → 토큰 영역 렌더 안 함 |
| EC12 | **복사 실패**(`navigator.clipboard.writeText` reject — 비보안 컨텍스트/권한 거부) | `WebhookTokenModal.tsx:16` 의 `'복사에 실패했습니다. 직접 선택해 복사해 주세요.'` 표시. 토큰 텍스트는 선택 가능하게 유지(gap 분석 CONCERN-4) |
| EC13 | **`id` 가 타 프로젝트/삭제된 룰에 귀속**(`AutomationImportIdConflictException`) | `AutomationImportCommandException` 으로 래핑돼 **S6 경로**(400 + `failedIndex`). 서버 `detail` 표시 + §S8 상시 도움말이 해결책(`id:` 제거)을 이미 노출 중(gap 분석 CONCERN-6) |
| EC14 | **`triggerType` 변경 불가** 등 기타 커맨드 검증 실패 | S6 경로 동일 — 서버 `detail` 을 그대로 표시(프론트가 사유를 열거하지 않는다) |
| EC15 | **선택한 파일을 읽지 못함**(`file.text()` reject — 선택 후 삭제/이동, 권한 거부) | `'파일을 읽지 못했습니다. 파일을 다시 선택해 주세요.'` 표시 + `confirming` 해제. 호출부가 `void handleConfirmApply()` 라 rejection 이 삼켜지므로 try/catch 없이는 에러 표시도 없이 "적용 중..." 에서 **교착**한다(코드리뷰 CONCERN-3) |
| EC16 | **응답이 스키마와 불일치**(ZodError — 백엔드 enum 확장 등) 또는 **수신 중 네트워크 단절** | 서버는 **이미 커밋한 뒤**다. "전량 취소" 를 단언하지 않고 결과 불확실 문구 표시 + 룰 목록 invalidate(FR10 · 코드리뷰 CRITICAL-2). `useMutation` 의 `TError` 제네릭은 컴파일 타임 선언일 뿐이라 ZodError 를 막지 못한다 — 런타임 `instanceof ApiError` 로 갈라야 한다 |

## 제약 조건

1. **순수 프론트**. `apps/web/**` 만 변경. 백엔드/DB/마이그레이션 0.
2. **BC 격리**. automation BC UI만. 다른 BC 컴포넌트 수정 금지. 단 `api/client.ts` 는 전 BC 공유 인프라 — FR11로 최소 변경(현재 진행 중인 다른 PR 없음을 확인, 충돌 위험 0).
3. **권한 게이팅 범위 밖**. `projectPermissionsSchema` (`api/project-permissions.ts:21-32`)에 `MANAGE_AUTOMATION` 키가 **부재**하고, automation UI에 게이팅 선례가 **0건**이다. 사전 게이팅하려면 백엔드 권한 요약 API 확장이 선행돼야 하므로([[ui-permission-gating-needs-summary-api-exposure]]) 본 PR 범위 밖. 기존 관례대로 **런타임 403 fail-closed**. → 후속 FR 후보로 기록.
4. **YAML 파싱 금지**. 프론트는 YAML을 파싱하지 않는다(js-yaml 등 신규 의존성 도입 금지). 원문 문자열을 그대로 전달하고 검증은 전부 백엔드 도메인 파서에 위임.
5. **MSW — 신규 핸들러 파일을 만들지 않는다**. `mocks/handlers.ts` 가 이미 `automationRuleHandlers` 를 import(:69) + 스프레드(:147) 하고 있으므로, export/import 핸들러를 **기존 `automation-rule-handlers.ts` 에 추가**하면 등록 누락이 원천 불가능하다(실물 확인). 새 핸들러 **파일**을 만드는 경우에만 [[msw-global-handler-registration-gap]] 의 "import + 배열 양쪽 등록" 규칙이 적용된다 — 이번 작업은 해당 없음(plan-eng-review §6 반영: 초안이 일반 학습을 그대로 복붙해 plan 과 모순됐음).
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

✅ **통과 (1회 gap 분석 → 전량 반영)**. 적대적 sanity check 1회로 BLOCKER 1건 · CONCERN 5건 · NIT 6건 발견. **Maxi 결정 불요 — 전부 백엔드 계약/기존 관례가 정답을 강제**했다. 스펙 자체를 수정해 해소(구현 단계로 미룬 항목 0).

**사전 의심 4건은 전부 "gap 없음" 으로 확인**(스펙이 옳았음, 실물 대조).
1. `client.ts` 문자열 pass-through 회귀 — 호출자 145건 + 테스트 전수 확인. 문자열 body 0건, `client.test.ts` 는 객체(`:81`)/FormData(`:235`)만 검증 → 깨질 테스트 없음.
2. `Content-Disposition` 한글/RFC 5987 — 백엔드가 헤더 조립 **전에** `^[A-Za-z0-9_-]+$` 화이트리스트를 강제(`AutomationRuleController.kt:174,358`)하므로 파일명은 **항상 ASCII**, `filename*=` 미방출. `search.ts:223` 정규식 그대로 동작.
3. 1MiB 단위 — `file.size`(바이트) vs `rawYaml.toByteArray(UTF_8).size`(바이트) 일치.
4. 401 재시도 시 문자열 body 재전송 — `fetchOptions` 가 `client.ts:110/121` 에서 재사용되고 문자열은 불변이라 안전(`ReadableStream` 이었다면 깨졌음).
5. (보너스) nginx 조기 413 없음 — `infra/prod/nginx.conf:16` `client_max_body_size 110m`.

**해소 내역**.

| 심각도 | 발견 | 해소 |
|---|---|---|
| **BLOCKER-1** | S5(깨진 YAML)와 S8(projectKey 불일치)이 와이어에서 구별 불가(같은 `else` 분기 → 동일 status/errorCode/type, `detail` 문자열로만 다름)인데 스펙은 다른 UI를 요구 → 문자열 매칭 강요([[crossbc-failure-classification-typed-not-name]] 위반) | **분기 제거**. S5로 합류시키고 서버 `detail` 을 신뢰. S8은 에러 분기가 아닌 **Dialog 상시 도움말**로 승격 |
| CONCERN-2 | "상단 툴바"가 실재하지 않음(route `<header>` 는 h1+설명문뿐, 버튼 행은 `AutomationRuleList` 안) → 구현자가 파일 단위로 갈림 | **FR0 신설** — `AutomationRuleList.tsx:361-367` 헤더 행에 prop threading(`onExportYaml`/`onImportYaml`/`isExportingYaml`)으로 확정. Maxi 확정 시안(버튼 3개 한 줄)과 일치 |
| CONCERN-3 | `X-XSRF-TOKEN` 누락 — 형제 mutation 3종과 어긋나는 유일한 mutation이 됨(기능은 무해, JWT Bearer는 CSRF skip) | FR4에 명시 |
| CONCERN-4 | `WebhookTokenModal` 의 기존 문구/복사 실패 처리를 미언급 + 복사 실패 EC 부재 | FR8에 문구 인용 확정(재사용은 문구만, 컴포넌트는 아님) + **EC12** 신설 |
| CONCERN-5 | "닫기 2단계 확인"의 닫기 경로 미열거 — Radix는 ESC/오버레이/X/onOpenChange 4경로. 누락 시 토큰 영구 분실 | **EC7 확장** — 4경로 전부 preventDefault 명시 |
| CONCERN-6 | `AutomationImportIdConflictException`(id 타 프로젝트 귀속)이 스펙 부재. S8 사용자가 `projectKey` 만 고치면 **다음에 만나는 에러**인데, S6 렌더 규칙상 `id:` 안내가 사라짐 | **EC13** 신설 + S8 상시 도움말이 구조적으로 해결(BLOCKER-1과 동시 해소) |
| NIT-7 | import Content-Type charset 미명시(export는 명시) — Boot 기본값 암묵 의존 | `;charset=UTF-8` 명시 |
| NIT-8 | NFR4가 `conflicts` 도 "키 생략 가능"이라 부정확 서술(실제로는 항상 배열) | 서술 정정 |
| NIT-9 | FR9의 invalidate queryKey 미명명 | `AUTOMATION_RULES_QUERY_KEY(projectKey)` 명시 |
| NIT-11 | 에러 표에 500/미매핑 fallback 부재 | 행 추가 |
| NIT-12 | 용어 드리프트 — UI 정본은 "룰", 스펙은 "규칙". 새 버튼이 "룰 추가" 옆에 놓이면 한 줄에 섞임 | **FR12 신설** — 화면 문자열은 전부 "룰" |
| NIT-10 | `file.size` ≠ 전송 바이트 엣지(BOM 제거 3B 감소 / 깨진 UTF-8 → U+FFFD 팽창) | **무해 확인** — BOM은 클라가 더 엄격해지는 방향, 팽창은 서버 413이 커버(에러 표 처리) → 조치 없음 |
