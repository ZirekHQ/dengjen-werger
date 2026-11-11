// Signs in through Supabase Auth's REST endpoints directly, so the page loads no third-party script.
const STORE = "werger.session";
const $ = (id) => document.getElementById(id);
let config;
let fix;
let sentences = [];
let current = 0;
const SIGNED_IN = ["listen", "suggest", "review", "who", "sign-out"];

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

// A magic link or Google sign-in returns the session in the URL fragment. Anyone can craft a fragment, so the page
// stores nothing from it: it trades the refresh token for a session Supabase itself issues, and stores that.
async function takeSessionFromUrl() {
  const fragment = new URLSearchParams(location.hash.slice(1));
  const refreshToken = fragment.get("refresh_token");
  const failure = fragment.get("error_description");
  if (!refreshToken && !failure) {
    return;
  }
  history.replaceState(null, "", location.pathname);
  if (failure) {
    say(`Sign-in failed: ${failure}`);
    return;
  }
  try {
    save(await auth("token?grant_type=refresh_token", { refresh_token: refreshToken }));
  } catch {
    say("Sign-in failed: the link did not carry a valid session.");
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
  for (const id of SIGNED_IN) {
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
  for (const id of SIGNED_IN) {
    $(id).hidden = false;
  }
  $("sign-in").hidden = true;
  await loadSentences();
}

// Plays audio the API returns, or says why there is none.
async function play(response) {
  if (!response.ok) {
    say(`No audio: ${await problem(response)}.`);
    return false;
  }
  const player = $("player");
  URL.revokeObjectURL(player.src);
  player.src = URL.createObjectURL(await response.blob());
  await player.play();
  return true;
}

async function loadSentences() {
  const response = await api("/listening/sentences");
  sentences = response.ok ? await response.json() : [];
  current = 0;
  showSentence();
}

function showSentence() {
  $("words").hidden = true;
  const sentence = sentences[current];
  const done = !sentence;
  for (const id of ["play", "right", "wrong"]) {
    $(id).disabled = done;
  }
  $("sentence").textContent = done ? "You have heard every test sentence. Thank you." : sentence.text;
  $("sentence-count").textContent = done ? "" : `Sentence ${current + 1} of ${sentences.length}`;
}

$("play").addEventListener("click", async () => {
  say("Loading the sentence.");
  if (await play(await api(`/listening/sentences/${sentences[current].index}/audio`))) {
    say("");
  }
});

$("right").addEventListener("click", () => {
  current += 1;
  showSentence();
  say(sentences[current] ? "Next sentence." : "That was the last sentence.");
  $("sentence").setAttribute("tabindex", "-1");
  $("sentence").focus();
});

$("wrong").addEventListener("click", () => {
  const buttons = $("word-buttons");
  buttons.replaceChildren();
  const words = sentences[current].text.match(/[\p{L}\p{N}]+/gu) ?? [];
  for (const word of words) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = word;
    button.addEventListener("click", () => {
      $("written").value = word;
      $("say").value = "";
      say(`Now type how ${word} should sound.`);
      $("say").focus();
    });
    buttons.append(button, " ");
  }
  $("words").hidden = false;
  buttons.querySelector("button")?.focus();
});

async function preview(text) {
  await play(await api("/listening/preview", { method: "POST", body: JSON.stringify({ text }) }));
}

$("hear-say").addEventListener("click", () => {
  const text = $("say").value.trim();
  if (text.startsWith("/")) {
    say("Phonemes cannot be played yet; type a respelling to hear it.");
    return;
  }
  preview(text);
});

$("hear-fix").addEventListener("click", () => preview(fix.say));

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
  $("hear-fix").hidden = fix.sayKind === "phonemes";
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
await takeSessionFromUrl();
if (saved()) {
  await showSignedIn();
} else {
  showSignIn();
}
