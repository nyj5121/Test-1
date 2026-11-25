import DeviceActivity
import ManagedSettings

/// 잠금 시간이 끝나면(앱이 꺼져 있어도) 시스템이 불러 줘서 잠금을 푼다.
class DandanMonitor: DeviceActivityMonitor {
    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        if activity.rawValue == "lock" {
            ManagedSettingsStore(named: .init("lock")).clearAllSettings()
        }
    }
}
