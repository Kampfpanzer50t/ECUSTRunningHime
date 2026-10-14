import argparse
from contextlib import redirect_stdout
from datetime import datetime, timedelta
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import huati


POINTS = [
    {"lat": 30.83, "lng": 121.50, "name": "测试点甲"},
    {"lat": 30.84, "lng": 121.51, "name": "测试点乙"},
    {"lat": 30.85, "lng": 121.52, "name": "测试点丙"},
]


class ToolTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def test_exactly_three_distinct_valid_points(self):
        self.assertEqual(huati.validate_points({"points": POINTS}), POINTS)
        for invalid in (POINTS[:2], [POINTS[0]] * 3,
                        [dict(POINTS[0], lat=True), *POINTS[1:]],
                        [dict(POINTS[0], lng=float("nan")), *POINTS[1:]],
                        [dict(POINTS[0], name=""), *POINTS[1:]]):
            with self.subTest(points=invalid), self.assertRaises(huati.ToolError):
                huati.validate_points(invalid)

    def test_minimum_distance_and_derived_pace(self):
        start = datetime(2026, 10, 11, 10, tzinfo=huati.SHANGHAI)
        payload = huati.update_payload(42, 99, start, start + timedelta(seconds=900), 2000, 2600)
        self.assertEqual(payload["pace"], 450)
        self.assertEqual(payload["running_time"], 900)
        self.assertEqual(payload["pass_point"], 3)
        with self.assertRaises(huati.ToolError):
            huati.update_payload(42, 99, start, start + timedelta(seconds=900), 1999, 2600)
        with self.assertRaises(huati.ToolError):
            huati.update_payload(42, 99, start, start, 2000, 2600)

    def test_default_run_never_logs_in_or_accesses_network(self):
        points = self.directory / "points.json"
        huati.write_json(points, POINTS)
        args = argparse.Namespace(submit=False, points=points, distance=2200,
                                  duration=900, steps=2600, base_url=huati.DEFAULT_URL)
        with patch.object(huati, "ApiClient") as client, redirect_stdout(io.StringIO()) as output:
            huati.run(args)
        client.assert_not_called()
        self.assertEqual(json.loads(output.getvalue())["mode"], "preview_only_no_network")

    def test_finish_timeout_keeps_uncertain_state_and_blocks_retry(self):
        state = {"status": "created", "student_id": "42", "record_id": "99",
                 "start_time": (huati.now() - timedelta(seconds=901)).isoformat()}
        path = self.directory / "session.json"
        client = MagicMock()
        client.post.side_effect = huati.ToolError("timeout")
        with self.assertRaises(huati.ToolError):
            huati.perform_finish(client, state, path, 2200, 2600)
        self.assertEqual(huati.read_json(path)["status"], "finish_unknown")
        with self.assertRaises(huati.ToolError):
            huati.perform_finish(client, state, path, 2200, 2600)
        self.assertEqual(client.post.call_count, 1)

    def test_successful_finish_is_not_submitted_twice(self):
        state = {"status": "created", "student_id": "42", "record_id": "99",
                 "start_time": (huati.now() - timedelta(seconds=901)).isoformat()}
        path = self.directory / "session.json"
        client = MagicMock()
        with redirect_stdout(io.StringIO()):
            huati.perform_finish(client, state, path, 2200, 2600)
        self.assertEqual(huati.read_json(path)["status"], "finished")
        with self.assertRaises(huati.ToolError):
            huati.perform_finish(client, state, path, 2200, 2600)
        self.assertEqual(client.post.call_count, 1)

    def test_resume_rejects_different_account(self):
        path = self.directory / "session.json"
        huati.write_json(path, {"status": "created", "student_id": "42", "record_id": "99",
                              "base_url": huati.DEFAULT_URL,
                              "start_time": (huati.now() - timedelta(seconds=900)).isoformat()})
        args = argparse.Namespace(session=path, distance=2200, steps=2600, submit=True)
        with patch.object(huati, "ApiClient") as client, patch.object(huati, "credentials", return_value=("phone", "pw")):
            client.return_value.login.return_value = "43"
            with self.assertRaises(huati.ToolError):
                huati.finish(args)
            client.return_value.post.assert_not_called()

    def test_claim_session_is_exclusive(self):
        path = self.directory / "session.json"
        huati.claim_session(path, {"status": "create_pending"})
        with self.assertRaises(huati.ToolError):
            huati.claim_session(path, {"status": "overwritten"})
        self.assertEqual(huati.read_json(path)["status"], "create_pending")

    def test_create_timeout_does_not_retry_and_preserves_state(self):
        points = self.directory / "points.json"
        path = self.directory / "session.json"
        huati.write_json(points, POINTS)
        args = argparse.Namespace(submit=True, points=points, distance=2200, duration=900,
                                  steps=2600, base_url=huati.DEFAULT_URL, session=path)
        with patch.object(huati, "ApiClient") as client, patch.object(huati, "credentials", return_value=("phone", "pw")):
            client.return_value.base_url = huati.DEFAULT_URL
            client.return_value.login.return_value = "42"
            client.return_value.post.side_effect = huati.ToolError("timeout")
            with self.assertRaises(huati.ToolError):
                huati.run(args)
            self.assertEqual(client.return_value.post.call_count, 1)
        self.assertEqual(huati.read_json(path)["status"], "create_unknown")

    def test_full_run_uses_returned_ids_and_waits_before_finishing(self):
        points = self.directory / "points.json"
        path = self.directory / "session.json"
        huati.write_json(points, POINTS)
        args = argparse.Namespace(submit=True, points=points, distance=2200, duration=900,
                                  steps=2600, base_url=huati.DEFAULT_URL, session=path)
        start = datetime(2026, 10, 11, 10, tzinfo=huati.SHANGHAI)
        with (patch.object(huati, "ApiClient") as client,
              patch.object(huati, "credentials", return_value=("phone", "pw")),
              patch.object(huati, "now", side_effect=[start, start + timedelta(seconds=900)]),
              patch.object(huati.time, "monotonic", side_effect=[0, 1, 900]),
              patch.object(huati.time, "sleep") as sleep,
              redirect_stdout(io.StringIO())):
            client.return_value.base_url = huati.DEFAULT_URL
            client.return_value.login.return_value = "42"
            client.return_value.post.side_effect = [{"record_id": 99}, None]
            huati.run(args)
            sleep.assert_called_once_with(1)
            calls = client.return_value.post.call_args_list
            self.assertEqual(calls[0].args, ("/api/createLine/", {"pass_point": POINTS, "student_id": "42"}))
            self.assertEqual(calls[1].args[0], "/api/updateRecord/")
            self.assertEqual(calls[1].args[1]["record_id"], "99")
            self.assertEqual(calls[1].args[1]["running_time"], 900)
        self.assertEqual(huati.read_json(path)["status"], "finished")

    def test_api_cookie_session_and_reference_json_contract(self):
        client = huati.ApiClient()
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{"code":1,"data":{"id":42}}'
        with patch.object(client.opener, "open", return_value=response) as mocked:
            self.assertEqual(client.login("phone", "pw"), "42")
            request = mocked.call_args.args[0]
            self.assertEqual(request.full_url, huati.DEFAULT_URL + "/api/userLogin/")
            self.assertEqual(json.loads(request.data), {"iphone": "phone", "password": "pw"})
        response.__enter__.return_value.read.return_value = b'{"code":0,"message":"denied"}'
        with patch.object(client.opener, "open", return_value=response), self.assertRaises(huati.ToolError):
            client.login("phone", "pw")

    def test_points_export_requires_unambiguous_selection(self):
        record = {"id": "a", "captured_at": "2026-10-11T00:00:00Z",
                  "request": {"method": "GET", "path": "/api/points/", "body": {}},
                  "response": {"status": 200, "body": {"kind": "json", "value": {"data": {"points": POINTS}}}}}
        capture = self.directory / "capture.jsonl"
        capture.write_text(json.dumps(record) + "\n", encoding="utf-8")
        args = argparse.Namespace(capture=capture, flow_id=None, source=None,
                                  json_path=None, output=self.directory / "points.json")
        with redirect_stdout(io.StringIO()):
            huati.export_points(args)

        self.assertEqual(huati.read_json(args.output)["points"], POINTS)
        record["id"] = "b"
        with capture.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record) + "\n")
        args.output = self.directory / "second.json"
        with self.assertRaises(huati.ToolError):
            huati.export_points(args)
        args.flow_id = "b"
        with redirect_stdout(io.StringIO()):
            huati.export_points(args)

    def test_import_existing_record_without_network_or_duplicate_create(self):
        record = {"id": "create-1", "captured_at": "2026-10-11T02:00:01+00:00",
                  "started_at": "2026-10-11T02:00:00+00:00",
                  "request": {"host": "run.ecust.edu.cn", "method": "POST", "path": "/api/createLine/",
                              "body": {"kind": "json", "value": {"student_id": "42", "pass_point": POINTS}}},
                  "response": {"status": 200, "body": {"kind": "json", "value": {"code": 1, "data": {"record_id": 99}}}}}
        path = self.directory / "capture.jsonl"
        path.write_text(json.dumps(record) + "\n", encoding="utf-8")
        args = argparse.Namespace(capture=path, flow_id=None, session=self.directory / "session.json")
        with patch.object(huati, "ApiClient") as client, redirect_stdout(io.StringIO()):
            huati.import_session(args)
            client.assert_not_called()
        state = huati.read_json(args.session)
        self.assertEqual(state["record_id"], "99")
        self.assertEqual(state["start_time"], "2026-10-11T10:00:00+08:00")
        self.assertEqual(state["points"], POINTS)
        with self.assertRaises(huati.ToolError):
            huati.import_session(args)


if __name__ == "__main__":
    unittest.main()
