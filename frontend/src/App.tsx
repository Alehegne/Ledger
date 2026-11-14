import {
  BrowserRouter,
  Navigate,
  Outlet,
  Route,
  Routes,
} from "react-router-dom";
import { AppProviders } from "./app/providers";
import { useAuth } from "./app/auth-context";
import { RequireAuth, RequireRole } from "./app/route-guards";
import { ErrorBoundary } from "./components/error-boundary";
import { AppShell } from "./components/layout/app-shell";
import { AccessDeniedPage } from "./features/auth/access-denied-page";
import { NotFoundPage } from "./features/auth/not-found-page";
import { LoginPage } from "./features/auth/login-page";
import { AcceptInvitationPage } from "./features/auth/accept-invitation-page";
import { DashboardPage } from "./features/dashboard/dashboard-page";
import { AccountsPage } from "./features/accounts/accounts-page";
import { JournalPage } from "./features/journal/journal-page";
import { ReportsPage } from "./features/reports/reports-page";
import { OrganizationPage } from "./features/organization/organization-page";
import { ReconciliationPage } from "./features/reconciliation/reconciliation-page";
import { AuditPage } from "./features/audit/audit-page";
import { AdminOverviewPage } from "./features/admin/admin-overview-page";
import { AdminOrganizationsPage } from "./features/admin/admin-organizations-page";
import { AdminAuditPage } from "./features/admin/admin-audit-page";

const ProtectedLayout = () => {
  return (
    <RequireAuth>
      <AppShell>
        <ErrorBoundary>
          <Outlet />
        </ErrorBoundary>
      </AppShell>
    </RequireAuth>
  );
};

const PublicLayout = () => {
  const { session } = useAuth();

  if (session) {
    return (
      <Navigate
        to={session.user.role === "SUPER_ADMIN" ? "/admin/overview" : "/"}
        replace
      />
    );
  }

  return <Outlet />;
};

const TenantHomeRoute = () => {
  const { session } = useAuth();

  if (session?.user.role === "SUPER_ADMIN") {
    return <Navigate to="/admin/overview" replace />;
  }

  return (
    <RequireRole allowedRoles={["ADMIN", "ACCOUNTANT", "VIEWER"]}>
      <DashboardPage />
    </RequireRole>
  );
};

function App() {
  return (
    <ErrorBoundary>
      <AppProviders>
        <BrowserRouter>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/accept-invitation"
              element={<AcceptInvitationPage />}
            />
          </Route>

          <Route element={<ProtectedLayout />}>
            <Route index element={<TenantHomeRoute />} />
            <Route
              path="/accounts"
              element={
                <RequireRole allowedRoles={["ADMIN", "ACCOUNTANT", "VIEWER"]}>
                  <AccountsPage />
                </RequireRole>
              }
            />
            <Route
              path="/journal"
              element={
                <RequireRole allowedRoles={["ADMIN", "ACCOUNTANT", "VIEWER"]}>
                  <JournalPage />
                </RequireRole>
              }
            />
            <Route
              path="/journal-entries"
              element={<Navigate to="/journal" replace />}
            />
            <Route
              path="/journal-entries/new"
              element={<Navigate to="/journal" replace />}
            />
            <Route
              path="/journal-entries/:entryId"
              element={<Navigate to="/journal" replace />}
            />
            <Route
              path="/reports"
              element={
                <RequireRole allowedRoles={["ADMIN", "ACCOUNTANT", "VIEWER"]}>
                  <ReportsPage />
                </RequireRole>
              }
            />
            <Route
              path="/reports/general-ledger"
              element={<Navigate to="/reports" replace />}
            />
            <Route
              path="/reports/trial-balance"
              element={<Navigate to="/reports" replace />}
            />
            <Route
              path="/users"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route
              path="/settings"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route
              path="/organization/users"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route
              path="/organization/settings"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route
              path="/audit"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <AuditPage />
                </RequireRole>
              }
            />
            <Route
              path="/reconciliation"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <ReconciliationPage />
                </RequireRole>
              }
            />
            <Route
              path="/audit-logs"
              element={<Navigate to="/audit" replace />}
            />
            <Route
              path="/admin"
              element={
                <RequireRole allowedRoles={["SUPER_ADMIN"]}>
                  <Navigate to="/admin/overview" replace />
                </RequireRole>
              }
            />
            <Route
              path="/admin/overview"
              element={
                <RequireRole allowedRoles={["SUPER_ADMIN"]}>
                  <AdminOverviewPage />
                </RequireRole>
              }
            />
            <Route
              path="/admin/organizations"
              element={
                <RequireRole allowedRoles={["SUPER_ADMIN"]}>
                  <AdminOrganizationsPage />
                </RequireRole>
              }
            />
            <Route
              path="/admin/audit"
              element={
                <RequireRole allowedRoles={["SUPER_ADMIN"]}>
                  <AdminAuditPage />
                </RequireRole>
              }
            />
            <Route path="/forbidden" element={<AccessDeniedPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    </AppProviders>
    </ErrorBoundary>
  );
}

export default App;
