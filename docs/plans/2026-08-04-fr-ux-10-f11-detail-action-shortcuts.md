# FR-UX-10 F11 — 이슈 상세 액션 단축키 8종

> slug: fr-ux-10-f11-detail-action-shortcuts
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-04

## Brief

**사용자 원문.** "fr-ux-10 남은 작업 진행해줘"

**FR ID.** FR-UX-10 (컨텍스트 의존 단축키) — 잔여 **F11 1건**.
정본 `docs/plan/product/personalization.md §4.8`. 선행이던 §4.9 FR-UX-11 은
F8(#337) · F9(#338) 로 2026-08-04 완주해 **차단 요인이 없다**.

**범위.** 이슈 상세 화면의 액션 단축키 **8종** — `a`/`i`/`m`/`e`/`l`/`s`/`w`/`.`
(`s` 는 이연↔승계 대사표에서 복원된 즐겨찾기 토글).
소비처 정본 명시 — `issues.$key.tsx` · `IssueMetaPanel.tsx` · `WatchersSection.tsx` ·
`CommentSection.tsx` · `api/favorites.ts`.

**🛑 불변 계약.** `shortcuts.ts` 의 `SHORTCUTS` 를 건드리지 않는다.
성공 판정식 = `shortcuts.test.ts:121` `toHaveLength(5)` **무수정 green** 유지
(F10 #336 은 `shortcuts.ts`·`shortcuts.test.ts` **git diff 0** 으로 판정했다 — 같은 기준 승계).

**완료 조건.** `personalization.md §4.8` D6/D7 을 `[x]` 로 닫는다 → FR-UX-10 완주.

### classify 정정 (재발 1건)

`scripts/workflow/classify-task.ts` 가 `type=backend` · `agent=backend-engineer` ·
`primary_bc=issue-tracking` · `slug=fr-ux-10-f11-8` 로 오분류했다. **F9(#338)에서 이미
관측된 재발**이다. 정본 근거로 정정 —

| 항목 | 분류기 출력 | 정정값 | 근거 |
|---|---|---|---|
| type | `backend` | **`ui`** | 선행 F10 #336 · F8 #337 · F9 #338 전량 백엔드 0줄 |
| agent | `backend-engineer` | **`frontend-engineer`** | 소비처 5파일 전부 `apps/web` |
| primary_bc | `issue-tracking` | **`personalization`**(논리) / `apps/web`(물리) | ADR §D2 논리 ≠ 물리 |
| slug | `fr-ux-10-f11-8` | **`fr-ux-10-f11-detail-action-shortcuts`** | F10·F8·F9 slug 관례 |

후속 항목 후보 — 분류기가 FR 정본을 참조하지 않아 **3회 연속 오분류**했다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
