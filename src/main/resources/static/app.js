// Signs in through Supabase Auth's REST endpoints directly, so the page loads no third-party script.
const STORE = "werger.session";
const $ = (id) => document.getElementById(id);
let config;
let fix;

function say(message) {
  $("status").textContent = message;
}

function saved() {
  try {
    return JSON.parse(localStorage.getItem(STORE));
  } catch {
    return null;
  }
}

// Tokens are JWTs or opaque base64url strings; anything else is not a session from Supabase and is never stored.
const TOKEN = /^[\w-]+(?:\.[\w-]+){0,2}$/;

function token(value) {
  if (typeof value !== "string" || value.length > 8192 || !TOKEN.test(value)) {
    throw new Error("not a Supabase session");
  }
  return value;
}

function save(session) {
  const lifetime = Number.parseInt(session.expires_in, 10);
  if (!Number.isSafeInteger(lifetime) || lifetime <= 0) {
    throw new Error("not a Supabase session");
  }
  localStorage.setItem(STORE, JSON.stringify({
    accessToken: token(session.access_token),
    refreshToken: token(session.refresh_token),
    expiresAt: Date.now() + lifetime * 1000,
  }));
}

// A magic link or Google sign-in returns the session in the URL fragment.
function takeSessionFromUrl() {
  const fragment = new URLSearchParams(location.hash.slice(1));
  if (fragment.get("access_token")) {
    try {
      save(Object.fromEntries(fragment));
    } catch {
      say("Sign-in failed: the link did not carry a valid session.");
    }
    history.replaceState(null, "", location.pathname);
  } else if (fragment.get("error_description")) {
    say(`Sign-in failed: ${fragment.get("error_description")}`);
    history.replaceState(null, "", location.pathname);
  }
}

async function auth(path, body) {
  const response = await fetch(`${config.supabaseUrl}/auth/v1/${path}`, {
    method: "POST",
    headers: { apikey: config.publishableKey, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`Supabase answered ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

async function accessToken() {
  const session = saved();
  if (!session) {
    return null;
  }
  if (session.expiresAt - Date.now() > 60_000) {
    return session.accessToken;
  }
  try {
    save(await auth("token?grant_type=refresh_token", { refresh_token: session.refreshToken }));
    return saved().accessToken;
  } catch {
    localStorage.removeItem(STORE);
    return null;
  }
}

async function api(path, init = {}) {
  const token = await accessToken();
  if (!token) {
    showSignIn();
    throw new Error("signed out");
  }
  const headers = { ...init.headers, Authorization: `Bearer ${token}` };
  if (init.body) {
    headers["Content-Type"] = "application/json";
  }
  return fetch(path, { ...init, headers });
}

async function problem(response) {
  try {
    return (await response.json()).detail;
  } catch {
    return `the server answered ${response.status}`;
  }
}

function showSignIn() {
  for (const id of ["suggest", "review", "who", "sign-out"]) {
    $(id).hidden = true;
  }
  $("sign-in").hidden = false;
}

async function showSignedIn() {
  const response = await api("/me");
  if (response.status === 403) {
    say("This account is suspended.");
    return;
  }
  if (!response.ok) {
    showSignIn();
    return;
  }
  const me = await response.json();
  $("who").textContent = `Signed in as a ${me.role}.`;
  for (const id of ["suggest", "review", "who", "sign-out"]) {
    $(id).hidden = false;
  }
  $("sign-in").hidden = true;
}

$("email-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const redirect = encodeURIComponent(location.origin + location.pathname);
  try {
    await auth(`otp?redirect_to=${redirect}`, { email: $("email").value, create_user: true });
    say("Check your email for the sign-in link.");
  } catch (error) {
    say(`Could not send the link: ${error.message}`);
  }
});

$("google").addEventListener("click", () => {
  const redirect = encodeURIComponent(location.origin + location.pathname);
  location.assign(`${config.supabaseUrl}/auth/v1/authorize?provider=google&redirect_to=${redirect}`);
});

$("sign-out").addEventListener("click", () => {
  localStorage.removeItem(STORE);
  showSignIn();
  say("Signed out.");
  $("email").focus();
});

$("suggest-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const response = await api("/pronunciation-fixes", {
    method: "POST",
    body: JSON.stringify({
      language: "kmr",
      written: $("written").value,
      say: $("say").value,
      note: $("note").value || null,
    }),
  });
  if (response.status === 201) {
    event.target.reset();
    say("Thank you. Another speaker will review your suggestion.");
    $("written").focus();
  } else {
    say(`Not sent: ${await problem(response)}.`);
  }
});

$("next").addEventListener("click", async () => {
  const response = await api("/pronunciation-fixes/next");
  if (response.status === 204) {
    $("fix").hidden = true;
    say("Nothing is waiting for review right now.");
    return;
  }
  fix = await response.json();
  $("fix-written").textContent = fix.written;
  $("fix-say").textContent = fix.sayKind === "phonemes" ? `/${fix.say}/ (IPA)` : fix.say;
  $("fix-note").textContent = fix.note ?? "None";
  $("comment").value = "";
  $("fix").hidden = false;
  say(`Suggestion for ${fix.written}.`);
  $("fix-written").closest("dl").setAttribute("tabindex", "-1");
  $("fix-written").closest("dl").focus();
});

async function decide(verdict) {
  const response = await api(`/pronunciation-fixes/${fix.id}/review`, {
    method: "POST",
    body: JSON.stringify({ verdict, comment: $("comment").value || null }),
  });
  $("fix").hidden = true;
  say(response.status === 204 ? "Thank you, your review is saved." : `Not saved: ${await problem(response)}.`);
  $("next").focus();
}

$("approve").addEventListener("click", () => decide("APPROVED"));
$("reject").addEventListener("click", () => decide("REJECTED"));

config = await (await fetch("/page-config")).json();
takeSessionFromUrl();
if (saved()) {
  await showSignedIn();
} else {
  showSignIn();
}
