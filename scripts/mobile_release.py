#!/usr/bin/env python3
"""Build, verify and publish the single APK for the public mobile release channel."""

import argparse
import base64
import binascii
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile


REPO = "Baizey/spofify-mobile"
API = f"https://api.github.com/repos/{REPO}"
UPLOADS = f"https://uploads.github.com/repos/{REPO}"
ASSET = "spofify-mobile-release.apk"
RELEASE_ID = "dev.spofify.mobile.release"
MAX_CODE = 2_100_000_000
MAX_BYTES = 150 * 1024 * 1024
MAX_LATEST_BYTES = 128 * 1024
ASSET_CDN = "release-assets.githubusercontent.com"
ASSET_MAX_REDIRECTS = 3
ASSET_TIMEOUT = 120
CERT_FILE = Path(__file__).with_name("mobile-release-cert.sha256")
WAITS = (0, 2, 4, 8, 16, 30)
SIGN_SECRETS = ("MOBILE_KEYSTORE_B64", "MOBILE_KEYSTORE_PASSWORD", "MOBILE_KEY_ALIAS", "MOBILE_KEY_PASSWORD")
TOKEN_ENV = "GH_TOKEN"


class InvalidRelease(Exception):
    pass


def need(condition, message):
    if not condition:
        raise InvalidRelease(message)


def version_code(text):
    need(isinstance(text, str) and re.fullmatch(r"[1-9][0-9]{0,9}", text),
         "Version code must be a positive decimal integer without leading zeroes.")
    code = int(text)
    need(code <= MAX_CODE, f"Version code must not exceed {MAX_CODE}.")
    return code


def version_name(text):
    need(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,79}", text),
         "Version name must be 1–80 ASCII letters/digits or .+_-, starting with a letter or digit.")
    return text


def tag_code(tag):
    need(isinstance(tag, str) and tag, "GitHub returned a missing tag name.")
    return version_code(tag.removeprefix("mobile-v")) if tag.startswith("mobile-v") else None


def cert_hash(value):
    need(re.fullmatch(r"[0-9a-fA-F]{64}", value),
         "Invalid or unconfigured certificate SHA-256 anchor. See docs/mobile-release.md.")
    return value.lower()


def signing_anchor(path):
    return cert_hash(path.read_text(encoding="ascii").strip())


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    need(sdk, "Set ANDROID_HOME to an SDK with Android build-tools 36.0.0.")
    return Path(sdk) / "build-tools" / "36.0.0" / name


def tool(name, *args, sensitive=False):
    env = {key: value for key, value in os.environ.items() if key not in (*SIGN_SECRETS, TOKEN_ENV)}
    if sensitive:
        for key in ("MOBILE_KEYSTORE_PASSWORD", "MOBILE_KEY_PASSWORD"):
            env[key] = os.environ[key]
    result = subprocess.run([build_tool(name), *args], capture_output=True, text=True, check=False,
                            env={**env, "LC_ALL": "C"})
    if result.returncode != 0:
        if not sensitive:
            print(result.stderr.strip(), file=sys.stderr)
        raise InvalidRelease(f"{name} failed; check toolchain or APK/signing inputs.")
    return result.stdout


def require_no_vcs_metadata(apk):
    try:
        with zipfile.ZipFile(apk) as package:
            need("META-INF/version-control-info.textproto" not in package.namelist(),
                 "APK contains build VCS metadata; do not publish it.")
    except zipfile.BadZipFile as exc:
        raise InvalidRelease("APK is not a readable ZIP package.") from exc


