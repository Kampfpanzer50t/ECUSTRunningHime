import BackgroundTasks
import UserNotifications
import UIKit

@MainActor final class BackgroundCoordinator {
    static let shared = BackgroundCoordinator()
    nonisolated static let runID = "cn.huati.assistant.ios.run"
    nonisolated static let probeID = "cn.huati.assistant.ios.probe"
    private var shortTask: UIBackgroundTaskIdentifier = .invalid
    private var shortWork: Task<Void, Never>?

    func requestNotifications() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }
    private func notification(id: String, title: String, text: String, after: TimeInterval) {
        let content = UNMutableNotificationContent(); content.title = title; content.body = text; content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, after), repeats: false)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }
    func schedule(_ model: AppModel) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ["daily", "finish"])
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.runID)
        guard !model.remoteOwned, model.startupError == nil, let core = model.core else { return }
        var wake: Double?
        if model.activeRecord {
            let remaining = (try? core.number("remaining", [model.run, Clock.now])) ?? 0
            wake = max(model.run.number("check_retry_ms"), Clock.now + max(1000, remaining))
            notification(id: "finish", title: "本次计时预计结束", text: "iOS 可能暂停后台执行。请打开应用查看提交状态；这条通知不表示已提交。", after: remaining / 1000)
        }
        if model.schedule.flag("enabled") {
            let settings = model.schedule
            var components = DateComponents(); components.timeZone = TimeZone(identifier: "Asia/Shanghai")
            components.hour = Int(settings.number("hour", 6)); components.minute = Int(settings.number("minute", 15))
            let content = UNMutableNotificationContent(); content.title = "今日计划时间到了"
            content.body = "打开华理跑步姬可在前台执行计划。iOS 后台任务是否运行请查看记录。"; content.sound = .default
            center.add(UNNotificationRequest(identifier: "daily", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: true)))
            if let preparation = try? core.number("wake", [settings, model.run, Clock.now]), preparation > 0 {
                wake = min(wake ?? .greatestFiniteMagnitude, max(Clock.now + 1000, preparation))
            }
        }
        if let wake {
            let request = BGProcessingTaskRequest(identifier: Self.runID)
            request.requiresNetworkConnectivity = true; request.requiresExternalPower = false; request.earliestBeginDate = Clock.date(wake)
            // earliestBeginDate is a lower bound, not an exact alarm.
            do { try BGTaskScheduler.shared.submit(request) }
            catch { model.backgroundEvent("系统拒绝安排后台任务，请检查后台刷新设置") }
        }
    }
    func enterBackground(_ model: AppModel) {
        model.deactivate()
        let wake = (try? model.core?.number("wake", [model.schedule, model.run, Clock.now])) ?? 0
        guard !model.remoteOwned, shortTask == .invalid, model.busy || (wake > 0 && wake - Clock.now < 20000) else { return }
        shortTask = UIApplication.shared.beginBackgroundTask(withName: "保存并完成当前请求") { [weak self] in
            Task { @MainActor in model.cancelCurrentWork(); self?.shortWork?.cancel(); self?.endShortTask() }
        }
        shortWork = Task { [weak self] in
            defer { BackgroundCoordinator.shared.schedule(model); self?.endShortTask() }
            let deadline = ProcessInfo.processInfo.systemUptime + 25
            while !Task.isCancelled && ProcessInfo.processInfo.systemUptime < deadline && UIApplication.shared.applicationState != .active {
                guard UIApplication.shared.backgroundTimeRemaining > 5 else { break }
                await model.service()
                let next = (try? model.core?.number("wake", [model.schedule, model.run, Clock.now])) ?? 0
                if !model.busy && (next <= 0 || next - Clock.now > 20000) { break }
                do { try await Task.sleep(nanoseconds: 300_000_000) } catch { break }
            }
        }
    }
    private func endShortTask() {
        if shortTask != .invalid { UIApplication.shared.endBackgroundTask(shortTask); shortTask = .invalid }
    }
    func probe(after seconds: Double) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.probeID)
        let request = BGProcessingTaskRequest(identifier: Self.probeID)
        request.requiresNetworkConnectivity = true; request.earliestBeginDate = Date().addingTimeInterval(seconds)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { AppModel.shared.backgroundEvent("系统拒绝安排后台自检") }
        notification(id: "probe", title: "后台自检观察时间到了", text: "通知不代表后台已执行。请打开排查报告查看是否有“后台自检已唤醒”。iOS 可能延迟唤醒。", after: seconds)
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BGTaskScheduler.shared.register(forTaskWithIdentifier: BackgroundCoordinator.runID, using: nil) { task in
            let work = Task { @MainActor in
                await AppModel.shared.backgroundTask()
                task.setTaskCompleted(success: !Task.isCancelled)
            }
            task.expirationHandler = { work.cancel() }
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: BackgroundCoordinator.probeID, using: nil) { task in
            let work = Task { @MainActor in
                await AppModel.shared.probe()
                task.setTaskCompleted(success: !Task.isCancelled)
            }
            task.expirationHandler = { work.cancel() }
        }
        return true
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { completionHandler([.banner, .sound]) }
}
