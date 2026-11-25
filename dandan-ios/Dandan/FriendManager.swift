import Foundation
import CryptoKit

struct Friend: Codable, Identifiable, Equatable {
    var name: String
    var pk: String          // 친구의 공개 열쇠(SPKI DER, base64url)
    var id: String { pk }
}

/// 친구 승인. 안드로이드 단단과 같은 규칙이라 같은 친구 페이지(friend.html)를 쓴다.
///  - 요청: d1|종류|이름|값|nonce|만료(초)|보낸 사람|사유|필요한 동의 수
///  - 친구가 링크에서 승인하면 ECDSA P-256 서명(r||s 64바이트)이 임시 주소(ntfy)로 온다.
@MainActor
final class FriendManager: ObservableObject {
    static let base = "https://nyj5121.github.io/Test-1/friend.html"
    static let maxFriends = 5
    static let ttl: TimeInterval = 24 * 3600

    @Published var friends: [Friend] = [] { didSet { save() } }
    @Published var myName: String { didSet { UserDefaults.standard.set(myName, forKey: "myName") } }
    @Published var quorum: Int { didSet { UserDefaults.standard.set(quorum, forKey: "quorum") } }
    @Published var pending: String? { didSet { UserDefaults.standard.set(pending, forKey: "pending") } }
    @Published var replyTopic: String { didSet { UserDefaults.standard.set(replyTopic, forKey: "replyTopic") } }
    @Published var approvedKeys: [String] { didSet { UserDefaults.standard.set(approvedKeys, forKey: "approvedKeys") } }
    @Published var inviteTopic: String?

    init() {
        let d = UserDefaults.standard
        myName = d.string(forKey: "myName") ?? "나"
        quorum = max(1, d.integer(forKey: "quorum"))
        pending = d.string(forKey: "pending")
        replyTopic = d.string(forKey: "replyTopic") ?? ""
        approvedKeys = d.stringArray(forKey: "approvedKeys") ?? []
        if let data = d.data(forKey: "friends"), let l = try? JSONDecoder().decode([Friend].self, from: data) {
            friends = l
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(friends) { UserDefaults.standard.set(data, forKey: "friends") }
    }

    var effectiveQuorum: Int { min(max(1, quorum), max(1, friends.count)) }

    // MARK: 인코딩
    static func b64uEncode(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func b64uDecode(_ s: String) -> Data? {
        var t = s.filter { !$0.isWhitespace }.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }

    private func clean(_ s: String, _ n: Int) -> String {
        String(s.replacingOccurrences(of: "|", with: " ").replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespaces).prefix(n))
    }

    static func newTopic() -> String {
        let chars = Array("abcdefghijklmnopqrstuvwxyz0123456789")
        return "dandan-r-" + String((0..<24).map { _ in chars.randomElement()! })
    }

    // MARK: 친구 등록
    func inviteURL(topic: String) -> URL? {
        let from = myName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return URL(string: Self.base + "#hello=1&from=\(from)&t=\(topic)")
    }

    @discardableResult
    func register(name: String, pkCode: String) -> Bool {
        guard let der = Self.b64uDecode(pkCode), (try? P256.Signing.PublicKey(derRepresentation: der)) != nil else { return false }
        let pk = Self.b64uEncode(der)
        let nm = clean(name, 40).isEmpty ? "친구" : clean(name, 40)
        if let i = friends.firstIndex(where: { $0.pk == pk }) { friends[i].name = nm; return true }
        if friends.count >= Self.maxFriends { return false }
        friends.append(Friend(name: nm, pk: pk))
        return true
    }

    /// 초대 링크를 받은 친구가 '친구 되어 주기'를 누르면 연결 코드가 임시 주소로 온다.
    func waitForInvite(topic: String, name: String) async -> Bool {
        for _ in 0..<120 {
            if Task.isCancelled { return false }
            for m in await Self.fetch(topic: topic) where register(name: name, pkCode: m) { return true }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
        return false
    }

    // MARK: 요청
    @discardableResult
    func newRequest(kind: String, label: String, reason: String) -> String {
        if pending != nil { Self.post(topic: replyTopic, text: "DONE") }   // 이전 링크는 닫는다
        var nonce = [UInt8](repeating: 0, count: 12)
        for i in nonce.indices { nonce[i] = UInt8.random(in: 0...255) }
        let exp = Int(Date().timeIntervalSince1970 + Self.ttl)
        let req = ["d1", kind, clean(label, 40), "0", Self.b64uEncode(Data(nonce)), String(exp),
                   clean(myName, 40), clean(reason, 120), String(effectiveQuorum)].joined(separator: "|")
        approvedKeys = []
        replyTopic = Self.newTopic()
        pending = req
        return req
    }

    func requestURL() -> URL? {
        guard let req = pending else { return nil }
        return URL(string: Self.base + "#r=" + Self.b64uEncode(Data(req.utf8)) + "&t=" + replyTopic)
    }

    func cancelRequest() {
        if pending != nil { Self.post(topic: replyTopic, text: "DONE") }
        pending = nil
        approvedKeys = []
    }

    private func need(_ req: String) -> Int {
        let n = Int(req.split(separator: "|", omittingEmptySubsequences: false).dropFirst(8).first ?? "1") ?? 1
        return min(max(1, n), max(1, friends.count))
    }

    private func validSignature(pkB64: String, message: String, sigB64: String) -> Bool {
        guard let der = Self.b64uDecode(pkB64), let raw = Self.b64uDecode(sigB64), raw.count == 64,
              let pk = try? P256.Signing.PublicKey(derRepresentation: der),
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: raw) else { return false }
        return pk.isValidSignature(sig, for: Data(message.utf8))
    }

    /// 코드가 걸어 둔 요청에 대한 친구의 서명이면 동의로 센다. 필요한 수가 모이면 true.
    func verify(_ code: String) -> Bool {
        guard let req = pending else { return false }
        let parts = req.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count >= 6, let exp = TimeInterval(parts[5]), Date().timeIntervalSince1970 <= exp else { return false }
        guard let who = friends.first(where: { validSignature(pkB64: $0.pk, message: req, sigB64: code) }) else { return false }
        if !approvedKeys.contains(who.pk) { approvedKeys.append(who.pk) }
        let have = Set(approvedKeys).intersection(friends.map { $0.pk }).count
        if have < need(req) { return false }
        Self.post(topic: replyTopic, text: "DONE")
        pending = nil
        approvedKeys = []
        return true
    }

    /// 승인이 모일 때까지 6초마다 확인한다. 승인되면 true, 취소·만료·화면 닫힘이면 false.
    func waitForApproval() async -> Bool {
        while pending != nil {
            if Task.isCancelled { return false }
            for m in await Self.fetch(topic: replyTopic) where verify(m) { return true }
            try? await Task.sleep(nanoseconds: 6_000_000_000)
        }
        return false
    }

    // MARK: 임시 주소(ntfy)
    nonisolated static func fetch(topic: String) async -> [String] {
        guard !topic.isEmpty, let url = URL(string: "https://ntfy.sh/\(topic)/json?poll=1&since=all"),
              let (data, resp) = try? await URLSession.shared.data(from: url),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return [] }
        return String(decoding: data, as: UTF8.self).split(separator: "\n").compactMap { line in
            guard let o = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
                  o["event"] as? String == "message", let m = o["message"] as? String else { return nil }
            return m.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    nonisolated static func post(topic: String, text: String) {
        guard !topic.isEmpty, let url = URL(string: "https://ntfy.sh/\(topic)") else { return }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.httpBody = Data(text.utf8)
        URLSession.shared.dataTask(with: r).resume()
    }
}
