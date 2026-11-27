import hashlib
import json
import tempfile
import time
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from lab.app import create_app
from lab.runner import Runner


class APITests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.runner = Runner(self.temp.name)
        self.app = create_app(self.runner, manage_proxy=False)
        self.client = TestClient(self.app)
        self.client.__enter__()
        self.token = self.client.get("/api/session").json()["token"]
        self.headers = {"X-Lab-Token": self.token}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def seeded_run(self):
        run = dict(id="Rtest", method_id="paging", case_id="default", mode="demo", network="good", status="running", created_at="2026-01-01", trials=[dict(trial_id="T1", status="running", phase="performance", arm="A", repeat=1, video_sequence=0)], summary={})
        self.runner.runs[run["id"]] = run
        self.runner.signals[run["id"]] = dict(heartbeat=time.monotonic(), cancelled=False, capture_ready=set(), finalized=set())
        (Path(self.temp.name)/"Rtest/T1").mkdir(parents=True)
        self.runner.persist(run)
        return run

    def test_catalog_and_no_fake_history(self):
        self.assertGreater(len(self.client.get("/api/methods").json()["methods"]), 15)
        self.assertEqual(self.client.get("/api/runs").json(), [])

    def test_launcher_can_identify_this_workspace_without_a_session_token(self):
        instance = self.client.get("/api/instance").json()
        self.assertEqual(instance["app"], "adaptive-news-performance-lab")
        self.assertTrue(Path(instance["workspace"]).is_dir())
        self.assertNotIn("token", instance)

    def test_mutations_require_local_session_token(self):
        self.assertEqual(self.client.post("/api/runs", json={}).status_code, 403)
        self.assertEqual(self.client.post("/api/runs", headers={**self.headers, "Origin": "https://evil.example"}, json={}).status_code, 403)

    def test_invalid_shell_like_input_is_rejected(self):
        response = self.client.post("/api/runs", headers=self.headers, json=dict(method_id="paging", case_id="default", device_serial="emulator-5554; whoami"))
        self.assertEqual(response.status_code, 422)

    def test_video_chunks_are_ordered_and_idempotent(self):
        self.seeded_run()
        url = "/api/runs/Rtest/evidence?trial_id=T1&sequence="
        self.assertEqual(self.client.post(url+"1", headers=self.headers, content=b"bad").status_code, 409)
        self.assertEqual(self.client.post(url+"0", headers=self.headers, content=b"video").status_code, 200)
        self.assertTrue(self.client.post(url+"0", headers=self.headers, content=b"video").json()["duplicate"])
        self.assertEqual(self.client.post(url+"0", headers=self.headers, content=b"different").status_code, 409)
        self.assertEqual(self.client.post(url+"1&final=true", headers=self.headers, content=b"").status_code, 200)
        self.assertEqual((Path(self.temp.name)/"Rtest/T1/evidence.webm").read_bytes(), b"video")
        self.assertIn("T1", self.runner.signals["Rtest"]["finalized"])

    def test_missing_video_cannot_be_finalized(self):
        self.seeded_run()
        response = self.client.post("/api/runs/Rtest/evidence?trial_id=T1&sequence=0&final=true", headers=self.headers, content=b"")
        self.assertEqual(response.status_code, 422)

    def test_completed_raw_cannot_be_modified(self):
        run = self.seeded_run()
        run["status"] = "completed"
        response = self.client.post("/api/runs/Rtest/evidence?trial_id=T1&sequence=0", headers=self.headers, content=b"video")
        self.assertEqual(response.status_code, 409)

    def test_restart_marks_unfinished_runs_incomplete(self):
        self.seeded_run()
        restored = Runner(self.temp.name)
        restored.load()
        self.assertEqual(restored.runs["Rtest"]["status"], "incomplete")
        self.assertEqual(restored.runs["Rtest"]["trials"][0]["status"], "incomplete")

    def test_cancel_and_heartbeat(self):
        self.seeded_run()
        self.assertEqual(self.client.post("/api/runs/Rtest/heartbeat", headers=self.headers).status_code, 200)
        self.assertEqual(self.client.post("/api/runs/Rtest/cancel", headers=self.headers).status_code, 200)
        self.assertTrue(self.runner.signals["Rtest"]["cancelled"])

    def test_pss_sample_does_not_collide_with_event_envelope(self):
        run = self.seeded_run()
        row = self.runner.emit(run, "pss", sample_monotonic=123, pss_kib=1024)
        self.assertEqual(row["sample_monotonic"], 123)
        self.assertGreater(row["host_monotonic"], 123)
        self.assertEqual(row["pss_kib"], 1024)


if __name__ == "__main__":
    unittest.main()
