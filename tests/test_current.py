import base64
from datetime import datetime, timedelta
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from current_run import finish, haversine, make_path
from huati import SHANGHAI, ToolError, write_json
from huati_current import KEY, encrypt


POINTS = [
    {"lat": "30.827536", "lng": "121.503933", "point_name": "38"},
    {"lat": "30.832999", "lng": "121.509337", "point_name": "单双杠练习场"},
    {"lat": "30.830954", "lng": "121.50272", "point_name": "信息楼南"},
]
LOCATION = {"lat": 30.83103, "lng": 121.503709}


class CurrentTests(unittest.TestCase):
    def test_cryptojs_wire_format_and_utf8_round_trip(self):
        data = {"record_id": 99, "text": "校园跑", "mileage": 2200}
        first, second = encrypt(data).split(":")
        iv, raw = base64.b64decode(first), base64.b64decode(second)
        self.assertEqual(len(iv), 16)
        self.assertEqual(len(raw) % 16, 0)
        cipher = Cipher(algorithms.AES(KEY), modes.CBC(iv)).decryptor()
        padded = cipher.update(raw) + cipher.finalize()
        unpad = padding.PKCS7(128).unpadder()
        self.assertEqual(json.loads(unpad.update(padded) + unpad.finalize()), data)

    def test_simulated_path_visits_each_point_and_matches_distance_and_time(self):
        start = datetime(2026, 10, 11, 18, tzinfo=SHANGHAI)
        path = make_path(POINTS, LOCATION, start, 2200, 900)
        for point in POINTS:
            self.assertLess(min(haversine(p, point) for p in path), 0.1)
        length = sum(haversine(a, b) for a, b in zip(path, path[1:]))
        self.assertAlmostEqual(length, 2200, delta=0.5)
        self.assertEqual(path[-1]["timestamp"] - path[0]["timestamp"], 900000)
        self.assertTrue(all(a["timestamp"] < b["timestamp"] for a, b in zip(path, path[1:])))

    def test_incomplete_duration_does_not_access_server(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.json"
            start = datetime(2026, 10, 11, 18, tzinfo=SHANGHAI)
            write_json(path, {"status": "created", "start_time": start.isoformat(), "duration": 900})
            with patch("current_run.now", return_value=start + timedelta(seconds=1)), patch("current_run.CurrentClient") as client:
                with self.assertRaises(ToolError):
                    finish(SimpleNamespace(session=path, submit=True))
                client.assert_not_called()


if __name__ == "__main__":
    unittest.main()
