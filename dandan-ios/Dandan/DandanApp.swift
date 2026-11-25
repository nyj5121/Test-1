import SwiftUI

@main
struct DandanApp: App {
    @StateObject private var lock = LockManager()
    @StateObject private var friends = FriendManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(lock)
                .environmentObject(friends)
                .preferredColorScheme(.dark)
        }
    }
}
