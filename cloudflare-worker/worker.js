/**
 * illumera Crash Report Worker
 *
 * Receives authenticated ACRA crash reports and relays them as GitHub issues
 * and, when configured, email. The worker fails closed when its shared auth
 * secret is missing and bounds/sanitizes untrusted report input before relay.
 */

const GITHUB_API = "https://api.github.com";
const MAX_REPORT_BYTES = 256 * 1024;
const MAX_FIELD_CHARS = 2_000;
const MAX_STACK_CHARS = 40_000;
const MAX_JSON_CHARS = 80_000;
const REPO_PART_RE = /^[A-Za-z0-9_.-]+$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/crash-report") {
      return new Response("Not found", { status: 404 });
    }

    // Fail closed. A missing deployment secret must never silently turn this
    // endpoint into an unauthenticated public issue/email relay.
    if (!env.AUTH_TOKEN) {
      console.error("Crash relay is not configured: AUTH_TOKEN is missing");
      return new Response("Service unavailable", { status: 503 });
    }

    const authHeader = request.headers.get("Authorization") || "";
    const expectedHeader = "Basic " + btoa("acra:" + env.AUTH_TOKEN);
    if (!(await constantTimeStringEqual(authHeader, expectedHeader))) {
      return new Response("Unauthorized", { status: 401 });
    }

    const declaredLength = Number(request.headers.get("Content-Length") || "0");
    if (Number.isFinite(declaredLength) && declaredLength > MAX_REPORT_BYTES) {
      return new Response("Payload too large", { status: 413 });
    }

    let rawBody;
    try {
      rawBody = await request.arrayBuffer();
    } catch {
      return new Response("Invalid request body", { status: 400 });
    }
    if (rawBody.byteLength > MAX_REPORT_BYTES) {
      return new Response("Payload too large", { status: 413 });
    }

    let parsed;
    try {
      parsed = JSON.parse(new TextDecoder().decode(rawBody));
    } catch {
      return new Response("Invalid JSON", { status: 400 });
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return new Response("Invalid report", { status: 400 });
    }

    const report = sanitizeReport(parsed);
    const results = await Promise.allSettled([
      relayToGitHub(report, env),
      relayToEmail(report, env),
    ]);

    const anySucceeded = results.some((r) => r.status === "fulfilled" && r.value);
    for (const result of results) {
      if (result.status === "rejected") {
        console.error("Crash relay failed");
      }
    }

    return anySucceeded
      ? new Response("OK", { status: 200 })
      : new Response("All relays failed or unconfigured", { status: 500 });
  },
};

async function relayToGitHub(report, env) {
  if (!env.GITHUB_TOKEN || !env.GITHUB_OWNER || !env.GITHUB_REPO) return false;
  if (!REPO_PART_RE.test(env.GITHUB_OWNER) || !REPO_PART_RE.test(env.GITHUB_REPO)) {
    console.error("Invalid GitHub repository configuration");
    return false;
  }

  const stackTrace = field(report.STACK_TRACE, "No stack trace", MAX_STACK_CHARS);
  const appVersion = field(report.APP_VERSION_NAME);
  const androidVersion = field(report.ANDROID_VERSION);
  const phoneModel = field(report.PHONE_MODEL);
  const brand = field(report.BRAND);
  const crashDate = field(report.USER_CRASH_DATE);
  const totalMemory = field(report.TOTAL_MEM_SIZE);
  const availableMemory = field(report.AVAILABLE_MEM_SIZE);

  const signature = await crashSignature(stackTrace);
  const dedupeLabel = `crash-${signature}`;
  const exceptionLine = singleLine(stackTrace.split("\n")[0] || "Unknown exception", 180);

  const occurrence = [
    `**Occurrence** — ${markdownText(crashDate)}`,
    `- App version: \`${markdownCode(appVersion)}\``,
    `- Device: ${markdownText(brand)} ${markdownText(phoneModel)}, Android ${markdownText(androidVersion)}`,
    `- Memory: ${markdownText(availableMemory)} available / ${markdownText(totalMemory)} total`,
    "",
    "<details><summary>Stack trace</summary>",
    "",
    `<pre>${escapeHtml(stackTrace)}</pre>`,
    "</details>",
  ].join("\n");

  const ghHeaders = {
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "illumera-crash-worker",
    "Content-Type": "application/json",
  };
  const repoPath = `${env.GITHUB_OWNER}/${env.GITHUB_REPO}`;

  const searchQuery = encodeURIComponent(
    `repo:${repoPath} label:${dedupeLabel} state:open`
  );
  const searchResponse = await fetch(
    `${GITHUB_API}/search/issues?q=${searchQuery}`,
    { headers: ghHeaders }
  );
  if (!searchResponse.ok) {
    console.error(`GitHub search failed with status ${searchResponse.status}`);
    return false;
  }
  const searchResult = await searchResponse.json();
  const existingIssue = searchResult.items?.[0];

  if (existingIssue) {
    const issueNumber = Number(existingIssue.number);
    if (!Number.isSafeInteger(issueNumber) || issueNumber <= 0) return false;

    const commentResponse = await fetch(
      `${GITHUB_API}/repos/${repoPath}/issues/${issueNumber}/comments`,
      {
        method: "POST",
        headers: ghHeaders,
        body: JSON.stringify({ body: occurrence }),
      }
    );
    if (!commentResponse.ok) {
      console.error(`GitHub comment failed with status ${commentResponse.status}`);
      return false;
    }
    return true;
  }

  const title = `Crash: ${exceptionLine}`.slice(0, 250);
  const body = [
    `Automatically reported crash from v${markdownText(appVersion)} (${markdownText(brand)} ${markdownText(phoneModel)}, Android ${markdownText(androidVersion)}).`,
    "",
    occurrence,
  ].join("\n");

  const createResponse = await fetch(`${GITHUB_API}/repos/${repoPath}/issues`, {
    method: "POST",
    headers: ghHeaders,
    body: JSON.stringify({
      title,
      body,
      labels: ["crash-report", dedupeLabel],
    }),
  });
  if (!createResponse.ok) {
    // Labels that don't exist can be rejected depending on token permissions.
    const retryResponse = await fetch(`${GITHUB_API}/repos/${repoPath}/issues`, {
      method: "POST",
      headers: ghHeaders,
      body: JSON.stringify({ title, body }),
    });
    if (!retryResponse.ok) {
      console.error(`GitHub issue creation failed with status ${retryResponse.status}`);
      return false;
    }
  }
  return true;
}