def require_apk(apk, app_id, code, name, *, debug=False):
    need(apk.is_file() and 0 < apk.stat().st_size <= MAX_BYTES, "APK missing, empty or over 150 MiB.")
    if not debug:
        require_no_vcs_metadata(apk)
    output = tool("aapt", "dump", "badging", apk)
    packages = [line for line in output.splitlines() if line.startswith("package:")]
    need(len(packages) == 1, "APK tool did not report exactly one package.")
    fields = dict(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)='([^']*)'", packages[0]))
    need(fields.get("name") == app_id, "Unexpected APK application ID.")
    need(fields.get("versionCode") == str(code), "APK versionCode does not match requested code.")
    need(fields.get("versionName") == name, "APK versionName does not match requested name.")
    manifest = tool("aapt", "dump", "xmltree", apk, "AndroidManifest.xml")
    need("versionCodeMajor" not in fields and not re.search(r"^\s*A: android:versionCodeMajor\b", manifest, re.MULTILINE),
         "APK must not set versionCodeMajor.")
    need("split" not in fields and not any(line.startswith("uses-split:") for line in output.splitlines()),
         "Expected a universal APK, not a split APK.")
    need(("application-debuggable" in output.splitlines()) == debug, "Unexpected debuggable flag.")
    need("sdkVersion:'26'" in output.splitlines(), "Unexpected APK minimum Android SDK.")


