import SwiftUI
import FamilyControls

struct ContentView: View {
    @EnvironmentObject var lock: LockManager
    @Environment(\.scenePhase) private var phase

    var body: some View {
        TabView {
            LockView().tabItem { Label("잠금", systemImage: "lock.fill") }
            FriendsView().tabItem { Label("친구", systemImage: "person.2.fill") }
            AboutView().tabItem { Label("안내", systemImage: "info.circle") }
        }
        .onChange(of: phase) { p in if p == .active { lock.refresh() } }
    }
}

struct LockView: View {
    @EnvironmentObject var lock: LockManager
    @EnvironmentObject var friends: FriendManager
    @State private var pickerOn = false
    @State private var minutes = 60
    @State private var askOn = false
    private let options = [15, 30, 60, 120, 240, 480]

    var body: some View {
        NavigationStack {
            List {
                if lock.isLocked, let end = lock.lockEnd {
                    Section {
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in
                            let left = max(0, Int(end.timeIntervalSince(ctx.date)))
                            VStack(spacing: 6) {
                                Text("잠겨 있어요").font(.headline)
                                Text(String(format: "%d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60))
                                    .font(.system(size: 44, weight: .light, design: .rounded)).monospacedDigit()
                            }.frame(maxWidth: .infinity)
                        }
                    }
                    Section {
                        if friends.friends.isEmpty {
                            Text("등록된 친구가 없어서 시간이 끝날 때까지 기다려야 해요.").font(.footnote)
                        } else {
                            Button("친구에게 해제 요청") { askOn = true }
                        }
                    }
                } else {
                    if !lock.authorized {
                        Section {
                            Button("화면 시간 권한 허용") { Task { await lock.requestAuthorization() } }
                        } footer: { Text("앱을 막으려면 iOS의 '화면 시간' 권한이 필요해요.") }
                    }
                    Section {
                        Toggle("폰 전체 잠금", isOn: $lock.wholePhone)
                        Button(lock.wholePhone ? "허용할 앱 고르기" : "잠글 앱 고르기") { pickerOn = true }
                        Text(summary).font(.footnote).foregroundStyle(.secondary)
                    } footer: { Text("전체 잠금은 고른 앱만 빼고 모두 막아요. 전화·메시지·설정 같은 일부 시스템 앱은 막을 수 없어요.") }
                    Section("잠글 시간") {
                        Picker("시간", selection: $minutes) {
                            ForEach(options, id: \.self) { m in Text(m < 60 ? "\(m)분" : "\(m / 60)시간").tag(m) }
                        }.pickerStyle(.segmented)
                        Button("지금 잠그기") { lock.lock(minutes: minutes) }.disabled(!lock.authorized)
                    }
                }
                if !lock.message.isEmpty { Section { Text(lock.message).foregroundStyle(.red) } }
            }
            .navigationTitle("단단")
            .familyActivityPicker(isPresented: $pickerOn, selection: $lock.selection)
            .sheet(isPresented: $askOn) { RequestView() }
        }
    }

    private var summary: String {
        let a = lock.selection.applicationTokens.count, c = lock.selection.categoryTokens.count
        return a + c == 0 ? "고른 앱이 없어요" : "앱 \(a)개 · 묶음 \(c)개 고름"
    }
}

struct RequestView: View {
    @EnvironmentObject var lock: LockManager
    @EnvironmentObject var friends: FriendManager
    @Environment(\.dismiss) private var dismiss
    @State private var reason = ""

