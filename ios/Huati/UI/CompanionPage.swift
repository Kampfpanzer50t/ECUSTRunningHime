import SwiftUI
import UniformTypeIdentifiers

struct CompanionControls: View {
    @EnvironmentObject private var model: AppModel
    let hour: Int
    let minute: Int
    let seed: Int
    @State private var importing = false
    @State private var discardConfirmation = false
    private var snapshot: JSON { model.companion.object("snapshot") }
    private var record: JSON { model.remoteRun }
    var body: some View {
        ModuleCard("电脑代执行", subtitle: "手机锁屏时，由自己的联网电脑按计划执行", index: seed) {
            Note(title: "按时开始的可选方案", text: "手机仍使用本 App 设置与查看记录。电脑提前两分钟准备校内模拟起点、刷新今日三点，到点开始，计满随机时长后直接提交。电脑需持续开机联网、关闭睡眠；手机锁屏不会暂停电脑任务。")
            Note(title: "起点说明", text: "电脑无法读取锁屏 iPhone 的实时位置，会选择校内模拟起点。首次需按随包说明启动电脑助手、配置自己的 HTTPS 地址并导入配对文件。")
            ActionButton(title: model.companion.isEmpty ? "导入电脑配对文件" : "更换电脑配对", secondary: true) { importing = true }.disabled(model.remoteOwned)
            if !model.companion.isEmpty {
                Text(model.remoteOwned ? "执行方：电脑（手机本机创建已停用）" : "执行方：手机；电脑尚未接管")
                    .font(.subheadline.weight(.medium)).foregroundStyle(Theme.ink)
                ActionButton(title: "同步以上时间到电脑并开启") { model.perform { try await model.companionAction("plan", hour: hour, minute: minute) } }
                ActionButton(title: "刷新电脑状态", secondary: true) { model.perform { try await model.syncCompanion() } }
                if !snapshot.text("preparation").isEmpty { Note(title: "电脑准备", text: snapshot.text("preparation")) }
                if !snapshot.text("message").isEmpty { Note(title: "最近任务", text: snapshot.text("message")) }
                if !record.isEmpty {
                    let labels = ["created": "模拟中", "paused": "已暂停", "finished": "已提交", "discarded": "已舍弃", "create_unknown": "创建待核实", "finish_unknown": "提交待核实"]
                    Note(title: "电脑当前记录 · " + (labels[record.text("status")] ?? "处理中"), text: String(format: "%.3f 公里 · 剩余 %@", record.number("distance") / 1000, Clock.duration(record.number("remaining_ms"))) + (record.text("error").isEmpty ? "" : "\n" + record.text("error")))
                    if record.text("status") == "created" { ActionButton(title: "暂停电脑本次模拟", secondary: true) { model.perform { try await model.companionAction("pause") } } }
                    if record.text("status") == "paused" { ActionButton(title: "继续电脑本次模拟") { model.perform { try await model.companionAction("resume") } } }
                    if snapshot.flag("unresolved") { ActionButton(title: "舍弃电脑本次记录", secondary: true) { discardConfirmation = true } }
                }
                if model.remoteOwned {
                    ActionButton(title: "停止后续电脑计划", secondary: true) { model.perform { try await model.companionAction("disable") } }
                    ActionButton(title: "结束代执行，交回手机", secondary: true) { model.perform { try await model.companionAction("release") } }
                }
                if model.companion.number("checked_at") > 0 { Text("状态查询于 " + Clock.label(model.companion.number("checked_at"))).font(.caption).foregroundStyle(Theme.muted) }
            }
        }.disabled(model.busy)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                model.perform {
                    let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                    try await model.importCompanion(Data(contentsOf: url))
                }
            case .failure(let error): model.message = error.localizedDescription
            }
        }
        .sheet(isPresented: $discardConfirmation) {
            CompanionSheet("舍弃电脑记录", subtitle: "停止本次模拟，保留服务器已创建记录") {
                Note(title: "保留服务器占位", text: "舍弃后本次无法继续。此操作会发送到你的电脑；后续每日计划仍按已保存的时间执行。")
            } actions: {
                ActionButton(title: "确认舍弃") { discardConfirmation = false; model.perform { try await model.companionAction("discard") } }
                ActionButton(title: "保留本次", secondary: true) { discardConfirmation = false }
            }
        }
    }
}
