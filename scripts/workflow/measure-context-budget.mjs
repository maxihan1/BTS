// 세션 시작 시 **항상** 로드되는 고정 컨텍스트를 항목별로 실측해 토큰 예산과 대조하는 측정 스크립트
// 입력. ~/.claude/CLAUDE.md, CLAUDE.md, <memory>/MEMORY.md, ~/.claude/skills, .claude/skills, 활성 플러그인
// 실행. node scripts/workflow/measure-context-budget.mjs [--json]
//
// 왜 있나. MIGRATION §2.2 의 `실측(Phase 4)` 열은 **재현 가능한 측정 없이는 영원히 빈칸**이다.
//   부록 B 5단계가 이 스크립트를 지목한다. 읽기 전용이고 게이트가 아니다 — 초과해도 exit 0 이다.
//
// ⚠ 이 스크립트는 「무엇이 세션 목록에 실리는가」를 **설정에서 다시 계산**한다.
//   skillOverrides / enabledPlugins 를 안 보면 적용 전후가 같은 값으로 나와 측정이 거짓이 된다.

import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { HOME, MEMORY_DIR, REPO_ROOT } from '../doc-index/config.mjs';

const JSON_OUT = process.argv.includes('--json');

/** 목표. MIGRATION §2.2 — 고정 컨텍스트 합계 상한. */
const TARGET_TOKENS = 8000;

/**
 * 빌트인 스킬은 **디스크에 없다**. 플러그인도 사용자 스킬도 아니라 파일로 셀 방법이 없다.
 * Phase 0(2026-08-13)이 세션 전사에서 15종 목록을 그대로 떼어 6,382 B 로 쟀다.
 * 재현 불가능한 유일한 항목이므로 상수로 두되 출력에 **추정**으로 표기한다.
 */
const BUILTIN_SKILLS = { count: 15, bytes: 6382, estimated: true };

/** MEMORY.md 를 ★ 구획과 라우터로 가르는 지점. 라우터 표의 첫 헤딩이다. */
const MEMORY_ROUTER_HEADING = '## 상황별 인덱스';

// ── 토큰 규칙 ────────────────────────────────────────────────────────────────
//
// 한글은 UTF-8 에서 3바이트고 대체로 글자당 1토큰에 가깝다. 영문은 4바이트당 1토큰에 가깝다.
// 그래서 한 가지 제수로 뭉뚱그리면 한글 문서를 과소, 영문 문서를 과대 평가한다.
// 임계 30% 는 Phase 0 측정을 재현하는 값이다 — 전역 CLAUDE.md(한글 5.9%)를 /4 로 두어
// Phase 0 의 2,086 과 일치시킨다. **행마다 어느 제수를 썼는지 출력**해 근사를 감추지 않는다.
const HANGUL_DIVISOR = 3;
const LATIN_DIVISOR = 4;
const HANGUL_THRESHOLD = 0.3;

function hangulBytes(text) {
  let bytes = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    const isHangul =
      (cp >= 0xac00 && cp <= 0xd7a3) || // 완성형 음절
      (cp >= 0x1100 && cp <= 0x11ff) || // 자모
      (cp >= 0x3130 && cp <= 0x318f); // 호환 자모
    if (isHangul) bytes += Buffer.byteLength(ch, 'utf8');
  }
  return bytes;
}

/** bytes → 토큰. 한글 비중에 따라 제수를 고르고, 고른 제수를 함께 돌려준다. */
function estimateTokens(text) {
  const bytes = Buffer.byteLength(text, 'utf8');
  const ratio = bytes === 0 ? 0 : hangulBytes(text) / bytes;
  const divisor = ratio >= HANGUL_THRESHOLD ? HANGUL_DIVISOR : LATIN_DIVISOR;
  return { bytes, hangulRatio: ratio, divisor, tokens: Math.round(bytes / divisor) };
}

// ── 설정 병합 ────────────────────────────────────────────────────────────────
//
// 우선순위는 전역 → 전역 local → 프로젝트 → 프로젝트 local 이고 뒤가 이긴다.
// 네 파일을 다 읽는 이유. 부록 A.2 는 프로젝트 settings.json 에 쓰지만, 전역에도 같은 키가
// 있어서(현재 enabledPlugins 가 전역에 있다) 한쪽만 보면 실제 세션 상태와 어긋난다.
const SETTINGS_FILES = [
  path.join(HOME, '.claude/settings.json'),
  path.join(HOME, '.claude/settings.local.json'),
  path.join(REPO_ROOT, '.claude/settings.json'),
  path.join(REPO_ROOT, '.claude/settings.local.json'),
];

