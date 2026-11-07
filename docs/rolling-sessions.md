# Rolling mobile sign-in

Mobile requests `X-Spofify-Session-Mode: rolling-v1` during explicit password
login. A supporting server confirms that mode in both the login JSON summary
and response header, with a policy-valid session cookie. A legacy server which
confirms no mode retains its legacy session behavior; absence is never treated
as support. Browser/default login remains unchanged on the server.

The negotiated session uses the **same access token, CSRF, logical auth
incarnation and actual `Session` object** while its deadline advances. There is
no extra renewal credential, remember-me toggle, password replay or keepalive.
Normal successful authenticated API/media activity, including background playback,
HEAD/ranges and conditional responses, maintains the server's rolling window.
The server coalesces durable touches hourly: the effective inactivity window is
29 days 23 hours through 30 days. Truly server-expired sessions require login.

## Deadline publication and persistence

Only a successful exact-origin response in the negotiated mode, carrying one
policy-valid cookie for the **same** token, may extend the client deadline.
The cookie must retain host-only/root-path/HttpOnly/scheme-appropriate Secure
policy. Authenticated redirects remain disabled; TLS verification is unchanged.
Ordinary unnegotiated Set-Cookie responses cannot install or replace auth.

The absolute `Expires` deadline is authoritative. `Max-Age` receipt-time rounding
must not create an apparent extension and storage write on every quiet poll.
Late smaller deadlines are ignored. Only real newer deadlines are persisted;
a failed save retains both live validated auth and the previous encrypted record,
with at most one retry per minute triggered by subsequent normal responses.
No scheduled request is created for persistence or expiry.

The negotiated mode is stored inside the existing Android Keystore/AES-GCM
session blob, with the original preference namespace, alias and wire layout.
Legacy records without the field remain readable. There is no plaintext fallback
or use of separately remembered passwords. Temporary Keystore/storage failures
retain ciphertext for explicit reconnect retry; malformed/tampered records still
fail closed without deleting unrelated data or regenerating shared keys.

## Races, restoration and output authority

A request captures its credential and logical generation when the Call is
created, so dispatcher queuing cannot borrow a successor account's credentials.
Ordinary authenticated calls recheck the **current live** authorization/deadline
both before connection work and at wire dispatch; a queued snapshot cannot send
an expired cookie or reacquire dormant auth. A healthy newer same-token deadline
continues to authorize its held call. Only the explicit session probe and fixed
retired logout may bypass that live-auth check. Its credential revision is
recorded at wire dispatch. An old in-flight 401 cannot
revoke a later deadline publication; a freshly dispatched authoritative 401 still
invalidates auth and fences playback. Uncertain writes and musical commands are
never automatically replayed.

Logout retires local auth and its store generation **before** network suspension.
Only the fixed one-shot logout request may send its captured old credential;
late cookies, logout responses and retired store owners cannot resurrect auth or
clear a successor login. Origin/account switches retain their existing fences.
Headers already committed by the server before logout may arrive later; these
client fences are required rather than assuming wire arrival order.

A persisted summary is not live authority. Restore validates the saved origin
and cookie against `/api/auth/session` before installing a Session. Offline,
5xx and incompatible/partial responses retain dormant saved auth for retry.
A locally stale rolling deadline may result from a lost response or interrupted
save: it is fenced dormant and may send the original cookie **only for server
revalidation**, without granting cached/media authority while waiting. The same
live ViewModel observes a distinct reconnect-required state and exposes the
existing SignIn saved-connection Retry action; it does not require recreation or
destructive logout. A late ordinary response may persist a valid newer deadline
but cannot restore authority after dormancy was observed. Explicit Retry performs
`GET /api/auth/session`; successful validation recovers the connection, while a
definitive 401 returns to ordinary login. The server still rejects a genuinely
expired/revoked row; the client does not renew it.

Ordinary deadline extensions do not emit a new auth incarnation, replace the
Session reference, restart coordination, select output, replay music, or alter
queue/current/elapsed/playing/history. Local-deadline uncertainty and authoritative
revocation separately fence live output. Receiver/activation leases remain
independent and are never restored from persistent sign-in.

Live authority reads consult the current cookie deadline on every check; they do
not retain an old expiry value or a one-shot timer which disappears on extension.
The separate captured playback-leave capability keeps its existing four-second,
capture-relative cleanup bound and captured cookie expiry. It must not borrow a
later deadline or successor credential. None of these source/JVM contracts imply
installed Android, physical audio/background acceptance or server deployment.
