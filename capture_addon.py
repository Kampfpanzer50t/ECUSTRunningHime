"""只记录指定域名的 HTTP 请求；不修改、不重放流量。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import parse_qsl

from mitmproxy import ctx


SECRET_KEYS = {
    "password", "passwd", "pwd", "token", "accesstoken", "refreshtoken",
    "authorization", "cookie", "setcookie", "session", "sessionid",
    "openid", "unionid", "iphone", "phone", "mobile",
    "userid", "signature", "sign", "ticket", "secret", "code",
}


def redact(value, keep_secrets=False):
    if keep_secrets:
        return value
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", str(key).lower())
            sensitive = (normalized in SECRET_KEYS or normalized.endswith("token")
                         or normalized.endswith("password") or normalized.endswith("sessionid"))
            # 业务成功码 code=1 可保留；字符串 code 可能是微信登录凭证。
            if normalized == "code" and type(item) in (int, float):
                sensitive = False
            result[key] = "[REDACTED]" if sensitive else redact(item)
        return result
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def body_summary(message, limit, keep_secrets):
    raw = message.content or b""
    metadata = {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
    if len(raw) > limit:
        return dict(metadata, kind="omitted", reason="size_limit")
    try:
        value = json.loads(raw)
    except (ValueError, UnicodeError):
        content_type = message.headers.get("content-type", "").lower()
        if "application/x-www-form-urlencoded" in content_type:
            try:
                pairs = parse_qsl(raw.decode("utf-8"), keep_blank_values=True)
            except UnicodeError:
                return dict(metadata, kind="omitted", reason="unknown_encoding")
            return dict(metadata, kind="form", value=redact(dict(pairs), keep_secrets))
        # 未知文本格式默认不落盘，避免遗漏其中的密码或令牌。
        return dict(metadata, kind="omitted", reason="non_json")
    return dict(metadata, kind="json", value=redact(value, keep_secrets))


class HuatiCapture:
    def load(self, loader):
        loader.add_option("huati_host", str, "run.ecust.edu.cn", "只记录该主机")
        loader.add_option("huati_output", str, "private/capture.jsonl", "JSONL 输出文件")
        loader.add_option("huati_keep_secrets", bool, False, "在本地保留敏感字段")
        loader.add_option("huati_max_body_bytes", int, 1048576, "正文大小限制")
        loader.add_option("huati_auth_output", str, "", "可选：将本账号请求凭证保存到指定本地文件供继续同一会话")

    def response(self, flow):
        self.record(flow)

    def error(self, flow):
        self.record(flow)

    def record(self, flow):
        if flow.request.host.rstrip(".").lower() != ctx.options.huati_host.rstrip(".").lower():
            return
        keep = ctx.options.huati_keep_secrets
        limit = ctx.options.huati_max_body_bytes
        auth_output = getattr(ctx.options, "huati_auth_output", "")
        if auth_output:
            credentials = {key: value for key, value in flow.request.headers.items()
                           if key.lower() in {"authorization", "cookie", "token", "x-token", "x-auth-token", "x-access-token", "x-session-id", "user-agent", "referer"}}
            if any(key.lower() in {"authorization", "cookie", "token", "x-token", "x-auth-token", "x-access-token", "x-session-id"}
                   for key in credentials):
                auth_path = Path(auth_output)
                auth_path.parent.mkdir(parents=True, exist_ok=True)
                auth_path.write_text(json.dumps({"host": flow.request.host, "headers": credentials}, ensure_ascii=False), encoding="utf-8")
        record = {
            "id": flow.id,
            "captured_at": datetime.now(timezone.utc).isoformat(),
            "started_at": datetime.fromtimestamp(
                getattr(flow.request, "timestamp_start", None) or datetime.now(timezone.utc).timestamp(),
                timezone.utc,
            ).isoformat(),
            "request": {
                "host": flow.request.host,
                "method": flow.request.method,
                "path": flow.request.path.split("?", 1)[0],
                "header_names": list(flow.request.headers.keys()),
                "query": redact(dict(flow.request.query.items()), keep),
                "body": body_summary(flow.request, limit, keep),
            },
            "response": None,
        }
        if flow.response is not None:
            record["response"] = {"status": flow.response.status_code,
                                  "body": body_summary(flow.response, limit, keep)}
            if record["request"]["path"] == "/api/userLogin/" and not keep:
                response_value = record["response"]["body"].get("value")
                if isinstance(response_value, dict) and "data" in response_value:
                    response_value["data"] = "[REDACTED]"
        if flow.error:
            record["error"] = "network_error"
        output = Path(ctx.options.huati_output)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        ctx.log.info(f"华体抓包：{flow.request.method} {record['request']['path']}")


addons = [HuatiCapture()]
