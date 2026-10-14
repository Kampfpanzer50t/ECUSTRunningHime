"""当前协议的模拟记录执行器；需明确指定 --submit。"""
from datetime import datetime, timedelta
import argparse
import json
import math
from pathlib import Path
import sys
import time

from huati import SHANGHAI, ToolError, claim_session, now, read_json, write_json
from huati_current import CurrentClient, encrypt


def haversine(a, b):
    lat1, lat2 = math.radians(float(a["lat"])), math.radians(float(b["lat"]))
    dl = math.radians(float(b["lat"]) - float(a["lat"]))
    dg = math.radians(float(b["lng"]) - float(a["lng"]))
    return 6371000 * 2 * math.asin(min(1, math.sqrt(math.sin(dl / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dg / 2) ** 2)))


def make_path(points, start_location, start_time, distance, duration):
    if len(points) != 3:
        raise ToolError("必须使用本次三个点位。")
    if distance < 2000 or duration <= 0:
        raise ToolError("模拟里程必须至少 2000 米，时长必须为正数。")
    try:
        coords = [(float(p["lat"]), float(p["lng"])) for p in [start_location, *points]]
    except (ValueError, TypeError, KeyError) as exc:
        raise ToolError("点位坐标格式无效。") from exc
    if any(not math.isfinite(lat) or not math.isfinite(lng) or not -90 <= lat <= 90 or not -180 <= lng <= 180
           for lat, lng in coords) or len(set(coords[1:])) != 3:
        raise ToolError("点位需要三个独立的有效坐标。")
    stops = [start_location, *points, start_location]
    path = []
    travelled = 0.0
    current = stops[0]
    # 沿指定点位组成的折线循环；最后一个线段截断以匹配指定总长度。
    index = 1
    path.append({"name": "", "lat": float(current["lat"]), "lng": float(current["lng"]),
                 "timestamp": int(start_time.timestamp() * 1000), "accuracy": 5})
    while travelled < distance:
        target = stops[index]
        segment = haversine(current, target)
        if segment <= 0:
            index = index % (len(stops) - 1) + 1
            continue
        use = min(segment, distance - travelled)
        steps = max(1, math.ceil(use / 8))
        for step in range(1, steps + 1):
            part = use * step / steps
            ratio = part / segment
            path.append({"name": "", "lat": float(current["lat"]) + (float(target["lat"]) - float(current["lat"])) * ratio,
                         "lng": float(current["lng"]) + (float(target["lng"]) - float(current["lng"])) * ratio,
                         "timestamp": int((start_time.timestamp() + duration * (travelled + part) / distance) * 1000), "accuracy": 5})
        travelled += use
        current = target
        index = index % (len(stops) - 1) + 1
    for target in points:
        if min(haversine(p, target) for p in path) > 20:
            raise ToolError("指定长度不足以经过本次三个点位。")
    return path


def require_success(result, endpoint):
    if result.get("code") != 1:
        raise ToolError(f"{endpoint}：{result.get('message') or result.get('msg') or '服务器未报告成功'}")
    return result.get("data")


def create(args):
    records = [json.loads(line) for line in Path(args.capture).read_text(encoding="utf-8").splitlines()]
    capture = next((r for r in reversed(records) if r["request"]["path"] == "/xcxtapi/activity/randrunInfo" and r.get("response")), None)
    if not capture:
        raise ToolError("未捕获本次随机点位响应。")
    result = capture["response"]["body"]["value"]
    points = require_success(result, "randrunInfo")
    if len(points) != 3:
        raise ToolError("随机点位数量不是三个。")
    age = (now() - datetime.fromisoformat(capture["captured_at"])).total_seconds()
    if age < 0 or age > 1800:
        raise ToolError("点位响应已超过 30 分钟，请重新捕获。")
    location = {key: float(result[key]) for key in ("lat", "lng")}
    start = now().replace(microsecond=0)
    make_path(points, location, start, args.distance, args.duration)
    if args.steps <= 0:
        raise ToolError("步数必须为正数。")
    state = {"mode": "simulated", "status": "preview", "start_time": start.isoformat(),
             "points": points, "location": location, "distance": args.distance,
             "duration": args.duration, "steps": args.steps}
    if not args.submit:
        print(json.dumps(state, ensure_ascii=False, indent=2))
        return
    client = CurrentClient()
    profile = require_success(client.request("/xcxapi/getUserInfo/"), "getUserInfo")
    if str(profile["id"]) != str(result["student_id"]):
        raise ToolError("点位响应所属账号与当前会话不一致。")
    state.update(status="create_pending", student_id=str(profile["id"]))
    claim_session(args.session, state)
    try:
        created = client.request("/xcxapi/createLine/", {"student_id": profile["id"], "pass_point": points})
        data = require_success(created, "createLine")
        if not isinstance(data, dict) or not data.get("record_id"):
            raise ToolError("响应缺少 record_id。")
    except BaseException:
        state["status"] = "create_unknown"
        write_json(args.session, state)
        raise
    state.update(status="created", record_id=data["record_id"], create_response=created)
    write_json(args.session, state)
    print(json.dumps({"status": "created", "distance": args.distance, "duration": args.duration,
                      "points": [p["point_name"] for p in points],
                      "planned_end": (start + timedelta(seconds=args.duration)).strftime("%H:%M:%S")}, ensure_ascii=False), flush=True)


