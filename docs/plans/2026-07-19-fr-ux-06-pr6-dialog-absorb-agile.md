# FR-UX-06 PR6 — 소규모 BC Dialog 흡수 (9파일)

> slug: fr-ux-06-pr6-dialog-absorb-agile
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-19

## Brief

FR-UX-06 Dialog 흡수 4분할 중 PR6. **정본 = PR5(#289 CloneIssueDialog)**.
plan 원안 "PR6=agile-planning"은 실측상 board/ 2파일뿐 → Maxi 결정으로 **소규모 BC 묶음 9파일**로 재배분.
auth 계열 10·automation 9는 PR7/PR8로 이연.

### IN-SCOPE (9파일, 실측)
| BC | 파일 |
|---|---|
| agile-planning `board/` | ResolutionPickerModal · SaveQuickFilterDialog |
| search-export-import `search/` | ExportDialog · SaveFilterDialog · ShareFilterDialog |
| project-workflow `workflow/` | PostActionFormDialog |
| notification-dashboard `dashboard/` | GadgetCatalogModal |
| personalization `keyboard-shortcuts/` | ShortcutsHelpDialog |
| personalization `status/` | StatusModal |

### 흡수 정본 패턴 (PR5 확립, 재사용)
- render() 껍데기만 교체(Root/Portal/Overlay/Content/Title/footer div → 래퍼). 비시각 로직 diff-0(`git diff -w` 검증).
- 시각=Jira 통일(스크림 /50·X·rounded-lg·bg-popover는 래퍼 위임). max-w/max-h만 `DialogContent className`.
- grid gap-4 재간격(개별 margin 정리). 함정: `sm:justify-between`(bare 못이김) · Cancel onClick은 DialogClose 미포장(이중호출).
- 정본 소스: `git show 67a641300:apps/web/src/components/issues/CloneIssueDialog.tsx` 또는 현 apps/web/src/components/issues/CloneIssueDialog.tsx.
- 관련: [[fr-ux-06-pr5-dialog-absorb-done]] · [[fr-ux-06-jira-redesign-plan]]

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
