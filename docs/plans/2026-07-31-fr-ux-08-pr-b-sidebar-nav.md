<!-- FR-UX-08 PR-B (F17) 사이드바 "내 작업"·"최근 항목" 워크플로우 진행 기록 -->
# FR-UX-08 PR-B — 사이드바 "내 작업"·"최근 항목" + nav 라벨 전수 판별식 (F17)

> slug: fr-ux-08-pr-b-sidebar-nav
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트 `apps/web/**` 전용)
> 생성: 2026-07-31

## Brief

FR-UX-08 을 완주시키는 후속 PR. PR-A(#326, F12 프로젝트 스위처 + 트리 펼침 영속)가 머지돼
코드 파일 교집합 0 이 확보됐으므로 착수 가능해졌다.

**범위 정본** — `docs/specs/2026-07-30-fr-ux-08-project-switcher.md` §11 PR-B 행.

| 축 | 값 |
|---|---|
| 로드맵 | F17 |
| 담당 FR | FR2 · FR4 · FR12 · FR13 · FR14 · FR15 · **FR16(정본 전수 동기화)** |
| 시나리오 | S7 · S8 · S9 |
| 엣지 케이스 | E2 · E3 · E4 · E8 · E9 |
| 제약 | §8 `MAIN_NAV_LINKS` 항목이 이 PR 부터 적용 |
| plan 원안 | T2 · T4 · T6 · T9 (PR-A plan 에서 이미 분해) |

**착수 전 확정 전제 3건 (PR-A 에서 실측으로 뒤집힌 것).**

1. ★ `?assignee=me` 는 **실재하지 않는다**. `IssueFilterQueryParser` 센티널은 `unassigned` 뿐이고
   그 외 값은 UUID 파싱 실패 시 **400**. → `?assignee=<whoami.userId>`.
2. ★ "최근 항목" = 최근 본 **이슈** (프로젝트 아님). 사이드바에 `ProjectTree` 전체 목록이 이미 있어 중복.
3. ★ FR16 이 PR-B 소관 → D 마커 `[x]` + 진척 132→133 + `verify-master-plan.sh` EXIT0 이 머지 조건.

classify 결과. `type=ui` / `agent=frontend-engineer` / `slug=fr-ux-08-pr-b-nav-f17-ui`
(브랜치 접두사가 이미 `ui/` 라 슬러그 말미 `-ui` 중복을 제거해 `fr-ux-08-pr-b-sidebar-nav` 로 사용).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
