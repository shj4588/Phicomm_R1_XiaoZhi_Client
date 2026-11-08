'use strict';
/**
 * local.js —— 本地曲库（原 r1-music / 8010 那半边，2026-10-17 并入 mini-lx）
 *
 * 合并前：r1-music 是一台独立的 Python(Flask) 容器（8010，mutagen 读标签），
 *         mini-lx 是 Node 容器（8100，在线音源），两个容器两个端口。
 * 合并后：一个容器、一个进程、一个端口 8100 ——
 *         /index.json 一次吐出「本地曲目 + 在线随机池 + 电台」，
 *         R1 的 files/music_index 只写一个地址就够了。
 *
 * ★ 标签读取用 music-metadata（npm，纯 JS，无需编译）取代 mutagen。
 *   它只读文件头部几 KB（我们连时长都不读），比扫整首快得多。
 *
 * ★★ 扫描结果落盘 files/local_cache.json：按 size+mtimeMs 命中就跳过解析。
 *   1188 首第一次全解析要点时间（并发 8 也要几十秒），之后只有新增/改动的
 *   才真正解析 —— 容器重建也丢不掉（files 是挂载卷）。
 */
const fs = require('fs');
const path = require('path');

const DIR = process.env.MUSIC_DIR || '/music';
const RADIOS_FILE = process.env.RADIOS_FILE || path.join(__dirname, 'files', 'radios.json');
const CACHE_FILE = path.join(__dirname, 'files', 'local_cache.json');
const EXTS = ['.mp3', '.m4a', '.flac', '.wav', '.ogg', '.aac', '.wma', '.ape'];
/** 列表缓存（只防连点，真正的省时靠落盘的标签 memo） */
const TTL = 10000;
/** 并发解析标签的线程数（IO 密集，8 个够） */
const CONC = 8;

let mm = null;
try { mm = require('music-metadata'); } catch { mm = null; }

const MIME = {
  '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.flac': 'audio/flac',
  '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.aac': 'audio/aac',
  '.wma': 'audio/x-ms-wma', '.ape': 'audio/x-monkeys-audio',
};

/** '晴天 - 周杰伦' -> ['晴天','周杰伦'] */
function hasCjk(s) { return /[一-鿿]/.test(String(s || '')); }

function splitTitle(name) {
  // ⚠️ 裸 '-' 只在两侧都是中文时才拆 —— 否则 'Lose-Yourself' 会被误拆
  for (const sep of [' - ', '－']) {
    if (name.includes(sep)) {
      const i = name.indexOf(sep);
      const a = name.slice(0, i).trim(), b = name.slice(i + sep.length).trim();
      if (a && b) return [a, b];
    }
  }
  const i = name.indexOf('-');
  if (i > 0) {
    const a = name.slice(0, i).trim(), b = name.slice(i + 1).trim();
    if (a && b && hasCjk(a) && hasCjk(b)) return [a, b];
  }
  return [name, ''];
}

// ---------------- 标签 memo ----------------
let MEMO = null;
let memoDirty = false;

function loadMemo() {
  if (MEMO) return MEMO;
  MEMO = {};
  try {
    const j = JSON.parse(fs.readFileSync(CACHE_FILE, 'utf8'));
    for (const t of (j.tracks || [])) {
      if (t && t.file) MEMO[t.file] = t;
    }
  } catch { /* 没有就从空开始 */ }
  return MEMO;
}

function saveMemo() {
  try {
    fs.mkdirSync(path.dirname(CACHE_FILE), { recursive: true });
    fs.writeFileSync(CACHE_FILE, JSON.stringify({ tracks: Object.values(MEMO) }));
    memoDirty = false;
  } catch { /* 只读环境也能跑，只是重启后要重解析一遍 */ }
}

/** 拿一个文件的标签：memo 命中直接用，否则解析后写回 */
async function tagsFor(abs, rel) {
  let size = 0, mt = 0;
  try {
    const st = fs.statSync(abs);
    size = st.size; mt = Math.floor(st.mtimeMs);
  } catch { return { title: '', artist: '', album: '' }; }
  const m = loadMemo()[rel];
  if (m && m.size === size && m.mtimeMs === mt) {
    return { title: m.title || '', artist: m.artist || '', album: m.album || '' };
  }
  let t = { title: '', artist: '', album: '' };
  if (mm) {
    try {
      const md = await mm.parseFile(abs, {
        duration: false, skipCovers: true, skipPostHeaders: true,
      });
      const c = (md && md.common) || {};
      t = {
        title: c.title || '',
        artist: c.artist || c.albumartist || '',
        album: c.album || '',
      };
    } catch { /* 读不到就交给文件名解析 */ }
  }
  MEMO[rel] = {
    file: rel, size, mtimeMs: mt,
    title: t.title, artist: t.artist, album: t.album,
  };
  memoDirty = true;
  return t;
}

// ---------------- 扫描 ----------------
function walk(dir, base, out) {
  let ents;
  try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
  const dirs = [], files = [];
  for (const e of ents) {
    if (e.isDirectory()) dirs.push(e.name);
    else if (e.isFile() && EXTS.includes(path.extname(e.name).toLowerCase())) files.push(e.name);
  }
  files.sort();
  dirs.sort();
  for (const f of files) {
    out.push(path.relative(base, path.join(dir, f)).replace(/\\/g, '/'));
  }
  for (const d of dirs) walk(path.join(dir, d), base, out);
}

let CACHED = { ts: 0, list: null, radios: null };

