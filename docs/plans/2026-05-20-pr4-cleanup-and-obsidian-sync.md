# chore 정리 묶음 — PR #4 잔여 + Obsidian 동기화 + CONTRIBUTING

> slug. pr4-cleanup-and-obsidian-sync
> type. chore (classify 수동 override — 원본은 `auth` 였음)
> agent. backend-engineer (대표, 실제는 wave 별 backend/security/docs 혼합 분기)
> primary BC. identity-access
> 생성. 2026-05-20

## Brief

사용자 원문. "chroe 정리 묶음 - pr #4 잔여 + obsidian 동기화 + contributing"

체크포인트 (`20260520-185752-stored-password-credential-shipped.md`) 의 잔여 작업 #4 의 단일 묶음. 두 번째 wave 병렬 dispatch dogfood 사례.

후보 task (7건).

| # | task | files | agent |
|---|---|---|---|
| 1 | escapeForLdapFilter 공백 처리 제거 (CONCERN-3, PR #4 본체) | backend identity-access (LDAP filter) 1 file | security-engineer |
| 2 | INSERT...RETURNING 최적화 — ExternalAccountRepository 본체 | backend identity-access (Repository) 1 file | backend-engineer |
| 3 | classify-task slug 한국어 50자 컷 | `scripts/workflow/classify-task.ts` 1 file | backend-engineer |
| 4 | `Maxi_wiki/BTS/_index.md` BC 매핑 9개 갱신 | Obsidian 1 file | (manual / docs) |
| 5 | `Maxi_wiki/BTS/glossary.md` 인증 섹션 5건 추가 | Obsidian 1 file | (manual / docs) |
| 6 | `Maxi_wiki/BTS/domain/identity-access.md` 정정 | Obsidian 1 file | (manual / docs) |
| 7 | `CONTRIBUTING.md` Testcontainers Docker Desktop 안내 | 루트 1 file | backend-engineer |

**분류 수동 override 이유**. classify-task 가 LDAP / INSERT 키워드로 `type=auth` 단정 + 한국어 slug 미컷. 두 가지 모두 본 PR 의 task 3 / task 1~2 가 해결할 대상이라 self-referential. 게이트 1 전 사용자 결정으로 `type=chore` + ASCII slug 강제.

## 도메인 정리 (← /bts-domain 채움)

(`/bts-domain` 단계에서 채움)

## 스펙 (← /bts-spec Phase A 채움)

(`/bts-spec` 단계에서 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

(`/bts-spec` Phase B 에서 채움)

## Plan (← /bts-plan 채움)

(`/bts-plan` 단계에서 채움. 7 task 메타 (agent / files / depends-on) + wave 계산)

## 리뷰 결과 (← /bts-review-plan 채움)

(`/bts-review-plan` 단계에서 채움)