def verify_apk(apk, code, name, expected_cert):
    require_apk(apk, RELEASE_ID, code, name)
    tool("zipalign", "-c", "-P", "16", "4", apk)
    output = tool("apksigner", "verify", "--verbose", "--print-certs", "-Werr", apk)
    signers = re.findall(r"^Signer #([0-9]+) certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                         output, re.MULTILINE)
    need(len(signers) == 1 and signers[0][0] == "1" and "Number of signers: 1" in output.splitlines(),
         "APK must have exactly one verified signer.")
    need(cert_hash(signers[0][1]) == expected_cert, "APK signer does not match the public certificate anchor.")


def stage_unsigned(directory, apk, code, name):
    files = list(directory.rglob("*.apk"))
    need(len(files) == 1, "Release build must produce exactly one APK.")
    require_apk(files[0], RELEASE_ID, code, name)
    shutil.copyfile(files[0], apk)


def sign_apk(unsigned, signed, code, name, expected_cert):
    require_apk(unsigned, RELEASE_ID, code, name)
    need(not signed.exists(), "Refusing to overwrite a signed APK.")
    need(all(os.environ.get(key) for key in SIGN_SECRETS), "Mobile signing secrets are missing.")
    with tempfile.TemporaryDirectory(prefix="mobile-sign-") as path:
        directory = Path(path)
        keystore = directory / "release.p12"
        try:
            keystore.write_bytes(base64.b64decode(os.environ["MOBILE_KEYSTORE_B64"], validate=True))
        except (binascii.Error, ValueError) as exc:
            raise InvalidRelease("MOBILE_KEYSTORE_B64 must contain single-line base64.") from exc
        aligned = directory / "aligned.apk"
        output = directory / ASSET
        tool("zipalign", "-P", "16", "-f", "4", unsigned, aligned)
        tool("apksigner", "sign", "--ks", keystore, "--ks-type", "PKCS12",
             "--ks-key-alias", os.environ["MOBILE_KEY_ALIAS"], "--ks-pass", "env:MOBILE_KEYSTORE_PASSWORD",
             "--key-pass", "env:MOBILE_KEY_PASSWORD", "--v4-signing-enabled", "false", "--out", output,
             aligned, sensitive=True)
        verify_apk(output, code, name, expected_cert)
        shutil.copyfile(output, signed)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def header_values(headers, name):
    if hasattr(headers, "get_all"):
        return headers.get_all(name, [])
    value = headers.get(name)
    return [] if value is None else [value]


def require_full_response(response):
    encoding = header_values(response.headers, "Content-Encoding")
    need(response.status == 200 and (not encoding or len(encoding) == 1 and encoding[0].lower() == "identity")
         and not header_values(response.headers, "Content-Range"), "Unexpected HTTP status, range or encoding for full body.")
    lengths = header_values(response.headers, "Content-Length")
    if not lengths:
        return None
    need(len(lengths) == 1 and re.fullmatch(r"[0-9]{1,19}", lengths[0]), "Invalid HTTP Content-Length.")
    return int(lengths[0])


def unique_json_object(values):
    mapping = dict(values)
    need(len(mapping) == len(values), "Duplicate JSON key in public latest response.")
    return mapping


class GitHub:
    def __init__(self, token=None):
        self.token = token
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, path, *, method="GET", payload=None, upload=None, missing=False):
        headers = {"Accept": "application/vnd.github+json", "Accept-Encoding": "identity",
                   "User-Agent": "spofify-mobile-release", "X-GitHub-Api-Version": "2022-11-28"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = None
        origin = API
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        if upload is not None:
            origin = UPLOADS
            data = upload
            headers.update({"Content-Type": "application/vnd.android.package-archive",
                            "Content-Length": str(os.fstat(upload.fileno()).st_size)})
        request = urllib.request.Request(origin + path, data=data, headers=headers, method=method)
        try:
            latest = path == "/releases/latest"
            with self.opener.open(request, timeout=10 if latest else 60) as response:
                limit = MAX_LATEST_BYTES if latest else 16 * 1024 * 1024
                length = require_full_response(response) if latest else None
                need(length is None or length <= limit, "GitHub API response exceeded size limit.")
                body = response.read(limit + 1)
                need(len(body) <= limit, "GitHub API response exceeded size limit.")
                need(length is None or len(body) == length, "Invalid GitHub API response length.")
                if latest:
                    return json.loads(body.decode("utf-8"), object_pairs_hook=unique_json_object)
                return json.loads(body)
        except urllib.error.HTTPError as exc:
            if missing and exc.code == 404:
                return None
            raise InvalidRelease(f"GitHub API {method} {path} failed (HTTP {exc.code}).") from exc
        except (OSError, ValueError, RecursionError, http.client.HTTPException) as exc:
            raise InvalidRelease(f"GitHub API {method} {path} did not return readable JSON.") from exc

    def all(self, path):
        for page in range(1, 1001):
            items = self.request(f"{path}?per_page=100&page={page}")
            need(isinstance(items, list) and all(isinstance(item, dict) for item in items),
                 "GitHub API returned invalid list data.")
            yield from items
            if len(items) < 100:
                return
        raise InvalidRelease("GitHub API pagination limit reached; cannot verify complete history.")


def require_public_repo(public):
    repo = public.request("")
    need(repo.get("full_name") == REPO and repo.get("private") is False and repo.get("visibility") == "public",
         f"Expected anonymous access to the public {REPO} repository.")


def git_commit(ref, name):
    target = ref.get("object", {})
    sha = target.get("sha")
    need(ref.get("ref") == name and target.get("type") == "commit" and isinstance(sha, str)
         and re.fullmatch(r"[0-9a-f]{40}", sha), "GitHub did not return the expected public Git commit ref.")
    return sha


def gate_history(auth, public, code, *, own_draft=None):
    highest = 0
    for tag in auth.all("/tags"):
        highest = max(highest, tag_code(tag.get("name")) or 0)
    for release in auth.all("/releases"):
        published_code = tag_code(release.get("tag_name"))
        if own_draft is not None and release.get("id") == own_draft:
            need(published_code == code and release.get("draft") is True and release.get("prerelease") is False,
                 "The reserved draft changed during publication.")
            continue
        highest = max(highest, published_code or 0)
    latest = public.request("/releases/latest", missing=True)
    if latest is not None:
        highest = max(highest, tag_code(latest.get("tag_name")) or 0)
    need(code > highest, f"Version code {code} must exceed all existing mobile release/tag codes (max {highest}).")


def browser_url(tag):
    return f"https://github.com/{REPO}/releases/download/{tag}/{ASSET}"


def approved_asset_redirect(location):
    need(location and len(location) <= 8192 and not re.search(r"[^\x21-\x7e]|[\\#]", location),
         "Unsafe APK download redirect.")
    parsed = urllib.parse.urlsplit(location)
    need(parsed.scheme == "https" and parsed.netloc == ASSET_CDN and parsed.path.startswith("/"),
         "APK download redirected outside the approved GitHub CDN.")
    return location


def verify_public_download(tag, size, digest):
    """Fetch the same canonical APK bytes as the updater; never use API auth or redirects implicitly."""
    need(tag_code(tag) is not None and 0 < size <= MAX_BYTES, "Invalid public APK tag or size.")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    url = browser_url(tag)
    deadline = time.monotonic() + ASSET_TIMEOUT
    for hop in range(ASSET_MAX_REDIRECTS + 1):
        need(time.monotonic() < deadline, "Public APK download exceeded time limit.")
        request = urllib.request.Request(url, headers={"Accept-Encoding": "identity", "Accept": "application/octet-stream",
                                                       "User-Agent": "Spofify-Mobile-Updater"})
        try:
            with opener.open(request, timeout=min(10, max(0.1, deadline - time.monotonic()))) as response:
                length = require_full_response(response)
                need(length is None or length == size, "Public APK Content-Length does not match signed APK.")
                downloaded = 0
                actual = hashlib.sha256()
                while True:
                    need(time.monotonic() < deadline, "Public APK download exceeded time limit.")
                    chunk = response.read1(min(64 * 1024, size - downloaded + 1))
                    need(time.monotonic() < deadline, "Public APK download exceeded time limit.")
                    if not chunk:
                        break
                    downloaded += len(chunk)
                    need(downloaded <= size, "Public APK exceeds the signed APK size.")
                    actual.update(chunk)
                need(downloaded == size, "Public APK size does not match signed APK.")
                need("sha256:" + actual.hexdigest() == digest, "Public APK SHA-256 does not match signed APK.")
                return
        except urllib.error.HTTPError as exc:
            with exc:
                if exc.code not in (301, 302, 303, 307, 308):
                    raise InvalidRelease(f"Anonymous APK download failed (HTTP {exc.code}).") from exc
                need(hop < ASSET_MAX_REDIRECTS, "Public APK exceeded redirect limit.")
                urls = exc.headers.get_all("Location", [])
                need(len(urls) == 1, "Missing or ambiguous APK download redirect.")
                try:
                    url = approved_asset_redirect(urls[0])
                except ValueError as invalid:
                    raise InvalidRelease("Invalid APK download redirect.") from invalid
        except (OSError, http.client.HTTPException, ValueError) as exc:
            raise InvalidRelease("Anonymous APK download did not return readable bytes.") from exc


def release_id(release):
    identifier = release.get("id")
    need(type(identifier) is int and identifier > 0, "GitHub did not return a valid release ID.")
    return identifier


def require_shape(release, identifier, tag, size, digest, *, draft, pending_digest=False):
    need(release_id(release) == identifier and release.get("tag_name") == tag
         and release.get("draft") is draft and release.get("prerelease") is False,
         "Release is not the expected stable draft/public release.")
    assets = release.get("assets")
    need(isinstance(assets, list), "Missing release assets list.")
    if pending_digest and not assets:
        return False
    need(len(assets) == 1 and isinstance(assets[0], dict), "Release must have exactly one asset.")
    asset = assets[0]
    need(asset.get("name") == ASSET and asset.get("state") == "uploaded",
         "Unexpected release asset name or upload state.")
    # Drafts may use GitHub's temporary "untagged-..." download URL; only the public URL is a contract.
    if not draft:
        need(asset.get("browser_download_url") == browser_url(tag), "Unexpected public APK download URL.")
    need(type(asset.get("size")) is int and 0 < asset["size"] <= MAX_BYTES and asset["size"] == size,
         "GitHub asset size does not match the signed APK (or is over 150 MiB).")
    value = asset.get("digest")
    if pending_digest and value in (None, ""):
        return False
    need(isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-fA-F]{64}", value)
         and value.lower() == digest, "GitHub asset SHA-256 digest is missing or mismatched.")
    return True