async function relayToEmail(report, env) {
  if (!env.RESEND_API_KEY || !env.REPORT_EMAIL) return false;

  const stackTrace = field(report.STACK_TRACE, "No stack trace", MAX_STACK_CHARS);
  const appVersion = field(report.APP_VERSION_NAME);
  const androidVersion = field(report.ANDROID_VERSION);
  const phoneModel = field(report.PHONE_MODEL);
  const brand = field(report.BRAND);
  const crashDate = field(report.USER_CRASH_DATE);
  const totalMemory = field(report.TOTAL_MEM_SIZE);
  const availableMemory = field(report.AVAILABLE_MEM_SIZE);
  const display = field(report.DISPLAY);

  const subject = singleLine(`illumera Crash - v${appVersion} - ${brand} ${phoneModel}`, 180);
  const fullReport = JSON.stringify(report, null, 2).slice(0, MAX_JSON_CHARS);
  const body = `
ILLUMERA CRASH REPORT
======================

Date: ${crashDate}
App Version: ${appVersion}

DEVICE INFO
-----------
Brand: ${brand}
Model: ${phoneModel}
Android: ${androidVersion}
Display: ${display}
Total Memory: ${totalMemory}
Available Memory: ${availableMemory}

STACK TRACE
-----------
${stackTrace}

FULL REPORT (JSON)
-------------------
${fullReport}
`.trim();

  const emailResponse = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.RESEND_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: "illumera Crashes <crashes@resend.dev>",
      to: [env.REPORT_EMAIL],
      subject,
      text: body,
    }),
  });

  if (!emailResponse.ok) {
    console.error(`Resend failed with status ${emailResponse.status}`);
    return false;
  }
  return true;
}

function sanitizeReport(report) {
  const sanitized = {};
  for (const [key, value] of Object.entries(report)) {
    if (!/^[A-Z0-9_]{1,80}$/.test(key)) continue;
    const limit = key === "STACK_TRACE" ? MAX_STACK_CHARS : MAX_FIELD_CHARS;
    sanitized[key] = stringifyValue(value).slice(0, limit);
  }
  return sanitized;
}

function stringifyValue(value) {
  if (value == null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return "[unserializable]";
  }
}

function field(value, fallback = "unknown", max = MAX_FIELD_CHARS) {
  const text = stringifyValue(value).trim();
  return (text || fallback).slice(0, max);
}

function singleLine(value, max) {
  return stringifyValue(value)
    .replace(/[\r\n\u0000-\u001f\u007f]+/g, " ")
    .trim()
    .slice(0, max);
}

function markdownText(value) {
  return singleLine(value, MAX_FIELD_CHARS).replace(/[\\`*_{}\[\]()#+.!|>-]/g, "\\$&");
}

function markdownCode(value) {
  return singleLine(value, MAX_FIELD_CHARS).replace(/`/g, "'");
}

function escapeHtml(value) {
  return stringifyValue(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

async function constantTimeStringEqual(left, right) {
  const encoder = new TextEncoder();
  const [leftDigest, rightDigest] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(left)),
    crypto.subtle.digest("SHA-256", encoder.encode(right)),
  ]);
  const a = new Uint8Array(leftDigest);
  const b = new Uint8Array(rightDigest);
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** Short, stable signature for a crash: hash of the exception type + top frames. */
async function crashSignature(stackTrace) {
  const normalized = stackTrace
    .split("\n")
    .slice(0, 6)
    .map((line) => line.replace(/:\d+\)?$/, "").trim())
    .join("\n");

  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(normalized)
  );
  const hex = [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return hex.slice(0, 12);
}
