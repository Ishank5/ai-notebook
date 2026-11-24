import { LayoutDashboard, ListChecks, LogOut, MessagesSquare, NotebookPen, Settings } from "lucide-react";
import { Link, NavLink, Outlet } from "react-router-dom";
import { ROLES, useAuth } from "../lib/auth";
import { Logo } from "./Logo";
import { cn } from "./ui";

/** Logo, nav, the signed-in person and sign out, above the page. Hidden when printing. */
export default function Shell() {
  const { profile, can, signOut } = useAuth();
  const nav = [
    { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true, show: true },
    { to: "/recordings", label: "Notes", icon: NotebookPen, end: false, show: true },
    { to: "/tasks", label: "Tasks", icon: ListChecks, end: true, show: true },
    { to: "/ask", label: "Ask AI", icon: MessagesSquare, end: true, show: true },
    { to: "/settings", label: "Settings", icon: Settings, end: true, show: can("recordings.manage") },
  ].filter((n) => n.show);

  const links = nav.map((n) => (
    <NavLink
      key={n.to}
      to={n.to}
      end={n.end}
      className={({ isActive }) => cn(
        "inline-flex h-9 shrink-0 items-center gap-2 rounded-full px-3.5 text-sm font-semibold transition-colors",
        isActive ? "bg-primary-tint text-primary" : "text-ink-muted hover:bg-surface-alt hover:text-ink",
      )}
    >
      <n.icon className="size-4" aria-hidden="true" />
      {n.label}
    </NavLink>
  ));

  // Phones: an equal-width tab bar, icon over a short word, so every page fits without scrolling.
  const phoneLinks = nav.map((n) => (
    <NavLink
      key={n.to}
      to={n.to}
      end={n.end}
      className={({ isActive }) => cn(
        "flex h-12 min-w-0 flex-col items-center justify-center gap-0.5 rounded-2xl text-[11px] font-semibold transition-colors",
        isActive ? "bg-primary-tint text-primary" : "text-ink-muted hover:text-ink",
      )}
    >
      <n.icon className="size-[18px]" aria-hidden="true" />
      <span className="max-w-full truncate px-1">{n.label}</span>
    </NavLink>
  ));

  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-line bg-surface/95 backdrop-blur-sm print:hidden">
        <div className="mx-auto flex h-16 max-w-6xl items-center gap-5 px-4 sm:px-6">
          <Link to="/" className="shrink-0 rounded-xl" aria-label="AI Notebook home">
            <Logo size={32} />
          </Link>
          <nav className="hidden items-center gap-1 lg:flex" aria-label="Main">{links}</nav>
          <div className="ml-auto flex min-w-0 items-center gap-3">
            <div className="hidden min-w-0 text-right xl:block">
              <div className="truncate text-sm font-bold leading-tight">{profile.name || profile.email}</div>
              <div className="truncate text-xs text-ink-faint">{ROLES[profile.role] ?? profile.role}</div>
            </div>
            <button type="button" onClick={signOut} className="btn-ghost btn-sm" aria-label="Sign out">
              <LogOut className="size-4" aria-hidden="true" />
              <span className="max-sm:hidden">Sign out</span>
            </button>
          </div>
        </div>
        <nav className="no-scrollbar mx-auto hidden max-w-6xl gap-1 overflow-x-auto px-6 pb-3 sm:flex lg:hidden" aria-label="Main">{links}</nav>
        <nav className="grid auto-cols-fr grid-flow-col gap-1 px-2 pb-2 sm:hidden" aria-label="Main">{phoneLinks}</nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 pb-16 pt-6 sm:px-6 sm:pt-8 print:max-w-none print:p-0">
        <Outlet />
      </main>
    </div>
  );
}
