import { Maximize, Minus, Network, Plus } from "lucide-react";
import { useLayoutEffect, useMemo, useRef, useState } from "react";

import { useWidth } from "../../components/charts";

/*
 * The note's mind map as a two-sided tree: the subject in the middle, its main themes to the left
 * and right, details further out. Drawn at real size from measured text, zoomed to fit the card when
 * that keeps the text readable, and otherwise scrolled or dragged around.
 */

const LEVELS = [
  { max: 190, font: 15, weight: 800, padX: 16, padY: 11 },
  { max: 190, font: 13, weight: 700, padX: 13, padY: 8 },
  { max: 180, font: 12.5, weight: 600, padX: 11, padY: 7 },
  { max: 170, font: 12, weight: 500, padX: 0, padY: 2 },
];
const COL_GAP = 40;
const SIB_GAP = [0, 22, 10, 4];
const gap = (level) => SIB_GAP[level] ?? 0;
const MARGIN = 20;
const MIN_READABLE = 0.72;

let ctx = null;
function textWidth(text, level) {
  const L = LEVELS[level];
  if (!ctx && typeof document !== "undefined") ctx = document.createElement("canvas").getContext("2d");
  if (!ctx) return text.length * L.font * 0.58;
  ctx.font = `${L.weight} ${L.font}px Manrope, ui-sans-serif, system-ui, sans-serif`;
  return ctx.measureText(text).width;
}

/** Words into lines no wider than the level allows; at most three lines, the last one cut with "...". */
function wrap(text, level) {
  const L = LEVELS[level];
  const room = L.max - L.padX * 2;
  const lines = [];
  let line = "";
  for (const word of String(text).split(/\s+/).filter(Boolean)) {
    const next = line ? `${line} ${word}` : word;
    if (textWidth(next, level) <= room || !line) line = next;
    else { lines.push(line); line = word; }
  }
  if (line) lines.push(line);
  if (lines.length > 3) {
    lines.length = 3;
    lines[2] = `${lines[2].replace(/\s*\S*$/, "")}...`;
  }
  return lines.length ? lines : [""];
}

function sizeOf(label, level) {
  const L = LEVELS[level];
  const lines = wrap(label, level);
  const lh = Math.round(L.font * 1.35);
  const w = Math.ceil(Math.max(...lines.map((l) => textWidth(l, level)))) + L.padX * 2 + (level === 3 ? 12 : 0);
  return { lines, lh, w: Math.min(L.max, Math.max(level === 0 ? 120 : 40, w)), h: lines.length * lh + L.padY * 2 };
}