    var body: some View {
        NavigationStack {
            Form {
                if friends.pending != nil, let url = friends.requestURL() {
                    Section {
                        Text("친구 \(friends.effectiveQuorum)명이 승인하면 풀려요. 지금 \(Set(friends.approvedKeys).count)명.")
                        ShareLink(item: url, message: Text("[단단] \(friends.myName)의 요청: 폰 잠금 해제\n사유: \(reason)\n승인해 주세요 ↓")) {
                            Label("친구에게 링크 보내기", systemImage: "square.and.arrow.up")
                        }
                        Button("요청 취소", role: .destructive) { friends.cancelRequest(); dismiss() }
                    } footer: { Text("친구가 링크에서 승인하면 자동으로 풀려요. 이 창은 닫아도 되지만, 앱을 한 번 열어야 확인해요. 요청은 24시간 동안 유효해요.") }
                } else {
                    Section("사유 (친구에게 그대로 보여요)") {
                        TextField("왜 풀어야 하나요?", text: $reason, axis: .vertical)
                        Button("요청 만들기") { friends.newRequest(kind: "phone_unlock", label: "폰 잠금", reason: reason) }
                            .disabled(reason.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
            .navigationTitle("해제 요청")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { dismiss() } } }
            .task(id: friends.pending) {
                if friends.pending != nil, await friends.waitForApproval() {
                    lock.release()
                    dismiss()
                }
            }
        }
    }
}

struct FriendsView: View {
    @EnvironmentObject var lock: LockManager
    @EnvironmentObject var friends: FriendManager
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Section("내 이름 (친구에게 보여요)") { TextField("이름", text: $friends.myName) }
                Section("친구 \(friends.friends.count)/\(FriendManager.maxFriends)") {
                    ForEach(friends.friends) { f in Text(f.name) }
                        .onDelete { idx in
                            if !lock.isLocked { friends.friends.remove(atOffsets: idx) }
                        }
                    if lock.isLocked { Text("잠겨 있는 동안은 친구를 바꿀 수 없어요.").font(.footnote).foregroundStyle(.secondary) }
                }
                if friends.friends.count >= 2 {
                    Section {
                        Stepper("필요한 동의 수: \(friends.effectiveQuorum)명", value: $friends.quorum, in: 1...friends.friends.count)
                            .disabled(lock.isLocked)
                    } footer: { Text("2명 이상으로 하면 그만큼의 친구가 모두 승인해야 풀려요.") }
                }
                if !lock.isLocked && friends.friends.count < FriendManager.maxFriends {
                    Section("친구 추가") {
                        TextField("친구 이름", text: $newName)
                        if let topic = friends.inviteTopic, let url = friends.inviteURL(topic: topic) {
                            ShareLink(item: url, message: Text("[단단] \(friends.myName)이(가) 너를 친구로 지정하고 싶대. 링크를 열어서 '친구 되어 주기'만 눌러 줘.")) {
                                Label("초대 링크 보내기", systemImage: "square.and.arrow.up")
                            }
                            Text("친구가 링크에서 '친구 되어 주기'를 누르면 자동으로 등록돼요. 이 화면을 열어 두세요.").font(.footnote)
                        } else {
                            Button("초대 링크 만들기") { friends.inviteTopic = FriendManager.newTopic() }
                                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                    }
                }
            }
            .navigationTitle("친구")
            .task(id: friends.inviteTopic) {
                if let t = friends.inviteTopic {
                    if await friends.waitForInvite(topic: t, name: newName) { newName = "" }
                    friends.inviteTopic = nil
                }
            }
        }
    }
}

struct AboutView: View {
    var body: some View {
        NavigationStack {
            List {
                Section("할 수 있는 것") {
                    Text("고른 앱 또는 폰 전체를 정한 시간 동안 잠가요. 시간이 끝나면 앱이 꺼져 있어도 저절로 풀려요.")
                    Text("친구 최대 5명을 등록하고, 필요한 동의 수(예: 2명)를 정해 잠금을 풀 수 있어요. 요청은 모두에게 같은 링크로 가요.")
                }
                Section("안드로이드 단단과 다른 점") {
                    Text("iOS는 다른 앱이 설정 화면이나 알림창을 막는 걸 허용하지 않아요. 그래서 설정 앱 차단, 앱 삭제 방지, 인스타 일부 가리기는 못 해요.")
                    Text("화면 시간 권한을 끄거나 이 앱을 지우면 잠금이 풀려요. 정말 풀리지 않게 하려면 iOS 기본 '화면 시간' 암호를 친구가 정해 주는 방법을 같이 쓰세요.")
                }
            }
            .navigationTitle("안내")
        }
    }
}
