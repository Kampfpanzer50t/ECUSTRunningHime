"""当前小程序的 xcxapi 会话和 AES 数据格式。只使用本机捕获的同账号会话。"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import sys
from urllib.request import Request, build_opener

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from huati import DEFAULT_URL, ToolError, read_json, write_json

KEY = b"A7fK92LmXyQw8vRp"


def encrypt(value):
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
    padder = padding.PKCS7(128).padder()
    padded = padder.update(raw) + padder.finalize()
    iv = os.urandom(16)
    cipher = Cipher(algorithms.AES(KEY), modes.CBC(iv)).encryptor()
    encrypted = cipher.update(padded) + cipher.finalize()
    return base64.b64encode(iv).decode() + ":" + base64.b64encode(encrypted).decode()


class CurrentClient:
    def __init__(self, path="private/captured-auth.json"):
        auth = read_json(path)
        if auth.get("host") != "run.ecust.edu.cn":
            raise ToolError("捕获会话的主机不匹配。")
        self.headers = auth.get("headers", {})
        if not any(k.lower() == "x-session-id" and v and v != "1111" for k, v in self.headers.items()):
            raise ToolError("尚未捕获当前账号的 X-Session-ID 请求头。")
        self.opener = build_opener()

    def request(self, path, data=None):
        if not path.startswith(("/xcxapi/", "/xcxtapi/")):
            raise ToolError("仅支持当前小程序接口路径。")
        headers = dict(self.headers, **{"Content-Type": "application/json", "Accept-Encoding": "identity"})
        req = Request(DEFAULT_URL + path,
                      data=None if data is None else json.dumps(data, ensure_ascii=False).encode(),
                      headers=headers, method="GET" if data is None else "POST")
        try:
            with self.opener.open(req, timeout=25) as response:
                raw = response.read(2 * 1024 * 1024)
                result = json.loads(raw)
        except Exception as exc:
            raise ToolError(f"接口请求未完成：{path}；未自动重试。") from exc
        if not isinstance(result, dict):
            raise ToolError("接口未返回 JSON 对象。")
        return result


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", help="精确的当前接口路径")
    parser.add_argument("--data", help="POST 的本地 JSON 文件；省略为 GET")
    parser.add_argument("--output", required=True, help="本地响应文件")
    args = parser.parse_args()
    client = CurrentClient()
    result = client.request(args.path, None if not args.data else read_json(args.data))
    if args.path == "/xcxapi/getUserInfo/" and isinstance(result.get("data"), dict):
        keys = {"id", "sex", "shortest_distance", "target_mileage", "min_pace", "max_pace",
                "min_step_frequency", "max_step_frequency", "distance_to_marker", "has_finished",
                "finished_percent", "mileage", "run_time"}
        result["data"] = {key: value for key, value in result["data"].items() if key in keys}
    write_json(args.output, result)
    print(json.dumps({"code": result.get("code"), "message": result.get("message"),
                      "data_keys": list(result.get("data", {})) if isinstance(result.get("data"), dict) else type(result.get("data")).__name__}, ensure_ascii=False))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except ToolError as exc:
        print(f"错误：{exc}")
        raise SystemExit(1)
