---
name: bts-domain
description: Use when starting a non-trivial task and the bounded context, ubiquitous language, or prior ADRs need to be reviewed before designing the solution. Skipped for chore/bugfix fast-track.
---

# /bts-domain

DDD의 유비쿼터스 언어를 다지는 단계. 작업이 어느 바운디드 컨텍스트에 속하는지, 새 용어가 필요한지, 기존 결정과 충돌하는지 확인.

## 선행 읽기 (필수)

작업 진입 시 다음 노트를 Read tool로 로드.

1. `/Users/maxi.moff/Maxi_wiki/BTS/glossary.md` — 전체 용어 사전
2. `/Users/maxi.moff/Maxi_wiki/BTS/domain/<bc>.md` — `classify.primary_bc`가 가리키는 BC 노트
3. `docs/decisions/` — 영향 받을 가능성 있는 ADR (관련 키워드 grep)

**ADR 디렉토리가 비어 있을 때 (Phase 0 초기)**.
- grep 결과 0 → 자동 fallback. "신규 작업, 기존 결정 충돌 없음"으로 진행
- plan 파일 `## 도메인 정리` 섹션에 명시. "관련 ADR: 없음 (BTS 첫 ADR 후보)"

## 절차

### Step 1. classify 결과로 BC 식별

```bash
cat .bts-cache/classify.json
# 예. { type: "feature", agent: "backend-engineer", primary_bc: "issue-tracking" }
```

`primary_bc`가 없으면 작업 제목/입력에서 추출.

| BC | 키워드 신호 |
|---|---|
| identity-access | auth, 인증, 2fa, saml, oauth, ldap, 권한 |
| issue-tracking | 이슈, 코멘트, 워처, 첨부, 라벨, 컴포넌트, 버전 |
| project-workflow | 워크플로우, 전이, FSM, 상태, 게이트 |
| agile-planning | 스프린트, 백로그, 보드, 에픽, 칸반 |
| automation | 자동화, 룰, 트리거, 액션, AQL, 검색 |
| notification | 알림, 멘션, 워치, 이메일 |
| slack-integration | slack, 슬랙, unfurl, slash 명령, 인터랙티브 |

### Step 2. grill-with-docs 스킬 호출

```
Skill({
  skill: "grill-with-docs",
  args: "BTS <BC> 컨텍스트에서 '<작업 제목>' 작업의 도메인 모델을 검증. 새 용어/엔티티/관계가 필요한지, glossary.md와 domain/<bc>.md와 일치하는지 확인."
})
```

grill-with-docs가 대화형으로 질문하면 응답. 모호한 점은 Maxi에게 AskUserQuestion으로 위임.

### Step 3. 결과 정리

grill-with-docs 산출물을 다음 위치에 반영.

| 산출물 | 반영 위치 |
|---|---|
| 새 용어 | `Maxi_wiki/BTS/glossary.md`에 추가 (Maxi 승인 후) |
| BC 노트 갱신 | `Maxi_wiki/BTS/domain/<bc>.md` (수동 영역, 자동 갱신 안 함 → Maxi에게 "추가할까요?" 확인) |
| ADR (결정 사항) | `docs/decisions/<date>-<slug>.md` 자동 생성 |
| plan 파일 갱신 | `docs/plans/<date>-<slug>.md`의 `## 도메인 정리` 섹션 |

### Step 4. plan 파일 업데이트

worktree 내부 plan 파일의 `## 도메인 정리` 섹션을 채운다.

```markdown
## 도메인 정리

- BC: issue-tracking
- 영향 엔티티: Issue, IssueComment, Mention (신규)
- 새 용어: "멘션" (글에서 다른 사용자 호출, `@username` 형식)
- 기존 결정 충돌: 없음
- 관련 ADR: [docs/decisions/2026-05-19-issue-mention-notify.md](../decisions/2026-05-19-issue-mention-notify.md) (생성됨)
```

### Step 5. 다음 스킬 체이닝

자동으로 `/bts-spec` 호출.

## 출력 형식

```
🔄 [2/8] /bts-domain
   ├─ BC: issue-tracking
   ├─ grill-with-docs: 신규 용어 1개 (멘션), 기존 결정 충돌 없음
   ├─ ADR: docs/decisions/2026-05-19-issue-mention-notify.md
   └─ glossary 갱신 대기: "멘션" (Maxi 승인 필요)
```

## 실패 / 엣지 케이스

- **BC 모호** (작업이 여러 BC 걸침). Maxi에게 "주된 BC는?" AskUserQuestion. 또는 "이 작업은 BC를 분리해야 하지 않나?" 제안
- **기존 결정과 충돌**. ADR 본문에 "이 작업으로 인해 기존 결정 <ADR 링크>가 무효화됨" 명시. plan-eng-review에서 BLOCKER로 처리됨
- **새 용어 거부**. Maxi가 "이 용어 추가 안 함"이라고 하면 작업 제목/스펙에서 해당 용어 사용 금지 가이드를 plan에 명시

## Fast-track 스킵 조건

`classify.type ∈ {bugfix, chore}` 시 이 단계 전체 스킵. 곧장 `/bts-plan`으로.

이유. 버그 수정/소소한 변경은 도메인 모델 영향 없음. 변경 범위가 좁아 grill-with-docs 비용 > 효익.