function loadSettings() {
  const merged = { skillOverrides: {}, enabledPlugins: {} };
  const read = [];
  for (const file of SETTINGS_FILES) {
    if (!fs.existsSync(file)) continue;
    let parsed;
    try {
      parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
      // 깨진 JSON 은 하네스가 조용히 무시한다(부록 A.5). 측정까지 조용하면 안 된다.
      console.warn(`WARN. ${path.relative(REPO_ROOT, file)} 파싱 실패 — 무시한다. ${e.message}`);
      continue;
    }
    read.push(file);
    Object.assign(merged.skillOverrides, parsed.skillOverrides || {});
    Object.assign(merged.enabledPlugins, parsed.enabledPlugins || {});
  }
  return { ...merged, read };
}

// ── 스킬 목록 ────────────────────────────────────────────────────────────────
//
// 세션에는 **본문이 아니라 목록만** 실린다. 그래서 SKILL.md 전체를 세면 20배가 부풀려진다.
// P7(Phase 0) 방식을 그대로 승계한다 — frontmatter 의 name·description 만 뽑아
// 세션 표기형 `- <표시이름>: <설명>\n` 으로 재조립하고 그 바이트를 센다.

/** frontmatter 를 얕게 파싱한다. YAML 전량 지원이 아니라 name·description·플래그만 본다. */
function parseFrontmatter(raw) {
  if (!raw.startsWith('---')) return {};
  const end = raw.indexOf('\n---', 3);
  if (end < 0) return {};
  const body = raw.slice(raw.indexOf('\n') + 1, end);
  const out = {};
  let key = null;
  let buf = [];
  for (const line of body.split('\n')) {
    const m = /^([A-Za-z][A-Za-z0-9_-]*):\s?(.*)$/.exec(line);
    if (m) {
      if (key) out[key] = buf.join('\n');
      key = m[1];
      buf = [m[2]];
    } else if (key) {
      buf.push(line);
    }
  }
  if (key) out[key] = buf.join('\n');
  return out;
}

/** YAML 스칼라 껍데기(따옴표 · `>` · `|`)를 벗기고 한 줄로 편다. 세션 목록이 한 줄이라서다. */
function scalar(value) {
  if (!value) return '';
  let v = value.trim();
  if (v.startsWith('>') || v.startsWith('|')) v = v.replace(/^[>|][-+]?[ \t]*/, '');
  else if (/^"[\s\S]*"$/.test(v) || /^'[\s\S]*'$/.test(v)) v = v.slice(1, -1);
  return v
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
    .join(' ')
    .trim();
}

/**
 * 스킬 디렉터리를 훑어 세션 노출 후보를 만든다.
 *
 * 표시 이름은 frontmatter 의 `name` 이 아니라 **디렉터리 이름**이다 — 실제 세션 목록이 그렇다
 * (`_gstack-command` 의 frontmatter name 은 `gstack` 인데 목록에는 `_gstack-command` 로 뜬다).
 * 여기서 name 을 쓰면 skillOverrides 키(부록 A.2 는 디렉터리 이름으로 적혀 있다)와 어긋난다.
 */
function scanSkillDir(dir, prefix = '') {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const name of fs.readdirSync(dir).sort()) {
    const file = path.join(dir, name, 'SKILL.md');
    if (!fs.existsSync(file)) continue;
    const fm = parseFrontmatter(fs.readFileSync(file, 'utf8'));
    out.push({
      dirName: name,
      display: prefix + name,
      declaredName: scalar(fm.name) || name,
      description: scalar(fm.description),
      // 모델 호출이 막힌 스킬은 세션 목록에 아예 안 실린다.
      modelInvocable: scalar(fm['disable-model-invocation']) !== 'true',
    });
  }
  return out;
}

/** 플러그인 커맨드(`commands/*.md`)도 같은 목록에 실린다. 스킬만 세면 과소 측정이다. */
function scanPluginCommands(dir, prefix) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.md'))
    .sort()
    .map((f) => {
      const fm = parseFrontmatter(fs.readFileSync(path.join(dir, f), 'utf8'));
      return {
        dirName: f.replace(/\.md$/, ''),
        display: prefix + f.replace(/\.md$/, ''),
        declaredName: f.replace(/\.md$/, ''),
        description: scalar(fm.description),
        modelInvocable: true,
      };
    });
}

