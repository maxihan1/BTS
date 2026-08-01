<!-- FR/명세 변경 시 전수 동기화 체크리스트 9종 + verify 강제 — CLAUDE.md 에서 분리 -->

# 명세/범위 변경 시 전수 동기화

> 정본. 이 파일. `CLAUDE.md` 는 요약과 링크만 갖는다.
> 도입 배경. 2026-06-05 — README/CLAUDE FR 카운트가 여러 FR 에 걸쳐 117 에 멈춰 있던 사고.

**초기 기획과 달라지는 모든 변경(신규 기능 / 기능·범위 변경 / 스펙 deviation / FR 추가·삭제)은
같은 PR 안에서 영향받는 모든 정본·미러·카운트를 전수 동기화한다.** 일부만 고쳐 문서 간 drift 를
남기지 않는다.

## 동기화 대상 체크리스트

1. `docs/plan/fr-index.md` — FR ID 행 · §A.2 BC 카운트 · 합계 · 상단 주석 카운트
2. `docs/sdd/` — 해당 챕터 + `02-requirements.md` (FR ID 표)
3. `docs/plan/product/<bc>.md` — FR § 본문 · D단계 체크박스 · §N 헤더 `(FR-XX, N개)` ·
   파일 L1 주석 · `소속 FR. N개` · BC 완료 게이트 `(FR-XX N개)`
4. `docs/plan/README.md` — §1 BC 테이블 행(FR 수 **및 진척 열**) · 합계 ·
   **본문 산문의 `N FR` 표기 전수**(`grep -nE '[0-9]{2,} FR' docs/plan/README.md`)
5. `CLAUDE.md` — FR 총수 등 카운트/상태 표기
6. ADR(`docs/decisions/` 또는 `docs/adr/`) · plan(`docs/plans/`) ·
   `docs/progress.html`(`node scripts/build-dashboard.mjs` 재생성)
7. Obsidian `Maxi_wiki/BTS/` — history · glossary · domain/<bc> · decisions·plans 미러
8. 자동 메모리 (`~/.claude/projects/.../memory/`) — `node scripts/build-doc-index.mjs` 재생성
9. `CHANGELOG.md` — `[Unreleased]` 블록의 `**범위**` · `**상태**` · §BC 요약 표 행
   (동결된 릴리스 블록은 건드리지 않는다)

## 강제

머지 전 `bash scripts/verify-master-plan.sh` 통과 필수. 다음을 자동 차단한다 (종료 4).

- FR ID 정합 (SDD ↔ plan 양방향 차집합)
- 카운트 drift — fr-index 합계·§A.2 / README 합계·BC테이블 /
  product `(FR-XX,N개)` 헤더·`소속 FR` / CLAUDE·README·CHANGELOG 살아있는 구역의 `N FR` (룰 E)
- README §1 진척 열 ⟺ product 미완 D 마커 양방향 정합 (룰 H)

**새 카운트 표기를 verify 가 못 잡는 형식으로 추가했다면 verify 스크립트도 같은 PR 에서
확장한다.** 룰 추가 시 일부러 위반을 넣어 fail 을 확인한다 — 위반을 넣어보지 않은 룰은
조용히 통과하는 장식일 수 있다.

## 관련

- 문서 인덱스 재생성·판별식. [`docs/rules/doc-index.md`](doc-index.md)
- 명령어·디렉토리 구조. [`docs/rules/commands.md`](commands.md)
