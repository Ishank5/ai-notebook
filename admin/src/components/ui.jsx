import { useEffect, useRef } from "react";
import { AlertTriangle, ChevronDown, Loader2, X } from "lucide-react";

/* The few shared pieces: chips, buttons, a switch, a select, a confirm dialog, and load states. */

// eslint-disable-next-line react-refresh/only-export-components
export const cn = (...parts) => parts.filter(Boolean).join(" ");

const TONES = {
  success: "bg-success-tint text-success",
  warning: "bg-amber-tint text-amber",
  danger: "bg-record-tint text-record",
  neutral: "bg-surface-alt text-ink-muted",
  primary: "bg-primary-tint text-primary",
};
const DOTS = { success: "bg-success", warning: "bg-amber", danger: "bg-record", neutral: "bg-ink-faint", primary: "bg-primary" };

/** A status or label pill. `dot` marks something still moving. */
export function Chip({ tone = "neutral", size, dot, children, className }) {
  return (
    <span className={cn(
      "inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full font-semibold",
      size === "sm" ? "h-6 px-2.5 text-[11px]" : "h-7 px-3 text-xs",
      TONES[tone] ?? TONES.neutral,
      className,
    )}>
      {dot && <span className={cn("live-dot size-1.5 rounded-full", DOTS[tone] ?? DOTS.neutral)} aria-hidden="true" />}
      {children}
    </span>
  );
}

const VARIANTS = { primary: "btn-primary", outline: "btn-secondary", secondary: "btn-secondary", ghost: "btn-ghost", destructive: "btn-danger" };

/** A pill button that shows it is working and cannot be pressed twice meanwhile. */
export function Btn({ variant = "primary", size, loading = false, disabled, className, children, ...props }) {
  return (
    <button
      type="button"
      {...props}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cn(VARIANTS[variant] ?? VARIANTS.primary, size === "sm" && "btn-sm", className)}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
      {children}
    </button>
  );
}

/** An on or off switch. */
export function Toggle({ checked, onCheckedChange, "aria-label": label }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onCheckedChange(!checked)}
      className={cn("relative inline-flex h-7 w-12 shrink-0 items-center rounded-full p-0.5 transition-colors", checked ? "bg-primary" : "bg-ink-faint/50")}
    >
      <span className={cn("block size-6 rounded-full bg-white shadow-sm transition-transform", checked ? "translate-x-5" : "translate-x-0")} />
    </button>
  );
}

/** A native select drawn as a pill. options: [{ value, label }]. */
export function Select({ value, onChange, options, icon: Icon, className, ...props }) {
  return (
    <span className={cn("relative inline-flex shrink-0 items-center", className)}>
      {Icon && <Icon className="pointer-events-none absolute left-3.5 size-4 text-ink-faint" aria-hidden="true" />}
      <select
        {...props}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={cn("pill w-full appearance-none pr-9 text-ink outline-none disabled:opacity-50", Icon && "pl-9")}
      >
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <ChevronDown className="pointer-events-none absolute right-3 size-4 text-ink-faint" aria-hidden="true" />
    </span>
  );
}

/** A small yes or no question. The confirm button says what will happen. */
export function Confirm({ open, title, children, confirmLabel, cancelLabel = "Cancel", danger = false, onConfirm, onCancel }) {
  const ref = useRef(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      onCancel={(e) => { e.preventDefault(); onCancel(); }}
      onClick={(e) => { if (e.target === ref.current) onCancel(); }}
      className="confirm m-auto w-[calc(100%-2rem)] max-w-md rounded-card border border-line bg-surface p-0 text-ink shadow-(--shadow-pop)"
    >
      {open && (
        <div className="p-6">
          <h2 className="text-base font-bold">{title}</h2>
          <div className="mt-2 text-sm text-ink-muted">{children}</div>
          <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" className="btn-secondary" onClick={onCancel}>{cancelLabel}</button>
            <button type="button" className={danger ? "btn-danger" : "btn-primary"} onClick={onConfirm}>{confirmLabel}</button>
          </div>
        </div>
      )}
    </dialog>
  );
}

/** Rows being fetched. */
export function Loading({ label, rows = 4 }) {
  return (
    <div role="status" aria-label={label} className="flex flex-col gap-3 p-5">
      {Array.from({ length: rows }, (_, i) => <div key={i} className="shimmer h-12 rounded-2xl" />)}
    </div>
  );
}

