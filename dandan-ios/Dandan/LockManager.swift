import Foundation
import FamilyControls
import ManagedSettings
import DeviceActivity

/// 화면 시간(Screen Time) API로 앱을 막는다. 시간이 끝나면 DandanMonitor 확장이 자동으로 푼다.
@MainActor
final class LockManager: ObservableObject {
    @Published var selection = FamilyActivitySelection() { didSet { saveSelection() } }
    @Published var lockEnd: Date? { didSet { UserDefaults.standard.set(lockEnd, forKey: "lockEnd") } }
    @Published var wholePhone: Bool { didSet { UserDefaults.standard.set(wholePhone, forKey: "wholePhone") } }
    @Published var authorized = false
    @Published var message = ""

    private let store = ManagedSettingsStore(named: .init("lock"))
    private let activity = DeviceActivityName("lock")

    init() {
        let d = UserDefaults.standard
        wholePhone = d.bool(forKey: "wholePhone")
        lockEnd = d.object(forKey: "lockEnd") as? Date
        if let data = d.data(forKey: "selection"),
           let s = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data) {
            selection = s
        }
        authorized = AuthorizationCenter.shared.authorizationStatus == .approved
        refresh()
    }

    var isLocked: Bool { (lockEnd ?? .distantPast) > Date() }

    private func saveSelection() {
        if let data = try? JSONEncoder().encode(selection) {
            UserDefaults.standard.set(data, forKey: "selection")
        }
    }

    func requestAuthorization() async {
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            authorized = AuthorizationCenter.shared.authorizationStatus == .approved
            message = authorized ? "" : "허용되지 않았어요"
        } catch {
            message = "권한 요청에 실패했어요: \(error.localizedDescription)"
        }
    }

    /// 앱을 열 때마다: 끝날 시간이 지났으면 잠금을 푼다.
    func refresh() {
        authorized = AuthorizationCenter.shared.authorizationStatus == .approved
        if let e = lockEnd, e <= Date() { release() }
    }

    private func apply() {
        let apps = selection.applicationTokens
        if wholePhone {
            store.shield.applicationCategories = .all(except: apps)
            store.shield.applications = nil
        } else {
            store.shield.applications = apps.isEmpty ? nil : apps
            let cats = selection.categoryTokens
            store.shield.applicationCategories = cats.isEmpty ? nil : .specific(cats)
        }
    }

    /// 화면 시간 API는 15분 이상의 구간만 예약할 수 있어서 최소 15분이다.
    func lock(minutes: Int) {
        guard authorized else { message = "먼저 권한을 허용해 주세요"; return }
        if !wholePhone && selection.applicationTokens.isEmpty && selection.categoryTokens.isEmpty {
            message = "잠글 앱을 먼저 골라 주세요"
            return
        }
        let m = max(minutes, 15)
        let start = Date()
        let end = start.addingTimeInterval(Double(m) * 60)
        apply()
        let cal = Calendar.current
        let parts: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        let schedule = DeviceActivitySchedule(
            intervalStart: cal.dateComponents(parts, from: start),
            intervalEnd: cal.dateComponents(parts, from: end),
            repeats: false
        )
        do {
            try DeviceActivityCenter().startMonitoring(activity, during: schedule)
            message = ""
        } catch {
            message = "자동 해제 예약에 실패했어요. 앱을 열면 시간이 지난 잠금이 풀려요."
        }
        lockEnd = end
    }

    func release() {
        store.clearAllSettings()
        DeviceActivityCenter().stopMonitoring([activity])
        lockEnd = nil
    }
}
