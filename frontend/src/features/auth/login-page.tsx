import { zodResolver } from "@hookform/resolvers/zod";
import { ArrowRight, LockKeyhole, Mail } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate } from "react-router-dom";
import { z } from "zod";
import { useAuth } from "../../app/auth-context";
import { env } from "../../lib/env";
import { useLedgerService } from "../../services/interfaces/service-context";

const loginSchema = z.object({
  email: z.string().email("Enter a valid work email."),
  password: z.string().min(8, "Password must be at least 8 characters."),
});

type LoginFormValues = z.infer<typeof loginSchema>;

export const LoginPage = () => {
  const { setSession } = useAuth();
  const service = useLedgerService();
  const navigate = useNavigate();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues:
      env.mode === "api"
        ? { email: "", password: "" }
        : {
            email: "alex@northstar.co",
            password: "P@ssw0rd123",
          },
  });

  const onSubmit = async (values: LoginFormValues) => {
    try {
      setServerError(null);
      const session = await service.login(values.email, values.password);
      setSession(session);
      navigate("/", { replace: true });
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Unable to sign in.";
      setServerError(message);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4 py-10">
      <div className="grid w-full max-w-5xl overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm lg:grid-cols-[1.15fr_0.85fr]">
        <div className="hidden bg-slate-900 p-10 text-white lg:flex lg:flex-col lg:justify-between">
          <div>
            <p className="text-xs uppercase tracking-[0.25em] text-slate-300">
              Northstar Co.
            </p>
            <h1 className="mt-4 text-4xl font-semibold leading-tight">
              Modern financial operations for multi-entity accounting.
            </h1>
          </div>

          <div className="space-y-3 text-sm text-slate-200">
            <p>• Double-entry controls with role-aware approvals</p>
            <p>• Real-time trial balance and general ledger visibility</p>
            <p>• Investor-ready reporting and audit trail</p>
          </div>
        </div>

        <div className="p-8 sm:p-10">
          <div className="mb-8 flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-slate-900 text-sm font-bold text-white">
              L
            </div>
            <div>
              <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
                Sign in
              </p>
              <h2 className="text-2xl font-semibold text-slate-900">
                Ledger access
              </h2>
            </div>
          </div>

          <form
            className="space-y-5"
            onSubmit={handleSubmit(onSubmit)}
            noValidate
          >
            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700">
                Work email
              </label>
              <div className="relative">
                <Mail className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-slate-400" />
                <input
                  {...register("email")}
                  type="email"
                  aria-invalid={Boolean(errors.email)}
                  aria-describedby={
                    errors.email ? "login-email-error" : undefined
                  }
                  className="input-control rounded-xl bg-slate-50 pl-10"
                  placeholder="alex@northstar.co"
                />
              </div>
              {errors.email && (
                <p id="login-email-error" className="mt-2 text-xs text-red-600">
                  {errors.email.message}
                </p>
              )}
            </div>

            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700">
                Password
              </label>
              <div className="relative">
                <LockKeyhole className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-slate-400" />
                <input
                  {...register("password")}
                  type="password"
                  aria-invalid={Boolean(errors.password)}
                  aria-describedby={
                    errors.password ? "login-password-error" : undefined
                  }
                  className="input-control rounded-xl bg-slate-50 pl-10"
                  placeholder="Enter password"
                />
              </div>
              {errors.password && (
                <p
                  id="login-password-error"
                  className="mt-2 text-xs text-red-600"
                >
                  {errors.password.message}
                </p>
              )}
            </div>

            {serverError ? (
              <div className="feedback-error" role="alert">
                {serverError}
              </div>
            ) : null}

            <button
              type="submit"
              disabled={isSubmitting}
              className="btn-primary w-full justify-center py-3"
            >
              {isSubmitting ? "Signing in..." : "Continue to Ledger"}
              <ArrowRight className="h-4 w-4" />
            </button>

            <div className="mt-4 text-center text-xs text-slate-500">
              Received an organization or team invitation?{" "}
              <Link
                to="/accept-invitation"
                className="font-semibold text-slate-900 hover:underline"
              >
                Accept invitation
              </Link>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};
