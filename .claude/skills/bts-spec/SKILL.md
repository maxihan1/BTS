---
name: bts-spec
description: Called by /bts step 2 — BC identification plus the written specification. Runs on T2/T3 only. Never invoke directly.
---

# /bts-spec

체인 [2]. **BC 식별 + 스펙 작성 + sanity check 를 한 스킬에서 끝낸다**(구 `/bts-domain` 흡수).
T0/T1 은 이 단계에 진입하지 않는다 — 1줄 요약은 게이트 2 요약에 싣는다.

## 선행 읽기

1. `/Users/maxi.moff/Maxi_wiki/BTS/glossary.md` — **전량 Read 금지(27KB)**. `grep -n '^## \|^### '` 로 헤딩 인덱스를 뽑고 작업 키워드와 맞는 항목만 부분 Read
2. `/Users/maxi.moff/Maxi_wiki/BTS/domain/<bc>.md` — `classify.primary_bc` 가 가리키는 BC 노트
3. `docs/decisions/` — 영향받을 ADR 을 키워드 grep. **0건이면 그대로 진행**하고 plan 의 `## 도메인 정리` 에 "관련 ADR: 없음" 을 명시한다
4. `docs/plans/<date>-<slug>.md` — Step 1 이 채울 대상
5. **(전 타입 필수)** `docs/design/jira-parity-contract.md` — Jira Cloud **실물 조회** 절차(§1) + 즉사 계약(§2) + 재사용 자산(§4). ui/design 은 §2·§4 까지, 비-UI 는 §1 만 읽으면 된다

## §1. BC 식별 (구 `/bts-domain`)

```bash
cat .bts-cache/classify.json
# 예. { type: "feature", agent: "backend-engineer", primary_bc: "issue-tracking", tier: "T2" }
```

`primary_bc` 가 `null` 이면 `scripts/workflow/classify-task.ts` 의 `BC_KEYWORDS`(9개 BC 정본)를
참조해 작업 제목에서 추출한다. **키워드 사본을 이 문서에 두지 않는다** — 두 목록은 서로를
검사하지 않아 사본은 drift 가 된다(실제로 이 자리의 구 사본은 7개 BC만 나열해 2개 BC가 누락돼 있었다).

`## 도메인 정리` 에 채울 것 — BC · 영향 엔티티 · 새 용어 · 기존 결정 충돌 여부 · 관련 ADR 링크.
새 용어는 Maxi 승인 후에만 `glossary.md` 에 반영하고, BC 노트는 자동 갱신하지 않는다 — 승인은 `AskUserQuestion` 으로 받는다.
**T3 이고 신규 도메인 개념(새 엔티티·경계·용어)이 있을 때만** `grill-with-docs` 를 부른다.

```
Skill({ skill: "grill-with-docs", args: "BTS <BC> 컨텍스트에서 '<제목>' 의 도메인 모델을 검증. glossary.md · domain/<bc>.md 와의 일치 확인." })
```

## §2. 스펙 9섹션

T2 는 plan 파일의 `## 스펙` 절에 직접 쓴다(별도 파일 없음). T3 만 `docs/specs/<date>-<slug>.md` 를 분리한다.

```markdown
## 사용자 시나리오 (Given-When-Then)
## Jira 대조 (전 타입 필수)
## 기능 요구사항 (FR)
## 비기능 요구사항 (NFR)
## API 인터페이스 (REST)
## 데이터 모델 변경
## 엣지 케이스
## 제약 조건
## 측정 가능한 완료 기준
```

**`## Jira 대조` 작성법** — `jira-parity-contract.md` §1 5단계의 산출물이다. **타입과 무관하게 쓴다.**

1. **§1-0 재사용 grep 먼저.** 명령의 정본은 `jira-parity-contract.md` §1-0 이다 — **여기에 사본을 두지 않는다.**
   그 자리에서 명령을 복사해 쓰고, 기존 행은 출처 URL·조회일 그대로 승계하며
   **이번에 새로 건드리는 조작만** 조회한다.
   ★사본을 지운 이유가 실측이다. 종전 이 줄은 검색 범위를 `docs/specs/ docs/plans/` 로 적은
   **낡은 사본**이었고, 계약이 그 사이 범위를 넓혀도 이쪽은 따라오지 않는다 — 이 저장소가
   이름 붙인 「두 목록이 서로를 검사하지 않는다」의 판본이다. 2026-09-05 에 그 사본을 보고
   `TODOS.md` 를 안 훑어 J12~J21 을 통째로 재조회했다.
