import { Navigate } from "react-router-dom";
import { useAuth } from "./auth-context";
import type { Role } from "../types/domain";

export const RequireAuth = ({ children }: { children: React.ReactNode }) => {
  const { session } = useAuth();

  if (!session) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
};

export const RequireRole = ({
  allowedRoles,
  children,
}: {
  allowedRoles: Role[];
  children: React.ReactNode;
}) => {
  const { session } = useAuth();

  if (!session) {
    return <Navigate to="/login" replace />;
  }

  if (!allowedRoles.includes(session.user.role)) {
    return <Navigate to="/forbidden" replace />;
  }

  return <>{children}</>;
};
