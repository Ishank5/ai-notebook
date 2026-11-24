import { useLayoutEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

/*
 * Small inline SVG charts, drawn at the card's real pixel width so text and corners never stretch.
 * Marks follow one spec: bars at most 24 px wide with a 4 px rounded top and a square base, hairline
 * solid gridlines, labels in ink colours (never the series colour), and a tooltip on hover and focus.
 * One series per chart, so the card title names it and no legend box is needed.
 */

const CHART = "var(--color-chart)";
const CHART_HOVER = "var(--color-primary-pressed)";
const GRID = "var(--color-grid)";
const AXIS = "var(--color-line)";
const LABEL = "var(--color-ink-faint)";

/** The width of an element, kept current. */
// eslint-disable-next-line react-refresh/only-export-components
export function useWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const measure = () => setWidth(Math.floor(el.getBoundingClientRect().width));
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width];
}

/** Round axis steps: 1, 2, 5, 10, 20, 50 ... so ticks read as clean numbers. */
function niceScale(max, maxTicks = 4) {
  if (!(max > 0)) return { top: maxTicks, step: 1 };
  const rough = max / maxTicks;
  const mag = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((m) => m * mag).find((s) => s >= rough) ?? 10 * mag;
  return { top: Math.ceil(max / step) * step, step };
}