/**
 * 후보에서 실제 세션 노출분만 남긴다. 왜 걸러지는지를 사유별로 세어 돌려준다 —
 * 「제외했다」만 적으면 skillOverrides 를 실제로 반영했는지 검증할 방법이 없다.
 */
function filterVisible(entries, overrides) {
  const seenNames = new Set();
  const kept = [];
  const dropped = { disabled: [], duplicate: [], override: [] };
  for (const e of entries) {
    if (!e.modelInvocable) {
      dropped.disabled.push(e.display);
      continue;
    }
    // 같은 frontmatter name 이 두 디렉터리에 있으면 세션에는 하나만 뜬다(사전순 먼저).
    if (seenNames.has(e.declaredName)) {
      dropped.duplicate.push(e.display);
      continue;
    }
    seenNames.add(e.declaredName);
    const rule = overrides[e.dirName] ?? overrides[e.declaredName];
    if (rule === 'user-invocable-only' || rule === 'off') {
      dropped.override.push(e.display);
      continue;
    }
    kept.push(e);
  }
  return { kept, dropped };
}

/** 세션 표기형으로 재조립한다. 본문은 세지 않는다 — 세션에는 목록만 실린다. */
function renderSkillList(entries) {
  return entries.map((e) => `- ${e.display}: ${e.description}\n`).join('');
}

// ── 플러그인 ────────────────────────────────────────────────────────────────

function loadPlugins(enabledPlugins) {
  const file = path.join(HOME, '.claude/plugins/installed_plugins.json');
  if (!fs.existsSync(file)) return [];
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  const out = [];
  for (const [key, installs] of Object.entries(data.plugins || {})) {
    const install = installs?.[0];
    if (!install?.installPath) continue;
    // 목록에 없으면 비활성이다. `false` 와 「없음」을 똑같이 다룬다 —
    // 실제 세션에서도 등재되지 않은 플러그인의 스킬은 안 뜬다.
    out.push({ key, root: install.installPath, enabled: enabledPlugins[key] === true });
  }
  return out;
}

/**
 * 플러그인의 SessionStart 훅이 주입하는 바이트를 **실제로 실행해서** 잰다.
 *
 * 훅 스크립트가 SKILL.md 본문을 통째로 실어 나르는 경우가 있어(superpowers 가 그렇다)
 * 파일 크기를 더하는 방식으로는 래퍼 문구를 놓친다. 실행이 유일한 정직한 측정이다.
 * 실패하면 0 이 아니라 **못 쟀다**로 표기한다 — 0 은 「주입이 없다」와 구분되지 않는다.
 */
function measureSessionStartInjection(pluginRoot) {
  const hooksFile = path.join(pluginRoot, 'hooks/hooks.json');
  if (!fs.existsSync(hooksFile)) return { bytes: 0, measured: true };
  let cfg;
  try {
    cfg = JSON.parse(fs.readFileSync(hooksFile, 'utf8'));
  } catch (e) {
    return { bytes: 0, measured: false, error: `hooks.json 파싱 실패: ${e.message}` };
  }
  let bytes = 0;
  for (const group of cfg.hooks?.SessionStart || []) {
    for (const hook of group.hooks || []) {
      if (hook.type !== 'command' || !hook.command) continue;
      try {
        const stdout = execSync(hook.command, {
          env: { ...process.env, CLAUDE_PLUGIN_ROOT: pluginRoot },
          encoding: 'utf8',
          timeout: 10_000,
          stdio: ['ignore', 'pipe', 'pipe'],
        });
        const parsed = JSON.parse(stdout);
        const ctx = parsed.hookSpecificOutput?.additionalContext ?? parsed.additional_context ?? '';
        bytes += Buffer.byteLength(ctx, 'utf8');
      } catch (e) {
        return { bytes: 0, measured: false, error: `훅 실행 실패: ${e.message.split('\n')[0]}` };
      }
    }
  }
  return { bytes, measured: true };
}

// ── 출력 ────────────────────────────────────────────────────────────────────

/** 한글·이모지는 터미널에서 두 칸을 먹는다. 표 정렬을 위해 표시 폭을 따로 센다. */
function displayWidth(text) {
  let w = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    const wide =
      (cp >= 0x1100 && cp <= 0x115f) ||
      (cp >= 0x2e80 && cp <= 0xa4cf) ||
      (cp >= 0xac00 && cp <= 0xd7a3) ||
      (cp >= 0xf900 && cp <= 0xfaff) ||
      (cp >= 0xfe30 && cp <= 0xfe6f) ||
      (cp >= 0xff00 && cp <= 0xff60) ||
      (cp >= 0x1f300 && cp <= 0x1faff);
    w += wide ? 2 : 1;
  }
  return w;
}