def publish(auth, public, apk, code, commit):
    need(re.fullmatch(r"[0-9a-f]{40}", commit), "Expected the exact packaged commit SHA.")
    tag = f"mobile-v{code}"
    need(apk.name == ASSET, f"Expected the signed APK to be named {ASSET}.")
    size = apk.stat().st_size
    need(0 < size <= MAX_BYTES, "Signed APK missing, empty or over 150 MiB.")
    with apk.open("rb") as stream:
        digest = "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()

    require_public_repo(public)
    gate_history(auth, public, code)
    draft = auth.request("/releases", method="POST", payload={
        "tag_name": tag, "name": tag, "target_commitish": commit, "body": "", "draft": True,
        "prerelease": False, "generate_release_notes": False, "make_latest": "false",
    })
    identifier = release_id(draft)
    path = f"/releases/{identifier}"
    need(draft.get("tag_name") == tag and draft.get("target_commitish") == commit
         and draft.get("draft") is True and draft.get("prerelease") is False and draft.get("assets") == [],
         "GitHub did not reserve the expected empty draft targeting the packaged commit.")
    with apk.open("rb") as stream:
        auth.request(f"{path}/assets?name={ASSET}", method="POST", upload=stream)
    for delay in WAITS:
        time.sleep(delay)
        draft = auth.request(path)
        need(draft.get("target_commitish") == commit, "Draft no longer targets the packaged commit.")
        if require_shape(draft, identifier, tag, size, digest, draft=True, pending_digest=True):
            break
    else:
        raise InvalidRelease("GitHub did not supply the APK digest before timeout; release remains a draft.")

    gate_history(auth, public, code, own_draft=identifier)
    draft = auth.request(path)
    need(draft.get("target_commitish") == commit, "Draft no longer targets the packaged commit.")
    require_shape(draft, identifier, tag, size, digest, draft=True)
    # Create-only, as on Desktop. Never reuse an existing tag, even if it has the same SHA.
    ref = f"refs/tags/{tag}"
    created = auth.request("/git/refs", method="POST", payload={"ref": ref, "sha": commit})
    need(git_commit(created, ref) == commit, "Release tag does not match the packaged commit.")
    try:
        auth.request(path, method="PATCH", payload={"draft": False, "prerelease": False, "make_latest": "true"})
        last_error = None
        for delay in WAITS:
            time.sleep(delay)
            try:
                latest = public.request("/releases/latest")
                require_shape(latest, identifier, tag, size, digest, draft=False)
                need(git_commit(public.request(f"/git/ref/tags/{tag}"), ref) == commit,
                     "Published tag does not point to the packaged commit.")
                verify_public_download(tag, size, digest)
                print(f"Verified public latest: {tag}, {ASSET}, {size} bytes, {digest}")
                return
            except InvalidRelease as exc:
                last_error = exc
        raise InvalidRelease(f"Anonymous latest verification did not complete: {last_error}")
    except InvalidRelease as exc:
        raise InvalidRelease(f"Release may already be public; inspect {tag} before taking action. {exc}") from exc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    debug = sub.add_parser("verify-debug")
    debug.add_argument("apk", type=Path)
    for command in ("validate-inputs", "stage-unsigned", "sign", "verify-apk", "publish"):
        options = sub.add_parser(command)
        options.add_argument("--version-code", required=True)
        options.add_argument("--version-name", required=True)
        if command in ("validate-inputs", "sign", "verify-apk"):
            options.add_argument("--cert-file", type=Path, default=CERT_FILE,
                                 help="Override for disposable-key tests only")
        if command == "stage-unsigned":
            options.add_argument("directory", type=Path)
        if command in ("stage-unsigned", "sign", "verify-apk", "publish"):
            options.add_argument("apk", type=Path)
        if command == "sign":
            options.add_argument("--unsigned", type=Path, required=True)
        if command == "publish":
            options.add_argument("--commit", required=True, help="Exact source commit packaged by the workflow")
    args = parser.parse_args()
    try:
        if args.command == "verify-debug":
            require_apk(args.apk, "dev.spofify.mobile", 1, "0.1.0", debug=True)
            tool("apksigner", "verify", args.apk)
        else:
            code, name = version_code(args.version_code), version_name(args.version_name)
            if args.command == "stage-unsigned":
                stage_unsigned(args.directory, args.apk, code, name)
            elif args.command == "publish":
                token = os.environ.get(TOKEN_ENV)
                need(token, "Missing GitHub Actions token (GH_TOKEN).")
                verify_apk(args.apk, code, name, signing_anchor(CERT_FILE))
                publish(GitHub(token), GitHub(), args.apk, code, args.commit)
            else:
                expected = signing_anchor(args.cert_file)
                if args.command == "sign":
                    sign_apk(args.unsigned, args.apk, code, name, expected)
                elif args.command == "verify-apk":
                    verify_apk(args.apk, code, name, expected)
    except (InvalidRelease, OSError, ValueError, AttributeError, TypeError) as exc:
        parser.exit(1, f"Mobile release: {exc}\n")


if __name__ == "__main__":
    main()
