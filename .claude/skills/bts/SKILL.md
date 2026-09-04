---
name: bts
description: Use when user gives a natural-language coding request for BTS — feature add, bug fix, refactor, new module. Skip for read-only questions, code explanation, or continuing existing work that doesn't start a new branch.
---

# /bts

BTS 모든 코드 작업의 **단일 진입점**. 티어를 판정하고 7단계 체인을 컨트롤한다.
**하위 7종은 이 컨트롤러만 호출한다.** 각 단계 응답을 받은 뒤 명시적으로 다음 `Skill()` 을 부른다 — 「자동으로 호출된 셈」 가정 금지.

## 체인 7단계

```
[1] bts-start        헬스체크 · classify · worktree+훅 · plan 스텁(T2+) · Draft PR
[2] bts-spec         BC 식별 + 스펙 9섹션 + sanity check          (T2+)
[3] bts-plan         TDD task 분해 + 메타 계약                     (T2+)
[4] bts-review-plan  리뷰 렌즈 라우팅 (한 응답 병렬 발행)          (T2+)
🛑 게이트 1 — Maxi 검토 (T2/T3 만)
[5] bts-impl         wave dispatch + TDD 강제
[6] bts-codereview   독립 리뷰 (T0/T1 1종 · T2/T3 2종)
🛑 게이트 2 — Maxi 검토 (**전 티어 필수 · 어떤 티어도 생략하지 않는다**)
[7] bts-merge        verify → merge → worktree 정리 → dashboard → Obsidian
```

## A-0. 활성 작업 감지 (모든 입력 형태에 선행, 필수)

이전 세션이 중단된 채 남긴 worktree / draft PR 을 감지. 발견되면 사용자 확인 없이 진행 금지.

