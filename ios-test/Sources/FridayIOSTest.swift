import SwiftUI
import AlarmKit

struct FridayAlarmMetadata: AlarmMetadata, Codable, Hashable, Sendable {}

@main struct FridayIOSTestApp: App {
    var body: some Scene { WindowGroup { FridayHome() } }
}

struct FridayHome: View {
    @State private var due = Date().addingTimeInterval(60)
    @State private var reminder = "Friday test reminder"
    @State private var status = "No alarm scheduled."
    @State private var busy = false
    @AppStorage("friday.lastAlarmID") private var lastID = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("FRIDAY").font(.largeTitle.bold()).foregroundStyle(.cyan)
                    Text("iPhone • AlarmKit test edition").foregroundStyle(.secondary)
                    Text("Local alarms only. No production database changes.")
                        .font(.footnote)
                }
                Section("Reminder") {
                    TextField("Reminder label", text: $reminder)
                    DatePicker("Time", selection: $due, in: Date()...)
                    Button("Schedule reminder") { schedule(at: due) }
                    Button("Test ring in 30 seconds") {
                        reminder = "Friday test reminder"
                        schedule(at: Date().addingTimeInterval(30))
                    }
                    Button("Cancel last alarm", role: .destructive) {
                        guard let id = UUID(uuidString: lastID) else { return }
                        do { try AlarmManager.shared.cancel(id: id); lastID = ""; status = "Cancelled." }
                        catch { status = "Cancel failed: " + error.localizedDescription }
                    }.disabled(lastID.isEmpty || busy)
                    Text(status).font(.footnote)
                }.disabled(busy)
                Section("Existing assistant") {
                    Link("Open existing Friday web app", destination: URL(string: "https://santhoshofficial91-png.github.io/todo-call-reminder/")!)
                    Text("Opens your existing live Friday in Safari. Native voice, task sync and business integration are not part of this alarm test.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Lock screen test") {
                    Text("Allow alarm access, schedule the 30-second test, then lock your iPhone. The system alarm should appear when due. This is not a phone call.")
                }
            }.navigationTitle("Friday Test")
        }
    }
    private func schedule(at date: Date) {
        guard date > Date().addingTimeInterval(1) else { status = "Select a future time."; return }
        busy = true
        Task { @MainActor in
            defer { busy = false }
            do {
                let auth = try await AlarmManager.shared.requestAuthorization()
                guard auth == .authorized else { status = "Alarm permission not granted."; return }
                let id = UUID()
                let label = reminder.trimmingCharacters(in: .whitespacesAndNewlines)
                let title = LocalizedStringResource(stringLiteral: String((label.isEmpty ? "Friday reminder" : label).prefix(80)))
                let stop = AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.circle")
                let alert = AlarmPresentation.Alert(title: title, stopButton: stop)
                let attributes = AlarmAttributes(presentation: AlarmPresentation(alert: alert), metadata: FridayAlarmMetadata(), tintColor: .cyan)
                let config = AlarmManager.AlarmConfiguration.alarm(schedule: .fixed(date), attributes: attributes, sound: .default)
                _ = try await AlarmManager.shared.schedule(id: id, configuration: config)
                if let previous = UUID(uuidString: lastID) {
                    do { try AlarmManager.shared.cancel(id: previous) }
                    catch { status = "New alarm set; previous cancellation failed. Check system alarms."; lastID = id.uuidString; return }
                }
                lastID = id.uuidString
                status = "Alarm scheduled for " + date.formatted(date: .abbreviated, time: .standard)
            } catch { status = "Could not schedule: " + error.localizedDescription }
        }
    }
}
