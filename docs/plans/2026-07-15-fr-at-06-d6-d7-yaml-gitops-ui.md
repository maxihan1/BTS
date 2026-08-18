# FR-AT-06 D6/D7 — YAML GitOps import/export UI

> slug: fr-at-06-d6-d7-yaml-gitops-ui
> type: ui
> agent: frontend-engineer
> primary_bc: automation
> 생성: 2026-07-15

## Brief

**사용자 원문**. `fr=at-06 d6 d7 진행하자`

**작업 범위**. FR-AT-06(YAML 가져오기/내보내기 — GitOps)의 D6(프론트 UI — YAML 업로드/다운로드) + D7(E2E). 백엔드 D1~D5는 PR #272로 완료 — 이번 작업은 **순수 프론트**(`apps/web`), 백엔드/DB 변경 0.

**classify 결과**. type=ui / agent=frontend-engineer / primary_bc=automation

**소비할 백엔드 엔드포인트** (PR #272 산출물).

| 메서드 | 경로 | 비고 |
|---|---|---|
| `GET` | `/api/v1/projects/{projectKey}/automation/rules/export` | `application/yaml;charset=UTF-8` · `Content-Disposition: attachment` · 활성+비활성 전 규칙 · 결정적 순서(createdAt→id) · webhook 토큰/version/nextFireAt 미포함 |
| `POST` | `/api/v1/projects/{projectKey}/automation/rules/import` | `@RequestBody` YAML 텍스트 · UUID id 기준 upsert · atomic fail-closed · 응답 `AutomationImportResponse{created, updated, total, ruleIds, webhookTokens?, conflicts?}` · `MAX_IMPORT_RULES=500`→413 |

**선례**. FR-AT-05 D6/D7 = PR #271 (같은 BC·같은 모양의 순수 프론트 UI PR).

**완료 시 반영**. automation BC 5/7 → **6/7**. FR 총수 123 불변(D-step).

## 도메인 정리

- **BC**. automation (9번째 모듈 `com.bts.automation`, JdbcTemplate)
- **영향 엔티티(전부 기존, 실재 확인됨)**.
  - `AutomationRule` — 자동화 룰(트리거+조건+액션). `automation_rules`(V300/V302/V304). 신규 마이그레이션 0.
  - `AutomationYamlCodec` (`gitops/AutomationYamlCodec.kt:135`) — GitOps YAML 스키마 ↔ 도메인 커맨드 변환. `SCHEMA_VERSION = 1`.
  - `AutomationRulesYaml`/`YamlRule`/`YamlTrigger`/`YamlAction` (`gitops/AutomationRulesYaml.kt:22-79`) — YAML 문서 구조.
  - `AutomationRuleService.exportRules` / `.importRules` / `.analyzeProjectConflicts` — 유스케이스 3종.
- **새 용어/유비쿼터스 언어**. **없음**. UI-facing 개념(내보내기=export, 가져오기=import, 규칙 충돌=conflicts, 스키마 버전=version)은 전부 #272(FR-AT-06 백엔드) + FR-AT-04(충돌 분석)가 접지. glossary의 [[트리거]]·[[액션]] 재사용.
- **기존 결정 충돌**. 없음. 확정된 백엔드 계약 위의 순수 UI — 신규 도메인/스키마/마이그레이션/ADR 0.
- **관련 ADR**. **FR-AT-06 전용 ADR 없음**(#272가 ADR 0건으로 머지 — 확인: `docs/decisions/` 에 at-06/gitops/yaml 매칭 0건). 인접 ADR [2026-07-11-fr-at-02-automation-actions](../decisions/2026-07-11-fr-at-02-automation-actions.md)·[2026-07-13-fr-at-04-conflict-analysis](../decisions/2026-07-13-fr-at-04-conflict-analysis.md) 재사용. 신규 ADR 불요(UI 결정만).
- **grill-with-docs**. **경량 패스로 대체**. 근거 — 신규 엔티티/용어 0 + ADR 충돌 0 인 D6/D7 UI-on-settled-backend는 대화형 도메인 검증의 산출이 없음. 직전 동형 PR #271(FR-AT-05 D6/D7)이 같은 판단을 내린 선례를 따름([[bts-review-plan-autoplan-overkill]] 정신). 그 대신 아래 §백엔드 API 계약을 코드 실물 grep으로 확정해 bts-spec 입력으로 삼음.
- **BC 노트 stale 발견(본 PR 범위 밖, 미수정)**. `Maxi_wiki/BTS/domain/automation.md:27` 의 "ANTLR 4로 AQL 파서"는 실제 구현(search-export-import BC의 손수 파서 + trgm)과 어긋난 기록. 같은 파일 `:11` 의 "Export/Import (CSV, JSON, Jira XML)"도 실제로는 search-export-import BC 소관. 수동 영역이라 자동 갱신 안 함 — Maxi 확인 후 별도 정리 권장.

### 백엔드 API 계약 (코드 실재 확인 — bts-spec 입력)

`AutomationRuleController.kt` + `AutomationRuleResponses.kt` grep 확정 (#272 산출물).

| # | Method · Path | 요청 | 응답 |
|---|---|---|---|
| 1 | `GET /api/v1/projects/{projectKey}/automation/rules/export` | 없음 | 200 + **`application/yaml;charset=UTF-8`** + `Content-Disposition: attachment; filename="automation-rules-{projectKey}.yaml"` + **YAML 텍스트 본문**(`ResponseEntity<String>`) |
| 2 | `POST /api/v1/projects/{projectKey}/automation/rules/import` | **`@RequestBody` YAML 원문 텍스트**. `consumes = ["application/yaml", "application/x-yaml", "text/yaml", "text/plain"]` — **JSON/multipart 아님** | 200 + `AutomationImportResponse` (JSON) |

**★ 프론트 함정 1 — export는 `<a href download>` 불가**. BTS는 STATELESS JWT(헤더 인증)라 브라우저의 순수 네비게이션 다운로드는 Authorization 헤더가 안 실려 401. `apiFetch` → `blob()` → `URL.createObjectURL` → `a.click()` → `revokeObjectURL` 경로 필수([[avatar-auth-image-cachebust]] 동형 — 인증 이미지가 같은 이유로 blob 경로를 씀). 파일명은 서버의 `Content-Disposition` 을 파싱하거나 `automation-rules-{projectKey}.yaml` 규칙을 프론트가 재구성.

**★ 프론트 함정 2 — import Content-Type**. `consumes` 화이트리스트가 YAML/텍스트 4종만 허용 → 기본 `application/json` 으로 보내면 **415**. 파일을 `File.text()` 로 읽어 **원문 문자열**을 `Content-Type: application/yaml` 로 POST. multipart 아님(첨부파일 FR-AC-01 패턴 재사용 금지).

**DTO 필드 (Zod 계약)**.
- `AutomationImportResponse`: `created`(Int) · `updated`(Int) · `total`(Int) · `ruleIds`(UUID[], 입력 순서 보존) · **`webhookTokens`(`ImportedWebhookTokenResponse[]?`)** · **`conflicts`(`RuleConflictResponse[]?`)**
- `ImportedWebhookTokenResponse`: `ruleId`(UUID) · `name`(String) · `token`(String) — **1회 노출**, 이후 재조회 불가. 새로 **생성된** WEBHOOK 룰만(갱신은 재mint 안 함).
- `RuleConflictResponse`: `type`(ConflictType) · `severity`(ConflictSeverity) · `ruleIds`(UUID[]) · `detail`(String) — FR-AT-04 기존 스키마 재사용 가능.
- **Zod 함정([[frontend-zod-backend-dto-contract-gap]])**. 둘 다 `@JsonInclude(NON_NULL)` 라 **키 자체가 생략**될 수 있음 → `.optional()` 필요. `webhookTokens` 는 "새 WEBHOOK 룰 없음"이면 키 생략(`[]` 와 `null` 을 의도적으로 구분). `conflicts` 는 실제로는 항상 리스트(빈 배열이어도 `[]`)지만 어노테이션 공유상 방어적으로 `.optional()`.

**상한 (실측 상수)**.
- `MAX_IMPORT_BYTES = 1_048_576` (1MiB, UTF-8 바이트) → 초과 시 **413** `AUTOMATION_IMPORT_TOO_LARGE`
- `MAX_IMPORT_RULES = 500` (파싱된 규칙 수) → 초과 시 **413** 동일 코드
- export `projectKey` 화이트리스트 `^[A-Za-z0-9_-]+$` (헤더 인젝션 방어) → 위반 시 400 `AUTOMATION_RULE_INVALID`

**에러코드 (RFC 7807 ProblemDetail `errorCode`)**.

| HTTP | errorCode | 사유 |
|---|---|---|
| 401 | `AUTOMATION_UNAUTHENTICATED` | 미인증 |
| 403 | `AUTOMATION_ACCESS_DENIED` | **MANAGE_AUTOMATION 권한 없음**. import는 파싱/크기검증보다 **먼저** 판정(#272 CONCERN-2 — 오라클 차단) |
| 400 | `AUTOMATION_IMPORT_INVALID` | YAML 파싱 실패·스키마 버전 불일치(EC1) / YAML `projectKey` 불일치(EC2) / 커맨드 검증 실패(C3 — **ProblemDetail에 `failedIndex`(0-based) 프로퍼티 추가**) |
| 413 | `AUTOMATION_IMPORT_TOO_LARGE` | 본문 >1MiB 또는 규칙 수 >500 |
| 409 | `AUTOMATION_RULE_VERSION_CONFLICT` | import-update 중 OCC 충돌 |
| 400 | `AUTOMATION_MALFORMED_REQUEST` | 본문 판독 불가 |

**시맨틱 (UI 문구에 직결)**.
- import = **UUID id 기준 upsert** + **atomic fail-closed**(하나라도 실패 시 전량 롤백 — "부분 적용" 없음. UI가 이 점을 명시해야 사용자가 재시도 안전성을 이해).
- export = 활성+비활성 전 규칙(소프트삭제 제외), 결정적 순서(createdAt→id), **webhook 토큰/OCC version/nextFireAt 미포함**.
- round-trip: 동일 프로젝트 재적용은 멱등. **다른 프로젝트로 복사하려면 YAML에서 `id:` 제거** 필요(살아있는 타 프로젝트 id 재사용은 400 EC4로 거부) — UI 안내 문구 후보.

**YAML 스키마 v1 구조** (E2E fixture·샘플 문구용).

```yaml
version: 1                    # SCHEMA_VERSION, 불일치 시 400
projectKey: PROJ
rules:
  - id: <UUID?>               # 생략 시 새 규칙 생성
    name: <String ≤200>
    enabled: <Boolean>
    actorUserId: <UUID?>
    trigger: { type: <TriggerType>, config: {...} }
    condition: {...}          # nullable
    actions: [{ type: <ActionType>, config: {...} }]
```

## 스펙

전체 스펙. [docs/specs/2026-07-15-fr-at-06-d6-d7-yaml-gitops-ui.md](../specs/2026-07-15-fr-at-06-d6-d7-yaml-gitops-ui.md)

**배치 결정(Maxi 확정)**. automation 설정 페이지 `AutomationRuleList` **기존 헤더 행**에 "YAML 내보내기"/"YAML 가져오기" 버튼 2개를 "룰 추가" 옆에 추가(버튼 3개 한 줄). prop threading(`onExportYaml`/`onImportYaml`/`isExportingYaml`) — `onAddRule`/`onViewHistory` 관례 동형. 가져오기는 Radix Dialog. **라우터 변경 0**.

핵심 시나리오 요약.
- "YAML 내보내기" → 즉시 다운로드(`triggerBlobDownload` 재사용). 룰 0개여도 버튼 활성(`rules: []` 는 정당한 GitOps 선언 + 손 작성 스캐폴드).
- "YAML 가져오기" → Dialog → 파일 선택 → **인라인 2단계 확인** → 적용 → 결과(생성/갱신/총).
- 실패 시 **Dialog 유지** + 서버 `detail` + **"적용된 변경 없음(전량 취소)"** 항상 병기(atomic fail-closed). `failedIndex` 는 **+1** 해 "N번째 룰".
- 새 WEBHOOK 룰 토큰은 결과 안에 목록+복사, **닫기 4경로 전부 2단계 확인**(ESC/오버레이/X/onOpenChange — 누락 시 영구 분실).
- 타 프로젝트 복사 안내(`id:` 제거)는 에러 조건부가 아닌 **Dialog 상시 도움말**.

**핵심 계약 함정**.
- **export는 `<a href download>` 불가** — STATELESS JWT라 401. `apiFetch`→`blob()`→`triggerBlobDownload` 필수([[avatar-auth-image-cachebust]]).
- **import는 multipart 불가** — 백엔드 `consumes` 화이트리스트가 YAML/텍스트 4종만 → 415. `File.text()` 원문 + `Content-Type: application/yaml;charset=UTF-8`.
- **`client.ts` 문자열 pass-through 필요**(FR11, 유일한 공유 인프라 변경) — 현재 FormData 아닌 body를 전부 `JSON.stringify`. 기존 호출자 145건에 문자열 body 0건 전수 확인(회귀 0).
- **Zod** — `webhookTokens` 는 키가 **실제로 생략**됨 → `.optional()` 필수. `conflicts` 는 항상 배열(방어적 `.optional()` 만).
- **UI 문구 용어는 "룰"**(`labels` 정본), 문서 산문만 "규칙".

## Brainstorming Check

✅ **통과 (1회 gap 분석 → 전량 스펙 반영)**. BLOCKER 1 · CONCERN 5 · NIT 6 발견, **Maxi 결정 불요**(백엔드 계약/기존 관례가 정답 강제), 구현 단계로 미룬 항목 0.

- **BLOCKER-1**. S5(깨진 YAML)/S8(projectKey 불일치)이 **와이어에서 구별 불가**(컨트롤러 같은 `else` 분기 → status/errorCode/type 동일, `detail` 문자열로만 다름). 구별하려면 메시지 문자열 매칭 = [[crossbc-failure-classification-typed-not-name]] 위반. → **UI 분기 제거**(서버 `detail` 신뢰) + S8 안내를 **상시 도움말**로 승격(CONCERN-6 동시 해소 — 타 프로젝트 사용자는 에러를 두 번 만나는데 정작 안내가 필요한 두 번째에서 사라지는 구조였음).
- **CONCERN-2**. "상단 툴바"가 실재하지 않아 구현자가 파일 단위로 갈릴 위험 → FR0으로 파일:라인 확정.
- 나머지(XSRF · 토큰 문구 재사용 · Radix 닫기 4경로 · charset · queryKey · 용어)는 스펙에 한 줄씩 못박음.
- **사전 의심 4건은 전부 gap 없음**(실물 대조) — `client.ts` 회귀 0(호출자+테스트 전수) · `Content-Disposition` 항상 ASCII(백엔드 화이트리스트가 헤더 조립 전 강제) · 1MiB 단위 일치 · 401 재시도 시 문자열 body 재전송 안전.

## Plan

> **Goal**. 자동화 룰을 YAML로 내보내고 올려 반영하는 GitOps UI를 automation 설정 화면에 붙인다.
> **Architecture**. 확정된 백엔드(#272) 계약 위의 순수 프론트. `AutomationRuleList` 헤더 행에 버튼 2개(prop threading), 가져오기는 신규 Radix Dialog. `client.ts` 에 문자열 body pass-through 1건 추가가 유일한 공유 인프라 변경.
> **Tech Stack**. React 19 · TypeScript strict · Zod · TanStack Query · Radix Dialog · MSW · Playwright · vitest.

### 파일 구조 (신규/수정)

| 파일 | 책임 | T |
|---|---|---|
| `apps/web/src/api/client.ts` (수정) | 문자열 body를 `JSON.stringify` 없이 통과 | T1 |
| `apps/web/src/api/automation-rules.types.ts` (수정) | `automationImportResponseSchema` · `importedWebhookTokenSchema` 추가 | T2 |
| `apps/web/src/api/automation-rules.ts` (수정) | `exportAutomationRulesYaml` · `importAutomationRulesYaml` 추가 | T3 |
| `apps/web/src/mocks/automation-rule-handlers.ts` (수정) | export/import MSW 핸들러 — **기존 파일 확장이라 `handlers.ts` 등록 불요** | T4 |
| `apps/web/src/mocks/automation-rule-fixtures.ts` (수정) | YAML 픽스처 + 시나리오 토글 키 | T4 |
| `apps/web/src/components/automation/AutomationYamlImportDialog.tsx` (신규) | 파일 선택 → 2단계 확인 → 결과/에러/토큰 | T5 |
| `apps/web/src/components/automation/AutomationRuleList.tsx` (수정) | 헤더 행 버튼 2개 + prop 3종 | T6 |
| `apps/web/src/routes/projects.$projectKey.settings.automation.tsx` (수정) | export mutation + Dialog 상태 조립 | T7 |
| `apps/web/e2e/automation-yaml-gitops.spec.ts` (신규) | D7 E2E | T8 |
| `docs/plan/product/automation.md` (수정) | D6/D7 체크박스 + 완료 노트 | T9 |

> **★ MSW 등록 함정 구조적 회피**. `mocks/handlers.ts` 는 이미 `automationRuleHandlers` 를 import(:69) + 스프레드(:147) 하고 있다. 신규 핸들러를 **기존 `automation-rule-handlers.ts` 에 추가**하면 등록 누락이 원천 불가능하다([[msw-global-handler-registration-gap]] 회피). 새 핸들러 파일을 만들지 **않는다**.

---

### Task 1. `client.ts` 문자열 body pass-through (FR11 · NFR5)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/client.ts`, `apps/web/src/api/client.test.ts`]
- depends-on: []

**RED**. `apps/web/src/api/client.test.ts` 에 3 테스트 추가.

```ts
it('문자열 body는 JSON.stringify 없이 원문 그대로 전송한다', async () => {
  const yaml = 'version: 1\nprojectKey: PROJ\nrules: []\n'
  const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }))
  await apiFetch('/api/v1/x', { method: 'POST', body: yaml, headers: { 'Content-Type': 'application/yaml' } })
  const init = fetchSpy.mock.calls[0]?.[1]
  expect(init?.body).toBe(yaml)              // JSON.stringify 였다면 따옴표로 감싸였을 것
})

it('문자열 body에 Content-Type: application/json 을 자동 부여하지 않는다', async () => {
  const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }))
  await apiFetch('/api/v1/x', { method: 'POST', body: 'plain text' })
  const headers = new Headers(fetchSpy.mock.calls[0]?.[1]?.headers)
  expect(headers.get('content-type')).toBeNull()
})

it('객체 body는 기존대로 JSON.stringify + application/json 을 유지한다 (회귀 가드)', async () => {
  const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }))
  await apiFetch('/api/v1/x', { method: 'POST', body: { a: 1 } })
  const init = fetchSpy.mock.calls[0]?.[1]
  expect(init?.body).toBe('{"a":1}')
  expect(new Headers(init?.headers).get('content-type')).toBe('application/json')
})
```

**실패 메시지(예상)**. 1번은 `expected '"version: 1\\nprojectKey: PROJ\\nrules: []\\n"' to be 'version: 1...'`(JSON.stringify 됨). 2번은 `expected 'application/json' to be null`.

**GREEN**. `client.ts:88` 과 `:93`, `:107` 세 곳.

```ts
// :88 — 직렬화 없이 그대로 보낼 body 타입(FormData → +문자열)
const isRawBody = body instanceof FormData || typeof body === 'string'

// :93 — 자동 JSON Content-Type은 raw body가 아닐 때만
if (body !== undefined && !isRawBody && !headers.has('content-type')) {
  headers.set('Content-Type', 'application/json')
}

// :107
body: body !== undefined ? (isRawBody ? body : JSON.stringify(body)) : undefined,
```

**REFACTOR**. `client.ts:73-79` KDoc 갱신 — "body 있으면 Content-Type: application/json 자동 설정" → "**객체** body면 `JSON.stringify` + `application/json` 자동 설정. FormData/문자열 body는 직렬화·Content-Type 자동 설정을 모두 건너뛴다(호출자가 Content-Type 지정)". `isFormData` 지역 변수는 `isRawBody` 로 대체되며 다른 참조가 없는지 확인.

**검증**. `node_modules/.bin/vitest run src/api/client.test.ts`
→ 신규 3 PASS + 기존 client.test.ts 전량 PASS(회귀 0).

---

### Task 2. import 응답 Zod 스키마 (NFR4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-rules.types.ts`, `apps/web/src/api/automation-rules.types.test.ts`]
- depends-on: []

**RED**. `automation-rules.types.test.ts` 에 추가.

```ts
describe('automationImportResponseSchema', () => {
  const base = { created: 1, updated: 2, total: 3, ruleIds: ['550e8400-e29b-41d4-a716-446655440000'] }

  it('webhookTokens 키가 생략된 응답을 파싱한다 (새 WEBHOOK 룰 없음 — 백엔드가 키를 뺀다)', () => {
    const parsed = automationImportResponseSchema.parse({ ...base, conflicts: [] })
    expect(parsed.webhookTokens).toBeUndefined()
  })

  it('webhookTokens 를 파싱한다', () => {
    const parsed = automationImportResponseSchema.parse({
      ...base,
      conflicts: [],
      webhookTokens: [{ ruleId: '550e8400-e29b-41d4-a716-446655440000', name: '웹훅 룰', token: 'secret-token' }],
    })
    expect(parsed.webhookTokens?.[0]?.token).toBe('secret-token')
  })

  it('conflicts 는 기존 ruleConflictResponseSchema 를 재사용한다', () => {
    const parsed = automationImportResponseSchema.parse({
      ...base,
      conflicts: [{ type: 'CYCLE', severity: 'WARNING', ruleIds: ['550e8400-e29b-41d4-a716-446655440000'], detail: '순환' }],
    })
    expect(parsed.conflicts?.[0]?.type).toBe('CYCLE')
  })
})
```

**실패 메시지(예상)**. `automationImportResponseSchema is not defined`.

**GREEN**. `automation-rules.types.ts` 끝에 추가.

```ts
/**
 * import 로 새로 생성된 WEBHOOK 룰의 1회 노출 토큰 — backend `ImportedWebhookTokenResponse` 1:1 대응
 * (FR-AT-06 D6). 갱신된 WEBHOOK 룰은 토큰을 재발급하지 않아 여기 담기지 않는다.
 */
export const importedWebhookTokenSchema = z.object({
  ruleId: z.string().uuid(),
  name: z.string(),
  token: z.string(),
})

/**
 * YAML import 응답 Zod 스키마 — backend `AutomationImportResponse` 1:1 대응 (FR-AT-06 D6).
 *
 * `webhookTokens` 는 새로 생성된 WEBHOOK 룰이 없으면 **키 자체가 생략**된다(백엔드가
 * `ifEmpty { null }` + `@JsonInclude(NON_NULL)`) → `.optional()` 필수. `conflicts` 는 같은
 * 어노테이션을 공유하지만 실제로는 항상 배열(빈 배열이어도 `[]`)이라 생략을 기대하지 않는다 —
 * 방어적으로만 `.optional()`.
 */
export const automationImportResponseSchema = z.object({
  created: z.number().int(),
  updated: z.number().int(),
  total: z.number().int(),
  ruleIds: z.array(z.string().uuid()),
  webhookTokens: z.array(importedWebhookTokenSchema).optional(),
  conflicts: z.array(ruleConflictResponseSchema).optional(),
})

export type ImportedWebhookToken = z.infer<typeof importedWebhookTokenSchema>
export type AutomationImportResponse = z.infer<typeof automationImportResponseSchema>
```

**REFACTOR**. 없음(신규 스키마 2개, 기존 `ruleConflictResponseSchema` 재사용).

**검증**. `node_modules/.bin/vitest run src/api/automation-rules.types.test.ts` → 신규 3 PASS.

---

### Task 3. export/import API 함수 (FR1 · FR4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-rules.ts`, `apps/web/src/api/automation-rules.test.ts`]
- depends-on: [1, 2]

**RED**. `automation-rules.test.ts` 에 추가.

```ts
describe('exportAutomationRulesYaml', () => {
  it('Content-Disposition 에서 파일명을 파싱하고 blob 을 반환한다', async () => {
    server.use(http.get('*/projects/PROJ/automation/rules/export', () =>
      new HttpResponse('version: 1\n', {
        status: 200,
        headers: {
          'Content-Type': 'application/yaml;charset=UTF-8',
          'Content-Disposition': 'attachment; filename="automation-rules-PROJ.yaml"',
        },
      })))
    const { blob, filename } = await exportAutomationRulesYaml('PROJ')
    expect(filename).toBe('automation-rules-PROJ.yaml')
    expect(await blob.text()).toBe('version: 1\n')
  })

  it('Content-Disposition 이 없으면 projectKey 기반 기본 파일명을 쓴다', async () => {
    server.use(http.get('*/projects/PROJ/automation/rules/export', () =>
      new HttpResponse('version: 1\n', { status: 200 })))
    const { filename } = await exportAutomationRulesYaml('PROJ')
    expect(filename).toBe('automation-rules-PROJ.yaml')
  })

  it('403 이면 ApiError 를 throw 한다', async () => {
    server.use(http.get('*/projects/PROJ/automation/rules/export', () =>
      HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 })))
    await expect(exportAutomationRulesYaml('PROJ')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('importAutomationRulesYaml', () => {
  it('YAML 원문을 application/yaml + X-XSRF-TOKEN 으로 전송하고 응답을 파싱한다', async () => {
    let seenBody = ''
    let seenContentType: string | null = null
    let seenXsrf: string | null = null
    server.use(http.post('*/projects/PROJ/automation/rules/import', async ({ request }) => {
      seenBody = await request.text()
      seenContentType = request.headers.get('content-type')
      seenXsrf = request.headers.get('x-xsrf-token')
      return HttpResponse.json({ created: 1, updated: 0, total: 1, ruleIds: ['550e8400-e29b-41d4-a716-446655440000'], conflicts: [] })
    }))
    const yaml = 'version: 1\nprojectKey: PROJ\nrules: []\n'
    const result = await importAutomationRulesYaml('PROJ', yaml)
    expect(seenBody).toBe(yaml)                                  // 원문 그대로 (JSON 이중 인코딩 아님)
    expect(seenContentType).toContain('application/yaml')        // multipart/json 이면 백엔드 415
    expect(seenXsrf).not.toBeNull()                              // 형제 mutation 3종과 동일
    expect(result.created).toBe(1)
  })

  it('400 이면 ApiError 를 throw 한다 (failedIndex 는 body 에 보존)', async () => {
    server.use(http.post('*/projects/PROJ/automation/rules/import', () =>
      HttpResponse.json({ errorCode: 'AUTOMATION_IMPORT_INVALID', detail: '조건식 위반', failedIndex: 2 }, { status: 400 })))
    await expect(importAutomationRulesYaml('PROJ', 'version: 1\n')).rejects.toBeInstanceOf(ApiError)
  })
})
```

**실패 메시지(예상)**. `exportAutomationRulesYaml is not a function`.

**GREEN**. `automation-rules.ts` 에 추가(`import { automationImportResponseSchema } from './automation-rules.types'` 및 타입 re-export 포함).

```ts
/** exportAutomationRulesYaml 반환값 */
export interface ExportAutomationRulesYamlResult {
  /** YAML 본문 Blob */
  blob: Blob
  /** Content-Disposition 헤더에서 파싱한 파일명 */
  filename: string
}

/**
 * GET /api/v1/projects/{projectKey}/automation/rules/export — 전 룰(활성+비활성)을 GitOps YAML로 내보낸다.
 *
 * `exportIssues`(search.ts) 패턴 복제 — apiFetch → non-ok 시 ApiError throw → ok면 res.blob().
 * STATELESS JWT라 `<a href download>` 순수 네비게이션은 401이 된다(Authorization 헤더 미첨부) —
 * 반드시 이 함수로 blob 을 받아 `triggerBlobDownload` 에 넘긴다. CSRF/credentials/401-refresh 는
 * apiFetch 가 처리(raw fetch 금지).
 *
 * @param projectKey 프로젝트 키
 * @returns { blob, filename }
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function exportAutomationRulesYaml(projectKey: string): Promise<ExportAutomationRulesYamlResult> {
  const res = await apiFetch(`${basePath(projectKey)}/export`)
  await throwIfNotOk(res)
  // Content-Disposition: attachment; filename="automation-rules-PROJ.yaml"
  const contentDisposition = res.headers.get('content-disposition') ?? ''
  const filename = /filename="([^"]+)"/.exec(contentDisposition)?.[1] ?? `automation-rules-${projectKey}.yaml`
  const blob = await res.blob()
  return { blob, filename }
}

/**
 * POST /api/v1/projects/{projectKey}/automation/rules/import — GitOps YAML을 올려 룰을 일괄 upsert 한다.
 *
 * 백엔드가 `@RequestBody` + `consumes = [application/yaml, application/x-yaml, text/yaml, text/plain]`
 * 이라 **multipart 는 415** 다 — YAML 원문 문자열을 그대로 보낸다(`client.ts` 의 문자열 pass-through 경로).
 * charset 명시는 export 대칭(미명시 시 컨버터 기본 charset 암묵 의존).
 * 원자성은 백엔드가 보장 — 하나라도 실패하면 전량 롤백이라 부분 적용이 없다.
 *
 * @param projectKey 프로젝트 키
 * @param yamlText YAML 원문(파일에서 `File.text()` 로 읽은 문자열)
 * @returns created/updated/total/ruleIds + (있으면) webhookTokens/conflicts
 * @throws ApiError(400, AUTOMATION_IMPORT_INVALID) YAML/스키마버전/projectKey 불일치/커맨드 검증 실패 시
 *   (커맨드 실패면 body 에 0-based `failedIndex` 포함)
 * @throws ApiError(413, AUTOMATION_IMPORT_TOO_LARGE) 본문 1MiB 초과 또는 룰 500개 초과 시
 * @throws ApiError(409, AUTOMATION_RULE_VERSION_CONFLICT) 동시 수정 충돌 시
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function importAutomationRulesYaml(
  projectKey: string,
  yamlText: string,
): Promise<AutomationImportResponse> {
  const res = await apiFetch(`${basePath(projectKey)}/import`, {
    method: 'POST',
    body: yamlText,
    headers: { 'Content-Type': 'application/yaml;charset=UTF-8', 'X-XSRF-TOKEN': readXsrfToken() },
  })
  await throwIfNotOk(res)
  const raw: unknown = await res.json()
  return automationImportResponseSchema.parse(raw)
}
```

**REFACTOR**. `extractAutomationRuleErrorCode` 옆에 `extractAutomationImportFailedIndex(error): number | null` 추가(ApiError body 의 `failedIndex` 를 number 로 안전 추출). FR10의 "+1 표시"는 T5가 이 헬퍼를 소비.

**검증**. `node_modules/.bin/vitest run src/api/automation-rules.test.ts` → 신규 5 PASS + 기존 전량 PASS.

---

### Task 4. MSW 핸들러 + 픽스처 (기존 파일 확장)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/automation-rule-handlers.ts`, `apps/web/src/mocks/automation-rule-fixtures.ts`, `apps/web/src/mocks/automation-rule-handlers.test.ts`]
- depends-on: [2]

**RED**. `automation-rule-handlers.test.ts` 에 추가 — export가 YAML+Content-Disposition을 주는지, import가 created/updated를 세는지, 시나리오 토글 3종이 각각 400(failedIndex)·413·토큰 노출을 재현하는지.

```ts
it('export 핸들러는 application/yaml 과 Content-Disposition 을 준다', async () => {
  const res = await fetch('/api/v1/projects/PROJ/automation/rules/export')
  expect(res.headers.get('content-type')).toContain('application/yaml')
  expect(res.headers.get('content-disposition')).toContain('automation-rules-PROJ.yaml')
  expect(await res.text()).toContain('version: 1')
})

it('import 핸들러는 YAML 원문을 받아 created/updated 를 센다', async () => {
  const res = await fetch('/api/v1/projects/PROJ/automation/rules/import', {
    method: 'POST', headers: { 'Content-Type': 'application/yaml' },
    body: 'version: 1\nprojectKey: PROJ\nrules:\n  - name: 새 룰\n    trigger: { type: ISSUE_CREATED, config: {} }\n    actions: []\n',
  })
  const body = await res.json()
  expect(body.created).toBe(1)
  expect(body.total).toBe(1)
})

it('IMPORT_FAILED_INDEX 시나리오는 400 + failedIndex 를 준다', async () => {
  window.localStorage.setItem(SCENARIO_KEY.IMPORT_FAILED_INDEX, 'true')
  const res = await fetch('/api/v1/projects/PROJ/automation/rules/import', {
    method: 'POST', headers: { 'Content-Type': 'application/yaml' }, body: 'version: 1\n',
  })
  expect(res.status).toBe(400)
  const body = await res.json()
  expect(body.errorCode).toBe('AUTOMATION_IMPORT_INVALID')
  expect(body.failedIndex).toBe(2)
})
```

**GREEN**.
- `automation-rule-fixtures.ts` 의 기존 `SCENARIO_KEY`(:29-34)에 **4키** 추가 — `IMPORT_FAILED_INDEX: 'msw:automation-rule:import-failed-index'` · `IMPORT_TOO_LARGE: 'msw:automation-rule:import-too-large'` · `IMPORT_WEBHOOK_TOKENS: 'msw:automation-rule:import-webhook-tokens'` · **`IMPORT_VERSION_CONFLICT: 'msw:automation-rule:import-version-conflict'`**(409, S9 — plan-eng-review §8). 네이밍은 기존 `msw:<bc>-<entity>:<scenario>` 관례.
- 409 시나리오는 백엔드 실물 문구를 그대로 미러 — `{ errorCode: 'AUTOMATION_RULE_VERSION_CONFLICT', detail: '다른 변경이 먼저 반영되었습니다. 최신 정보를 다시 불러온 뒤 시도해 주세요.' }` (`AutomationRuleController.kt:546`). T5가 이 detail 을 **그대로 표시**하는지 검증하는 데 쓴다(프론트 고정문구 금지 확인).
- 유효 YAML 픽스처 상수 `VALID_GITOPS_YAML` 추가(스펙 §YAML 스키마 v1 구조 그대로 — `version: 1` / `projectKey: PROJ` / `rules:` 1건).
- `automation-rule-handlers.ts` 에 `http.get('*/projects/:projectKey/automation/rules/export')` + `http.post('*/projects/:projectKey/automation/rules/import')` 2 핸들러 추가. import는 요청 본문의 `rules:` 항목 수를 세어 `created`/`total` 산출(정교한 YAML 파싱 불요 — 줄 단위 카운트). 시나리오 토글 3종 분기. **`handlers.ts` 는 건드리지 않는다**(기존 `automationRuleHandlers` 스프레드에 자동 포함).

**REFACTOR**. 시나리오 분기가 3개로 늘어 `if` 사슬이 깊어지면 `resolveImportScenario(): 'ok' | 'failedIndex' | 'tooLarge' | 'webhookTokens'` 로 추출.

**검증**. `node_modules/.bin/vitest run src/mocks/automation-rule-handlers.test.ts` → 신규 5 PASS.

---

### Task 5. `AutomationYamlImportDialog` (FR2~FR10 · FR12 · EC1~EC14)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationYamlImportDialog.tsx`, `apps/web/src/components/automation/AutomationYamlImportDialog.test.tsx`]
- depends-on: [3, 4]

> **depends-on 에 4 추가**(plan-eng-review §3). 이 Dialog 테스트는 같은 BC의 모든 Dialog 테스트 관례대로 `automationRuleHandlers` 를 소비하고 T4의 시나리오 키(`SCENARIO_KEY.IMPORT_*`)를 쓴다. wave 결과는 동일(W3)하나 메타 정확성 문제.

**RED**. `AutomationYamlImportDialog.test.tsx` — 시나리오 15종.

```ts
it('파일 미선택이면 적용 버튼이 disabled 다 (EC1)', ...)
it('파일 input 을 접근 가능한 이름으로 찾을 수 있다 (a11y)', ...)      // getByLabelText('YAML 파일')
it('1MiB 초과 파일은 요청 없이 즉시 차단한다 (S7-a/EC3)', ...)          // importAutomationRulesYaml 미호출 spy
it('적용 → 2단계 확인("확정") → 성공 시 생성/갱신/총 을 표시한다 (S4)', ...)
it('적용 진행 중이면 확정 버튼이 disabled + "적용 중..." 이다 (EC5)', ...)
it('failedIndex 를 +1 해 "3번째 룰" 로 표시하고 전량취소를 병기한다 (S6/FR10)', ...)
it('failedIndex 없는 400 은 서버 detail + 전량취소만 표시한다 (S5)', ...)
it('409 도 서버 detail + 전량취소로 표시한다 — 프론트 고정문구 없음 (S9)', ...)
it('detail 이 없는 실패는 fallback 문구 + 전량취소를 표시한다 (NIT-11)', ...)
it('타 프로젝트 복사 안내를 에러와 무관하게 항상 표시한다 (S8 상시 도움말)', ...)
it('conflicts 를 인라인 경고로 표시한다 — 중첩 모달 없이 (FR7)', ...)
it('conflicts 가 빈 배열이면 경고 영역을 렌더하지 않는다 (EC10)', ...)
it('webhookTokens 를 결과 카운트보다 먼저 렌더한다 (정보 계층)', ...)   // DOM 순서 검증
it('webhookTokens 를 목록+복사로 표시하고 복사 실패 문구를 낸다 (S10/EC12)', ...)
it('토큰을 localStorage/sessionStorage 에 기록하지 않는다 (NFR3)', ...)  // 네거티브
it('webhookTokens 키가 생략되면 토큰 영역을 렌더하지 않는다 (EC11)', ...)
it('토큰 노출 중 ESC/오버레이/X 모두 2단계 확인을 거친다 (EC7)', ...)
it('open 이 false→true 로 재전환하면 파일·결과·에러 상태가 초기화된다 (EC8)', ...)
```

> **★ EC8 이 T7 → T5 로 이관**(plan-eng-review §7, **구조 결함 해소**). 초안은 EC8(Dialog 재오픈 시 상태 초기화) 테스트를 T7에 두면서 정작 리셋 로직은 `AutomationYamlImportDialog.tsx` 안에 있어야 한다고 서술했다 — 그 파일은 **T5의 files 선언에만** 있어서, T7 구현자가 병렬 wave 규약("선언 외 파일 수정 금지 → BLOCKED 보고")을 지키면 **실행 중단**된다. 리셋은 Dialog 자신의 책임이므로 구현·테스트 모두 T5 소유. T7은 route 레벨 black-box 관측만 남긴다.

**GREEN**. `radix-ui` 직접 사용(`WebhookTokenModal.tsx` 동형 — `components/ui` 에 Dialog 래퍼 부재). 파일 상단 한국어 L1 주석 필수.

핵심 골격.
```tsx
// 자동화 룰 GitOps YAML 업로드 Dialog — 파일 선택 → 2단계 확인 → 결과/에러/토큰 1회 노출 (FR-AT-06 D6)
const MAX_IMPORT_BYTES = 1_048_576   // 백엔드 MAX_IMPORT_BYTES 미러 — 선제 차단(S7-a)

const labels = {
  title: 'YAML 가져오기',
  fileInputLabel: 'YAML 파일',
  // 상시 도움말(S8) — 에러 분기에 매달지 않는다(BLOCKER-1 해소)
  copyHint: '다른 프로젝트의 룰을 복사하려면 YAML에서 id: 줄을 제거하세요. 같은 프로젝트에 다시 적용하는 경우에는 그대로 두면 됩니다.',
  applyButton: '적용',
  applyingButton: '적용 중...',                 // 로딩 라벨(design-review §1) — ImportMappingWizard.tsx:387 선례
  confirmWarning: '적용하면 기존 룰이 덮어쓰일 수 있습니다.',
  confirmButton: '확정',                        // RuleExecutionTraceRow.tsx:26 과 통일(design-review §5)
  cancelButton: '취소',
  rollbackNote: '적용된 변경이 없습니다(전량 취소).',
  genericFailure: '가져오기에 실패했습니다.',   // detail 부재 시 fallback(NIT-11)
  tooLarge: '파일이 너무 큽니다(최대 1MiB).',
  // WebhookTokenModal.tsx:12-17 문구 그대로 (CONCERN-4)
  tokenWarning: '이 토큰은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없습니다.',
  copyButton: '복사', copiedLabel: '복사됨',
  copyFailed: '복사에 실패했습니다. 직접 선택해 복사해 주세요.',
  closeConfirm: '토큰은 다시 볼 수 없습니다. 닫을까요?',
} as const
```

- `useMutation({ mutationFn: (yamlText) => importAutomationRulesYaml(projectKey, yamlText) })`.
- 성공 `onSuccess` → `queryClient.invalidateQueries({ queryKey: AUTOMATION_RULES_QUERY_KEY(projectKey) })` (FR9).
- **에러 표시는 단일 규칙**(errorCode 분기 없음 — spec §에러). `failedIndex` 있으면 `${failedIndex + 1}번째 룰에서 실패했습니다. ` 접두 + `detail ?? genericFailure` + **항상 `rollbackNote` 병기**. `extractAutomationImportFailedIndex`(T3 REFACTOR 산출) 사용. `extractAutomationRuleErrorCode` 는 **로깅/E2E 식별용**으로만.
- **인라인 확인 박스는 `RuleExecutionTraceRow.tsx:192-199` 구성 그대로**(design-review §5) — `<div className="space-y-2 rounded-md border border-destructive/20 bg-destructive/5 p-3">` + 확정 `<Button variant="destructive" size="sm" disabled={isPending}>` + 취소 `<Button variant="outline" size="sm">`. 중첩 Dialog 금지.
- **렌더 순서**(design-review §2) — ① `webhookTokens`(있으면) → ② 결과 카운트(생성/갱신/총) → ③ `conflicts`(있으면). 토큰이 가장 되돌릴 수 없는 정보라 최상단.
- **`role="alert"`**(design-review §3b) — 토큰 경고 영역과 conflicts 경고 영역 둘 다. `WebhookTokenModal`/`RuleConflictWarningModal` 선례 동형.
- 토큰 노출 중이면 `<Dialog.Content onEscapeKeyDown={guard} onPointerDownOutside={guard}>` + `onOpenChange` 가드로 **4경로 전부** 확인 절차(EC7).
- **EC8 리셋** — `useEffect(() => { if (open) resetState() }, [open])` 로 `open` false→true 전환에서 파일·결과·에러 초기화. `key` prop 재마운트 방식 금지([[react-usestate-stale-key-prop]]).
- 파일 읽기 `await file.text()`.
- **NFR3** — 토큰은 `useState` 로만 보유. storage/URL/로그 기록 금지.

**REFACTOR**. 토큰 목록 행을 `ImportedTokenRow` 지역 컴포넌트로 분리(복사 상태·실패 문구를 행 단위 소유 — `WebhookTokenModal` 의 단건 로직을 목록으로 확장).

**검증**. `node_modules/.bin/vitest run src/components/automation/AutomationYamlImportDialog.test.tsx` → 신규 18 PASS.

> **리스크(낮음)**. `File.prototype.text()` 는 설치된 jsdom 29.1.1 에서 **정상 동작 확인됨**(plan-eng-review §5 — `Blob-impl.js` 에 구현, `File-impl` 이 상속). 만에 하나 막히면 테스트에서 스텁하지 말고 **prod 코드를 `await new Response(file).text()`** 로 바꾼다(브라우저·jsdom 양쪽 지원).

---

### Task 6. `AutomationRuleList` 헤더 버튼 2개 (FR0 · FR12)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationRuleList.tsx`, `apps/web/src/components/automation/AutomationRuleList.test.tsx`]
- depends-on: []

**RED**. `AutomationRuleList.test.tsx` 에 추가.

> **★ 테스트 구조 정정**(plan-eng-review §2). 이 파일에는 **`baseProps` 객체가 없다**. 실제 구조는 `function renderList(onAddRule = vi.fn(), onEditRule = vi.fn(), onViewHistory = vi.fn())` 팩토리(`:60`)이고 **호출부가 13곳**이다. 따라서 신규 prop 3종은 **`renderList` 시그니처에 기본값과 함께 추가**한다 — 그러면 기존 13개 호출부를 **한 곳도 고치지 않아도** 된다.

```ts
// :60 시그니처 확장 — 기본값을 주므로 기존 13개 호출부 무수정
function renderList(
  onAddRule = vi.fn(), onEditRule = vi.fn(), onViewHistory = vi.fn(),
  onExportYaml = vi.fn(), onImportYaml = vi.fn(), isExportingYaml = false,
) { /* ... <AutomationRuleList ... onExportYaml={onExportYaml} onImportYaml={onImportYaml} isExportingYaml={isExportingYaml} /> */ }

it('YAML 내보내기/가져오기 버튼을 렌더하고 클릭 시 콜백을 호출한다', async () => {
  const onExportYaml = vi.fn(); const onImportYaml = vi.fn()
  renderList(vi.fn(), vi.fn(), vi.fn(), onExportYaml, onImportYaml)
  await userEvent.click(screen.getByTestId('automation-yaml-export-button'))
  await userEvent.click(screen.getByTestId('automation-yaml-import-button'))
  expect(onExportYaml).toHaveBeenCalledOnce()
  expect(onImportYaml).toHaveBeenCalledOnce()
})

it('내보내기 진행 중이면 버튼이 disabled + "내보내는 중..." 이다 (EC6)', () => {
  renderList(vi.fn(), vi.fn(), vi.fn(), vi.fn(), vi.fn(), true)
  const button = screen.getByTestId('automation-yaml-export-button')
  expect(button).toBeDisabled()
  expect(button).toHaveTextContent('내보내는 중...')
})
```

**GREEN**. `AutomationRuleListProps`(:86-95)에 **필수** prop 3종 추가(실 소비처가 `routes/projects.$projectKey.settings.automation.tsx:103` + 테스트 팩토리 2곳뿐 — 둘 다 이번 PR 범위 T7/T6이 갱신하므로 required 가 타당). `labels`(:22)에 `exportYamlButton: 'YAML 내보내기'` · `exportingYamlButton: '내보내는 중...'` · `importYamlButton: 'YAML 가져오기'` 추가.

헤더 행(:362)에 **반응형 대비 필수**(design-review §7). 현재 `flex items-center justify-between` 은 wrap/stack이 없어, 페이지 컨테이너가 `max-w-2xl p-8`(route `:95`)이라 모바일에서 헤딩+버튼 3개가 넘친다. 같은 파일의 `AutomationRuleRow`(`:197`)가 이미 `flex flex-col gap-2 ... sm:flex-row sm:items-center sm:justify-between` 패턴을 쓰므로 **동형 적용**.

```tsx
<div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
  <h2 className="text-base font-semibold">{labels.heading}</h2>
  <div className="flex flex-wrap items-center gap-2">
    <Button size="sm" data-testid="automation-rule-add-button" onClick={onAddRule}>{labels.addButton}</Button>
    <Button size="sm" variant="outline" data-testid="automation-yaml-export-button"
            disabled={isExportingYaml} onClick={onExportYaml}>
      {isExportingYaml ? labels.exportingYamlButton : labels.exportYamlButton}
    </Button>
    <Button size="sm" variant="outline" data-testid="automation-yaml-import-button"
            onClick={onImportYaml}>{labels.importYamlButton}</Button>
  </div>
</div>
```
위계 — primary 는 "룰 추가" 1개 유지, 신규 2개는 `variant="outline"`(DESIGN.md §2 "페이지당 핵심 CTA 1개" 준수, design-review §4 확인).

**REFACTOR**. 없음(기존 구조 유지, 버튼 그룹화 + 반응형만).

**검증**. `node_modules/.bin/vitest run src/components/automation/AutomationRuleList.test.tsx` → 신규 2 PASS + **기존 13개 renderList 호출부 무수정 PASS**(기본값 덕분).

---

### Task 7. 페이지 조립 (FR0 · FR1 · S1~S3)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.automation.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`]
- depends-on: [3, 5, 6]

**RED**. route 테스트에 추가.

```ts
it('내보내기 클릭 → blob 다운로드를 트리거하고 성공 토스트를 낸다 (S1)', async () => {
  const spy = vi.spyOn(downloadLib, 'triggerBlobDownload').mockImplementation(() => {})
  render(<ProjectAutomationSettingsPage projectKey="PROJ" />, { wrapper })
  await userEvent.click(await screen.findByTestId('automation-yaml-export-button'))
  await waitFor(() => expect(spy).toHaveBeenCalledWith(expect.any(Blob), 'automation-rules-PROJ.yaml'))
})

it('내보내기 403 이면 권한 없음 토스트를 낸다 (S3)', ...)
it('가져오기 클릭 → Dialog 가 열린다 (S4)', ...)
```

> **EC8 은 T5 소유**(plan-eng-review §7). 재오픈 상태 초기화는 Dialog 내부 책임이라 T5가 구현·검증한다. T7은 `open` 상태 전달만 담당 — route 테스트에서 리셋을 재검증하지 않는다(T7의 files 밖 파일을 건드리게 되는 구조 결함 회피).

**GREEN**. 페이지에 상태 1개(`yamlImportOpen`) + export mutation 추가.

```tsx
const exportMutation = useMutation({
  mutationFn: () => exportAutomationRulesYaml(projectKey),
  onSuccess: ({ blob, filename }) => { triggerBlobDownload(blob, filename); toast.success('YAML을 내보냈습니다.') },
  onError: (error) => {
    const code = extractAutomationRuleErrorCode(error)
    toast.error(code === 'AUTOMATION_ACCESS_DENIED' ? '권한이 없습니다.' : 'YAML 내보내기에 실패했습니다.')
  },
})
```
`<AutomationRuleList onExportYaml={() => exportMutation.mutate()} isExportingYaml={exportMutation.isPending} onImportYaml={() => setYamlImportOpen(true)} ... />` + `<AutomationYamlImportDialog open={yamlImportOpen} onOpenChange={setYamlImportOpen} projectKey={projectKey} />`.

**REFACTOR**. 토스트 문구를 페이지 `labels` 상수로(기존 페이지 관례 확인 후 정렬).

**검증**. `node_modules/.bin/vitest run src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx` → 신규 3 PASS + 기존 전량 PASS.

---

### Task 8. E2E (D7)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/automation-yaml-gitops.spec.ts`]
- depends-on: [4, 7]

**RED→GREEN**. 신규 spec 4 시나리오(스펙 §완료 기준). 기존 `automation-execution-history.spec.ts` 의 시나리오 토글 관례 복제 — `addInitScript` 는 **반드시 `goto` 이전**, `SCENARIO_KEY` 문자열은 import 대신 리터럴 재선언 + 출처 주석.

- E1. 내보내기 → `page.waitForEvent('download')` → `suggestedFilename() === 'automation-rules-PROJ.yaml'` (S1)
- E2. 가져오기 성공 → `setInputFiles` → 2단계 확인 → 생성/갱신 표시 + 목록 갱신 (S4)
- E3. `IMPORT_FAILED_INDEX` 토글 → "3번째 룰에서 실패" + 전량취소 문구 (S6)
- E4. `IMPORT_WEBHOOK_TOKENS` 토글 → 토큰 목록 + ESC 눌러도 2단계 확인 (S10/EC7)

**검증**. `node_modules/.bin/playwright test e2e/automation-yaml-gitops.spec.ts` → 4 PASS. 실행 후 고아 vite(5173) 정리 확인([[e2e-orphan-vite-after-worktree-remove]]).

---

### Task 9. 문서 마킹 + BC 진척 반영

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/automation.md`]
- depends-on: [8]

**GREEN**(TDD 무관 — 문서).
- §2.6 FR-AT-06 의 `- [ ] D6` / `- [ ] D7` → `- [x]`.
- D1~D5 노트 아래에 **D6/D7 완료 노트** 추가(#271의 §2.5 노트 형식 동형 — 배치 결정·핵심 함정·테스트 수·FR 총수 불변 명시).
- 말미 `→ automation BC **5/7 → 6/7**` 반영.
- **FR 총수 123 불변**(D-step) — `fr-index.md`·`README.md`·`CLAUDE.md` 카운트 **미변경**(#271 선례가 `product/automation.md` 6줄만 수정).

**검증**. `bash scripts/verify-master-plan.sh` → 통과(카운트 drift 0). 대시보드 재생성(`node scripts/build-dashboard.mjs`)은 **머지 후 별도 chore 커밋**(#271/#272 선례, [skip ci]).

---

## Plan 메타

- **task 수**. 9
- **wave 예상**. 6 — W1[T1·T2·T6] → W2[T3·T4] → W3[T5] → W4[T7] → W5[T8] → W6[T9]
  - T1/T2/T6은 depends-on `[]` + files 교집합 ∅ → 3-병렬
  - T6이 W1에 드는 이유. props만 추가하는 순수 표현 변경이라 API/Dialog 불요
  - T5 depends-on `[3, 4]`(리뷰 반영) — wave 결과는 W3로 동일
- **TDD 강제**. yes (T9 문서 제외 — RED 없음)
- **병렬 dispatch**. bts-impl이 메타(depends-on + files)로 wave 계산
- **추가 검증**. `pnpm lint` · `pnpm typecheck`(CI는 `tsconfig.app.json`) · `pnpm test` 전량 · `pnpm build` · `pnpm test:e2e`
- **공유 인프라 주의**. T1이 `client.ts`(전 BC 공유) 변경 → 반드시 **전체 테스트**로 회귀 확인(단일 파일 테스트 통과로 판단 금지)
- **worktree 주의**. `node_modules` 설치 완료(실디렉토리). `pnpm` 대신 `node_modules/.bin/*` 직접 호출 권장([[worktree-pnpm-verify-deps-symlink]])
- **git stash 금지**. sub-agent 는 `git stash` 사용 금지([[subagent-git-stash-worktree-shared-collision]]) · 자기 파일만 stage([[parallel-dispatch-precommit-hook-race]])

## 리뷰 결과

집중 리뷰(design + eng 렌즈, 2026-07-15 — 기존 UI 미러라 mockup 생성/autoplan 4-phase 생략, [[bts-review-plan-autoplan-overkill]] · 직전 동형 PR #271 선례). **BLOCKER 0**, CONCERN 전량 반영, **Maxi 결정 불요**(선례/실물이 전부 정답 강제).

### plan-design-review (집중, 2026-07-15)
- **평점 7.5/10.** DESIGN.md 이탈 0(임의 색상/간격/토큰 신설 없음)·위계 정확(primary "룰 추가" 1개 + 신규 2개 outline = DESIGN.md §2 "페이지당 핵심 CTA 1개")·닫기 4경로 Radix API 정확·색만으로 정보전달 없음·용어 규율(FR12) 일관.
- ⚠️ **반영 T6 — 반응형 부재**(가장 실질적). 헤더 행 `flex items-center justify-between`(:362)에 wrap/stack 없음 + 페이지 컨테이너 `max-w-2xl p-8` → 모바일에서 헤딩+버튼 3개 넘침. 같은 파일 `AutomationRuleRow`(:197)가 이미 `flex-col ... sm:flex-row` 패턴 보유 → 동형 적용.
- ⚠️ 반영 T5 — 인라인 확인 박스의 **시각 패턴 미인용**(서술만) → `RuleExecutionTraceRow.tsx:192-199`(`border-destructive/20 bg-destructive/5` + `variant="destructive"`) 클래스 명시.
- ⚠️ 반영 T5 — 확인 버튼 라벨 편차("확인" vs 선례 `:26` **"확정"**) → 같은 BC 위험 액션 확정 라벨로 통일. **Maxi 확인 불요**(선례가 정답).
- ⚠️ 반영 T5/T6 — 로딩 피드백이 `disabled` 뿐 → `ImportMappingWizard.tsx:387` 선례 따라 `'적용 중...'`/`'내보내는 중...'` 라벨 전환.
- ⚠️ 반영 T5 — **정보 계층**: 토큰(1회 노출, 놓치면 영구 분실)이 결과 카운트/conflicts 뒤에 오면 안 됨 → 렌더 순서 ①토큰 ②카운트 ③conflicts 명시.
- ⚠️ 반영 T5 — 파일 input 접근가능 이름 RED 부재 · conflicts/토큰 영역 `role="alert"` 미명시 → 둘 다 명시.
- ⚠️ 반영 spec — S10 산문 "규칙명" 슬립(FR8은 정상) → "룰 이름".
- BLOCKER: 없음.

### plan-eng-review (집중, 2026-07-15)
- ✅ **실물 대조로 plan 주장 3건 전부 사실 확인**. ① T1 `client.ts` 회귀 0 — `apiFetch` 145건 + `apiPost` 15건 전수 문자열 body 0, `body: ''` 케이스도 0(`webauthn.ts`/`mfa.ts` 의 `JSON.stringify` 는 raw `fetch` 라 영향권 밖). ② T2 Zod 가 백엔드 `AutomationImportResponse.kt:264-296` 과 1:1(`webhookTokens` 는 `.optional()` 이 정답 — `.nullable()` 이면 파싱 실패). ③ T4 MSW 등록 불요 주장 참(`handlers.ts:69/147` 실물).
- ✅ wave DAG 파일 교집합 0, 순서 정확. `request.text()` YAML 판독 가능(`dashboard-handlers.ts:565` 선례).
- ✅ jsdom `File.prototype.text()` **동작 확인**(jsdom 29.1.1 `Blob-impl.js`) → plan 의 리스크 노트는 과잉 우려로 확인, 대안은 유지(해 없음).
- 🛑→✅ **반영 T5/T7 — 구조 결함**(가장 심각). T7의 EC8 테스트가 GREEN 을 만들려면 `AutomationYamlImportDialog.tsx`(**T5 files**)를 수정해야 함 → 병렬 wave 규약("선언 외 파일 수정 시 BLOCKED 보고")상 **T7 실행 중단**으로 귀결. 리셋은 Dialog 자신의 책임 → EC8 구현·테스트 **T5로 이관**, T7은 black-box 관측만.
- ⚠️→✅ **반영 T4/T5 — S9(409) 커버리지 0**. 초안이 409만 유일하게 서버 `detail` 대신 프론트 고정문구를 쓰게 해 errorCode 분기가 필요했는데, 이를 강제하는 테스트가 어디에도 없어 **놓쳐도 아무 테스트도 안 깨지는 가짜 그린**. → 백엔드가 이미 사용자용 한국어 안내를 주므로(`AutomationRuleController.kt:546`) **분기 자체를 제거**해 전 실패를 단일 규칙으로 통일 + MSW 409 시나리오 키 + T5 테스트 추가.
- ⚠️→✅ **반영 T6 — RED 코드가 존재하지 않는 구조 전제**. `AutomationRuleList.test.tsx` 에 `baseProps` 없음(실제는 `renderList(...)` 팩토리 `:60` + 호출부 13곳). 초안대로면 `baseProps is not defined` 로 즉시 막힘 → `renderList` 시그니처에 **기본값과 함께** prop 3종 추가(기존 13개 호출부 무수정).
- ⚠️→✅ 반영 T5 메타 — depends-on `[3]` → **`[3, 4]`**(Dialog 테스트가 T4 핸들러/시나리오 키 소비). wave 결과는 동일(W3).
- ⚠️→✅ 반영 spec — §제약 조건 5(MSW 2곳 등록)가 plan 과 **모순**(일반 학습 복붙 잔재) → "신규 파일 만들 때만 적용, 이번은 기존 파일 확장이라 해당 없음"으로 정정.
- ⚠️→✅ 반영 T5 — FR9(invalidate)·NFR3(토큰 비영속 네거티브)·EC5(적용 중 disabled) 전용 테스트 부재 → 3건 추가.
- BLOCKER: 없음(구조 결함 1건은 plan 수정으로 사전 해소).

**종합**. BLOCKER 0. design 8건 + eng 7건 전량 plan/spec 에 반영. T5 테스트 11 → **18**, T6 RED 구조 정정, T5/T7 경계 재설계, 409 분기 제거로 가짜 그린 위험 차단. 저위험 순수 프론트 — 게이트 1 진입 가능.