2. **실물 조회.** §1 표의 5개 도메인만 근거다. `WebFetch`·`WebSearch` 는 이 컨트롤러 단계에서 돈다 —
   구현 sub-agent 에는 web 도구가 없어 여기서 못 하면 아무도 못 한다.
3. **근거 표.** 행마다 `J1`·`J2` 번호 + **원문 인용 + 출처 URL + 조회일 + Cloud/DC 구분**.
   ui/design 은 조작감 갭을, 비-UI 는 기능 스펙(전환 규칙·권한 스킴·JQL 의미론)을 잰다.
4. **의도적 편차**는 `X1`·`X2` 로 번호를 붙여 근거와 함께 따로 적는다.
5. 대응 개념이 없으면 「**대응 없음** — ADS `<패턴>` **준용**」 + 사유. **절을 통째로 지우지 않는다** —
   조회했고 대응이 없었다는 기록과 애초에 안 본 것은 다르다.

서식 정본은 `docs/specs/2026-08-04-fr-ux-12-f4-command-palette-search.md` 의 같은 절.
`scripts/workflow/jira-research-guard.test.ts` 가 출처 URL 유무를 **푸시 훅**에서 대조한다 (CI 자동 실행은 `eca4a9c7f` 로 꺼져 있다).

## §3. Sanity check (내재화 — 외부 스킬 호출 없음)

작성한 스펙을 **스스로 한 번 흔든다**. 점검 4항목 — ① 누락된 요구사항 ② 모호한 표현
③ 가정 누락 ④ 엣지 케이스 미커버.
**스펙을 재작성하지 않는다. gap 만 열거한다.**

| gap | 처리 |
|---|---|
| 없음 | 스펙 끝에 `## Sanity Check ✅ 통과 (<한 줄 요약>)` append |
| 있음 · 스스로 보강 가능 | **1회만** 보강하고 보강 항목을 `❓ 발견` 라벨로 남긴다 |
| 있음 · Maxi 결정 필요 | 곧장 `AskUserQuestion` |

**루프 상한을 두지 않는다.** 2회째에도 gap 이 남으면 보강을 반복하지 말고 `AskUserQuestion` 으로 묻는다(같은 파일 §3 표와 같은 처리 — 표현을 갈라 두지 않는다) — 반복 보강이 왕복만 늘리고 결론을 못 내던 구간이었다.

## §4. plan 파일 갱신

worktree 의 plan 파일에서 `## 도메인 정리` · `## 스펙` · `## Sanity Check` 3절을 채운다.
T3 이면 `## 스펙` 에는 `docs/specs/…` 링크 + 핵심 시나리오 3줄 요약만 둔다.

## §5. ui 표면이 T2 로 승격된 경우

`type == "ui"` 인데 셸·보안 표면을 함께 건드려 T2 가 된 작업은 **경량 경로**를 쓴다.
스펙은 `## Jira 대조` + `## 엣지 케이스` + `## 측정 가능한 완료 기준` 3섹션으로 줄이고,
§3 sanity check 대신 즉사 계약(§2) 체크로 갈음한다. 단 **시각 검증 기준**(관련 E2E 목록 + 눈확인 항목)은 필수 기재.

## 체이닝 · 출력 · 엣지

`/bts` 컨트롤러에 반환 → 컨트롤러가 `/bts-plan` 호출.

```
🔄 [2/7] /bts-spec
   ├─ BC: issue-tracking (신규 용어 1건 — "멘션", Maxi 승인 대기)
   ├─ 스펙: plan `## 스펙` 절 9섹션 (T2 — 별도 파일 없음)
   └─ Sanity Check ✅ (gap 1건 발견 → 권한 체크 시나리오 보강)
```

- **BC 모호**(여러 BC 걸침). "주된 BC는?" `AskUserQuestion`, 또는 BC 분리 제안
- **기존 결정과 충돌**. plan 에 "기존 ADR <링크> 무효화" 명시 → `plan-eng-review` 가 BLOCKER 로 처리
- **gap 이 계속 나온다**. 작업이 너무 크다 — PR 분할 제안
