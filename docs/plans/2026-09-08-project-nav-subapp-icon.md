# 프로젝트 내비게이션 서브앱 재편 + 프로젝트 아이콘 신설

> 티어: T3
> slug: project-nav-subapp-icon
> type: migration
> agent: db-engineer
> 생성: 2026-09-08

## Brief

Maxi 원문 4건 + 추가 1건.

1. 사이드바 프로젝트 하위에서 `> 리포트`, `프로젝트 설정` 메뉴 제거
2. 사이드바 프로젝트 `⋯` → 프로젝트 설정 페이지로 들어가면 「프로젝트 이름 변경」 페이지만 보인다.
   사이드바 영역에 설정 메뉴를 리스트업하고 설정 내 하위 메뉴를 노출
3. 설정 페이지에도 요약·타임라인·보드·백로그 등 탭 영역이 노출된다. 설정 페이지에서는 제거
4. 프로젝트 아이콘 메뉴 신설, 사이드바에도 노출
5. (추가) 리포트 페이지에서도 요약·타임라인·보드·백로그 탭 제거

### Maxi 결정 (착수 전 · AskUserQuestion 4문)

| 질문 | 결정 |
|---|---|
| ①로 리포트 그룹을 지우면 리포트 4화면 진입로가 0이 된다 | **프로젝트 탭바에 「리포트」 탭 신설** |
| 프로젝트 아이콘 저장 방식 (백엔드 지원 0) | **풀스택 — 마이그레이션 + API + UI** |
| 설정 페이지 진입 시 사이드바 | **사이드바 전체 교체** + 「← 프로젝트로 돌아가기」 |
| 잔재 worktree 3개 | 유지 + 새 작업 추가 |

### 컨트롤러 결정 (Maxi 에게 고지 후 진행)

`컴포넌트`·`버전`은 정본 9탭인데 경로가 `/projects/$key/settings/...` 라 ③과 정면 충돌한다.
선을 여기서 긋는다 — **두 화면은 정본 탭으로만 유지**(탭바 그대로 · 일반 사이드바)하고
**설정 메뉴 목록에서는 제거**한다. 그러면 「설정 경로 = 탭바 없음」이 예외 있는 규칙이 되지 않고,
「설정 서브앱에 속한 경로 = 탭바 없음」이 규칙이 된다. 정본 목록은 한 곳이어야 한다.

### classify 결과

```
slug=project-nav-subapp-icon · type=migration · agent=db-engineer · tier=T3(선언 · --tier 지정)
primary_bc=null · task_count=0
```

⚠️ classify 는 제목 문자열만 본다. 실측 티어는 머지 전 `detect-tier` 로 다시 잰다.
T3 선언 근거는 ④가 `projects` 테이블 마이그레이션을 요구한다는 것(`MIGRATION` 표면).

### 착수 시점 실측 (원문 판단의 근거)

- 프로젝트 아이콘은 **백엔드에 전혀 없다** — `apps/web/src/api/projects.ts` 의 `projectSchema` 는
  `id/key/name/archived` 4필드이고 `backend/modules/project-workflow/src/main` 에
  `icon`·`avatar` 키워드 실측 0건. 컬럼·API·마이그레이션이 전부 신설이다.
- `ProjectTree.tsx` 의 `REPORT_LINKS`(4) · `SETTINGS_LINKS`(12) 가 그 16화면의 **유일한 진입로**다
  (파일 :460 주석이 2026-09-04 전수 grep 결과로 그렇게 못박아 뒀다). ①을 그냥 지우면 16화면이 고아가 된다.
- 탭바는 `ProjectViewChrome` 이 `useParams.projectKey` 존재만으로 마운트한다 — 경로 종류를 안 본다.
  그래서 설정·리포트에서도 뜬다(③·⑤의 원인).

### 관련 FR (착수 시점 추정 — /bts-spec 이 확정)

- FR-UX-06 · FR-UX-08 — 사이드바/프로젝트 내비게이션 정본
- FR-PJ-02 · FR-PJ-03 — 프로젝트 CRUD (아이콘 필드가 여기 붙는지 신규 FR 인지 spec 이 판정)
- 신규 FR 필요 여부는 `docs/rules/fr-sync-checklist.md` 전수 동기화 대상

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움 · T3 이므로 `docs/specs/` 본체를 여기서 링크)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
