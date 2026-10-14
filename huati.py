"""华体内控协议工具；默认仅预览。协议参考见 README.md。"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import getpass
import http.cookiejar
import json
import math
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener

SHANGHAI = timezone(timedelta(hours=8))
DEFAULT_URL = "https://run.ecust.edu.cn"


class ToolError(Exception):
    pass


def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as exc:
        raise ToolError(f"无法读取 JSON 文件：{path} ({exc})") from exc


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def claim_session(path, state):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as handle:
            json.dump(state, handle, ensure_ascii=False, indent=2)
    except FileExistsError as exc:
        raise ToolError("会话文件已存在，停止创建以避免重复记录。") from exc


def now():
    return datetime.now(SHANGHAI)


def validate_points(value):
    points = value.get("points") if isinstance(value, dict) else value
    if not isinstance(points, list) or len(points) != 3:
        raise ToolError("点位文件必须包含本次选择的恰好三个点位。")
    coordinates = set()
    for point in points:
        if not isinstance(point, dict):
            raise ToolError("每个点位必须是包含 lat、lng、name 的对象。")
        for key, limit in (("lat", 90), ("lng", 180)):
            number = point.get(key)
            if (type(number) not in (float, int) or not math.isfinite(number)
                    or not -limit <= number <= limit):
                raise ToolError(f"点位 {key} 必须是有效的经纬度数值。")
        if not isinstance(point.get("name"), str) or not point["name"].strip():
            raise ToolError("点位 name 不能为空。")
        coordinates.add((point["lat"], point["lng"]))
    if len(coordinates) != 3:
        raise ToolError("三个点位不能使用重复坐标。")
    return points


def validate_metrics(distance, duration, steps):
    if type(distance) is not int or distance < 2000:
        raise ToolError("提交里程必须至少为 2000 米。")
    if type(duration) is not int or duration <= 0:
        raise ToolError("时长必须是正整数秒。")
    if type(steps) is not int or steps <= 0:
        raise ToolError("步数必须是正整数。")


def update_payload(student_id, record_id, start, end, distance, steps):
    duration = int((end - start).total_seconds())
    validate_metrics(distance, duration, steps)
    return {
        "end_time": end.astimezone(SHANGHAI).strftime("%Y-%m-%d %H:%M:%S"),
        "mileage": distance,
        "pace": int(duration / distance * 1000),
        "pass_point": 3,
        "record_id": str(record_id),
        "running_time": duration,
        "start_time": start.astimezone(SHANGHAI).strftime("%Y-%m-%d %H:%M:%S"),
        "step_count": steps,
        "student_id": str(student_id),
    }


class ApiClient:
    def __init__(self, base_url=DEFAULT_URL, timeout=25):
        parsed = urlsplit(base_url)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment
                or parsed.path not in ("", "/")):
            raise ToolError("服务器地址必须是无账号信息、无路径的 HTTPS 地址。")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def post(self, endpoint, payload):
        request = Request(
            self.base_url + endpoint,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                body = response.read(2 * 1024 * 1024 + 1)
                if len(body) > 2 * 1024 * 1024:
                    raise ToolError("服务器响应过大，停止处理。")
                result = json.loads(body)
        except HTTPError as exc:
            raise ToolError(f"{endpoint} 返回 HTTP {exc.code}；没有自动重试。") from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise ToolError(f"{endpoint} 网络失败；请求是否生效尚不确定，没有自动重试。") from exc
        except (ValueError, UnicodeError) as exc:
            raise ToolError(f"{endpoint} 未返回预期 JSON，可能需要更新协议。") from exc
        if not isinstance(result, dict) or type(result.get("code")) is not int:
            raise ToolError(f"{endpoint} 响应结构与参考协议不符。")
        if result["code"] != 1:
            message = str(result.get("message", "未提供原因"))[:240]
            raise ToolError(f"{endpoint} 业务失败：{message}")
        return result.get("data")

    def login(self, phone, password):
        data = self.post("/api/userLogin/", {"iphone": phone, "password": password})
        if not isinstance(data, dict) or not valid_id(data.get("id")):
            raise ToolError("登录响应缺少学生 ID，停止提交。")
        return str(data["id"])


def valid_id(value):
    return type(value) in (int, str) and str(value).strip() not in ("", "[REDACTED]")


def credentials(args):
    phone = args.phone or os.getenv("HUATI_PHONE") or input("手机号：").strip()
    password = os.getenv("HUATI_PASSWORD") or getpass.getpass("密码（输入不回显）：")
    if not phone or not password:
        raise ToolError("手机号和密码不能为空。")
    return phone, password


def preview(args):
    points = validate_points(read_json(args.points))
    start = now().replace(microsecond=0)
    validate_metrics(args.distance, args.duration, args.steps)
    print(json.dumps({
        "mode": "preview_only_no_network",
        "base_url": args.base_url,
        "createLine": {"pass_point": points, "student_id": "<登录后获取>"},
        "updateRecord": update_payload("<登录后获取>", "<创建后获取>", start,
                                       start + timedelta(seconds=args.duration),
                                       args.distance, args.steps),
    }, ensure_ascii=False, indent=2))


def perform_finish(client, state, path, distance, steps):
    if state.get("status") != "created":
        raise ToolError("此记录已完成或提交结果待核实，不能自动重复提交。")
    start = datetime.fromisoformat(state["start_time"])
    payload = update_payload(state["student_id"], state["record_id"], start,
                             now().replace(microsecond=0), distance, steps)
    state.update(status="finish_pending", finish_payload=payload)
    write_json(path, state)
    try:
        client.post("/api/updateRecord/", payload)
    except (ToolError, KeyboardInterrupt):
        state["status"] = "finish_unknown"
        write_json(path, state)
        raise
    state["status"] = "finished"
    write_json(path, state)
    print(f"服务器返回业务成功。里程 {distance} 米，时长 {payload['running_time']} 秒。")
    print("考核是否计入，以小程序中的记录为准。")


def run(args):
    if not args.submit:
        preview(args)
        return
    points = validate_points(read_json(args.points))
    validate_metrics(args.distance, args.duration, args.steps)
    path = Path(args.session)
    if path.exists():
        raise ToolError("会话文件已存在。请先核实已有记录；新记录请指定新的 --session 文件。")
    client = ApiClient(args.base_url)
    student_id = client.login(*credentials(args))
    state = {"status": "create_pending", "base_url": client.base_url,
             "student_id": student_id, "points": points,
             "start_time": now().replace(microsecond=0).isoformat()}
    # 网络失败也保留状态，避免用户误以为未创建而重复请求。
    claim_session(path, state)
    try:
        data = client.post("/api/createLine/", {"pass_point": points, "student_id": student_id})
        if not isinstance(data, dict) or not valid_id(data.get("record_id")):
            raise ToolError("创建响应缺少 record_id；请在小程序核实是否已创建记录。")
    except (ToolError, KeyboardInterrupt):
        state["status"] = "create_unknown"
        write_json(path, state)
        raise
    state.update(status="created", record_id=str(data["record_id"]))
    write_json(path, state)
    print(f"记录已创建；等待 {args.duration} 秒。状态保存到 {path.resolve()}", flush=True)
    deadline = time.monotonic() + args.duration
    try:
        while (remaining := deadline - time.monotonic()) > 0:
            time.sleep(min(remaining, 1))
    except KeyboardInterrupt:
        print("\n已停止等待，已有记录仍保留。之后可使用 finish 命令继续。")
        return
    perform_finish(client, state, path, args.distance, args.steps)


def finish(args):
    state = read_json(args.session)
    if not isinstance(state, dict) or state.get("status") != "created":
        raise ToolError("仅支持继续状态为 created 的记录；其他状态请先在小程序核实。")
    for key in ("student_id", "record_id"):
        if not valid_id(state.get(key)):
            raise ToolError(f"会话文件缺少有效的 {key}。")
    try:
        start = datetime.fromisoformat(state["start_time"])
        if start.tzinfo is None:
            raise ValueError("missing timezone")
    except (KeyError, TypeError, ValueError) as exc:
        raise ToolError("会话文件中的开始时间无效。") from exc
    payload = update_payload(state["student_id"], state["record_id"], start,
                             now().replace(microsecond=0), args.distance, args.steps)
    if not args.submit:
        safe = dict(payload, student_id="<本地会话中的学生 ID>", record_id="<本地会话中的记录 ID>")
        print(json.dumps(safe, ensure_ascii=False, indent=2))
        return
    client = ApiClient(state["base_url"])
    if client.login(*credentials(args)) != str(state["student_id"]):
        raise ToolError("登录账号与创建记录的账号不一致，停止提交。")
    perform_finish(client, state, args.session, args.distance, args.steps)


def read_capture(path):
    try:
        with Path(path).open(encoding="utf-8-sig") as handle:
            for index, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                    if not isinstance(record, dict) or not isinstance(record.get("request"), dict):
                        raise ValueError("missing request")
                except ValueError as exc:
                    raise ToolError(f"抓包文件第 {index} 行格式无效。") from exc
                yield record
    except OSError as exc:
        raise ToolError(f"无法读取抓包文件：{path}") from exc


def point_arrays(value, path="$"):
    if isinstance(value, list):
        try:
            validate_points(value)
        except ToolError:
            for index, item in enumerate(value):
                yield from point_arrays(item, f"{path}[{index}]")
        else:
            yield path, value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield from point_arrays(item, f"{path}.{key}")


def analyze(args):
    counts = Counter()
    found = 0
    for record in read_capture(args.capture):
        request = record["request"]
        counts[(request.get("method"), request.get("path"),
                (record.get("response") or {}).get("status"))] += 1
        for side in ("request", "response"):
            body = (record.get(side) or {}).get("body", {})
            for json_path, _ in point_arrays(body.get("value")):
                found += 1
                print(f"三个点位候选：flow={record.get('id')} {side} {json_path} {request.get('path')}")
    for (method, path, status), count in counts.items():
        print(f"{method} {path}  HTTP={status}  次数={count}")
    print(f"共 {sum(counts.values())} 条请求，{found} 个三点数组。")


def export_points(args):
    candidates = []
    for record in read_capture(args.capture):
        if args.flow_id and record.get("id") != args.flow_id:
            continue
        for side in ("request", "response"):
            if args.source and args.source != side:
                continue
            body = (record.get(side) or {}).get("body", {})
            for json_path, points in point_arrays(body.get("value")):
                if not args.json_path or args.json_path == json_path:
                    candidates.append((record, side, json_path, points))
    if len(candidates) != 1:
        raise ToolError(f"找到 {len(candidates)} 个候选；使用 analyze 查看，再以 --flow-id、--source、--json-path 精确选择。")
    record, side, json_path, points = candidates[0]
    if Path(args.output).exists():
        raise ToolError("输出文件已存在，请使用新的文件名。")
    write_json(args.output, {"captured_at": record.get("captured_at"),
                            "source": {"flow_id": record.get("id"), "side": side,
                                       "json_path": json_path}, "points": points})
    print(f"点位已导出到 {Path(args.output).resolve()}。请核对是否属于本次考核。")


def import_session(args):
    candidates = []
    for record in read_capture(args.capture):
        request = record["request"]
        if (request.get("host") != urlsplit(DEFAULT_URL).hostname
                or request.get("path") != "/api/createLine/"
                or request.get("method") != "POST"
                or (args.flow_id and record.get("id") != args.flow_id)):
            continue
        request_body = request.get("body", {}).get("value")
        response = record.get("response") or {}
        result = response.get("body", {}).get("value")
        if (response.get("status") != 200 or not isinstance(request_body, dict)
                or not isinstance(result, dict) or result.get("code") != 1
                or not isinstance(result.get("data"), dict)):
            continue
        student_id = request_body.get("student_id")
        record_id = result["data"].get("record_id")
        if not valid_id(student_id) or not valid_id(record_id):
            continue
        points = validate_points(request_body.get("pass_point"))
        try:
            start = datetime.fromisoformat(record.get("started_at") or record["captured_at"])
            if start.tzinfo is None:
                raise ValueError("missing timezone")
        except (KeyError, TypeError, ValueError) as exc:
            raise ToolError("抓包记录缺少带时区的开始时间。") from exc
        candidates.append({"status": "created", "base_url": DEFAULT_URL,
                           "student_id": str(student_id), "record_id": str(record_id),
                           "start_time": start.astimezone(SHANGHAI).isoformat(),
                           "points": points, "source_flow_id": record.get("id")})
    if len(candidates) != 1:
        raise ToolError(f"找到 {len(candidates)} 条可导入的创建记录。需要成功的 createLine 请求与响应；多个时用 --flow-id 精确选择。")
    claim_session(args.session, candidates[0])
    print(f"已导入小程序创建的记录到 {Path(args.session).resolve()}，未发送网络请求。")
    print("核实该记录尚未结束后，使用 finish 预览或提交；不需要再次 run。")


def make_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    for command in ("preview", "run"):
        sub = subs.add_parser(command, help="预览提交内容" if command == "preview" else "创建、等待并更新记录")
        sub.add_argument("--points", required=True, help="本次三个点位的 JSON 文件")
        sub.add_argument("--distance", type=int, required=True, help="里程，单位米，至少 2000")
        sub.add_argument("--duration", type=int, required=True, help="时长，单位秒")
        sub.add_argument("--steps", type=int, required=True, help="步数")
        sub.add_argument("--base-url", default=DEFAULT_URL)
        if command == "run":
            sub.add_argument("--submit", action="store_true", help="实际调用服务器；不加此项只预览")
            sub.add_argument("--phone", help="也可使用 HUATI_PHONE 环境变量")
            sub.add_argument("--session", default="private/session.json", help="状态文件，不保存密码")
    sub = subs.add_parser("finish", help="继续已创建的记录；时长从开始时间计算")
    sub.add_argument("--session", default="private/session.json")
    sub.add_argument("--distance", type=int, required=True)
    sub.add_argument("--steps", type=int, required=True)
    sub.add_argument("--phone")
    sub.add_argument("--submit", action="store_true")
    sub = subs.add_parser("analyze", help="列出抓包接口和点位候选，不打印密码或令牌")
    sub.add_argument("capture")
    sub = subs.add_parser("export-points", help="从实际捕获的请求或响应导出三个点位")
    sub.add_argument("capture")
    sub.add_argument("--flow-id")
    sub.add_argument("--source", choices=("request", "response"))
    sub.add_argument("--json-path", help="analyze 列出的路径，例如 $.data.points")
    sub.add_argument("--output", default="private/points.json")
    sub = subs.add_parser("import-session", help="导入小程序已创建的记录，避免再次创建")
    sub.add_argument("capture")
    sub.add_argument("--flow-id")
    sub.add_argument("--session", default="private/session.json")
    return parser


def main():
    args = make_parser().parse_args()
    try:
        {"preview": preview, "run": run, "finish": finish,
         "analyze": analyze, "export-points": export_points,
         "import-session": import_session}[args.command](args)
        return 0
    except ToolError as exc:
        print(f"错误：{exc}")
        return 1
    except (KeyboardInterrupt, EOFError):
        print("\n操作已取消。")
        return 130


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    raise SystemExit(main())