/** Builds the laid-out nodes and links from `{ center, branches }`. */
function layout(map) {
  const toNode = (item, level) => ({
    label: String(item?.label ?? "").trim(),
    level,
    ...sizeOf(String(item?.label ?? ""), level),
    children: level < 3 ? (item?.children ?? []).filter((c) => c?.label).slice(0, 6).map((c) => toNode(c, level + 1)) : [],
  });
  const branches = (map.branches ?? []).filter((b) => b?.label).slice(0, 6).map((b) => toNode(b, 1));
  const center = { label: map.center || "Main idea", level: 0, ...sizeOf(map.center || "Main idea", 0) };

  const span = (node) => {
    const kids = node.children.reduce((s, c) => s + span(c), 0) + Math.max(0, node.children.length - 1) * gap(node.level + 1);
    node.span = Math.max(node.h, kids);
    return node.span;
  };
  const right = branches.slice(0, Math.ceil(branches.length / 2));
  const left = branches.slice(Math.ceil(branches.length / 2));
  const sideHeight = (list) => list.reduce((s, b) => s + span(b), 0) + Math.max(0, list.length - 1) * gap(1);
  const rightH = sideHeight(right);
  const leftH = sideHeight(left);

  // Column widths per depth, per side, so long labels never collide with the next column.
  const colWidths = (list) => {
    const widths = [];
    const walk = (n) => { widths[n.level] = Math.max(widths[n.level] ?? 0, n.w); n.children.forEach(walk); };
    list.forEach(walk);
    return widths;
  };
  const rw = colWidths(right);
  const lw = colWidths(left);
  const sideW = (w) => w.slice(1).reduce((s, x) => s + (x ?? 0) + COL_GAP, 0);
  const height = Math.max(rightH, leftH, center.h) + MARGIN * 2;
  const width = sideW(lw) + center.w + sideW(rw) + MARGIN * 2;
  const cx = MARGIN + sideW(lw) + center.w / 2;
  const cy = height / 2;

  const nodes = [{ ...center, x: cx - center.w / 2, y: cy - center.h / 2, side: 0 }];
  const links = [];

  const place = (node, side, colX, top, parentAnchor, widths) => {
    const y = top + node.span / 2 - node.h / 2;
    const x = side > 0 ? colX : colX - node.w;
    nodes.push({ ...node, x, y, side });
    const near = { x: side > 0 ? x : x + node.w, y: y + node.h / 2 };
    links.push({ from: parentAnchor, to: near, level: node.level, side });
    const far = { x: side > 0 ? x + node.w : x, y: y + node.h / 2 };
    if (node.level === 3) return;
    const kidsH = node.children.reduce((s, c) => s + c.span, 0) + Math.max(0, node.children.length - 1) * gap(node.level + 1);
    let t = top + node.span / 2 - kidsH / 2;
    const nextCol = side > 0 ? colX + widths[node.level] + COL_GAP : colX - widths[node.level] - COL_GAP;
    for (const c of node.children) {
      place(c, side, nextCol, t, far, widths);
      t += c.span + gap(node.level + 1);
    }
  };

  let t = cy - rightH / 2;
  for (const b of right) {
    place(b, 1, cx + center.w / 2 + COL_GAP, t, { x: cx + center.w / 2, y: cy }, rw);
    t += b.span + gap(1);
  }
  t = cy - leftH / 2;
  for (const b of left) {
    place(b, -1, cx - center.w / 2 - COL_GAP, t, { x: cx - center.w / 2, y: cy }, lw);
    t += b.span + gap(1);
  }
  return { nodes, links, width, height };
}

const LINK_COLOR = ["", "var(--color-chart)", "#b9cbc5", "#d3ddd9"];

function Node({ n }) {
  const L = LEVELS[n.level];
  const anchor = n.level === 0 ? "middle" : n.side > 0 ? "start" : "end";
  const tx = n.level === 0 ? n.x + n.w / 2 : n.side > 0 ? n.x + L.padX + (n.level === 3 ? 12 : 0) : n.x + n.w - L.padX - (n.level === 3 ? 12 : 0);
  const fill = ["var(--color-primary)", "var(--color-primary-tint)", "var(--color-surface)", "none"][n.level];
  const ink = ["#fff", "var(--color-primary-pressed)", "var(--color-ink)", "var(--color-ink-muted)"][n.level];
  return (
    <g>
      {n.level < 3 && (
        <rect x={n.x} y={n.y} width={n.w} height={n.h} rx={n.level === 0 ? 18 : n.level === 1 ? 13 : 10}
          fill={fill} stroke={n.level === 2 ? "var(--color-line)" : "none"} strokeWidth="1" />
      )}
      {n.level === 3 && (
        <circle cx={n.side > 0 ? n.x + 4 : n.x + n.w - 4} cy={n.y + n.h / 2} r="3" fill="var(--color-chart)" opacity="0.55" />
      )}
      <text textAnchor={anchor} fontSize={L.font} fontWeight={L.weight} fill={ink}>
        {n.lines.map((line, i) => (
          <tspan key={i} x={tx} y={n.y + L.padY + n.lh * i + n.lh / 2} dy="0.35em">{line}</tspan>
        ))}
      </text>
    </g>
  );
}

