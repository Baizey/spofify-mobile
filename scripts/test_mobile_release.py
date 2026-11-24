import contextlib
import copy
from email.message import Message
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, Mock, patch
from urllib.error import HTTPError
import zipfile

import mobile_release as release


class VersionTest(unittest.TestCase):
    def test_codes(self):
        for bad in ("", "0", "01", "+1", "-1", "1.0", " 1", "1\n", "١", "2100000001", "9" * 5000):
            with self.subTest(bad=bad[:16]), self.assertRaises(release.InvalidRelease):
                release.version_code(bad)
        self.assertEqual(release.version_code("1"), 1)
        self.assertEqual(release.version_code("2100000000"), 2100000000)

    def test_names(self):
        self.assertEqual(release.version_name("1.2.3-rc_0+4"), "1.2.3-rc_0+4")
        for bad in ("", "-1", "1\n2", "1 2", "1/2", "1'2", "1;true", "1" * 81):
            with self.subTest(bad=bad), self.assertRaises(release.InvalidRelease):
                release.version_name(bad)

    def test_unconfigured_or_invalid_certificate_fails_closed(self):
        for bad in ("UNCONFIGURED", "", "abc", "F" * 63, "g" * 64):
            with self.subTest(bad=bad), self.assertRaises(release.InvalidRelease):
                release.cert_hash(bad)
        self.assertEqual(release.cert_hash("AB" * 32), "ab" * 32)

def mobile_release(code, name, *, draft=False, prerelease=False):
    return {"id": code, "tag_name": f"mobile-v{code}", "name": name, "draft": draft, "prerelease": prerelease}


class PlanTest(unittest.TestCase):
    def history(self, tags=(), releases=(), latest=None):
        auth, public = Mock(), Mock()
        auth.all.side_effect = {"/tags": [{"name": tag} for tag in tags], "/releases": releases}.__getitem__
        public.request.return_value = latest
        return auth, public

    def test_first_release_and_ignored_broker(self):
        broker = {"tag_name": "ci-broker-b6eb54f", "draft": False, "prerelease": False, "assets": []}
        for history in (self.history(), self.history((broker["tag_name"],), [broker], broker)):
            for bump, name in (("major", "1.0.0"), ("minor", "0.1.0"), ("patch", "0.0.1")):
                with self.subTest(bump=bump, history=history):
                    self.assertEqual(release.resolve_versions(*history, bump), (1, name))

    def test_uses_numerically_highest_stable_semver_and_separate_code_maximum(self):
        lower = mobile_release(20, "Spofify Mobile 2.9.49")
        higher = mobile_release(17, "Spofify Mobile 2.10.3")
        draft = mobile_release(30, "Spofify Mobile 10.0.0", draft=True)
        prerelease = mobile_release(40, "legacy prerelease title", prerelease=True)
        for stable_releases in ([lower, higher], [higher, lower]):
            for tags, extras, expected_code in (
                ((), [], 21), ((), [draft], 31), ((), [draft, prerelease], 41),
                (("mobile-v50",), [draft, prerelease], 51),
            ):
                for bump, name in (("major", "3.0.0"), ("minor", "2.11.0"), ("patch", "2.10.4")):
                    history = self.history(tags, stable_releases + extras, lower)
                    self.assertEqual(release.resolve_versions(*history, bump), (expected_code, name))

    def test_malformed_reserved_metadata_does_not_get_bypassed(self):
        for tag in ("mobile-v0004", "mobile-v", "mobile-v4-rc1", "mobile-v2100000001"):
            with self.subTest(tag=tag), self.assertRaisesRegex(release.InvalidRelease, "Version code"):
                release.resolve_versions(*self.history((tag,)), "patch")
        for title in (None, "mobile-v4", "Spofify Mobile 01.2.3", "Spofify Mobile 1.2.3-alpha",
                      "Spofify Mobile 1.2.3 ", "Spofify Mobile 1.2.3\n", "Spofify Mobile 4294967296.0.0"):
            with self.subTest(title=title), self.assertRaisesRegex(release.InvalidRelease, "exact title"):
                release.resolve_versions(*self.history(releases=[mobile_release(4, title)]), "patch")
        invalid = mobile_release(4, "Spofify Mobile 1.2.3")
        invalid.pop("draft")
        with self.assertRaisesRegex(release.InvalidRelease, "Missing release status"):
            release.resolve_versions(*self.history(releases=[invalid]), "patch")

    def test_fails_closed_if_public_latest_disagrees(self):
        record = mobile_release(4, "Spofify Mobile 1.2.3")
        for history in (self.history(latest=record),
                        self.history(releases=[dict(record, name="Spofify Mobile 1.2.4")], latest=record)):
            with self.assertRaisesRegex(release.InvalidRelease, "Anonymous latest disagrees"):
                release.resolve_versions(*history, "patch")

    def test_version_limits(self):
        with self.assertRaisesRegex(release.InvalidRelease, "No Android version codes remain"):
            release.resolve_versions(*self.history((f"mobile-v{release.MAX_CODE}",)), "patch")
        history = self.history(releases=[mobile_release(5, "Spofify Mobile 0.0.4294967295")])
        with self.assertRaisesRegex(release.InvalidRelease, "outside supported range"):
            release.resolve_versions(*history, "patch")
        self.assertEqual(release.resolve_versions(*history, "minor"), (6, "0.1.0"))

    def test_requires_the_exact_planned_pair(self):
        for code, name in ((0, "0.0.1"), (2, "0.0.1"), (1, "0.1.0")):
            with self.subTest(code=code, name=name), self.assertRaisesRegex(release.InvalidRelease, "no longer matches"):
                release.check_plan(*self.history(), "patch", code, name)
        release.check_plan(*self.history(), "patch", 1, "0.0.1")

    def test_resolution_uses_tags_and_releases_beyond_first_page(self):
        github, public = release.GitHub(), Mock()
        github.request = Mock(side_effect=[
            ([{"name": "unrelated"}] * 100, True), ([{"name": "mobile-v101"}], False),
            ([{"tag_name": "unrelated"}] * 100, True), ([mobile_release(7, "Spofify Mobile 1.10.0")], False),
        ])
        public.request.return_value = None
        self.assertEqual(release.resolve_versions(github, public, "patch"), (102, "1.10.1"))
        self.assertEqual([c.args[0] for c in github.request.call_args_list], [
            "/tags?per_page=100&page=1", "/tags?per_page=100&page=2",
            "/releases?per_page=100&page=1", "/releases?per_page=100&page=2",
        ])


