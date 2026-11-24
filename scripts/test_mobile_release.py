import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, Mock, patch

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

    def test_history_checks_all_sources_not_only_latest(self):
        auth = Mock()
        public = Mock()
        tags = [{"name": "unrelated"}, {"name": "mobile-v20"}]
        releases = [{"id": 1, "tag_name": "mobile-v30", "draft": True},
                    {"id": 2, "tag_name": "mobile-v40", "prerelease": True}]
        for existing_tags, existing_releases, latest, maximum in (
            (tags, [], None, 20), (tags, releases[:1], None, 30),
            (tags, releases, {"tag_name": "mobile-v10"}, 40), ([], [], {"tag_name": "mobile-v50"}, 50),
        ):
            public.request.return_value = latest
            for trial in (maximum - 1, maximum, maximum + 1):
                auth.all.side_effect = [iter(existing_tags), iter(existing_releases)]
                with self.subTest(trial=trial, maximum=maximum):
                    if trial <= maximum:
                        with self.assertRaisesRegex(release.InvalidRelease, f"max {maximum}"):
                            release.gate_history(auth, public, trial)
                    else:
                        release.gate_history(auth, public, trial)

    def test_malformed_reserved_tag_does_not_get_bypassed(self):
        auth = Mock()
        public = Mock()
        auth.all.return_value = [{"name": "mobile-v0004"}]
        with self.assertRaises(release.InvalidRelease):
            release.gate_history(auth, public, 50)

    def test_pagination_checks_beyond_first_page(self):
        github = release.GitHub()
        amounts = [{"name": "unrelated"}] * 100, [{"name": "mobile-v101"}]
        github.request = Mock(side_effect=amounts)
        self.assertIn({"name": "mobile-v101"}, list(github.all("/tags")))
        self.assertEqual([c.args[0] for c in github.request.call_args_list],
                         ["/tags?per_page=100&page=1", "/tags?per_page=100&page=2"])


class TransportTest(unittest.TestCase):
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
                             f"https://uploads.github.com/repos/{release.REPO}/releases/1/assets?name={release.ASSET}")
            self.assertEqual(req.get_header("Content-length"), "3")
            self.assertIs(req.data, apk)
            self.assertEqual(req.get_header("Authorization"), "Bearer disposable-token-fixture")
        self.assertIsNone(release.NoRedirect().redirect_request(None, None, 302, None, {}, "https://example.invalid"))

    def test_latest_check_is_anonymous(self):
        public = release.GitHub()
        public.opener = MagicMock()
        public.opener.open.return_value.__enter__.return_value.read.return_value = b"{}"
        public.request("/releases/latest")
        req = public.opener.open.call_args.args[0]
        self.assertEqual(req.full_url, f"https://api.github.com/repos/{release.REPO}/releases/latest")
        self.assertIsNone(req.get_header("Authorization"))


