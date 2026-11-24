/** The logo mark: a pine teal rounded square with a small waveform and two note lines. */
export function LogoMark({ size = 32, className }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" className={className} aria-hidden="true">
      <rect width="48" height="48" rx="13.5" fill="var(--color-primary)" />
      <path d="M12 19.5v9M17.5 15v18M23 17.5v13M28.5 20.5H36M28.5 27.5H33" stroke="#fff" strokeWidth="3.4" strokeLinecap="round" fill="none" />
    </svg>
  );
}

/** Mark and wordmark together. */
export function Logo({ size = 32, className = "" }) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <LogoMark size={size} />
      <span className="text-[17px] font-extrabold tracking-tight text-ink">AI Notebook</span>
    </span>
  );
}
