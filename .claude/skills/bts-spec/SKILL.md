---
name: bts-spec
description: Use when a task needs a written specification with user scenarios, FR/NFR, edge cases, and a sanity check before planning. Skipped for chore/bugfix fast-track.
---

# /bts-spec

스펙 작성 + sanity check를 2-Phase로 처리. office-hours가 만든 스펙을 brainstorming으로 다시 흔들어 누락/모호 발견.

## 선행 읽기 (필수)

1. `/Users/maxi.moff/Maxi_wiki/BTS/plans/` — 유사 작업 plan 검색 (slug grep)
2. `/Users/maxi.moff/Maxi_wiki/BTS/decisions/` — 관련 ADR 검색
3. `docs/plans/<date>-<slug>.md` — `## 도메인 정리` 섹션 (직전 단계 결과)
4. (UI 타입) `DESIGN.md` — 디자인 토큰/컴포넌트 컨벤션 (있을 때)
5. (UI 타입) `docs/design/jira-parity-contract.md` — **Jira Cloud 대조 사고 절차(§1) + 즉사 계약(§2) + 재사용 자산(§4)**. ui/design 스펙은 이 계약 위에서 작성한다

## 절차

### Phase A. 세부 스펙 도출

#### A-1. (UI 작업 + DESIGN.md 없음) design-consultation 호출

UI 작업 판정. `classify.type ∈ {ui, design}` OR (`classify.type == feature` AND 본문에 UI 키워드 `["페이지", "화면", "컴포넌트", "ui"]` 중 1개 이상).

```bash
# DESIGN.md 부재 체크 — 프로젝트 첫 UI 작업 여부 판정
if [ ! -f /Users/maxi.moff/Projects/BTS/DESIGN.md ]; then
  # design-consultation 호출 (아래)
  IS_FIRST_UI=true
else
  IS_FIRST_UI=false
fi
```

`IS_FIRST_UI == true` 시.

```
Skill({
  skill: "design-consultation",
  args: "BTS 프로젝트의 디자인 시스템 (DESIGN.md) 초기 생성. shadcn/ui + Radix 기반, Tailwind v4 토큰. 사내 1,000명 규모 협업 도구 미적."
})
```

**프로젝트당 1회만.** 이후 UI 작업은 기존 DESIGN.md 위에서 작업.

#### A-2. (UI 작업 — 새 화면 / 컴포넌트) design-shotgun 호출

UI 작업 판정. A-1과 동일 조건. 단, `bugfix` (기존 UI 수정) 또는 단순 텍스트 변경은 스킵.

```
Skill({
  skill: "design-shotgun",
  args: "<작업 제목> 화면의 디자인 변형 4종 생성. 사용자가 비교 후 선택."
})
```

산출물. `public/mockups/<slug>-{1,2,3,4}.html`. Maxi가 1개 선택 → 선택된 변형이 designer agent의 입력.

비-UI 작업이면 스킵.

#### A-3. office-hours 호출

```
Skill({
  skill: "office-hours",
  args: "BTS <BC> 작업 '<작업 제목>'의 세부 스펙. 도메인 정리 결과: <plan의 ## 도메인 정리 섹션>. 유사 작업: <검색된 유사 plans>. 사용자 시나리오, 엣지 케이스, 제약 조건 도출."
})
```

산출물. `docs/specs/<date>-<slug>.md`. 다음 섹션 포함.

```markdown
# <작업 제목> — 스펙

## 사용자 시나리오 (Given-When-Then)
## Jira 대조 (ui/design 타입 필수)
## 기능 요구사항 (FR)
## 비기능 요구사항 (NFR)
## API 인터페이스 (REST)
## 데이터 모델 변경
## 엣지 케이스
## 제약 조건
## 측정 가능한 완료 기준
```

**`## Jira 대조` 작성법** (ui/design 타입, `jira-parity-contract.md` §1 절차의 산출물).
Jira Cloud 의 대응 화면 → 조작감 갭 목록 → 즉사 계약(§2)·재사용 자산(§4)과의 교차 결과.
대응 화면이 없으면 "Jira 대응 없음, ADS 준용" 을 명시. 비-UI 타입은 섹션 생략.

### Phase B. Brainstorming Sanity Check

```
Skill({
  skill: "superpowers:brainstorming",
  args: "다음 스펙을 sanity check. 누락된 요구사항, 모호한 표현, 가정 누락, 엣지 케이스 미커버를 찾아내. 스펙 파일: docs/specs/<date>-<slug>.md. 도메인 컨텍스트: <plan의 ## 도메인 정리>."
})
```

**중요**. brainstorming은 스펙을 **재작성하지 않는다**. 발견된 gap만 보고.

#### B-1. 결과 분기

| brainstorming 결과 | 동작 |
|---|---|
| **gap 없음** | 스펙 파일 끝에 `## Brainstorming Check ✅ 통과 (<brainstorming 한 줄 요약>)` append → Phase C |
| **gap 발견 (수정 가능)** | gap 항목을 plan 파일 `## 스펙` 섹션에 "❓ Brainstorming 발견" 라벨로 추가 → Phase A로 loop back (office-hours 재호출하되 gap만 보강) |
| **gap 발견 (Maxi 결정 필요)** | AskUserQuestion으로 Maxi에게 옵션 제시 → 답변 후 Phase A로 loop back |

**무한 루프 방지**. Phase A↔B는 최대 3회. 4회째에도 gap 발견 시 Maxi에게 "이 작업은 더 작은 단위로 쪼개야 합니다" 제안.

### Phase C. plan 파일 갱신

worktree의 plan 파일에서 다음 섹션 채움.

```markdown
## 스펙

전체 스펙. [docs/specs/2026-05-19-issue-mention-notify.md](../specs/2026-05-19-issue-mention-notify.md)

핵심 시나리오 3줄 요약.
- 사용자가 코멘트에서 `@username`을 입력하면 자동완성
- 코멘트 저장 시 멘션된 사용자에게 알림 발사 (인앱 + 이메일 옵션)
- 멘션된 사용자가 해당 이슈를 못 보면 알림 안 감 (권한 체크)

## Brainstorming Check

✅ 통과 (3회 iteration, 권한 체크 누락 발견 후 보강)
```

### Phase D. 다음 스킬 체이닝

자동으로 `/bts-plan` 호출.

## 출력 형식

```
🔄 [3/8] /bts-spec
   ├─ Phase A: office-hours → docs/specs/2026-05-19-issue-mention-notify.md
   ├─ Phase B: brainstorming → gap 1건 발견 (권한 체크)
   ├─ Phase A (재): office-hours 보강 → 권한 체크 시나리오 추가
   ├─ Phase B (재): 통과
   └─ Brainstorming Check ✅ (2회 iteration)
```

## Fast-track 스킵 조건

`classify.type ∈ {bugfix, chore}` 시 이 단계 전체 스킵.

이유. 버그 수정은 스펙이 "기존 동작 복원"으로 자명. office-hours/brainstorming 비용 > 효익.

## 실패 / 엣지 케이스

- **office-hours가 builder mode로 들어가서 "이 기능 만들 가치 없음" 결론**. 사용자에게 "정말 진행할까요?" 확인. yes → 그대로 진행, no → 작업 중단 + worktree 정리
- **brainstorming이 3회 후에도 gap 발견**. 작업 너무 큼. plan 분할 제안 (`feature` 1개 → 2-3개로 쪼개기)
- **design-shotgun 변형이 모두 거부**. Maxi가 "다 별로"라면 → designer agent에 직접 위임 (자유 형식)