class PublicationFixture:
    code = 21
    tag = "mobile-v21"
    main = "a" * 40
    release_id = 7

    def __init__(self, apk_bytes):
        self.public = Mock()
        self.auth = Mock()
        self.public.request.side_effect = self.public_request
        self.auth.request.side_effect = self.auth_request
        self.auth.all.side_effect = self.auth_list
        self.repo = {"full_name": release.REPO, "private": False, "visibility": "public"}
        self.tags = [{"name": "mobile-v19"}]
        self.history = [{"tag_name": "mobile-v20", "id": 4, "draft": False, "prerelease": False}]
        self.created = self.uploaded = self.patched = False
        self.change_main = self.race = self.hide_latest = self.bad_tag_target = False
        self.apk_digest = hashlib.sha256(apk_bytes).hexdigest()
        self.expected_asset = {
            "name": release.ASSET, "state": "uploaded", "size": len(apk_bytes),
            "browser_download_url": f"https://github.com/{release.REPO}/releases/download/{self.tag}/{release.ASSET}",
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
        return {"id": self.release_id, "tag_name": self.tag, "target_commitish": self.main, "draft": draft,
                "prerelease": False, "assets": assets}

    def auth_list(self, path):
        if path == "/tags":
            return self.tags + ([{"name": "mobile-v99"}] if self.race and self.created else [])
        if path == "/releases":
            return self.history + ([self.record(draft=True)] if self.created else [])
        raise AssertionError(f"Unexpected list: {path}")

    def public_request(self, path, *, missing=False):
        if path == "":
            return self.repo
        if path == "/git/ref/heads/main":
            sha = "b" * 40 if self.created and self.change_main else self.main
            return {"ref": "refs/heads/main", "object": {"type": "commit", "sha": sha}}
        if path == "/releases/latest":
            return self.record(draft=False) if self.patched and not self.hide_latest else self.history[0]
        if path == f"/git/ref/tags/{self.tag}":
            sha = "c" * 40 if self.bad_tag_target else self.main
            return {"ref": f"refs/tags/{self.tag}", "object": {"type": "commit", "sha": sha}}
        raise AssertionError(f"Unexpected public request: {path}")

    def auth_request(self, path, *, method="GET", payload=None, upload=None):
        if method == "POST" and path == "/releases":
            assert payload == {"tag_name": self.tag, "name": self.tag, "target_commitish": self.main, "body": "",
                               "draft": True, "prerelease": False, "generate_release_notes": False,
                               "make_latest": "false"}, payload
            self.created = True
            return self.record(draft=True)
        if method == "POST" and path == f"/releases/{self.release_id}/assets?name={release.ASSET}":
            assert self.created and not self.uploaded
            assert hashlib.sha256(upload.read()).hexdigest() == self.apk_digest
            self.uploaded = True
            return self.draft_asset
        if path == f"/releases/{self.release_id}":
            if method == "PATCH":
                assert self.uploaded
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

    def publish(self):
        release.publish(self.fixture.auth, self.fixture.public, self.apk, self.fixture.code)

    def rejected(self, reason, *, draft_created, patched=False):
        with self.assertRaisesRegex(release.InvalidRelease, reason):
            self.publish()
        self.assertEqual(self.fixture.created, draft_created)
        self.assertEqual(self.fixture.patched, patched)
        self.assertNotIn("Verified public latest", self.output.getvalue())

    def test_verified_stable_release(self):
        self.publish()
        self.assertTrue(self.fixture.patched)
        self.assertIn("Verified public latest: mobile-v21", self.output.getvalue())

    def test_private_repository_rejected_before_mutation(self):
        self.fixture.repo["private"] = True
        self.rejected("Expected anonymous access", draft_created=False)

    def test_existing_tag_rejected_before_mutation(self):
        self.fixture.tags = [{"name": self.fixture.tag}]
        self.rejected("must exceed", draft_created=False)

    def test_absent_draft_digest_never_publishes(self):
        self.fixture.draft_asset.pop("digest")
        self.rejected("did not supply the APK digest", draft_created=True)

    def test_wrong_draft_digest_never_publishes(self):
        self.fixture.draft_asset["digest"] = "sha256:" + "0" * 64
        self.rejected("digest is missing or mismatched", draft_created=True)

    def test_wrong_draft_size_never_publishes(self):
        self.fixture.draft_asset["size"] += 1
        self.rejected("size does not match", draft_created=True)

    def test_extra_asset_never_publishes(self):
        self.fixture.extra_asset = True
        self.rejected("exactly one asset", draft_created=True)

    def test_version_race_is_checked_again(self):
        self.fixture.race = True
        self.rejected("max 99", draft_created=True)

    def test_public_main_move_is_rejected_before_patch(self):
        self.fixture.change_main = True
        self.rejected("Public main changed", draft_created=True)

    def test_stale_public_latest_is_not_reported_as_success(self):
        self.fixture.hide_latest = True
        self.rejected("may already be public", draft_created=True, patched=True)

    def test_missing_public_digest_is_not_reported_as_success(self):
        self.fixture.expected_asset.pop("digest")
        self.rejected("digest is missing or mismatched", draft_created=True, patched=True)

    def test_wrong_public_download_url_is_not_reported_as_success(self):
        self.fixture.expected_asset["browser_download_url"] = "https://example.invalid/wrong.apk"
        self.rejected("Unexpected public APK download URL", draft_created=True, patched=True)

    def test_wrong_public_tag_commit_is_not_reported_as_success(self):
        self.fixture.bad_tag_target = True
        self.rejected("does not point", draft_created=True, patched=True)


if __name__ == "__main__":
    unittest.main()
