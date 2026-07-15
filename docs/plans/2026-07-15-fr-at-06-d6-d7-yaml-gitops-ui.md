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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
