# 보드 설정 잔여 4탭 — 카드 레이아웃 · 추정 · 작업일 · 상세 보기 (부채 177)

> 티어: T3
> slug: board-settings-remaining-tabs-177
> type: migration
> agent: db-engineer
> 생성: 2026-09-05

## Brief

**사용자 원문.** 「보드 관련 다음 남은 작업」에서 부채 177 잔여 4탭을 골랐고,
「하나의 PR 로 할 수 없어?」 → 「4탭 모두 적용이 필요한 것들이잖아?」로 **4탭 한 PR** 을 확정했다.

**classify 결과.** `slug=board-settings-remaining-tabs-177` · `type=migration` ·
`agent=db-engineer` · `tier=T3` · `primary_bc=null`(migration 은 BC 무관 —
`classify-task.ts:545`).

**티어 근거.** `--tier T3` 를 명시해 넘겼다. `classify-task.ts:582` 가
`input.tier ?? DEFAULT_TIER` 라 티어를 type 에서 유도하지 않으므로 호출자가 선언한다.
네 탭 모두 `boards` 에 대응 칸이 없어 **신규 스키마가 확정**이고, 티어 표에서 MIGRATION 은 T3 다.

## 무엇을 만드는가

지라 Board settings 7탭 중 BTS 에 **아직 없는 4탭**을 만든다.

| 탭 | 갭 | 백엔드 현황(2026-09-05 실측) |
|---|---|---|
| Card layout | B | 없음. `BoardCard.tsx` 가 고정 필드를 그린다 |
| Estimation and tracking | C | **이슈 층에 실데이터 있음** — `WorklogService` · `remainingEstimateSeconds` · 백로그/보드 응답의 추정 필드. 보드 층 설정만 신규 |
| Working days | D | 없음 |
| Issue detail view | E | 없음 |

`boards` 현재 컬럼 — `id · project_key · name · created_at · updated_at · deleted_at`
(`V500__boards.sql`) + `swimlane_field` + `board_type`. **네 탭 어디에도 대응 칸이 없다.**

**Swimlanes · Quick filters 2탭은 범위가 아니다.** #452 가 편차 `X3` 로
「보드 화면 인라인 유지 · 설정 탭으로 수렴시키지 않는다」를 Maxi 확정으로 등재했다.

## FR

**기존** — `FR-BD-01` · `FR-BD-03` · `FR-BD-04`.

**신규 FR 필요 여부는 스펙에서 판단한다.** #452 계획이 남긴 쟁점을 그대로 잇는다 —
「카드 레이아웃 갭 B 가 유일한 후보이고, `FR-BD-03` 범위 확장으로 흡수 가능한지가 쟁점」.
갭 C·D·E 는 그 판단을 한 번 더 해야 한다(넷을 한 PR 로 묶었으므로 네 번이 아니라 한 자리에서).

## 착수 시점에 이미 아는 것

- **탭바가 아직 없다.** `settings.tsx:89` — 「탭이 하나라 탭바 자체를 안 만든다 —
  **두 번째 탭을 만드는 PR 이 탭바를 도입한다**」. 그 PR 이 이것이다.
- **비활성 골격을 미리 그리지 않는다는 결정이 있다**(`board-labels.ts:303`, Maxi 확정 2026-09-04) —
  「누를 수 있는데 아무 일도 안 일어나는」 화면은 장부가 경계한 「도달할 UI 가 없는 기능」의 거울상.
  이 PR 은 네 탭을 **전부 동작하게** 만들므로 그 결정과 충돌하지 않는다.
- **J 번호가 하나도 없다.** 네 탭 모두 `docs/design/jira-parity-contract.md` 에 항목이 없어
  **지라 실물 조회부터** 필요하다. 이것이 스펙 작업의 대부분이다.
- **마이그레이션은 1개로 묶는다.** 네 탭 설정 칸을 한 V-번호에 몰아 V-번호 동시 충돌
  함정([[migration-vnumber-concurrent-branch-collision]])을 네 번이 아니라 한 번만 상대한다.
  이것이 4탭을 한 PR 로 묶는 유일한 기술적 이득이다.

## 알고 감수하는 것 (Maxi 확정 2026-09-05)

4탭 한 PR 의 비용을 제시했고 그대로 진행하기로 했다.

1. **게이트 2 리뷰 표면이 4배.** #452 는 **1탭**인데도 CONCERNS 9건이 나왔다.
2. **main 이 빠르다.** 최근 하루에 `#449`~`#456` 이 들어왔다. 며칠짜리 PR 은 리베이스를 반복한다.
3. **되돌리기 단위가 사라진다.** 한 탭이 잘못되면 4탭이 통째로 롤백된다.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

정본은 `docs/specs/2026-09-05-board-settings-remaining-tabs-177.md` (T3 은 분리).

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