/** A bar with a 4 px rounded top and a square base. */
function barPath(x, y, w, h) {
  const r = Math.min(4, w / 2, h);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

/** The floating readout: the value leads, the label follows. */
function Tip({ x, y, width, children }) {
  if (x === null) return null;
  const w = 168;
  const left = Math.max(0, Math.min(width - w, x - w / 2));
  return (
    <div
      className="pointer-events-none absolute z-10 rounded-xl border border-line bg-surface px-3 py-2 text-xs shadow-(--shadow-pop)"
      style={{ left, top: y, width: w }}
      role="status"
    >
      {children}
    </div>
  );
}

/**
 * Columns over days. data: [{ key, label, value, detail, tip }]. `format` writes a value for the axis;
 * `tip` is the tooltip's value line. Arrow keys move the readout when the chart has focus.
 */
export function ColumnChart({ data, height = 190, format = (v) => String(v), ariaLabel }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const n = data.length;
  const pad = { left: 34, right: 6, top: 10, bottom: 26 };
  const plotW = Math.max(0, width - pad.left - pad.right);
  const plotH = height - pad.top - pad.bottom;
  const max = Math.max(0, ...data.map((d) => d.value));
  const { top, step } = niceScale(max);
  const band = n ? plotW / n : 0;
  const bw = Math.max(3, Math.min(24, band * 0.62));
  const y = (v) => pad.top + plotH - (v / top) * plotH;
  const ticks = [];
  for (let v = 0; v <= top + 1e-9; v += step) ticks.push(v);
  const labelEvery = Math.max(1, Math.ceil(n / Math.max(1, Math.floor(plotW / 62))));

  const onKey = (e) => {
    if (e.key === "ArrowRight") { e.preventDefault(); setHover((h) => Math.min(n - 1, h === null ? n - 1 : h + 1)); }
    if (e.key === "ArrowLeft") { e.preventDefault(); setHover((h) => Math.max(0, h === null ? n - 1 : h - 1)); }
    if (e.key === "Escape") setHover(null);
  };
  const hovered = hover !== null ? data[hover] : null;

  return (
    <div ref={ref} className="relative w-full" style={{ height }}>
      {width > 0 && (
        <svg
          width={width}
          height={height}
          className="block touch-pan-y rounded-lg"
          tabIndex={0}
          role="img"
          aria-label={ariaLabel}
          onKeyDown={onKey}
          onBlur={() => setHover(null)}
          onPointerLeave={() => setHover(null)}
        >
          {ticks.map((v) => (
            <g key={v}>
              <line x1={pad.left} x2={width - pad.right} y1={y(v)} y2={y(v)} stroke={v === 0 ? AXIS : GRID} strokeWidth="1" shapeRendering="crispEdges" />
              <text x={pad.left - 8} y={y(v)} dy="0.32em" textAnchor="end" fontSize="11" fill={LABEL} className="num">{format(v)}</text>
            </g>
          ))}
          {data.map((d, i) => {
            const cx = pad.left + band * i + band / 2;
            const h = d.value > 0 ? Math.max(2, (d.value / top) * plotH) : 0;
            const showLabel = (n - 1 - i) % labelEvery === 0;
            return (
              <g key={d.key}>
                {hover === i && <rect x={pad.left + band * i} y={pad.top} width={band} height={plotH} fill={GRID} rx="4" />}
                {h > 0 && <path d={barPath(cx - bw / 2, pad.top + plotH - h, bw, h)} fill={hover === i ? CHART_HOVER : CHART} />}
                {showLabel && (
                  <text x={cx} y={height - 8} textAnchor="middle" fontSize="11" fill={LABEL} className="num">{d.label}</text>
                )}
                <rect
                  x={pad.left + band * i}
                  y={pad.top}
                  width={band}
                  height={plotH + pad.bottom}
                  fill="transparent"
                  onPointerEnter={() => setHover(i)}
                  onPointerDown={() => setHover(i)}
                />
              </g>
            );
          })}
        </svg>
      )}
      {hovered && (
        <Tip x={pad.left + band * hover + band / 2} y={0} width={width}>
          <div className="num text-sm font-bold text-ink">{hovered.tip ?? format(hovered.value)}</div>
          <div className="text-ink-muted">{hovered.detail ?? hovered.label}</div>
        </Tip>
      )}
      <table className="sr-only">
        <tbody>
          {data.map((d) => <tr key={d.key}><th>{d.detail ?? d.label}</th><td>{format(d.value)}</td></tr>)}
        </tbody>
      </table>
    </div>
  );
}

/** A small trend line: grey history, the latest point in teal. */
export function Sparkline({ values, width = 88, height = 30, label }) {
  if (!values?.length || values.every((v) => !v)) return null;
  const max = Math.max(...values, 1);
  const pad = 5;
  const step = (width - pad * 2) / Math.max(1, values.length - 1);
  const pts = values.map((v, i) => [pad + i * step, height - pad - (v / max) * (height - pad * 2)]);
  const last = pts[pts.length - 1];
  return (
    <svg width={width} height={height} role="img" aria-label={label} className="shrink-0 overflow-visible">
      <polyline points={pts.map((p) => p.join(",")).join(" ")} fill="none" stroke="var(--color-ink-faint)" strokeOpacity="0.55" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={last[0]} cy={last[1]} r="4" fill={CHART} stroke="var(--color-surface)" strokeWidth="2" />
    </svg>
  );
}

/**
 * Parts of a whole as a list: name and figure on one line, a thin bar under it, all bars on one
 * scale. items: [{ key, label, icon, value, figure, to }].
 */
export function BarList({ items, total }) {
  const sum = total ?? items.reduce((s, i) => s + i.value, 0);
  return (
    <ul className="flex flex-col gap-3.5">
      {items.map((item) => {
        const share = sum > 0 ? item.value / sum : 0;
        const Icon = item.icon;
        const body = (
          <>
            <div className="flex items-center justify-between gap-3 text-sm">
              <span className="flex min-w-0 items-center gap-2 font-semibold text-ink">
                {Icon && <Icon className="size-4 shrink-0 text-ink-muted" aria-hidden="true" />}
                <span className="truncate">{item.label}</span>
              </span>
              <span className="num shrink-0 text-ink-muted">
                <b className="font-bold text-ink">{item.figure ?? item.value}</b>
                <span className="ml-1.5 text-xs text-ink-faint">{Math.round(share * 100)}%</span>
              </span>
            </div>
            <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-primary-tint">
              {share > 0 && <div className="h-full rounded-full bg-chart" style={{ width: `${Math.max(2, share * 100)}%` }} />}
            </div>
          </>
        );
        return (
          <li key={item.key}>
            {item.to ? (
              <Link to={item.to} className="-mx-2 block rounded-xl px-2 py-1 transition-colors hover:bg-bg">{body}</Link>
            ) : (
              <div className="py-1">{body}</div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export const MOODS = [
  { id: "positive", label: "Positive", color: "var(--color-success)" },
  { id: "neutral", label: "Neutral", color: "var(--color-ink-faint)" },
  { id: "mixed", label: "Mixed", color: "var(--color-amber)" },
  { id: "negative", label: "Negative", color: "var(--color-record)" },
];

/**
 * Each note's mood as a dot on its day, one row per mood, so the row (not only the colour) says
 * which mood it is. moods: [{ date, mood, recordingId, title }], days: ["YYYY-MM-DD", ...].
 */
export function MoodStrip({ moods, days, dayLabel, onOpen }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const rowH = 32;
  const labelW = width < 420 ? 100 : 112;
  const pad = { right: 10, top: 6, bottom: 24 };
  const height = pad.top + MOODS.length * rowH + pad.bottom;
  const plotW = Math.max(0, width - labelW - pad.right);
  const index = new Map(days.map((d, i) => [d, i]));
  const x = (i) => labelW + (days.length > 1 ? (i / (days.length - 1)) * plotW : plotW / 2);
  const rowY = (m) => pad.top + MOODS.findIndex((r) => r.id === m) * rowH + rowH / 2;

  const counts = Object.fromEntries(MOODS.map((m) => [m.id, 0]));
  const groups = new Map();
  for (const m of moods) {
    if (!(m.mood in counts) || !index.has(m.date)) continue;
    counts[m.mood]++;
    const k = `${m.date}|${m.mood}`;
    groups.set(k, [...(groups.get(k) ?? []), m]);
  }
  const dots = [];
  for (const [k, list] of groups) {
    const [date, mood] = k.split("|");
    list.forEach((m, j) => {
      dots.push({ ...m, cx: x(index.get(date)) + (j - (list.length - 1) / 2) * 7, cy: rowY(mood) });
    });
  }
  const ticks = days.length > 1 ? [0, Math.floor((days.length - 1) / 2), days.length - 1] : [0];
  const hovered = hover !== null ? dots[hover] : null;

  return (
    <div ref={ref} className="relative w-full" style={{ height }}>
      {width > 0 && (
        <svg width={width} height={height} className="block" role="img" aria-label="Mood of each note by day" onPointerLeave={() => setHover(null)}>
          {MOODS.map((m) => {
            const cy = rowY(m.id);
            return (
              <g key={m.id}>
                <line x1={labelW} x2={width - pad.right} y1={cy} y2={cy} stroke={GRID} strokeWidth="1" shapeRendering="crispEdges" />
                <circle cx={8} cy={cy} r="4" fill={m.color} />
                <text x={18} y={cy} dy="0.32em" fontSize="12" fontWeight="600" fill="var(--color-ink)">{m.label}</text>
                <text x={labelW - 14} y={cy} dy="0.32em" fontSize="11" textAnchor="end" fill={LABEL} className="num">{counts[m.id]}</text>
              </g>
            );
          })}
          {ticks.map((i) => (
            <text key={i} x={x(i)} y={height - 7} fontSize="11" fill={LABEL} className="num"
              textAnchor={i === 0 ? "start" : i === days.length - 1 ? "end" : "middle"}>
              {dayLabel(days[i])}
            </text>
          ))}
          {dots.map((d, i) => {
            const mood = MOODS.find((m) => m.id === d.mood);
            return (
              <g key={`${d.recordingId}-${i}`}>
                <circle cx={d.cx} cy={d.cy} r={hover === i ? 6.5 : 5} fill={mood.color} stroke="var(--color-surface)" strokeWidth="2" />
                <circle
                  cx={d.cx}
                  cy={d.cy}
                  r="12"
                  fill="transparent"
                  tabIndex={0}
                  role="link"
                  aria-label={`${d.title || "Note"}, ${mood.label}, ${dayLabel(d.date)}`}
                  className="cursor-pointer outline-none"
                  onPointerEnter={() => setHover(i)}
                  onFocus={() => setHover(i)}
                  onBlur={() => setHover(null)}
                  onClick={() => onOpen?.(d.recordingId)}
                  onKeyDown={(e) => { if (e.key === "Enter") onOpen?.(d.recordingId); }}
                />
              </g>
            );
          })}
        </svg>
      )}
      {hovered && (
        <Tip x={hovered.cx} y={Math.max(0, hovered.cy - 74)} width={width}>
          <div className="truncate text-[13px] font-bold text-ink">{hovered.title || "Untitled note"}</div>
          <div className="mt-0.5 flex items-center gap-1.5 text-ink-muted">
            <span className="size-2 rounded-full" style={{ background: MOODS.find((m) => m.id === hovered.mood)?.color }} />
            {MOODS.find((m) => m.id === hovered.mood)?.label} · {dayLabel(hovered.date)}
          </div>
        </Tip>
      )}
    </div>
  );
}