function readRadios() {
  try {
    const j = JSON.parse(fs.readFileSync(RADIOS_FILE, 'utf8'));
    const arr = Array.isArray(j) ? j : (j.radios || []);
    // 字段名各家不同（r1-music 用 url，别的清单可能写 stream/addr/src），都认
    const u = r => String(r.url || r.stream || r.addr || r.src || r.link || '');
    return arr.filter(r => r && r.name && u(r))
      .map(r => ({ name: String(r.name), url: u(r) }));
  } catch { return []; }
}

/**
 * 列出本地曲目（按路径排序，和 Python 版一致）。
 * @returns {Promise<Array<{file:string,title:string,artist:string,album:string}>>}
 */
async function list() {
  const now = Date.now();
  if (CACHED.list && now - CACHED.ts < TTL) return CACHED.list;

  const files = [];
  walk(DIR, DIR, files);

  // 并发解析标签，但**结果按 files 原顺序**回填（顺序 = 曲库序号，不能乱）
  const tags = new Array(files.length);
  for (let i = 0; i < files.length; i += CONC) {
    const chunk = files.slice(i, i + CONC);
    const rs = await Promise.all(chunk.map(rel => tagsFor(path.join(DIR, rel), rel)));
    for (let k = 0; k < rs.length; k++) tags[i + k] = rs[k];
  }
  if (memoDirty) saveMemo();

  const out = files.map((rel, i) => {
    const parts = rel.split('/');
    const stem = path.basename(rel, path.extname(rel));
    // 1) 文件名 / 目录名解析（兜底）
    let fTitle = stem, fArtist = '';
    const [a, b] = splitTitle(stem);
    if (b) { fTitle = a; fArtist = b; }
    else if (parts.length >= 2) { fArtist = parts[parts.length - 2]; }
    // 2) 内嵌标签优先
    const tg = tags[i] || {};
    return {
      file: rel,
      title: tg.title || fTitle,
      artist: tg.artist || fArtist,
      album: tg.album || '',
    };
  });

  CACHED = { ts: now, list: out, radios: readRadios() };
  return out;
}

/** 电台清单（与 list() 共用缓存，避免每请求都读盘） */
function radios() {
  if (CACHED.radios) return CACHED.radios;
  return readRadios();
}

/** 强制重扫（传完歌不想等 TTL 就调 /reindex） */
function reindex() {
  CACHED = { ts: 0, list: null, radios: null };
}

/**
 * 写回电台清单（Web 上「加台 / 删台」用）。
 * 只留 name + 能认出来的 http(s) 地址，其余字段一律丢弃 —— 免得存进去脏数据。
 * @param {Array<{name:string,url:string}>} list
 * @returns {{ok:boolean,count:number,err?:string}}
 */
function saveRadios(list) {
  const urlOf = r => String((r && (r.url || r.stream || r.addr || r.src || r.link)) || '').trim();
  const arr = (Array.isArray(list) ? list : [])
    .map(r => ({ name: String((r && r.name) || '').trim(), url: urlOf(r) }))
    .filter(r => r.name && /^https?:\/\//i.test(r.url));
  try {
    fs.mkdirSync(path.dirname(RADIOS_FILE), { recursive: true });
    fs.writeFileSync(RADIOS_FILE, JSON.stringify(arr, null, 2), 'utf8');
    // 只换 radios，别把已扫好的本地列表缓存清掉（否则加个台要重扫几千个文件）
    CACHED = { ts: CACHED.ts, list: CACHED.list, radios: arr };
    return { ok: true, count: arr.length };
  } catch (e) {
    return { ok: false, count: 0, err: String((e && e.message) || e) };
  }
}

/**
 * 相对路径 -> 绝对路径，并挡住 ../ 穿越。
 *
 * ⚠️ 两边都要 resolve：DIR 可能是 'C:/a/b'（compose 里写的）也可能是 '/music'，
 * 而与 path.sep（Windows 是 '\'）直接拼会永远不相等 —— 实测在 Windows 上
 * 会因此把所有文件都判成「不在目录内」而 404。
 */
function absOf(rel) {
  const base = path.resolve(DIR);
  const abs = path.resolve(base, rel);
  if (abs !== base && !abs.startsWith(base + path.sep)) return null;
  return abs;
}

function mimeOf(rel) {
  return MIME[path.extname(rel).toLowerCase()] || 'audio/mpeg';
}

/**
 * 自检：本地库为什么是 0 首？（挂载没生效 / 目录空的 / 扩展名不认 / 权限）
 * /api/health 的 local 字段直接用它 —— 不用进容器就能看出来。
 */
function diag() {
  const d = {
    dir: DIR, exists: false, isDir: false,
    topEntries: [], scanned: 0, sample: [], hasTags: !!mm, err: null,
  };
  try {
    const st = fs.statSync(DIR);
    d.exists = true;
    d.isDir = st.isDirectory();
    if (d.isDir) d.topEntries = fs.readdirSync(DIR).slice(0, 10);
  } catch (e) {
    d.err = String(e && e.message || e);   // 多半是 ENOENT（没挂上）
    return d;
  }
  try {
    const files = [];
    walk(DIR, DIR, files);
    d.scanned = files.length;
    d.sample = files.slice(0, 5);
  } catch (e) {
    d.err = String(e && e.message || e);   // 多半是 EACCES（权限）
  }
  return d;
}

module.exports = {
  list, radios, saveRadios, reindex, absOf, mimeOf, diag, DIR, RADIOS_FILE, hasTags: !!mm,
};
