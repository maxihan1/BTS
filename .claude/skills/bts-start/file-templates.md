<!-- /bts-start 가 만드는 파일 2종(plan 스텁 · Draft PR 본문)의 템플릿 정본 -->
# /bts-start 파일 템플릿

`SKILL.md` Step 4·5 가 참조한다. 본문은 여기 1곳에만 둔다.

## §1. plan 스텁 (T2/T3 만)

T2 는 spec 을 `## 스펙` 절로 흡수한 **1파일**. T3 는 `docs/specs/` 를 따로 두고 여기서 링크한다.
**T0/T1 은 이 파일을 만들지 않는다** — 부재가 곧 「T0/T1 선언」이고, 빈 스텁은 티어 판별식을 속인다.

```bash
cd .worktrees/${slug}
mkdir -p docs/plans
date=$(date +%Y-%m-%d)
cat > "docs/plans/${date}-${slug}.md" <<EOF
# ${TITLE}

> 티어: ${TIER}
> slug: ${slug}
> type: ${TYPE}
> agent: ${AGENT}
> 생성: ${date}

## Brief

<사용자 원문 + classify 결과>

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
EOF
```

**머리 `티어:` 행은 생략 금지.** 판별식이 「plan 파일 존재 시 이 행의 값이 선언 티어」로 읽는다.
파일명 `YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID 명시 — T2 단일 파일에도 그대로 적용된다.

## §2. Draft PR 본문

`gh pr create --body-file` 로 넘길 본문. 체크리스트 행은 **그 티어에서 실제로 도는 단계만** 남긴다.

```markdown
## 개요
${USER_INPUT}

## 작업 분류
- 티어: ${TIER} (선언)
- type: ${TYPE} / agent: ${AGENT} / slug: ${slug}

## Plan
docs/plans/${date}-${slug}.md   <!-- T0/T1 은 이 절을 지운다 -->

## 체크리스트
- [ ] 스펙 (/bts-spec)          <!-- T2+ -->
- [ ] plan (/bts-plan)          <!-- T2+ -->
- [ ] plan 리뷰 (/bts-review-plan)  <!-- T2+ -->
- [ ] 구현 (/bts-impl)
- [ ] 코드 리뷰 (/bts-codereview)
```
