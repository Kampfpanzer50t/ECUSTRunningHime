import Foundation

private struct CompanionRejection: LocalizedError {
    let reason: String
    var errorDescription: String? { reason }
}

private final class CompanionNoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler completion: @escaping (URLRequest?) -> Void) { completion(nil) }
}

struct CompanionClient {
    let endpoint: URL
    let token: String
    static let session: URLSession = {
        let settings = URLSessionConfiguration.ephemeral
        settings.timeoutIntervalForRequest = 45; settings.timeoutIntervalForResource = 60
        settings.urlCache = nil; settings.httpCookieStorage = nil
        return URLSession(configuration: settings, delegate: CompanionNoRedirect(), delegateQueue: nil)
    }()
    init(_ value: JSON) throws {
        guard let url = URL(string: value.text("endpoint")), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              ["", "/"].contains(url.path), value.text("token").range(of: "^[A-Za-z0-9_-]{40,128}$", options: .regularExpression) != nil else {
            throw AppError("配对文件需包含自己的 HTTPS 根地址和有效配对信息")
        }
        endpoint = url; token = value.text("token")
    }
    func request(command: JSON? = nil) async throws -> JSON {
        let url = endpoint.appendingPathComponent(command == nil ? "v1/status" : "v1/command")
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpMethod = command == nil ? "GET" : "POST"
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let command { request.httpBody = try JSONSerialization.data(withJSONObject: command, options: [.sortedKeys]) }
        let (data, response) = try await Self.session.data(for: request)
        guard let http = response as? HTTPURLResponse, data.count <= 2 * 1024 * 1024,
              let value = try JSONSerialization.jsonObject(with: data) as? JSON else { throw AppError("电脑响应无效") }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 400, let command, value.text("result") == "rejected", value.text("command_id") == command.text("id") {
                throw CompanionRejection(reason: value.text("error", "电脑拒绝了指令"))
            }
            throw AppError("电脑连接或认证失败，请检查助手与私人网络")
        }
        guard value.text("version") == "2.0.5", value.number("server_time_ms") > 0,
              value["enabled"] is Bool, value["unresolved"] is Bool, value["run"] is JSON else { throw AppError("电脑响应缺少有效状态") }
        if let command, value.text("confirmed_id") != command.text("id") { throw AppError("电脑未确认原指令编号，请刷新核实") }
        return value
    }
}

@MainActor extension AppModel {
    private func saveCompanion(_ value: JSON) throws { try disk().write("companion", value); companion = value }
    func importCompanion(_ data: Data) async throws {
        guard !remoteOwned else { throw AppError("请先结束当前电脑代执行再更换配对") }
        guard data.count <= 16384, let pair = try JSONSerialization.jsonObject(with: data) as? JSON,
              pair.text("format") == "huati-companion-pair", pair.number("version") == 1 else { throw AppError("请选择电脑生成的配对文件") }
        let client = try CompanionClient(pair), snapshot = try await client.request()
        // A provisioned computer always remains the owner until its plan is released.
        let owner = snapshot.flag("enabled") || snapshot.flag("unresolved") ? "remote" : "local"
        guard owner == "local" || !unresolved else { throw AppError("电脑和手机均有未完成记录，请先分别处理") }
        try saveCompanion(["endpoint": pair.text("endpoint"), "token": pair.text("token"), "owner": owner, "snapshot": snapshot])
        if owner == "remote" { var next = schedule; next["enabled"] = false; try saveSchedule(next) }
        BackgroundCoordinator.shared.schedule(self)
        message = "已连接自己的电脑"
    }
    func syncCompanion() async throws {
        let client = try CompanionClient(companion)
        let pending = companion.object("pending_command")
        let snapshot: JSON
        do { snapshot = try await client.request(command: pending.isEmpty ? nil : pending) }
        catch let error as CompanionRejection {
            if !pending.isEmpty {
                var next = companion; next["owner"] = next.text("pending_previous_owner", "remote")
                next.removeValue(forKey: "pending_command"); next.removeValue(forKey: "pending_previous_owner"); try saveCompanion(next)
            }
            throw error
        }
        var next = companion; next["snapshot"] = snapshot; next["checked_at"] = Clock.now
        if !pending.isEmpty {
            next.removeValue(forKey: "pending_command")
            next.removeValue(forKey: "pending_previous_owner")
            next["owner"] = pending.text("action") == "release" ? "local" : "remote"
        }
        try saveCompanion(next)
    }
    func companionAction(_ action: String, hour: Int = 6, minute: Int = 15) async throws {
        guard companion.object("pending_command").isEmpty else { throw AppError("上次指令结果未知，请先刷新电脑状态以确认原指令") }
        guard ["plan", "disable", "pause", "resume", "discard", "release"].contains(action) else { throw AppError("指令无效") }
        let client = try CompanionClient(companion)
        var command: JSON = ["id": UUID().uuidString, "action": action]
        if action == "plan" {
            guard signedIn, !unresolved else { throw AppError("请先登录并处理手机当前记录") }
            guard try core?.flag("validTime", [hour, minute]) == true else { throw AppError("时间需在北京时间 06:00–21:00") }
            command["hour"] = hour; command["minute"] = minute
            command["session"] = config; command["roads"] = preferences.flag("road_mode")
        }
        // Claim ownership before sending; a lost response must never re-enable local scheduling.
        let previousOwner = companion.text("owner", "local")
        var next = companion; next["pending_previous_owner"] = previousOwner; next["owner"] = "pending"; next["pending_command"] = command
        try saveCompanion(next)
        var local = schedule; local["enabled"] = false; try saveSchedule(local)
        BackgroundCoordinator.shared.schedule(self)
        do {
            let snapshot = try await client.request(command: command)
            next["snapshot"] = snapshot; next["checked_at"] = Clock.now
            next.removeValue(forKey: "pending_command"); next.removeValue(forKey: "pending_previous_owner"); next["owner"] = action == "release" ? "local" : "remote"
            try saveCompanion(next); message = snapshot.text("message", "电脑指令已确认")
        } catch {
            if error is CompanionRejection {
                next["owner"] = previousOwner; next.removeValue(forKey: "pending_command"); next.removeValue(forKey: "pending_previous_owner")
                try saveCompanion(next); throw error
            }
            message = "电脑指令未确认，本机自动运行已关闭。请刷新电脑状态，系统会核实原指令。"
            throw error
        }
    }
}