def finish(args):
    state = read_json(args.session)
    if state.get("status") not in ("created", "check_failed"):
        raise ToolError("状态不允许自动提交，先核实小程序记录。")
    start = datetime.fromisoformat(state["start_time"])
    remaining = state["duration"] - (now() - start).total_seconds()
    if remaining > 0:
        raise ToolError(f"计划时长尚未经过，还需等待 {math.ceil(remaining)} 秒。")
    end = now().replace(microsecond=0)
    seconds = int((end - start).total_seconds())
    payload = {"record_id": state["record_id"], "pace": seconds / 60 / (state["distance"] / 1000),
               "running_time": seconds, "mileage": state["distance"],
               "start_time": start.strftime("%Y-%m-%d %H:%M:%S"), "end_time": end.strftime("%Y-%m-%d %H:%M:%S"),
               "pass_point": 3, "step_count": state["steps"]}
    path = make_path(state["points"], state["location"], start, state["distance"], seconds)
    if not args.submit:
        print(json.dumps({"payload": payload, "path_points": len(path)}, ensure_ascii=False, indent=2))
        return
    client = CurrentClient()
    profile = require_success(client.request("/xcxapi/getUserInfo/"), "getUserInfo")
    if str(profile["id"]) != state["student_id"]:
        raise ToolError("会话账号发生变化。")
    encrypted = {"a": encrypt(payload)}
    checked = client.request("/xcxapi/checkRecord/", encrypted)
    write_json(Path(args.session).with_name("current-check.json"), checked)
    checked_data = require_success(checked, "checkRecord")
    if not isinstance(checked_data, dict) or checked_data.get("status") not in (0, 1):
        raise ToolError("服务器校验状态无法识别，未继续结束记录。")
    if checked_data["status"] == 0 and not getattr(args, "accept_invalid", False):
        state.update(status="check_failed", check_response=checked)
        write_json(args.session, state)
        raise ToolError(f"服务器校验不通过：{checked.get('message')}；未继续结束记录。")
    state.update(status="finish_pending", finish_payload=payload, check_response=checked,
                 accepted_invalid=checked_data["status"] == 0)
    write_json(args.session, state)
    try:
        updated = client.request("/xcxapi/updateRecordNew/", encrypted)
        require_success(updated, "updateRecordNew")
        state.update(status="record_updated", update_response=updated)
        write_json(args.session, state)
        for index in range(0, len(path), 50):
            encoded = encrypt({"record_id": state["record_id"], "path_point": path[index:index + 50], "path_image": ""})
            uploaded = client.request("/xcxapi/uploadPathPointV3/", {"img_url": encoded})
            require_success(uploaded, "uploadPathPointV3")
            state["uploaded_points"] = min(index + 50, len(path))
            write_json(args.session, state)
    except BaseException:
        state["status"] = "finish_unknown"
        write_json(args.session, state)
        raise
    state.update(status="finished", path=path, check_response=checked)
    write_json(args.session, state)
    print(json.dumps({"status": "finished", "distance": state["distance"], "duration": seconds,
                      "check": checked, "update": updated, "path_points": len(path)}, ensure_ascii=False))


def wait_finish(args):
    state = read_json(args.session)
    if state.get("status") != "created" or not args.submit:
        raise ToolError("等待提交仅支持已创建记录，并需明确指定 --submit。")
    target = datetime.fromisoformat(state["start_time"]) + timedelta(seconds=state["duration"])
    print(f"等待至 {target.strftime('%H:%M:%S')} 后提交同一条记录。", flush=True)
    last_minute = None
    try:
        while (remaining := (target - now()).total_seconds()) > 0:
            minute = math.ceil(remaining / 60)
            if minute != last_minute:
                print(f"剩余约 {minute} 分钟。", flush=True)
                last_minute = minute
            time.sleep(min(remaining, 1))
    except KeyboardInterrupt:
        print("已停止自动等待，创建的记录仍保留。", flush=True)
        return
    finish(args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    sub = subs.add_parser("create")
    sub.add_argument("--capture", default="private/capture.jsonl")
    sub.add_argument("--distance", type=int, default=2200)
    sub.add_argument("--duration", type=int, default=900)
    sub.add_argument("--steps", type=int, default=2600)
    sub.add_argument("--session", default="private/current-session.json")
    sub.add_argument("--submit", action="store_true")
    sub = subs.add_parser("finish")
    sub.add_argument("--session", default="private/current-session.json")
    sub.add_argument("--submit", action="store_true")
    sub.add_argument("--accept-invalid", action="store_true", help="接受服务器明确判定无效的结果，并结束同一条记录")
    sub = subs.add_parser("wait-finish")
    sub.add_argument("--session", default="private/current-session.json")
    sub.add_argument("--submit", action="store_true")
    args = parser.parse_args()
    {"create": create, "finish": finish, "wait-finish": wait_finish}[args.command](args)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except ToolError as exc:
        print(f"错误：{exc}")
        raise SystemExit(1)
