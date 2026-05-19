<!-- Phase 0 PoC 의존성 카탈로그 작성 시 결정한 사항 + 근거 (append-only) -->

# Phase 0 PoC 결정 노트

**원칙**. append-only. 한 결정 = 한 섹션. 결정 + 근거 + 대안 + 영향.

## 2026-05-19 — "라이브러리 모두 설치"를 명세 작성으로 우회

**결정**. Maxi의 "SDD 확인하고 관련 라이브러리 모두 설치 해줘" 요청을 **`docs/poc/dependencies.md` 카탈로그 작성** 으로 대체. 실제 설치(build.gradle.kts / package.json 작성, `gradle build`, `pnpm install`)는 **각 PoC 항목 진입 시점**으로 미룸.

**근거**.

1. **DEVELOPMENT.md §1.16** — "신규 npm/maven 의존성 추가 시 Maxi 확인 필수. SDD/Skills에 명시된 라이브러리만 자동 사용". 50개를 한 번에 깔면 §1.16 정신과 정면 충돌 (검토 단위가 너무 큼).
2. **learnings.md §사전 등록 함정 — Claude의 환각** — "잘못된 라이브러리/API 사용". 카탈로그 형태로 SDD → 카탈로그 → 빌드 파일 순으로 격리해 추적성 확보.
3. **/bts 워크플로우 부적합** — `/bts-impl`의 TDD 강제 (`test:` → `feat:`)는 라이브러리 설치에 적용 불가. spec-compliance-verifier가 BLOCKER 반환 위험.
4. **PR당 한 BC 원칙** — DEVELOPMENT.md §3 "한 PR에 여러 BC 변경 금지". 백엔드+프론트+인프라 동시 변경은 이 원칙 위반.
5. **Phase 0 PoC 본질** — SDD 22.6.1은 PoC를 **13개 항목으로 분해**. 의존성도 같은 단위로 점진 도입이 자연스러움. "1일차에 다 깔기"는 PoC가 의존성 호환성 검증으로 변질됨.
6. **현재 상태** — `backend/`, `apps/web/`, `packages/`, `infra/` 디렉토리 부재 (CLAUDE.md "Phase 0 PoC 진입 후 생성"). 디렉토리도 없는데 빌드 파일을 두는 건 어색.

**대안 (불채택)**.

- A. 한 PR로 일괄 설치. → BC 원칙 위반 + 충돌 디버깅 난이도 ↑.
- B. 영역별 3개 PR (backend / frontend / infra). → 각 PR이 여전히 10~20개 의존성 (스코프 큼).
- C. PoC 항목 단위 점진 설치. → 좋지만 "지금 모든 라이브러리 명세를 한 곳에 정리"라는 가치가 빠짐.
- D. **카탈로그 + PoC별 점진 설치 (채택)**. C의 장점 + 단일 진실 원천(카탈로그) 확보.

**Maxi 확인**. 2026-05-19, 옵션 D 명시 선택.

**영향**.

- 다음 작업. 첫 PoC 항목(워크플로우 엔진 FSM 또는 React 19 부트스트랩 중) 선택 → `/bts` 진입 → 그 항목에 필요한 의존성만 카탈로그 §6 매핑 따라 추가.
- `/bts-codereview`의 향후 룰. "의존성 추가 시 카탈로그에 존재하는지 확인" 검증 항목 신설 후보 (learning 등록 가능).

**관련**. [dependencies.md](dependencies.md), [checklist.md](checklist.md), DEVELOPMENT.md §1.16, learnings.md §사전 등록 함정.

---

## 2026-05-19 — `docs/poc/` 디렉토리 신설

**결정**. PoC 관련 산출물을 `docs/poc/` 하위에 모음 (`docs/sdd/`와 분리).

**근거**.

- `docs/sdd/`는 **설계 문서** (변하지 않는 챕터 26개). PoC 산출물은 **실행 노트** (자주 변경, append-only).
- 머지 후 Obsidian 미러는 `Maxi_wiki/BTS/plans/`, `decisions/`로 가는데 PoC 메타 노트는 별도 영역이 적절.
- `docs/adr/`는 향후 ADR (개별 결정). PoC 카탈로그는 결정이 아닌 명세 모음 → `docs/poc/`.

**대안**.

- `docs/sdd/24-poc-deps.md`로 SDD 챕터 추가. → SDD는 v0.5.0으로 봉인. 24장 추가는 v0.5.1 버전 변경 필요.
- 루트 `dependencies.md`. → docs 영역 분류와 안 맞음.

**영향**. 앞으로 PoC 관련 새 노트는 `docs/poc/` 안에. PoC 종료 시 일부를 `docs/adr/`로 승격 (예. Gantt 차트 결정).

---

## 2026-05-19 — pgmq 설치 방식 보류 (ADR 후보)

**관찰**. SDD 3.6은 pgmq를 PostgreSQL Extension으로 사용한다고 명시. 하지만 `postgres:16` 공식 이미지에는 pgmq가 **포함되지 않음**.

**선택지 (현재 미결정)**.

- 자체 Dockerfile. `FROM postgres:16` + pgmq 빌드/설치
- `tembo-io/pgmq` 사전 빌드 이미지 사용
- `quay.io/coredb/pgmq-pg`

**판단**. Phase 0 PoC §1.5 "pgmq 트랜잭션 일관성 검증" 시점에 ADR로 결정. 지금 결정하면 검증 안 된 선택.

**관련**. checklist.md §1.5, dependencies.md §4.1.

---

## 다음 결정 후보 (PoC 진입 시 발생 예정)

- 첫 PoC 항목 선택 순서 (FSM부터? React 19부터?). Maxi 결정 영역.
- Gantt 차트. 자체 SVG vs Recharts (SDD §1.7 참조).
- Naver Cloud 계정/리전 설정 시점 (Phase 0 후반).