/** The mind map, or a short note (with `action`, for example Re-run) when there is none. */
export function MindMap({ map, action }) {
  const valid = map && Array.isArray(map.branches) && map.branches.some((b) => b?.label);
  const drawing = useMemo(() => (valid ? layout(map) : null), [map, valid]);
  const [boxRef, boxW] = useWidth();
  const [zoom, setZoom] = useState(null);
  const scroller = useRef(null);
  const drag = useRef(null);

  const fit = drawing && boxW ? Math.min(1, (boxW - 2) / drawing.width) : 1;
  const z = zoom ?? Math.max(fit, Math.min(1, MIN_READABLE));

  // Opens centred on the subject when the map is wider than the card.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el || !drawing) return;
    el.scrollLeft = (el.scrollWidth - el.clientWidth) / 2;
    el.scrollTop = (el.scrollHeight - el.clientHeight) / 2;
  }, [drawing, boxW]);

  if (!drawing) {
    return (
      <div className="card flex flex-col items-center px-6 py-14 text-center">
        <span className="mb-3 flex size-11 items-center justify-center rounded-full bg-primary-tint text-primary">
          <Network className="size-5" aria-hidden="true" />
        </span>
        <div className="text-sm font-bold">No mind map for this note yet</div>
        <div className="mt-1 max-w-sm text-sm text-ink-muted">Re-run the summary to draw one.</div>
        {action && <div className="mt-4">{action}</div>}
      </div>
    );
  }

  const onPointerDown = (e) => {
    if (e.pointerType !== "mouse" || e.button !== 0) return;
    const el = scroller.current;
    drag.current = { x: e.clientX, y: e.clientY, left: el.scrollLeft, top: el.scrollTop };
    el.setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e) => {
    const d = drag.current;
    if (!d) return;
    scroller.current.scrollLeft = d.left - (e.clientX - d.x);
    scroller.current.scrollTop = d.top - (e.clientY - d.y);
  };
  const endDrag = () => { drag.current = null; };
  const step = (f) => setZoom(Math.min(1.6, Math.max(0.4, Math.round(z * f * 100) / 100)));

  return (
    <section className="card overflow-hidden">
      <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-2.5 sm:px-5">
        <h2 className="truncate">Mind map</h2>
        <div className="flex items-center gap-1">
          <button type="button" className="btn-ghost btn-sm w-8 px-0" onClick={() => step(1 / 1.2)} aria-label="Zoom out"><Minus className="size-4" aria-hidden="true" /></button>
          <span className="num w-11 text-center text-xs font-semibold text-ink-muted">{Math.round(z * 100)}%</span>
          <button type="button" className="btn-ghost btn-sm w-8 px-0" onClick={() => step(1.2)} aria-label="Zoom in"><Plus className="size-4" aria-hidden="true" /></button>
          <button type="button" className="btn-ghost btn-sm px-2.5 text-xs" onClick={() => setZoom(fit)} aria-label="Fit to card">
            <Maximize className="size-3.5" aria-hidden="true" />
            <span className="max-sm:hidden">Fit</span>
          </button>
        </div>
      </div>
      <div ref={boxRef} className="w-full">
        <div
          ref={scroller}
          className="max-h-[68vh] cursor-grab overflow-auto bg-bg/60 active:cursor-grabbing"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        >
          <div className="flex min-w-full justify-center" style={{ width: drawing.width * z }}>
            <svg
              width={drawing.width * z}
              height={drawing.height * z}
              viewBox={`0 0 ${drawing.width} ${drawing.height}`}
              role="img"
              aria-label={`Mind map of ${map.center || "this note"}`}
              className="block shrink-0 select-none"
            >
              {drawing.links.map((l, i) => {
                const dx = (l.to.x - l.from.x) * 0.5;
                return (
                  <path key={i} d={`M${l.from.x},${l.from.y} C${l.from.x + dx},${l.from.y} ${l.to.x - dx},${l.to.y} ${l.to.x},${l.to.y}`}
                    fill="none" stroke={LINK_COLOR[l.level]} strokeWidth={l.level === 1 ? 2 : 1.5} strokeLinecap="round" opacity={l.level === 1 ? 0.55 : 1} />
                );
              })}
              {drawing.nodes.map((n, i) => <Node key={i} n={n} />)}
            </svg>
          </div>
        </div>
      </div>
      <p className="sr-only">
        {map.center}: {(map.branches ?? []).map((b) => `${b.label} (${(b.children ?? []).map((c) => c.label).join(", ")})`).join("; ")}
      </p>
    </section>
  );
}