const padEndW = (s, n) => s + ' '.repeat(Math.max(0, n - displayWidth(s)));
const padStartW = (s, n) => ' '.repeat(Math.max(0, n - displayWidth(s))) + s;
const num = (n) => n.toLocaleString('en-US');

function main() {
  const settings = loadSettings();
  const overrides = settings.skillOverrides;
  const rows = [];
  const notes = [];

  const push = (label, text, extra = {}) => {
    const m = estimateTokens(text);
    rows.push({ label, ...m, ...extra });
    return m;
  };

  // 1) 지침 파일 3종
  const globalClaudeMd = path.join(HOME, '.claude/CLAUDE.md');
  const projectClaudeMd = path.join(REPO_ROOT, 'CLAUDE.md');
  push('전역 ~/.claude/CLAUDE.md', fs.readFileSync(globalClaudeMd, 'utf8'));
  push('프로젝트 CLAUDE.md', fs.readFileSync(projectClaudeMd, 'utf8'));

  const memoryMdPath = path.join(MEMORY_DIR, 'MEMORY.md');
  const memoryMd = fs.readFileSync(memoryMdPath, 'utf8');
  const splitAt = memoryMd.indexOf(MEMORY_ROUTER_HEADING);
  if (splitAt < 0) {
    // 구획을 못 가르면 통째로 한 행에 넣는다. 조용히 절반만 세는 쪽이 훨씬 나쁘다.
    notes.push(`MEMORY.md 에서 '${MEMORY_ROUTER_HEADING}' 를 못 찾아 ★/라우터를 못 갈랐다.`);
    push('MEMORY.md 전체 (구획 분리 실패)', memoryMd);
  } else {
    const starSection = memoryMd.slice(0, splitAt);
    // 건수는 헤딩의 괄호가 아니라 **실제 렌더된 줄**에서 센다. 헤딩은 사람이 손댈 수 있다.
    const starCount = (starSection.match(/^- .*\[\[.*\]\]$/gm) || []).length;
    push(`MEMORY.md ★ 항상 지킬 것 (${starCount}건)`, starSection);
    push('MEMORY.md 라우터', memoryMd.slice(splitAt));
  }

  // 2) 플러그인 — SessionStart 주입 + 스킬·커맨드 목록
  const plugins = loadPlugins(settings.enabledPlugins);
  for (const plugin of plugins) {
    const short = plugin.key.split('@')[0];
    if (!plugin.enabled) {
      notes.push(`플러그인 '${plugin.key}' 비활성 — 주입·스킬 목록 모두 0 으로 계산했다.`);
      continue;
    }
    const injection = measureSessionStartInjection(plugin.root);
    if (!injection.measured) {
      notes.push(`플러그인 '${plugin.key}' SessionStart 주입을 못 쟀다 (${injection.error}).`);
    } else if (injection.bytes > 0) {
      // 주입 문자열은 훅 실행 결과라 파일이 아니다. 바이트만 받아 왔으므로 제수는 영문 기준이다
      // (현재 유일한 주입인 superpowers 는 전량 영문이다 — 한글 주입이 생기면 문자열을 받아 재야 한다).
      rows.push({
        label: `${short} SessionStart 주입`,
        bytes: injection.bytes,
        hangulRatio: 0,
        divisor: LATIN_DIVISOR,
        tokens: Math.round(injection.bytes / LATIN_DIVISOR),
      });
    }
    const cand = [
      ...scanSkillDir(path.join(plugin.root, 'skills'), `${short}:`),
      ...scanPluginCommands(path.join(plugin.root, 'commands'), `${short}:`),
    ];
    const { kept, dropped } = filterVisible(cand, overrides);
    push(`${short} 스킬 목록 (${kept.length}종)`, renderSkillList(kept), {
      detail: { candidates: cand.length, ...countDropped(dropped) },
    });
  }

  // 3) 사용자 스킬 — 전역 · 프로젝트
  const globalCand = scanSkillDir(path.join(HOME, '.claude/skills'));
  const globalVis = filterVisible(globalCand, overrides);
  push(`전역 스킬 목록 (${globalVis.kept.length}종)`, renderSkillList(globalVis.kept), {
    detail: { candidates: globalCand.length, ...countDropped(globalVis.dropped) },
  });

  const projectCand = scanSkillDir(path.join(REPO_ROOT, '.claude/skills'));
  const projectVis = filterVisible(projectCand, overrides);
  push(`프로젝트 스킬 목록 (${projectVis.kept.length}종)`, renderSkillList(projectVis.kept), {
    detail: { candidates: projectCand.length, ...countDropped(projectVis.dropped) },
  });

  // 4) 빌트인 — 디스크에 없어 실측 불가
  rows.push({
    label: `빌트인 스킬 (${BUILTIN_SKILLS.count}종 · 추정)`,
    bytes: BUILTIN_SKILLS.bytes,
    hangulRatio: 0,
    divisor: LATIN_DIVISOR,
    tokens: Math.round(BUILTIN_SKILLS.bytes / LATIN_DIVISOR),
    estimated: true,
  });

  // skillOverrides 의 죽은 키 — 부록 A.5 가 지목한 「오타는 에러 없이 조용히 무효」 표면이다.
  const allDirNames = new Set(
    [...globalCand, ...projectCand].flatMap((e) => [e.dirName, e.declaredName]),
  );
  const deadOverrides = Object.keys(overrides).filter((k) => !allDirNames.has(k));
  if (deadOverrides.length) {
    notes.push(
      `skillOverrides 키 ${deadOverrides.length}건이 어느 스킬과도 안 맞는다 (오타 의심): ${deadOverrides.join(', ')}`,
    );
  }

  const totalTokens = rows.reduce((s, r) => s + r.tokens, 0);
  const totalBytes = rows.reduce((s, r) => s + r.bytes, 0);

  if (JSON_OUT) {
    console.log(
      JSON.stringify(
        {
          measuredAt: new Date().toISOString(),
          targetTokens: TARGET_TOKENS,
          totalBytes,
          totalTokens,
          rows,
          notes,
          settingsRead: settings.read,
        },
        null,
        2,
      ),
    );
    return;
  }

  const LABEL_W = Math.max(34, ...rows.map((r) => displayWidth(r.label)));
  const line = '─'.repeat(LABEL_W + 32);
  console.log(`고정 컨텍스트 예산 — 세션 시작 시 항상 로드되는 분량 (${new Date().toISOString().slice(0, 10)} 측정)`);
  console.log('');
  console.log(
    `${padEndW('항목', LABEL_W)} ${padStartW('bytes', 9)} ${padStartW('한글%', 7)} ${padStartW('규칙', 6)} ${padStartW('토큰', 7)}`,
  );
  console.log(line);
  for (const r of rows) {
    console.log(
      `${padEndW(r.label, LABEL_W)} ${padStartW(num(r.bytes), 9)} ${padStartW(`${(r.hangulRatio * 100).toFixed(1)}%`, 7)} ${padStartW(`/${r.divisor}`, 6)} ${padStartW(num(r.tokens), 7)}`,
    );
  }
  console.log(line);
  console.log(
    `${padEndW('합계', LABEL_W)} ${padStartW(num(totalBytes), 9)} ${padStartW('', 7)} ${padStartW('', 6)} ${padStartW(num(totalTokens), 7)}`,
  );
  console.log('');
  const diff = totalTokens - TARGET_TOKENS;
  const verdict = diff <= 0 ? `여유 ${num(-diff)}` : `초과 ${num(diff)}`;
  console.log(
    `목표 ${num(TARGET_TOKENS)} 대비 ${verdict} (${Math.round((totalTokens / TARGET_TOKENS) * 100)}%).`,
  );
  console.log('');
  console.log('스킬 목록은 SKILL.md 본문이 아니라 name+description 만 세션 표기형으로 재조립해 잰 값이다.');
  console.log(
    `빌트인 ${BUILTIN_SKILLS.count}종은 디스크에 없어 **실측 불가**다. Phase 0 세션 전사 기준 ${num(BUILTIN_SKILLS.bytes)} B 를 상수로 썼다 — 추정치다.`,
  );
  for (const r of rows) {
    if (!r.detail) continue;
    const d = r.detail;
    console.log(
      `  · ${r.label} — 후보 ${d.candidates} / 모델호출차단 ${d.disabled} / 이름중복 ${d.duplicate} / skillOverrides 비노출 ${d.override}`,
    );
  }
  for (const n of notes) console.log(`  ⚠ ${n}`);
  console.log('');
  console.log(`설정 병합 원천 ${settings.read.length}건. ${settings.read.join(' → ')}`);
}

function countDropped(dropped) {
  return {
    disabled: dropped.disabled.length,
    duplicate: dropped.duplicate.length,
    override: dropped.override.length,
  };
}

main();
