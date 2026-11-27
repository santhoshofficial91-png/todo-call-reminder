import SwiftUI
import AlarmKit
import UserNotifications
import AppIntents

struct TalkToFridayIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Talk to Friday"
    static var openAppWhenRun = true
    @Parameter(title: "Alarm ID") var alarmID: String
    init() { alarmID = "" }
    init(alarmID: String) { self.alarmID = alarmID }
    func perform() async throws -> some IntentResult {
        UserDefaults.standard.set(alarmID, forKey: "friday.talkRequest")
        if let id = UUID(uuidString: alarmID) { try? AlarmManager.shared.stop(id: id) }
        return .result()
    }
}


struct FridayAlarmMetadata: AlarmMetadata, Codable, Hashable, Sendable {}

@main struct FridayIOSTestApp: App {
    var body: some Scene { WindowGroup { FridayHome() } }
}

struct FridayHome: View {
    @State private var due = Date().addingTimeInterval(60)
    @State private var reminder = "Friday test reminder"
    @State private var status = "No alarm scheduled."
    @State private var busy = false
    @State private var permission = "Not checked"
    @State private var fallback = true
    @AppStorage("friday.talkRequest") private var talkRequest = ""
    @Environment(\.scenePhase) private var scenePhase
    @State private var showTalk = false
    @AppStorage("friday.lastAlarmID") private var lastID = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("FRIDAY").font(.largeTitle.bold()).foregroundStyle(.cyan)
                    Text("iPhone • AlarmKit test edition v0.4").foregroundStyle(.secondary)
                    Text("Local alarms only. No production database changes.")
                        .font(.footnote)
                }
                Section("Alarm diagnostics") {
                    Text("Authorization: " + permission)
                    Text("Usage description: " + ((Bundle.main.object(forInfoDictionaryKey: "NSAlarmKitUsageDescription") as? String)?.isEmpty == false ? "Present" : "MISSING"))
                    Button("Enable Alarm Permission") { Task { @MainActor in
                        do { let result = try await AlarmManager.shared.requestAuthorization(); permission = String(describing: result) }
                        catch { status = details(error, stage: "Authorization") }
                    } }
                    Button("Check Alarm Status") { permission = String(describing: AlarmManager.shared.authorizationState) }
                    Toggle("Notification fallback", isOn: $fallback)
                    Button("Test notification in 30 seconds") { Task { @MainActor in await notify(after: 30) } }
                }.disabled(busy)
                Section("Reminder") {
                    TextField("Reminder label", text: $reminder)
                    DatePicker("Time", selection: $due, in: Date()...)
                    Button("Schedule reminder") { schedule(at: due) }
                    Button("Test ring in 30 seconds") {
                        reminder = "Friday test reminder"
                        schedule(at: Date().addingTimeInterval(30))
                    }
                    Button("Cancel pending notification", role: .destructive) {
                        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["friday-test-fallback"])
                        status = "Pending notification cancelled."
                    }
                    Button("Cancel last alarm", role: .destructive) {
                        guard let id = UUID(uuidString: lastID) else { return }
                        do { try AlarmManager.shared.cancel(id: id); lastID = ""; status = "Cancelled." }
                        catch { status = "Cancel failed: " + error.localizedDescription }
                    }.disabled(lastID.isEmpty || busy)
                    Text(status).font(.footnote)
                }.disabled(busy)
                Section("Existing assistant") {
                    Button("Preview Talk to Friday screen") { showTalk = true }
                    Link("Open existing Friday web app", destination: URL(string: "https://santhoshofficial91-png.github.io/todo-call-reminder/")!)
                    Text("Opens your existing live Friday in Safari. Native voice, task sync and business integration are not part of this alarm test.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Lock screen test") {
                    Text("Allow alarm access, schedule the 30-second test, then lock your iPhone. The system alarm should appear when due. This is not a phone call.")
                }
            }.navigationTitle("Friday Test")
                .onAppear { checkTalkRequest() }
                .onChange(of: scenePhase) { _, phase in if phase == .active { checkTalkRequest() } }
                .onChange(of: talkRequest) { _, _ in if scenePhase == .active { checkTalkRequest() } }
                .fullScreenCover(isPresented: $showTalk) { talkScreen }
        }
    }
    private func checkTalkRequest() {
        let request = UserDefaults.standard.string(forKey: "friday.talkRequest") ?? ""
        guard !request.isEmpty else { return }
        talkRequest = ""
        showTalk = true
    }

    private var talkScreen: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "waveform.circle.fill").font(.system(size: 96)).foregroundStyle(.cyan)
            Text("Friday").font(.largeTitle.bold())
            Text("Your reminder is ready").foregroundStyle(.secondary)
            Spacer()
            Link(destination: URL(string: "https://santhoshofficial91-png.github.io/todo-call-reminder/")!) {
                Label("Open Friday Voice Assistant", systemImage: "phone.fill")
                    .frame(maxWidth: .infinity).padding()
                    .background(.green, in: RoundedRectangle(cornerRadius: 20))
                    .foregroundStyle(.white)
            }
            Text("Opens Friday in Safari. Tap Call Friday and Answer to start the conversation. Internet and existing pairing may be required.").font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("Close", role: .cancel) { showTalk = false }.padding()
        }.padding(28).frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.black).preferredColorScheme(.dark)
    }

    private func schedule(at date: Date) {
        guard date > Date().addingTimeInterval(1) else { status = "Select a future time."; return }
        busy = true
        Task { @MainActor in
            defer { busy = false }
            var stage = "Authorization"
            let delay = date.timeIntervalSinceNow
            do {
                let auth = try await AlarmManager.shared.requestAuthorization()
                permission = String(describing: auth)
                guard auth == .authorized else { status = "Alarm permission not granted."; if fallback { await notify(after: delay, prefix: status) }; return }
                stage = "Scheduling"
                let id = UUID()
                let label = reminder.trimmingCharacters(in: .whitespacesAndNewlines)
                let title = LocalizedStringResource(stringLiteral: String((label.isEmpty ? "Friday reminder" : label).prefix(80)))
                let stop = AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.circle")
                let talk = AlarmButton(text: "Talk to Friday", textColor: .white, systemImageName: "phone.fill")
                let alert = AlarmPresentation.Alert(title: title, stopButton: stop, secondaryButton: talk, secondaryButtonBehavior: .custom)
                let attributes = AlarmAttributes(presentation: AlarmPresentation(alert: alert), metadata: FridayAlarmMetadata(), tintColor: .cyan)
                let config = AlarmManager.AlarmConfiguration.alarm(schedule: .fixed(max(date, Date().addingTimeInterval(5))), attributes: attributes, secondaryIntent: TalkToFridayIntent(alarmID: id.uuidString), sound: .default)
                _ = try await AlarmManager.shared.schedule(id: id, configuration: config)
                if let previous = UUID(uuidString: lastID) {
                    do { try AlarmManager.shared.cancel(id: previous) }
                    catch { status = "New alarm set; previous cancellation failed. Check system alarms."; lastID = id.uuidString; return }
                }
                lastID = id.uuidString
                status = "Alarm scheduled for " + date.formatted(date: .abbreviated, time: .standard)
            } catch {
                status = details(error, stage: stage)
                if fallback { await notify(after: delay, prefix: status) }
            }
        }
    }
    private func details(_ error: Error, stage: String) -> String {
        let ns = error as NSError
        return "\(stage): \(ns.domain) code \(ns.code) • \(String(reflecting: error)) • iOS \(ProcessInfo.processInfo.operatingSystemVersionString)"
    }

    @MainActor private func notify(after delay: TimeInterval, prefix: String = "") async {
        do {
            let center = UNUserNotificationCenter.current()
            guard try await center.requestAuthorization(options: [.alert, .sound, .badge]) else {
                status = prefix + "\nNotification permission denied."
                return
            }
            let content = UNMutableNotificationContent()
            content.title = "Friday reminder"
            content.body = reminder
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(5, delay), repeats: false)
            center.removePendingNotificationRequests(withIdentifiers: ["friday-test-fallback"])
            try await center.add(UNNotificationRequest(identifier: "friday-test-fallback", content: content, trigger: trigger))
            status = prefix + "\nStandard notification scheduled. Lock screen to test; this is not a full-screen alarm."
        } catch { status = prefix + "\n" + details(error, stage: "Notification") }
    }
}