class ApkMetadataTest(unittest.TestCase):
    def test_rejects_embedded_vcs_information(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / release.ASSET
            with zipfile.ZipFile(path, "w") as apk:
                apk.writestr("AndroidManifest.xml", b"fixture")
            release.require_no_vcs_metadata(path)
            with zipfile.ZipFile(path, "a") as apk:
                apk.writestr("META-INF/version-control-info.textproto", b"source-sha")
            with self.assertRaisesRegex(release.InvalidRelease, "VCS metadata"):
                release.require_no_vcs_metadata(path)


class TransportTest(unittest.TestCase):
    def test_short_page_with_continuation_stays_on_canonical_api_route(self):
        github = release.GitHub("disposable-token-fixture")
        github.opener = MagicMock()
        first, second = MagicMock(), MagicMock()
        first.__enter__.return_value.read.return_value = b'[{"tag_name":"unrelated"}]'
        first.__enter__.return_value.headers = {"Link": '<https://example.invalid/api?page=2>; rel="next"'}
        second.__enter__.return_value.read.return_value = b'[{"tag_name":"mobile-v4"}]'
        second.__enter__.return_value.headers = {"Link": '<https://example.invalid/api?page=1>; rel="prev"'}
        github.opener.open.side_effect = [first, second]
        self.assertEqual(list(github.all("/releases")), [{"tag_name": "unrelated"}, {"tag_name": "mobile-v4"}])
        self.assertEqual([c.args[0].full_url for c in github.opener.open.call_args_list],
                         [f"{release.API}/releases?per_page=100&page={p}" for p in (1, 2)])

    def test_fixed_upload_origin_and_streamed_length(self):
        github = release.GitHub("disposable-token-fixture")
        github.opener = MagicMock()
        response = github.opener.open.return_value.__enter__.return_value
        response.read.return_value = json.dumps({"state": "uploaded"}).encode()
        with tempfile.TemporaryFile() as apk:
            apk.write(b"abc")
            apk.seek(0)
            github.request(f"/releases/1/assets?name={release.ASSET}", method="POST", upload=apk)
            req = github.opener.open.call_args.args[0]
            self.assertEqual(req.full_url,
                             f"https://uploads.github.com/repos/Baizey/spofify-mobile/releases/1/assets?name={release.ASSET}")
            self.assertEqual(req.get_header("Content-length"), "3")
            self.assertIs(req.data, apk)
            self.assertEqual(req.get_header("Authorization"), "Bearer disposable-token-fixture")
        self.assertIsNone(release.NoRedirect().redirect_request(None, None, 302, None, {}, "https://example.invalid"))

    def test_latest_check_is_anonymous(self):
        public = release.GitHub()
        public.opener = MagicMock()
        response = public.opener.open.return_value.__enter__.return_value
        response.status, response.headers = 200, {}
        response.read.return_value = b"{}"
        public.request("/releases/latest")
        req = public.opener.open.call_args.args[0]
        self.assertEqual(req.full_url, "https://api.github.com/repos/Baizey/spofify-mobile/releases/latest")
        self.assertIsNone(req.get_header("Authorization"))
        response.read.assert_called_once_with(128 * 1024 + 1)
        response.read.return_value = b"{}" + b" " * (128 * 1024 - 2)
        self.assertEqual(public.request("/releases/latest"), {})
        response.read.return_value += b" "
        with self.assertRaisesRegex(release.InvalidRelease, "exceeded size limit"):
            public.request("/releases/latest")
        response.read.return_value = b'{"tag_name":"mobile-v1","\\u0074ag_name":"mobile-v2"}'
        with self.assertRaisesRegex(release.InvalidRelease, "Duplicate JSON key"):
            public.request("/releases/latest")


class DownloadTest(unittest.TestCase):
    tag = "mobile-v21"
    apk = b"synthetic APK bytes"
    cdn = "https://release-assets.githubusercontent.com/fixture?id=21"
    digest = "sha256:" + hashlib.sha256(apk).hexdigest()

    def setUp(self):
        self.opener = Mock()
        self.enterContext(patch.object(release.urllib.request, "build_opener", return_value=self.opener))

    def verify(self):
        release.verify_public_download(self.tag, len(self.apk), self.digest)

    def redirect(self, target, url=None):
        headers = Message()
        headers["Location"] = target
        return HTTPError(url or release.browser_url(self.tag), 302, "Found", headers, io.BytesIO())

    def response(self, data=None, **headers):
        response = io.BytesIO(self.apk if data is None else data)
        response.status = 200
        response.headers = {key.replace("_", "-"): value for key, value in headers.items()}
        return response

    def test_download_starts_from_canonical_url_and_follows_only_approved_cdn_anonymously(self):
        self.opener.open.side_effect = [self.redirect(self.cdn), self.response(Content_Length=str(len(self.apk)))]
        self.verify()
        requests = [call.args[0] for call in self.opener.open.call_args_list]
        self.assertEqual([req.full_url for req in requests], [release.browser_url(self.tag), self.cdn])
        for req in requests:
            self.assertEqual(req.get_method(), "GET")
            self.assertIsNone(req.get_header("Authorization"))
            self.assertIsNone(req.get_header("Cookie"))
            self.assertEqual(req.get_header("Accept"), "application/octet-stream")

    def test_rejects_unapproved_and_ambiguous_redirect_origins(self):
        for target in ("http://release-assets.githubusercontent.com/asset",
                       "https://release-assets.githubusercontent.com.evil.invalid/asset",
                       "https://example.invalid@release-assets.githubusercontent.com/asset",
                       "https://release-assets.githubusercontent.com:444/asset", "//release-assets.githubusercontent.com/asset",
                       "https://github.com/other", "/asset"):
            with self.subTest(target=target):
                self.opener.open.reset_mock()
                self.opener.open.side_effect = [self.redirect(target)]
                with self.assertRaisesRegex(release.InvalidRelease, "approved GitHub CDN"):
                    self.verify()
                self.assertEqual(self.opener.open.call_count, 1)

    def test_redirect_chain_is_bounded(self):
        self.opener.open.side_effect = [self.redirect(self.cdn) for _ in range(4)]
        with self.assertRaisesRegex(release.InvalidRelease, "exceeded redirect limit"):
            self.verify()
        self.assertEqual(self.opener.open.call_count, 4)
        self.opener.open.side_effect = [self.redirect(self.cdn + "x" * 8192)]
        with self.assertRaisesRegex(release.InvalidRelease, "Unsafe APK download redirect"):
            self.verify()

    def test_rejects_partial_and_ambiguous_body_headers(self):
        for pairs in ((('Content-Range', 'bytes 0-17/18'),),
                      (('Content-Encoding', 'identity'), ('Content-Encoding', 'identity')),
                      (('Content-Length', '18'), ('Content-Length', '18'))):
            response = self.response()
            response.headers = Message()
            for name, value in pairs:
                response.headers[name] = value
            self.opener.open.return_value = response
            with self.assertRaises(release.InvalidRelease):
                self.verify()

    def test_rejects_length_hash_and_encoding_mismatch(self):
        for data, headers, message in (
            (self.apk, {"Content_Length": "999"}, "Content-Length"),
            (self.apk, {"Content_Encoding": "gzip"}, "encoding"),
            (self.apk + b"X", {}, "exceeds the signed APK size"),
            (self.apk[:-1], {}, "size does not match"),
            (b"\x00" * len(self.apk), {}, "SHA-256 does not match"),
        ):
            with self.subTest(message=message):
                self.opener.open.return_value = self.response(data, **headers)
                with self.assertRaisesRegex(release.InvalidRelease, message):
                    self.verify()


class PublicationFixture:
    code = 21
    name = "0.1.1"
    tag = "mobile-v21"
    title = "Spofify Mobile 0.1.1"
    commit = "a" * 40
    release_id = 7

    def __init__(self, apk_bytes):
        self.public = Mock()
        self.auth = Mock()
        self.public.request.side_effect = self.public_request
        self.auth.request.side_effect = self.auth_request
        self.auth.all.side_effect = self.auth_list
        self.repo = {"full_name": release.REPO, "private": False, "visibility": "public"}
        self.tags = [{"name": "mobile-v19"}]
        self.history = [mobile_release(20, "Spofify Mobile 0.1.0")]
        self.created = self.uploaded = self.patched = False
        self.tagged = self.tag_exists = self.bad_created_ref = False
        self.race = self.late_semver_race = self.promotion_race = self.hide_latest = self.bad_tag_target = False
        self.bad_draft_title = self.bad_public_title = self.missing_draft = False
        self.apk_digest = hashlib.sha256(apk_bytes).hexdigest()
        self.expected_asset = {
            "name": release.ASSET, "state": "uploaded", "size": len(apk_bytes),
            "browser_download_url": f"https://github.com/Baizey/spofify-mobile/releases/download/{self.tag}/{release.ASSET}",
            "digest": "sha256:" + self.apk_digest,
        }
        self.draft_asset = copy.deepcopy(self.expected_asset)
        self.draft_asset["browser_download_url"] = "https://github.com/temporary-draft-url"
        self.extra_asset = False

    def record(self, *, draft):
        asset = self.draft_asset if draft else self.expected_asset
        assets = [asset] if self.uploaded else []
        if self.extra_asset and self.uploaded:
            assets += [asset]
        title = self.title
        if (draft and self.uploaded and self.bad_draft_title) or (not draft and self.bad_public_title):
            title = "Spofify Mobile 9.9.9"
        return {"id": self.release_id, "tag_name": self.tag, "name": title, "target_commitish": self.commit,
                "draft": draft, "prerelease": False, "assets": assets}

    def auth_list(self, path):
        if path == "/tags":
            return self.tags + ([{"name": "mobile-v99"}] if self.race and self.created else []) + (
                [{"name": self.tag}] if self.tagged else [])
        if path == "/releases":
            return self.history + ([self.record(draft=not self.patched)] if self.created and not self.missing_draft else []) + (
                [mobile_release(17, "Spofify Mobile 0.2.0")] if self.late_semver_race and self.tagged else []) + (
                [mobile_release(22, "Spofify Mobile 0.2.0")] if self.promotion_race and self.patched else [])
        raise AssertionError(f"Unexpected list: {path}")

    def public_request(self, path, *, missing=False):
        if path == "":
            return self.repo
        if path == "/releases/latest":
            return self.record(draft=False) if self.patched and not self.hide_latest else self.history[0]
        if path == f"/git/ref/tags/{self.tag}":
            assert self.tagged
            sha = "c" * 40 if self.bad_tag_target else self.commit
            return {"ref": f"refs/tags/{self.tag}", "object": {"type": "commit", "sha": sha}}
        raise AssertionError(f"Unexpected public request: {path}")

    def auth_request(self, path, *, method="GET", payload=None, upload=None):
        if method == "POST" and path == "/releases":
            assert payload == {"tag_name": self.tag, "name": self.title, "target_commitish": self.commit, "body": "",
                               "draft": True, "prerelease": False, "generate_release_notes": False,
                               "make_latest": "false"}, payload
            self.created = True
            return self.record(draft=True)
        if method == "POST" and path == f"/releases/{self.release_id}/assets?name={release.ASSET}":
            assert self.created and not self.uploaded
            assert hashlib.sha256(upload.read()).hexdigest() == self.apk_digest
            self.uploaded = True
            return self.draft_asset
        if method == "POST" and path == "/git/refs":
            assert self.uploaded and not self.tagged
            ref = f"refs/tags/{self.tag}"
            assert payload == {"ref": ref, "sha": self.commit}, payload
            if self.tag_exists:
                raise release.InvalidRelease("Reference already exists")
            self.tagged = True
            return {"ref": ref, "object": {"type": "commit", "sha": "d" * 40 if self.bad_created_ref else self.commit}}
        if path == f"/git/ref/tags/{self.tag}":
            assert self.tagged
            return {"ref": f"refs/tags/{self.tag}", "object": {"type": "commit", "sha": self.commit}}
        if path == f"/releases/{self.release_id}":
            if method == "PATCH":
                assert self.tagged
                assert payload == {"draft": False, "prerelease": False, "make_latest": "true"}, payload
                self.patched = True
            return self.record(draft=not self.patched)
        raise AssertionError(f"Unexpected authenticated request: {method} {path}")


class PublicationTest(unittest.TestCase):
    def setUp(self):
        temp = self.enterContext(tempfile.TemporaryDirectory())
        self.apk = Path(temp) / release.ASSET
        self.apk.write_bytes(b"synthetic APK bytes")
        self.fixture = PublicationFixture(self.apk.read_bytes())
        self.output = self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.enterContext(patch.object(release, "WAITS", (0, 0)))
        self.anchor = "a" * 64
        self.enterContext(patch.object(release, "signing_anchor", return_value=self.anchor))
        self.verify_apk = self.enterContext(patch.object(release, "verify_apk"))
        self.download = self.enterContext(patch.object(release, "verify_public_download"))
        self.download.side_effect = self.verify_download_after_promotion

    def verify_download_after_promotion(self, tag, size, digest):
        self.assertTrue(self.fixture.patched)
        self.assertEqual((tag, size, digest), (self.fixture.tag, self.apk.stat().st_size,
                                               "sha256:" + self.fixture.apk_digest))

    def publish(self):
        release.publish(self.fixture.auth, self.fixture.public, self.apk, "patch",
                        self.fixture.code, self.fixture.name, self.fixture.commit)

    def rejected(self, reason, *, draft_created, patched=False):
        with self.assertRaisesRegex(release.InvalidRelease, reason):
            self.publish()
        self.assertEqual(self.fixture.created, draft_created)
        self.assertEqual(self.fixture.patched, patched)
        self.assertNotIn("Verified public latest", self.output.getvalue())

    def test_verified_stable_release(self):
        self.publish()
        self.assertTrue(self.fixture.tagged)
        self.assertTrue(self.fixture.patched)
        self.verify_apk.assert_called_once_with(self.apk, self.fixture.code, self.fixture.name, self.anchor)
        self.download.assert_called_once()
        self.assertIn("Verified public latest: mobile-v21", self.output.getvalue())

    def test_branch_name_is_not_a_packaged_commit(self):
        self.fixture.commit = "main"
        self.rejected("exact packaged commit SHA", draft_created=False)
        self.fixture.auth.request.assert_not_called()

    def test_private_repository_rejected_before_mutation(self):
        self.fixture.repo["private"] = True
        self.rejected("Expected anonymous access", draft_created=False)

    def test_existing_tag_rejected_before_mutation(self):
        self.fixture.tags = [{"name": self.fixture.tag}]
        self.rejected(r"no longer matches.*\(22, 0\.1\.1\)", draft_created=False)

    def test_absent_draft_digest_never_publishes(self):
        self.fixture.draft_asset.pop("digest")
        self.rejected("did not supply the APK digest", draft_created=True)
        self.assertFalse(self.fixture.tagged)
        self.download.assert_not_called()

    def test_wrong_draft_digest_never_publishes(self):
        self.fixture.draft_asset["digest"] = "sha256:" + "0" * 64
        self.rejected("digest is missing or mismatched", draft_created=True)

    def test_wrong_draft_size_never_publishes(self):
        self.fixture.draft_asset["size"] += 1
        self.rejected("size does not match", draft_created=True)

    def test_extra_asset_never_publishes(self):
        self.fixture.extra_asset = True
        self.rejected("exactly one asset", draft_created=True)

    def test_code_race_is_checked_again(self):
        self.fixture.race = True
        self.rejected(r"no longer matches.*\(100, 0\.1\.1\)", draft_created=True)
        self.assertFalse(self.fixture.tagged)

    def test_semver_race_after_tag_creation_prevents_promotion(self):
        self.fixture.late_semver_race = True
        self.rejected(r"no longer matches.*\(21, 0\.2\.1\)", draft_created=True)
        self.assertTrue(self.fixture.tagged)

    def test_release_overtaken_during_promotion_is_not_reported_as_success(self):
        self.fixture.promotion_race = True
        self.rejected("Published release is not ahead", draft_created=True, patched=True)
        self.download.assert_not_called()

    def test_draft_title_must_match_version_name(self):
        self.fixture.bad_draft_title = True
        self.rejected("Release title does not match", draft_created=True)
        self.assertFalse(self.fixture.tagged)

    def test_missing_reservation_prevents_tag_creation(self):
        self.fixture.missing_draft = True
        self.rejected("reserved draft is missing", draft_created=True)
        self.assertFalse(self.fixture.tagged)

    def test_unsigned_or_wrongly_signed_apk_never_mutates_github(self):
        self.verify_apk.side_effect = release.InvalidRelease("apksigner failed")
        self.rejected("apksigner failed", draft_created=False)
        self.fixture.auth.request.assert_not_called()

    def test_wrong_public_title_is_not_reported_as_success(self):
        self.fixture.bad_public_title = True
        self.rejected("Release title does not match", draft_created=True, patched=True)

    def test_tag_created_concurrently_is_not_reused(self):
        self.fixture.tag_exists = True
        self.rejected("Reference already exists", draft_created=True)

    def test_wrong_created_ref_is_not_promoted(self):
        self.fixture.bad_created_ref = True
        self.rejected("Release tag does not match", draft_created=True)

    def test_stale_public_latest_is_not_reported_as_success(self):
        self.fixture.hide_latest = True
        self.rejected("may already be public", draft_created=True, patched=True)

    def test_missing_public_digest_is_not_reported_as_success(self):
        self.fixture.expected_asset.pop("digest")
        self.rejected("digest is missing or mismatched", draft_created=True, patched=True)

    def test_wrong_public_download_url_is_not_reported_as_success(self):
        self.fixture.expected_asset["browser_download_url"] = "https://example.invalid/wrong.apk"
        self.rejected("Unexpected public APK download URL", draft_created=True, patched=True)

    def test_failed_anonymous_download_is_not_reported_as_success(self):
        self.download.side_effect = release.InvalidRelease("asset smoke failed")
        self.rejected("asset smoke failed", draft_created=True, patched=True)

    def test_wrong_public_tag_commit_is_not_reported_as_success(self):
        self.fixture.bad_tag_target = True
        self.rejected("does not point", draft_created=True, patched=True)


if __name__ == "__main__":
    unittest.main()
