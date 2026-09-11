# 계획 — CI/CD 다이어트 (검증 GHA · 배포 젠킨스)

> 스펙 `docs/specs/2026-09-11-cicd-gha-migration.md`. 티어 **T2** (가드·CI 표면).

## 순서 원칙 — 검증 공백 0

젠킨스는 지금 **검증과 배포의 유일 경로**다. 먼저 끄면 백엔드·프론트·시각회귀·조립부팅·
인프라봉인이 동시에 0회가 된다. 그래서 **세우고 → 초록을 보고 → 내린다.**

## PR ① 선행 수정 — 참조 정합 구멍 (T2)

- [ ] 1-1 `discriminant-reference-integrity.test.ts` 의 `LIVE_ROOTS` 에 `Jenkinsfile.e2e` 추가
      → **red 를 먼저 본다.** 필터에 `f.startsWith('Jenkinsfile.')` 가 있는데 목록에
      `Jenkinsfile.e2e` 가 없어 **도달 불가 조건**이었다 (가짜 그린 양식)
- [ ] 1-2 red 로 드러난 dangling 참조를 고친다
- [ ] 게이트 — 판별식 전량 EXIT=0 (기준선 792건 · 29.2초)

## PR ② 실측 — runner-smoke (T2)

- [ ] 2-1 `.github/workflows/runner-smoke.yml` 작성. 스펙 §6값을 재고 artifact 로 남긴다
- [ ] 2-2 푸시 후 1회 실행 → 6값 수집
- [ ] 게이트 — 6값이 전부 수집됨. **여기서 나온 숫자가 PR ③ 의 timeout 과 샤드 수를 정한다**

## PR ③ 검증 워크플로우 (T2)

- [ ] 3-1 `verify.yml` — route 잡이 `select-test-scope.ts` 를 1회 실행해 outputs 로 낸다
      (**YAML 에 판정을 다시 적지 않는다** — Jenkinsfile:255 경고)
- [ ] 3-2 discriminants 잡 — 조건 없이 무조건
- [ ] 3-3 frontend · backend(matrix 9) · assemble 잡
- [ ] 3-4 차집합 판별식 — 「YAML 이 실행하는 명령 ⊆ 계산기 출력」. **비-공허 짝 필수**
- [ ] 게이트 — GHA 초록 + 판별식 전량 EXIT=0

## PR ④ E2E 전량 + 시각 회귀 (T2)

- [ ] 4-1 `e2e.yml` — 819건 샤딩. 샤드 수는 PR ② 실측으로 결정
- [ ] 4-2 vite dev 서버 불안정 해소 (맥 7회 중 3회 red · `ERR_CONNECTION_REFUSED`)
- [ ] 4-3 MSW 게이트를 **점 표기**로 (`import.meta.env.VITE_ENABLE_MSW`).
      대괄호 표기는 Vite 정적 치환이 안 돼 **운영 번들에 MSW 가 실린다** (스크래치 빌드 실측)
- [ ] 4-4 시각 회귀 소유자를 `e2e.yml` **하나로** 일원화 (중복 실행 금지)
- [ ] 게이트 — 전량 초록 3회 연속 (flaky 아님을 확인)

## PR ⑤ 게이트 배선 + 젠킨스 축소 (T2)

- [ ] 5-1 `jenkins-build-status.ts` → 검증(GHA `gh pr checks`) + 배포(젠킨스) 2출처 합성.
      **「체크 0건」을 통과로 읽지 않는다** (`merge-skill-contract.test.ts` 계약)
- [ ] 5-2 배포 잡의 `RUN_DEEP`·`GIT_BRANCH_NAME` 공급원 확정 (4개 설계가 전부 미룬 자리)
- [ ] 5-3 젠킨스 검증 stage 철거 — **Maxi 확정 2026-09-11: 포함.** GHA 초록을 본 뒤 같은 작업에서
      지운다. 운영 VM 의 `mem_limit 10g` · `cpus 2.0` 회수가 목적이다
- [ ] 5-4 판별식 27개 재조준 (Jenkinsfile → `.github/workflows`)
- [ ] 게이트 — 배포 1회 성공

## PR ⑥ 문서 전수 동기화 (T1)

- [ ] 6-1 `CLAUDE.md` · `DEVELOPMENT.md` · `behavior-rules.md` · `bts*/SKILL.md`
- [ ] 6-2 `bts-impl/SKILL.md:115` 의 「34개 파일」 → 개수를 지운다
      (CLAUDE.md 가 금지한 「지시문에 개수」 그 자체. 실제는 792건)
- [ ] 6-3 런북 drift 2건 — 「시각 회귀 막는 기계 0개」·「배포 실행 0회」가 코드보다 낡았다
- [ ] 6-4 공개 저장소용 `README.md` · `SECURITY.md` · `CODEOWNERS`

## 결정 기록

| 결정 | 근거 |
|---|---|
| `paths:` 안 쓴다 | 실측 매칭 0건 6파일. 걸면 CI 부재 + required check 영구 pending |
| 판정 정본 = 계산기 | Jenkinsfile:255 가 「두 목록」을 미리 경고. YAML 재기술 금지 |
| E2E 소유자 = `e2e.yml` 하나 | 설계 2안이 6샤드+8샤드로 두 번 돌렸다 |
| 액션 버전 = 현재 메이저 | 설계가 핀한 8종이 전부 node20(구형). 현재는 node24 |
| 젠킨스 철거는 마지막 | 지금 검증·배포 유일 경로. 먼저 끄면 검증 0회 구간이 생긴다 |

## 미해결 — Maxi 확인

- ~~젠킨스 검증 stage 철거 범위~~ → **해소 2026-09-11. 포함한다** (GHA 초록 확인 후).
- ~~저장소 공개가 실측의 선행인가~~ → **해소 2026-09-11. 아니다.**
  push 34602622965 가 private 상태에서 **queued 로 들어갔다** — 3주 전 「결제 차단으로
  `ubuntu-latest` 가 steps=0 으로 죽었다」는 상태는 이미 풀려 있었고, 그 뒤로 아무도
  다시 시도하지 않아 몰랐다. 공개 전환은 **병렬 20잡 무료**를 위한 것이지
  「GHA 를 쓸 수 있느냐」의 조건이 아니다. 공개 시점과 이 작업의 순서를 분리할 수 있다.

## Jira 대조

대응 없음 — 빌드 파이프라인 인프라 작업이라 Jira Cloud 에 대조할 사용자 화면이 없다.
ADS 준용 대상도 아니다 (렌더되는 UI 를 만들지 않는다). 계약 §1 5단계 면제 경로.
