# Authentication boundary

## Ownership

`ServerOrigin` accepts explicit HTTP and HTTPS root origins, including IPv6,
canonicalizes scheme/host/effective port and rejects credentials, ambiguous
authority, controls, paths, query and fragment. HTTP is an explicit configured
transport, not a guessed fallback. TLS and hostname validation are never changed.
Android permits cleartext because the server URL is user-configured; the only
network adapter constructs fixed auth paths within that exact origin.

`OkHttpAuthApi` implements typed JSON with kotlinx.serialization. Cookies are
explicit request/response credentials, not an automatic shared jar. Redirects,
connection retry, authenticators and private disk cache are disabled. POST bodies
are one-shot, including OkHttp follow-up cases. No request/response logging is
installed. Error bodies never become user-facing copy.

- POST /api/auth/login: exact Origin, X-Spofify-Login: 1,
  X-Spofify-Session-Mode: rolling-v1 and JSON username/password.
- GET /api/auth/session: session cookie; JSON accountId/username/role/csrfToken.
- POST /api/auth/logout: captured cookie, exact Origin and X-CSRF-Token.
  Logout cannot renew or republish a cookie.
- HTTP uses spofify_session; HTTPS uses __Host-spofify_session. Cookies must be
  host-only, HttpOnly, root-path and have the transport-appropriate Secure flag.

Login is not promoted until a subsequent session validation agrees on immutable
accountId. Successful validation may renew a cookie; absence of renewal preserves
the prior absolute expiry and negotiation. Persisted Max-Age is normalized to an
absolute Expires. A local clock/deadline does not prove server revocation. There is
no keepalive, silent password replay or inferred expiry extension.

## Lifetime and publication

`SessionRepository` is application-lifetime, with a private operation generation
and a distinct logical authenticated identity. Every transition cancels the old
request/operation job and fences navigation immediately. Network operations run
outside the publication monitor; completion, cookie promotion and persistence
must still own the captured generation. Serialization of all store writes prevents
a late predecessor from replacing a successor. Renewal/revalidation keeps the
logical identity; a new login has a new incarnation.

Only current GET-session 401 is authoritative session loss. Offline/TLS/DNS/5xx,
malformed response, corrupt storage and account mismatch fence private access but
retain recovery material for explicit retry or manual sign-in. Stale 401 or
Set-Cookie cannot affect a successor. This slice has no catalogue/media work.

Sign out fences immediately. Its independently scheduled local invalidation is not
cancelled by a new login; a newer durable generation prevents a delayed clear from
erasing a successor. Best-effort bounded remote logout uses only the retired
credential. Local or remote failure is reported without deleting keys or other data.
A failed local invalidation cannot promise restart-safe signout.

## Installed encrypted records

No migration is performed. The reviewed historical Android namespaces are retained:

| Record | SharedPreferences | Entry | Android Keystore alias | AAD |
| --- | --- | --- | --- | --- |
| Session | session | encrypted | spofify.session.v1 | none |
| Successful login | remembered_login | encrypted_login | spofify.login.v1 | alias UTF-8 |

Ciphertext remains Base64(12-byte IV + AES/GCM ciphertext with 128-bit tag).
Session JSON retains server/cookie/session/sessionMode; nested session retains
accountId/username/role/csrfToken. Remembered JSON retains
server/username/password. Unknown legacy fields are ignored; legacy remember flags
do not suppress successful saves. Existing key aliases are reused, never exported,
replaced or deleted. A missing key with existing ciphertext is an error, not
permission to regenerate. Read errors preserve the original encrypted entry.

The latest fully successful current login replaces the remembered triple with
canonical origin, authenticated username and unmodified password. Failed,
cancelled and partial attempts never replace it. Storage failures keep the valid
in-memory session and report that saving failed; encryption completes before any
preference edit. Password input belongs only to a private non-saveable editor and
submission lifetime, not StateFlow/SavedStateHandle/Bundle/logs. Autofill is
revision-fenced, fills the complete triple and never submits. Origin/username changes
clear a mismatched secret and reveal state. Failed network submission preserves
edits. Signout/expiry retain remembered credentials for explicit reauthentication.

Backup and device transfer exclude every app data domain. Auth code never touches
download/cache/receipt files. Same application ID and compatible record syntax
alone do not establish installed signing/data continuity or actual Keystore proof.
