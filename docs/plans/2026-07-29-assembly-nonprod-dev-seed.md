# 조립 앱 비-prod dev 시드 — 로그인 가능한 최소 데이터

> slug: assembly-nonprod-dev-seed
> type: auth
> agent: security-engineer
> 생성: 2026-07-29

## Brief

### 사용자 원문 (Maxi)

> 조립 앱(`:modules:app`)을 비-prod(기본) 프로파일로 띄웠을 때 **로그인이 가능하도록** dev 시드 데이터를 넣는다.
> 현재 계정·프로젝트 0건이라 부팅은 되지만 손검증이 불가능하다.
> D5 결정(`SystemPermissionResolver` 가 비-prod 에서도 실제 판정)에 따라 권한 판정이 실제로 돌기 때문에 시드가 선행 조건이다.
> 주입 방식(Flyway `R__` repeatable / app 전용 `ApplicationRunner` / 수동 SQL)과
> 시드 깊이(관리자 계정만 vs 프로젝트·워크플로 스킴·권한 스킴까지 5계층)는 **스펙 단계에서 옵션으로 제시**할 것.
> **prod 프로파일에는 절대 시드가 들어가면 안 된다.**

### classify 결과 + 수동 정정

| 항목 | 값 | 비고 |
|---|---|---|
| type | `auth` | 알려진 비밀번호를 가진 계정을 만드는 작업이라 보안 등급이 지배적. 그대로 채택 |
| agent | `security-engineer` | 채택. 단 시드 대상이 3개 BC 에 걸쳐 backend-engineer 공동 태스크가 나올 수 있음 (plan 태스크 메타에서 지정) |
| primary_bc | `identity-access` | **주의 — 데이터의 소속 BC 일 뿐, 코드가 놓일 자리가 아니다.** 아래 「선행 제약」 참조 |
| slug | ~~`prod-dev-prod`~~ → `assembly-nonprod-dev-seed` | classify 산출값이 `prod`/`dev` 토큰만 남은 무의미 문자열이라 수동 교정. #321 선례 `assembly-nonprod-bean-wiring` 과 정렬 |
| task_count | 0 | classify 가 태스크 수를 못 냄. `/bts-plan` 에서 실제 분해 |

### 선행 제약 (착수 전 확정 사항)

1. **코드 위치는 조립 모듈 우선 검토.** 시드 대상 데이터가 identity-access(계정) · issue-tracking(프로젝트) ·
   project-workflow(스킴) **3개 BC** 에 걸친다. BC 격리 규칙(한 PR = 한 BC, 다른 BC 는 이벤트 발행만)과 정면으로 만난다.
   #321 이 확립한 A안 — *"조립 계층의 문제는 조립 모듈에서 봉합한다"* — 이 여기에도 적용되는지가 **도메인 단계의 첫 질문**이다.
2. **prod 격리는 타입/구조로 닫는다.** `@Profile("!prod")` 문자열 하나에 의존하는 형태는 오타·프로파일 추가 시 조용히 뚫린다.
   #321 D2 교훈(`FilterType.REGEX` 금지, 개명 시 컴파일 에러화)과 같은 강도의 판별식이 필요하다.
3. **스텁 금지.** #321 D7 — 비-prod 전용이라도 **prod 실구현 경로**로 만든다.
   시드가 도메인 불변식을 우회해 SQL 로 직접 꽂히면, 실제 가입/생성 경로가 요구하는 상태를 못 갖춰
   "로그인은 되는데 그다음이 깨지는" 상태가 된다.
4. **D5 파급.** 비-prod 도 실제 권한 판정이므로 계정만 넣으면 로그인 후 화면이 전부 403 이 될 수 있다.
   「로그인 가능」의 성공 기준을 **어느 화면까지 도달**로 정의할지가 시드 깊이 결정과 같은 질문이다.

### 성공 기준 (초안 — 스펙에서 확정)

- 비-prod 프로파일로 `:modules:app` 부팅 후 **브라우저에서 실제 로그인 성공**
- prod 프로파일 부팅 시 시드 데이터 **0건** (음성 대조군으로 실증, 뮤테이션으로 판별력 확인)
- 다른 9개 BC 의 프로덕션 코드 변경 최소화 (#321 은 0줄 달성)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
