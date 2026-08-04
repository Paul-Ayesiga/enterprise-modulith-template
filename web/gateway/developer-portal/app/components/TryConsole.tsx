"use client";

import { useState, type FormEvent } from "react";

type ProxyResponse = {
  url: string;
  status?: number;
  statusText?: string;
  timeMs?: number;
  headers?: Record<string, string>;
  body?: string;
  error?: string;
};

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"] as const;

type AuthMode = "session" | "bearer" | "apikey";

const MODES: { value: AuthMode; label: string }[] = [
  { value: "session", label: "My session" },
  { value: "bearer", label: "Bearer token" },
  { value: "apikey", label: "API key" }
];

export function TryConsole({
  initialPath = "/api/v1/settings",
  initialMethod = "GET",
  signedIn = false
}: {
  initialPath?: string;
  initialMethod?: string;
  signedIn?: boolean;
}) {
  const [method, setMethod] = useState<string>(METHODS.includes(initialMethod as never) ? initialMethod : "GET");
  const [path, setPath] = useState(initialPath);
  // Signed in → default to the session token (nothing to paste); pasting stays one click away.
  const [authMode, setAuthMode] = useState<AuthMode>(signedIn ? "session" : "bearer");
  const [credential, setCredential] = useState("");
  const [body, setBody] = useState("");
  const [loading, setLoading] = useState(false);
  const [res, setRes] = useState<ProxyResponse | null>(null);

  const hasBody = method !== "GET" && method !== "HEAD";

  async function send(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setRes(null);
    try {
      const response = await fetch("/api/proxy", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          method,
          path,
          authMode,
          credential: authMode === "session" ? null : credential,
          body: hasBody ? body : null
        })
      });
      setRes((await response.json()) as ProxyResponse);
    } catch (e) {
      setRes({ url: path, error: e instanceof Error ? e.message : String(e) });
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="console">
      <form className="console__req" onSubmit={send}>
        <div className="console__line">
          <select
            className="console__method"
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            aria-label="HTTP method"
          >
            {METHODS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
          <input
            className="console__path"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            placeholder="/api/v1/settings"
            aria-label="Request path"
            autoComplete="off"
            spellCheck={false}
          />
          <button type="submit" className="btn btn--primary" disabled={loading}>
            {loading ? "Sending…" : "Send"}
          </button>
        </div>

        <div className="field">
          <span className="field__label" id="try-auth-label">
            Authenticate as
          </span>
          <div role="radiogroup" aria-labelledby="try-auth-label" style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
            {MODES.map((m) => (
              <label key={m.value} style={{ display: "inline-flex", alignItems: "center", gap: 6, cursor: "pointer" }}>
                <input
                  type="radio"
                  name="try-auth-mode"
                  value={m.value}
                  checked={authMode === m.value}
                  onChange={() => setAuthMode(m.value)}
                />
                {m.label}
                {m.value === "session" && !signedIn && (
                  <span className="field__hint" style={{ margin: 0 }}>(sign in first)</span>
                )}
              </label>
            ))}
          </div>
          {authMode === "session" ? (
            <span className="field__hint">
              {signedIn
                ? "Requests carry your signed-in session token — server-side, nothing to paste."
                : "You're not signed in — session requests will answer 401. Sign in, or pick a pasted credential."}
            </span>
          ) : (
            <>
              <input
                id="try-credential"
                className="console__token"
                type="password"
                value={credential}
                onChange={(e) => setCredential(e.target.value)}
                placeholder={authMode === "bearer" ? "paste a token — run: make token" : "paste an API key — mint one under Credentials"}
                aria-label={authMode === "bearer" ? "Bearer token" : "API key"}
                autoComplete="off"
                spellCheck={false}
              />
              <span className="field__hint">
                {authMode === "bearer"
                  ? "Sent server-side as Authorization: Bearer; never stored. Leave blank to test an open route or see a 401."
                  : "Sent server-side as X-Api-Key; never stored. Keys come from the Credentials page."}
              </span>
            </>
          )}
        </div>

        {hasBody && (
          <div className="field">
            <label className="field__label" htmlFor="try-body">
              Request body (JSON)
            </label>
            <textarea
              id="try-body"
              className="console__body-in"
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={5}
              placeholder={'{ "value": "hi" }'}
              spellCheck={false}
            />
          </div>
        )}
      </form>

      {res && <ResponseView res={res} />}
    </div>
  );
}

function ResponseView({ res }: { res: ProxyResponse }) {
  const [showHeaders, setShowHeaders] = useState(false);

  if (res.error) {
    return (
      <div className="response response--err" role="alert">
        <p className="response__head">
          <span className="status-pill status-pill--err">Request failed</span>
        </p>
        <pre className="response__body">{res.error}</pre>
      </div>
    );
  }

  const cls = !res.status ? "err" : res.status < 300 ? "ok" : res.status < 500 ? "warn" : "err";
  return (
    <div className={`response response--${cls}`} aria-live="polite">
      <div className="response__head">
        <span className={`status-pill status-pill--${cls}`}>
          {res.status} {res.statusText}
        </span>
        <span className="response__time">{res.timeMs} ms</span>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={() => setShowHeaders((v) => !v)}
          aria-expanded={showHeaders}
        >
          {showHeaders ? "Hide" : "Show"} headers
        </button>
      </div>
      {showHeaders && res.headers && (
        <pre className="response__headers">
          {Object.entries(res.headers)
            .map(([k, v]) => `${k}: ${v}`)
            .join("\n")}
        </pre>
      )}
      <pre className="response__body">{pretty(res.body ?? "")}</pre>
    </div>
  );
}

function pretty(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw || "(empty response body)";
  }
}
