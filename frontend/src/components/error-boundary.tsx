import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  errorMessage: string;
}

export class ErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = {
    hasError: false,
    errorMessage: "",
  };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return {
      hasError: true,
      errorMessage:
        error?.message || "An unexpected rendering error occurred.",
    };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Ledger UI ErrorBoundary caught error:", error, info);
  }

  handleReset = () => {
    this.setState({ hasError: false, errorMessage: "" });
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="mx-auto my-8 max-w-xl rounded-2xl border border-red-200 bg-white p-6 shadow-sm">
          <div className="flex items-start gap-3">
            <div className="rounded-xl bg-red-100 p-2 text-red-700">
              <AlertTriangle className="h-5 w-5" />
            </div>
            <div className="space-y-2">
              <h2 className="text-lg font-semibold text-slate-900">
                Unable to render this view
              </h2>
              <p className="text-sm text-slate-600">
                {this.state.errorMessage}
              </p>
              <div className="flex flex-wrap gap-2 pt-2">
                <button
                  type="button"
                  onClick={this.handleReset}
                  className="btn-primary inline-flex items-center gap-1.5 text-sm"
                >
                  <RefreshCw className="h-4 w-4" />
                  Try again
                </button>
                <a href="/" className="btn-secondary text-sm">
                  Return to dashboard
                </a>
              </div>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
