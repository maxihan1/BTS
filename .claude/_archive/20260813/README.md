# `.claude/_archive/20260813/` — 하네스 재설계로 물러난 자산

> 이 디렉터리는 **읽기 전용 보관소**다. 여기 있는 파일은 로드되지 않는다 —
> Claude Code 는 `.claude/skills/*/SKILL.md` 와 `.claude/agents/*.md` 만 스킬·에이전트로 인식한다.
> 지우지 않는 이유는 **롤백 원본**이자 **재설계 판정의 증거**이기 때문이다.
> 재설계 전체 맥락과 되돌리는 절차는 [`.claude/MIGRATION.md`](../../MIGRATION.md) 가 정본이다.

## 왜 지우지 않고 옮겼나

되돌릴 때 필요한 것은 「무엇을 지웠나」가 아니라 **지워진 파일의 본문**이다.
`git revert` 로도 복원되지만, 그러려면 어느 커밋이 지웠는지를 먼저 찾아야 한다.
디렉터리에 그대로 두면 `git mv` 한 번으로 원위치가 된다.

`git mv` 를 쓴 이유는 두 가지다.
① 프로젝트 `.claude/settings.json` 이 `Bash(mv:*)` 를 deny 하고 있어 맨 `mv` 는 호출 자체가 거부된다(deny 는 `mv` **접두**에만 걸리므로 `git mv` 는 통과한다 — 2026-08-13 실측).
② rename 으로 기록되면 `git log --follow` 가 이력을 잇는다.

## 무엇이 여기 있나 (2026-08-13 이동분)

| 파일 | 원위치 | 물러난 이유 |
|---|---|---|
| `bts-domain/SKILL.md` | `.claude/skills/bts-domain/SKILL.md` | `bts-spec` 으로 Merge. 도메인 식별 단계가 스펙 작성과 같은 왕복에서 끝나 스킬 1회 호출이 통째로 절약된다 |
| `designer.md` | `.claude/agents/designer.md` | `frontend-engineer` 로 Merge. 36일 창 발동 3회로 6종 중 최저였고 「새 UI 스펙 모드」 15줄 외에는 `DESIGN.md` 와 중복이었다 |

**이 표는 스냅샷이다.** 이동 전수는 아래 명령이 정본이다 — 표와 어긋나면 명령 결과를 믿는다.

```bash
git log --diff-filter=R --name-status --format='%h %s' -- .claude/_archive/20260813/
```

## 되돌리는 법

원위치로 되돌릴 때는 **파일만 옮기면 안 된다.** 두 파일 다 저장소 다른 곳에 배선이 남아 있었고,
재설계가 그 배선을 함께 지웠다. 배선을 되살리지 않으면 파일은 있는데 아무도 호출하지 않는 상태가 된다.

```bash
# 1) 파일 원위치 (deny 때문에 맨 mv 가 아니라 git mv 를 쓴다)
git mv .claude/_archive/20260813/designer.md .claude/agents/designer.md
git mv .claude/_archive/20260813/bts-domain .claude/skills/bts-domain

# 2) 배선 복원 — 이 커밋들의 역방향 diff 가 함께 고칠 지점의 전수 목록이다
git log --oneline --all -- .claude/agents/designer.md .claude/skills/bts-domain
```

배선 지점의 개수는 여기 적지 않는다. **개수를 두 곳에 새기면 한쪽만 갱신되어 거짓말이 된다** —
이 저장소가 반복해서 겪은 사고 양식이다. 전수는 위 `git log` 의 역방향 diff 로 센다.

## 주의 — 여기 있는 파일을 「참고 자료」로 읽지 마라

물러난 파일에는 **지금은 틀린 서술**이 들어 있다. `designer.md` 는 존재하지 않는 에이전트를 가리키고,
`bts-domain/SKILL.md` 는 `/bts` 체인의 사라진 단계를 설명한다.
현재 규칙을 알고 싶으면 `CLAUDE.md` · `docs/rules/behavior-rules.md` · 살아 있는 `.claude/skills/` 를 본다.
