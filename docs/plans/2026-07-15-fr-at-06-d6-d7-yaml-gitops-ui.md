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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
