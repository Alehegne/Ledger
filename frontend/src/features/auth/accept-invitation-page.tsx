import { type FormEvent, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { CheckCircle2, Lock, Mail, User } from "lucide-react";
import { useLedgerService } from "../../services/interfaces/service-context";

export const AcceptInvitationPage = () => {
  const service = useLedgerService();
  const [searchParams] = useSearchParams();

  const urlToken = searchParams.get("token") ?? "";
  const urlEmail = searchParams.get("email") ?? "";

  const [token, setToken] = useState(urlToken);
  const [email, setEmail] = useState(urlEmail);
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setErrorMessage(null);

    if (!token.trim()) {
      setErrorMessage("Invitation token is required.");
      return;
    }

    if (!email.trim()) {
      setErrorMessage("Email address is required.");
      return;
    }

    if (!fullName.trim()) {
      setErrorMessage("Full name is required.");
      return;
    }

    if (password.length < 8) {
      setErrorMessage("Password must be at least 8 characters long.");
      return;
    }

    if (password !== confirmPassword) {
      setErrorMessage("Passwords do not match.");
      return;
    }

    setIsLoading(true);

    try {
      await service.acceptInvitation({
        token: token.trim(),
        email: email.trim(),
        fullName: fullName.trim(),
        password,
      });
      setSuccess(true);
    } catch (err) {
      setErrorMessage(
        err instanceof Error
          ? err.message
          : "Failed to accept invitation. The invitation may be invalid or expired.",
      );
    } finally {
      setIsLoading(false);
    }
  };

  if (success) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 p-4">
        <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 shadow-sm text-center space-y-4">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-100 text-emerald-600">
            <CheckCircle2 className="h-6 w-6" />
          </div>
          <h2 className="text-xl font-semibold text-slate-900">
            Account activated!
          </h2>
          <p className="text-sm text-slate-600">
            Your invitation has been accepted and your password is set. You can now log in to Ledger with your email.
          </p>
          <div className="pt-2">
            <Link
              to="/login"
              className="inline-flex w-full justify-center rounded-xl bg-slate-900 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-slate-800"
            >
              Go to sign in
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 p-4">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
        <div className="mb-6 text-center">
          <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
            Team Onboarding
          </p>
          <h1 className="mt-1 text-2xl font-semibold text-slate-900">
            Accept Invitation
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            Set up your credentials to join your organization on Ledger.
          </p>
        </div>

        {errorMessage ? (
          <div
            className="mb-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700"
            role="alert"
          >
            {errorMessage}
          </div>
        ) : null}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700">
              Email address
            </label>
            <div className="relative mt-1">
              <Mail className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@example.com"
                className="input-control w-full pl-9"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700">
              Full name
            </label>
            <div className="relative mt-1">
              <User className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
              <input
                type="text"
                required
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="Alex Morgan"
                className="input-control w-full pl-9"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700">
              Invitation token
            </label>
            <input
              type="text"
              required
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="Paste invitation token"
              className="input-control mt-1 w-full font-mono text-xs"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700">
              Password
            </label>
            <div className="relative mt-1">
              <Lock className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
              <input
                type="password"
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 8 characters"
                className="input-control w-full pl-9"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700">
              Confirm password
            </label>
            <div className="relative mt-1">
              <Lock className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
              <input
                type="password"
                required
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="Re-enter password"
                className="input-control w-full pl-9"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={isLoading}
            className="btn-primary mt-2 w-full justify-center"
          >
            {isLoading ? "Setting up account..." : "Complete account setup"}
          </button>
        </form>

        <p className="mt-6 text-center text-xs text-slate-500">
          Already have an account?{" "}
          <Link to="/login" className="font-medium text-slate-800 underline">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
};
