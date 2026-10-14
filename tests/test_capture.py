import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

try:
    import capture_addon as capture
except ImportError:
    capture = None


@unittest.skipIf(capture is None, "未安装可选的 mitmproxy")
class CaptureTests(unittest.TestCase):
    def message(self, data, content_type="application/json"):
        return SimpleNamespace(content=json.dumps(data).encode(), headers={"content-type": content_type})

    def test_nested_secrets_are_masked_but_points_preserved(self):
        redacted = capture.redact({"password": "pw", "data": {"access_token": "secret", "student_id": 42},
                                   "code": 1, "points": [{"lat": 30, "lng": 121, "name": "x", "id": 1}]})
        self.assertEqual(redacted["password"], "[REDACTED]")
        self.assertEqual(redacted["data"]["access_token"], "[REDACTED]")
        self.assertEqual(redacted["data"]["student_id"], 42)
        self.assertEqual(redacted["code"], 1)
        self.assertEqual(redacted["points"][0]["id"], 1)

    def test_unknown_text_and_large_body_are_not_dumped(self):
        message = SimpleNamespace(content=b"password=secret", headers={"content-type": "text/plain"})
        result = capture.body_summary(message, 1024, False)
        self.assertNotIn("value", result)
        self.assertEqual(result["kind"], "omitted")
        self.assertEqual(capture.body_summary(self.message({"x": "y" * 200}), 10, False)["reason"], "size_limit")

    def test_domain_filter_and_login_data_redaction(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.jsonl"
            context = SimpleNamespace(options=SimpleNamespace(
                huati_host="run.ecust.edu.cn", huati_output=str(path),
                huati_keep_secrets=False, huati_max_body_bytes=1024), log=SimpleNamespace(info=lambda _: None))
            request = self.message({"iphone": "12345", "password": "pw"})
            request.host, request.method, request.path, request.query = "example.com", "POST", "/api/userLogin/", {}
            response = self.message({"code": 1, "data": {"id": 42, "name": "test"}})
            response.status_code = 200
            flow = SimpleNamespace(id="flow-a", request=request, response=response, error=None)
            with patch.object(capture, "ctx", context):
                capture.HuatiCapture().record(flow)
                self.assertFalse(path.exists())
                request.host = "run.ecust.edu.cn"
                capture.HuatiCapture().record(flow)
            saved = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(saved["request"]["body"]["value"]["password"], "[REDACTED]")
            self.assertEqual(saved["response"]["body"]["value"]["data"], "[REDACTED]")
            self.assertEqual(saved["response"]["body"]["value"]["code"], 1)


if __name__ == "__main__":
    unittest.main()