/** A read that failed, said as a failure, never as an empty list. */
export function ErrorState({ title, body, onRetry }) {
  return (
    <div role="alert" className="card card-pad flex flex-col items-center text-center">
      <span className="mb-3 flex size-10 items-center justify-center rounded-full bg-record-tint text-record">
        <AlertTriangle className="size-5" aria-hidden="true" />
      </span>
      <div className="text-sm font-bold">{title}</div>
      {body && <div className="mt-1 max-w-md text-sm text-ink-muted">{body}</div>}
      {onRetry && <button type="button" onClick={onRetry} className="btn-secondary mt-4">Try again</button>}
    </div>
  );
}

/** Nothing to show, with a short reason. */
export function EmptyState({ title, hint, icon: Icon }) {
  return (
    <div className="card flex flex-col items-center px-6 py-14 text-center">
      {Icon && (
        <span className="mb-3 flex size-11 items-center justify-center rounded-full bg-primary-tint text-primary">
          <Icon className="size-5" aria-hidden="true" />
        </span>
      )}
      <div className="text-sm font-bold">{title}</div>
      {hint && <div className="mt-1 max-w-sm text-sm text-ink-muted">{hint}</div>}
    </div>
  );
}

/** A row of choices where exactly one is on. options: [{ value, label, icon? }]. */
export function Segmented({ value, onChange, options, size, className, "aria-label": label }) {
  return (
    <div role="radiogroup" aria-label={label} className={cn("inline-flex shrink-0 items-center gap-0.5 rounded-full bg-surface-alt p-1", className)}>
      {options.map((o) => {
        const on = o.value === value;
        const Icon = o.icon;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={on}
            onClick={() => onChange(o.value)}
            className={cn(
              "inline-flex items-center gap-1.5 whitespace-nowrap rounded-full font-semibold transition-colors",
              size === "sm" ? "h-7 px-3 text-xs" : "h-8 px-3.5 text-[13px]",
              on ? "bg-surface text-ink shadow-(--shadow-soft)" : "text-ink-muted hover:text-ink",
            )}
          >
            {Icon && <Icon className="size-3.5" aria-hidden="true" />}
            {o.label}
            {o.count !== undefined && <span className={cn("num text-[11px]", on ? "text-ink-faint" : "text-ink-faint/80")}>{o.count}</span>}
          </button>
        );
      })}
    </div>
  );
}

/** A dialog with a title bar and a close button. Escape and a click outside close it. */
export function Modal({ open, title, onClose, children, footer, wide = false }) {
  const ref = useRef(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      onCancel={(e) => { e.preventDefault(); onClose(); }}
      onClick={(e) => { if (e.target === ref.current) onClose(); }}
      className={cn(
        "confirm m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] rounded-card border border-line bg-surface p-0 text-ink shadow-(--shadow-pop)",
        wide ? "max-w-2xl" : "max-w-md",
      )}
    >
      {open && (
        <div className="flex max-h-[calc(100dvh-2rem)] flex-col">
          <div className="flex items-center justify-between gap-3 border-b border-line px-5 py-4 sm:px-6">
            <h2 className="text-base font-bold">{title}</h2>
            <button type="button" onClick={onClose} className="btn-ghost btn-sm -mr-2 w-8 px-0" aria-label="Close">
              <X className="size-4" aria-hidden="true" />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">{children}</div>
          {footer && <div className="flex flex-col-reverse gap-2 border-t border-line px-5 py-4 sm:flex-row sm:justify-end sm:px-6">{footer}</div>}
        </div>
      )}
    </dialog>
  );
}

/** A grey block standing in for content that is on its way. */
export function Skeleton({ className }) {
  return <div className={cn("shimmer rounded-xl", className)} aria-hidden="true" />;
}

/** A card's heading row: title, an optional count, and actions on the right. */
export function CardHead({ title, icon: Icon, count, action, className }) {
  return (
    <div className={cn("flex min-h-8 items-center justify-between gap-3", className)}>
      <h2 className="flex min-w-0 items-center gap-2">
        {Icon && <Icon className="size-4 shrink-0 text-primary" aria-hidden="true" />}
        <span className="truncate">{title}</span>
        {count > 0 && <span className="num text-xs font-semibold text-ink-faint">{count}</span>}
      </h2>
      {action}
    </div>
  );
}