```bash
# ★git 에게 묻는다. 디렉터리 훑기는 위치를 놓친다 —
#   `ls -d .worktrees/*/` 만 보던 종전 판정은 하네스가 만든 `.claude/worktrees/` 아래를
#   전혀 못 봤다. 2026-09-04 실측으로 **중단된 작업방 3개가 있는데 0건**을 냈다.
#   위치를 목록으로 유지하면 그 목록이 또 하나의 썩는 두 번째 목록이 된다.
MAIN_TREE=$(dirname "$(git rev-parse --git-common-dir)")
ACTIVE_WORKTREES=$(git worktree list --porcelain | awk '/^worktree /{print $2}' \
  | grep -vx "$MAIN_TREE" | grep -vx "$(git rev-parse --show-toplevel)")
ACTIVE_DRAFT_PRS=$(gh pr list --draft --author @me --json number,title,headRefName 2>/dev/null)
```

둘 중 하나라도 비어있지 않으면 `AskUserQuestion` — ① 이어가기 ② 새 작업 추가 ③ 이전 폐기(worktree 삭제 + draft PR close).
**「이어가기」의 재개 지점은 추정하지 않는다.** `.claude/STATE.md` 의 마지막 기록을 읽어 그 단계로 복귀한다. STATE 가 없거나 `⚠ STALE` 배너가 있으면 Maxi 에게 재개 지점을 묻는다.
활성 작업이 없고 입력이 모호하면(30자 미만 + 동사만) `AskUserQuestion` 으로 3 옵션, 빈 입력이면 `gh pr list --state open` 후 "어떤 PR 이어서 작업?".

## 티어 판정 5문 (착수 시점 — 선언)

티어 **정의**(표면 4행표)는 `CLAUDE.md` 가 정본이다. 사본을 여기 두지 않는다.

① 혼합이면 **최고 티어**(max)를 쓴다 — 티어는 절차 강도이므로 가장 위험한 표면이 지배한다.
② 기본값은 **T1**. Maxi 가 티어를 지정하면 그것이 항상 우선한다.
③ 어느 표면에도 안 걸리는 경로는 T1 로 두되 `UNMAPPED: <경로>` 를 **게이트 2 요약에 그대로 싣는다** — 미분류를 조용히 통과시키지 않는다.
④ **테스트만 바뀌면 티어를 올리지 않는다.** 소스가 함께 바뀌면 소스 표면이 기준.
⑤ **임의 승격 금지.** 머지 전 실측 티어가 선언보다 높으면 자동 승격하지 않고 **게이트 2 에서 정지해 사람이 결정**한다. 선언 티어와 실측 티어를 게이트 2 요약에 나란히 적는다.

## 티어별 절차 (이 표가 정본)

| | **T0 즉시** | **T1 경량** | **T2 표준** | **T3 중량** |
|---|---|---|---|---|
| 대표 표면 | `DOC` `STYLE_COPY` | `FE_SRC` `HARNESS` `TEST` `SHELL` | `SEC_*` `API` `BE_MAIN` `DEPS` `GUARD_CI` | `MIGRATION` `SHARED_KERNEL` `TOPOLOGY` |
| 사전 계획 · `docs/plans` | 없음 · **0파일** | 없음(1줄 요약은 게이트 2 요약에) · **0파일** | 변경 계획→승인 · **1파일**(spec 흡수) | 설계 문서(ADR)→승인 · spec+plan(+ADR) |
| 영향 범위 제시 | — | — | 필수 | 필수 |
| 테스트 | lint+타입체크 · 시각 변경이면 눈확인 1회(덮는 E2E 로 대체 가능 · 4화면은 `visual` 잡이 면제) | 재현 테스트 1개 먼저 | 정식 TDD red-first + 이벤트 계약 | 정식 TDD + 마이그레이션 검증(`DATA.md` 정본) |
| 독립 리뷰 종수 | **1종**(하한) | 1종 | 2종 | 2종 + ceo |
| 보안 렌즈 | — | — | 보안 표면 포함 시 생략 불가 | 생략 불가 |
| 도는 단계 | [1] 축약 · **[5] 인라인** · [6] · [7] | 〃 | [1]~[7] 전량 | [1]~[7] 전량 + ADR |
| 게이트 · 목표 호출/정지 | 2만 · 3/1 | 2만 · 3/1 | 1+2 · 7/2 | 1+2 · 7/2 |

- **T0/T1 의 「[5] 인라인」** — `bts-impl` 을 호출하지 않고 이 컨트롤러가 직접 편집한다. 규율은 위 표의 「테스트」 행 그대로. 호출 3회(=[1]·[6]·[7])가 이 티어의 목표치다.
- **독립 리뷰는 인라인하지 않는다.** `bts-codereview` 는 전 티어 호출이다 — 자기 구현을 자기가 리뷰하면 「독립」이 아니고, T0 의 1종은 하한이라 0종으로 내려갈 자리가 없다.
- **`bts-start` 와 `bts-merge` 도 전 티어 호출**한다. 두 스킬 본문에 사고 방어 절차와 내용 계약이 걸려 있어 축약·복사가 곧 회귀다.
- CI 범위는 변경 경로가 정한다 — `.github/workflows/` 의 `paths` 가 정본이고 이 표는 그것을 재기술하지 않는다.

## 게이트

**★두 게이트 모두 `AskUserQuestion` 으로 낸다. 산문 3지선다 금지.**
본문에 「1. 승인 2. 수정 3. 보류」를 적고 숫자를 타이핑하게 하지 않는다 —
2026-08-23 · 08-24 · 09-02 세 세션 연속 같은 지적이 나왔고 **세 번 다 원인이 달랐다**
(거부 기록 오독 / 요약을 내면 질문 단계가 증발 / 한 번 썼으니 됐다는 감각).
산문 지시로는 안 막힌다는 것이 이미 실증됐으므로 여기에 도구를 못 박는다.

**기계적 판정** — 답을 사람이 골라야 하는 문장(「~할까요」「~아니면」「어느 쪽」)을
쓰려는 순간, 그 문장을 쓰지 말고 도구를 부른다. 보고서 마지막 줄에 질문을 붙이지 않는다.

| 게이트 | 대상 | 도구 | 선택지 (`AskUserQuestion` options) |
|---|---|---|---|
| 🛑 1 (plan 산출물 요약) | T2/T3 | **`AskUserQuestion` 필수** | `승인`→[5] 호출 / `수정 요청`→**어느 섹션인지도 같은 질문의 두 번째 항목으로 묻는다** → [2] [3] 중 재호출 후 재진입 / `중단`→worktree·draft PR 유지 |
| 🛑 2 (PR diff + 리뷰 결과) | **전 티어** | **`AskUserQuestion` 필수** | `승인`→[7] 호출 / `수정 후 재리뷰`→concerns 첨부해 [5] 재호출→[6] 재호출 / `보류`→A-0 복원 경로로 재진입 |

게이트 1 요약에는 **티어 · task 수 · 영향 파일 · 리뷰 렌즈 결과 · BLOCKER 유무**를 싣는다.
게이트 2 요약에는 **선언 티어 · 실측 티어 · `UNMAPPED` 줄 · 건너뛴 단계**를 반드시 싣는다. 우회 사실을 Maxi 가 보고 승인하게 하는 것이 이 요약의 목적이다.

**요약을 다 쓴 것이 질문을 낸 것이 아니다.** 요약은 질문의 재료다 — 요약 뒤에 반드시 도구 호출이 온다.

**T0/T1 에서 UI 를 건드렸으면 `Jira 대응 3줄`도 같이 싣는다** — `Jira 대응`(화면 + 출처 URL + 조회일) ·
`채택` · `편차`. T1 은 plan 파일이 0개라 이 3줄이 Jira 패리티 판단의 **유일한 기록**이고,
저장소에 안 남으므로 판별식이 못 잡는다. 절차는 `docs/design/jira-parity-contract.md` §1 「T1 UI 경량 경로」.

## 선행 읽기

- `/Users/maxi.moff/Maxi_wiki/BTS/_index.md` · 같은 폴더 `history.md` 마지막 50줄을 Read.
- **`learnings.md` 전량 Read 금지.** `grep -n '^### '` 로 헤딩 인덱스만 뽑아 이번 작업 키워드·BC·타입 관련 항목 + 최근 5건만 부분 Read 한다. 여기서 고른 발췌가 체인 전체(및 `bts-codereview` 주입)의 learnings 컨텍스트다.

## 진행 출력

각 단계마다 1줄 출력해 black box 를 피한다(`🔄 [1/7] 분류 중… → type=feature, tier=T2, tasks=4`).
**보고는 i-have-adhd 규칙을 따른다** — 다음 행동부터, 여러 단계는 번호, 서두·요약·마무리 인사 없음. T0/T1 은 바꾼 파일과 확인 방법 각 1줄. T2/T3 은 무엇을 왜 · 확인 방법 · 남은 위험 · 다음 할 일 각 1줄.

## 실패 / 엣지 케이스

- **worktree 충돌**. 동일 slug 가 이미 있으면 `-2` 접미사 자동
- **plan 리뷰 BLOCKER**. 중단 후 수정안 제시, 승인 후 리뷰 재실행
- **verifier BLOCKER**. implementer 재dispatch. **3회 실패하면 중단**하고 Maxi 에게 보고 — T0/T1 에서 3회에 도달하면 보고에 「티어 재판정 제안」을 포함한다
- **머지 충돌**. `git pull --rebase origin main` 자동, 충돌 시 Maxi 개입

## 관련 스킬

[bts-start](../bts-start/SKILL.md) · [bts-spec](../bts-spec/SKILL.md) · [bts-plan](../bts-plan/SKILL.md) · [bts-review-plan](../bts-review-plan/SKILL.md) · [bts-impl](../bts-impl/SKILL.md) · [bts-codereview](../bts-codereview/SKILL.md) · [bts-merge](../bts-merge/SKILL.md)
