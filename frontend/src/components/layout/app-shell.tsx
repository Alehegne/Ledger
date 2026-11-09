import { useContext, type ReactNode } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import {
  BarChart3,
  BookOpenText,
  Building2,
  CreditCard,
  History,
  LogOut,
  ShieldCheck,
  Users,
} from "lucide-react";
import { useAuth } from "../../app/auth-context";
import { env } from "../../lib/env";
import { ServiceContext } from "../../services/interfaces/service-context";
import type { Role } from "../../types/domain";

const navItems: Array<{
  to: string;
  label: string;
  icon: typeof BarChart3;
  roles: Role[];
}> = [
  {
    to: "/",
    label: "Dashboard",
    icon: BarChart3,
    roles: ["ADMIN", "ACCOUNTANT", "VIEWER"],
  },
  {
    to: "/accounts",
    label: "Accounts",
    icon: CreditCard,
    roles: ["ADMIN", "ACCOUNTANT", "VIEWER"],
  },
  {
    to: "/journal",
    label: "Journal",
    icon: BookOpenText,
    roles: ["ADMIN", "ACCOUNTANT", "VIEWER"],
  },
  {
    to: "/reports",
    label: "Reports",
    icon: Building2,
    roles: ["ADMIN", "ACCOUNTANT", "VIEWER"],
  },
  { to: "/users", label: "Users", icon: Users, roles: ["ADMIN"] },
  { to: "/audit", label: "Audit Logs", icon: History, roles: ["ADMIN"] },
  { to: "/settings", label: "Settings", icon: ShieldCheck, roles: ["ADMIN"] },
  {
    to: "/admin/overview",
    label: "Platform Overview",
    icon: BarChart3,
    roles: ["SUPER_ADMIN"],
  },
  {
    to: "/admin/organizations",
    label: "Organizations",
    icon: Building2,
    roles: ["SUPER_ADMIN"],
  },
  {
    to: "/admin/audit",
    label: "Platform Audit",
    icon: History,
    roles: ["SUPER_ADMIN"],
  },
];

export const AppShell = ({ children }: { children: ReactNode }) => {
  const { session, logout } = useAuth();
  const service = useContext(ServiceContext);
  const navigate = useNavigate();
  const userRole = session?.user.role ?? "VIEWER";
  const isPlatformAdmin = userRole === "SUPER_ADMIN";
  const organizationName =
    session?.user.organizationName ??
    (isPlatformAdmin ? "Ledger Platform" : "Northstar Co.");
  const sessionBadge = env.mode === "api" ? null : session ? "mock" : "demo";
  const visibleNavItems = navItems.filter(({ roles }) =>
    roles.includes(userRole),
  );

  const handleLogout = async () => {
    const refreshToken = session?.refreshToken;
    try {
      if (service) {
        await service.logout(refreshToken);
      }
    } catch {
      // Proceed with local sign-out even if server revocation fails
    } finally {
      logout();
      navigate("/login", { replace: true });
    }
  };

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900">
      <div className="mx-auto flex max-w-[1440px] gap-4 p-4 sm:gap-6 sm:p-5">
        <aside className="hidden w-72 shrink-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm lg:block">
          <div className="mb-8 flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-slate-900 text-sm font-bold text-white">
              L
            </div>
            <div>
              <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
                Ledger
              </p>
              <p className="text-lg font-semibold">{organizationName}</p>
            </div>
          </div>

          <nav className="space-y-2" aria-label="Primary navigation">
            {visibleNavItems.map(({ to, label, icon: Icon }) => (
              <NavLink
                key={to}
                to={to}
                end={to === "/" || to === "/admin/overview"}
                aria-label={`Navigate to ${label}`}
                className={({ isActive }) =>
                  `flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition ${
                    isActive
                      ? "bg-slate-900 text-white shadow-sm"
                      : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  }`
                }
              >
                <Icon className="h-4 w-4" />
                {label}
              </NavLink>
            ))}
          </nav>

          <div className="mt-8 rounded-xl border border-slate-200 bg-slate-50 p-3">
            <div className="mb-2 flex items-center justify-between gap-3">
              <p className="text-xs uppercase tracking-[0.18em] text-slate-500">
                Session
              </p>
              {sessionBadge ? (
                <span className="rounded-full bg-slate-900 px-2 py-1 text-[10px] font-medium uppercase tracking-[0.2em] text-white">
                  {sessionBadge}
                </span>
              ) : null}
            </div>
            <p className="mt-2 text-sm font-semibold text-slate-800">
              {session?.user.fullName}
            </p>
            <p className="text-xs text-slate-500">{session?.user.role}</p>
          </div>
        </aside>

        <main className="min-w-0 flex-1">
          <header className="mb-4 rounded-2xl border border-slate-200 bg-white shadow-sm">
            <div className="flex items-center justify-between px-4 py-4 sm:px-5">
              <div>
                <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
                  {isPlatformAdmin
                    ? "Platform control plane"
                    : "Finance workspace"}
                </p>
                <h1 className="text-xl font-semibold text-slate-900">
                  {isPlatformAdmin
                    ? "Organization governance"
                    : "Accounting overview"}
                </h1>
              </div>

              <div className="flex items-center gap-2 sm:gap-3">
                {sessionBadge ? (
                  <span className="inline-flex items-center rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[10px] font-medium uppercase tracking-[0.22em] text-slate-700">
                    {sessionBadge === "mock" ? "Mock mode" : "Demo mode"}
                  </span>
                ) : null}
                <button
                  type="button"
                  onClick={handleLogout}
                  className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-2 text-sm font-medium text-slate-700 transition hover:border-slate-300 hover:bg-slate-100 sm:px-3"
                  aria-label="Sign out"
                >
                  <LogOut className="h-4 w-4" />
                  <span className="hidden sm:inline">Sign out</span>
                </button>
              </div>
            </div>

            <nav
              className="flex gap-2 overflow-x-auto border-t border-slate-200 px-3 py-2 lg:hidden"
              aria-label="Mobile navigation"
            >
              {visibleNavItems.map(({ to, label, icon: Icon }) => (
                <NavLink
                  key={`mobile-${to}`}
                  to={to}
                  end={to === "/" || to === "/admin/overview"}
                  aria-label={`Navigate to ${label}`}
                  className={({ isActive }) =>
                    `inline-flex shrink-0 items-center gap-2 rounded-lg px-3 py-1.5 text-sm font-medium transition ${
                      isActive
                        ? "bg-slate-900 text-white"
                        : "border border-slate-200 bg-white text-slate-700 hover:bg-slate-50"
                    }`
                  }
                >
                  <Icon className="h-4 w-4" />
                  {label}
                </NavLink>
              ))}
            </nav>
          </header>

          <div className="space-y-6">{children}</div>
        </main>
      </div>
    </div>
  );
};
