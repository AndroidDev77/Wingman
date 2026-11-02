import Foundation

/// `connector_event.v1` envelopes for platform `imessage` (port of `envelope.py`, plus the
/// contract 1.3.0 inbound fields the spike predates).
///
/// Identities (stable across restarts, reinstalls and chat.db rebuilds: Apple GUIDs, never
/// ROWIDs):
/// * `external_message_id` = `imessage:<message.guid>`
/// * `external_thread_id`  = `imessage:chat:<chat.guid>` (keeps the `iMessage;`/`SMS;`/`any;`
///   prefix, so the transport stays visible)
/// * `external_contact_id` = `imessage:<normalized address>`, taken from the **1:1 chat GUID**
///   (`<service>;-;<address>`). Identity is linked only by the chat GUID: a 1:1 chat maps to
///   exactly one person, so the event is `identity: linked`.
/// * A **group** chat (`;+;`) maps one thread to several people, and contract v1.3.1 has no
///   group flag, so the backend could not keep group replies approval-only (D-024). Group
///   messages are therefore sent as `identity: ambiguous` (stored as an inbound notice, never
///   replied to) or skipped, per `GroupPolicy`. Proposal for the integrator: an `is_group`
///   payload field (docs/integrations/imessage_companion.md §9).
/// * `event_id` = UUIDv5(namespace, `user_id|type|key`), identical to the spike's.
public enum Envelope {
    public static let platform = "imessage"
    public static let eventNamespace = UUID(uuidString: "6f0e4a52-6d0b-5d4e-9a38-1f5d2c0e2002")!
    public static let maxText = 20_000
    /// U+FFFC, which Messages puts in `text` for each attachment.
    static let objectReplacement: Character = "\u{FFFC}"

    public static func externalMessageId(_ guid: String) -> String { "\(platform):\(guid)" }
    public static func externalThreadId(_ chatGuid: String) -> String { "\(platform):chat:\(chatGuid)" }

    public static func chatGuid(fromThreadId thread: String) -> String? {
        let prefix = "\(platform):chat:"
        guard thread.hasPrefix(prefix) else { return nil }
        let g = String(thread.dropFirst(prefix.count))
        return g.isEmpty ? nil : g
    }

    public static func normalizeHandle(_ handle: String) -> String {
        let h = handle.trimmingCharacters(in: .whitespacesAndNewlines)
        if h.contains("@") { return h.lowercased() }
        return String(h.filter { !" \t\n().-".contains($0) })
    }

    /// The address part of a 1:1 chat GUID (`iMessage;-;+15550100001` -> `+15550100001`).
    public static func oneToOneAddress(_ chatGuid: String) -> String? {
        let parts = chatGuid.split(separator: ";", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3, parts[1] == "-", !parts[2].isEmpty else { return nil }
        return String(parts[2])
    }

    /// `imessage` | `sms` | `rcs` | `unknown`, from the row's service or the GUID prefix.
    public static func transport(chatGuid: String?, service: String?) -> String {
        for value in [service, chatGuid?.split(separator: ";").first.map(String.init)] {
            switch (value ?? "").lowercased() {
            case "imessage": return "imessage"
            case "sms", "mms": return "sms"
            case "rcs": return "rcs"
            default: continue
            }
        }
        return "unknown"
    }

    public static func eventId(userId: String, type: String, key: String) -> String {
        uuid5(namespace: eventNamespace, name: "\(userId)|\(type)|\(key)").lower
    }

    public static func cleanText(_ text: String?) -> String {
        guard let text else { return "" }
        let t = String(text.filter { $0 != objectReplacement })
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String(t.prefix(maxText))
    }

    // MARK: classification

    public enum SkipReason: String, Sendable, Codable {
        case fromMe = "from_me", reaction, systemItem = "system_item", retracted
        case noContent = "no_content", noSender = "no_sender", noChat = "no_chat"
        case beforeBaseline = "before_baseline", noDate = "no_date", group = "group"
    }

    /// `nil` when the row becomes `message.received`. Same order as the spike.
    public static func classify(_ row: MessageRow, baseline: Date?) -> SkipReason? {
        if row.isFromMe { return .fromMe }
        if row.isReaction { return .reaction }
        if row.isSystemItem { return .systemItem }
        if row.retracted { return .retracted }
        guard let occurred = row.occurredAt else { return .noDate }
        if let baseline, occurred < baseline { return .beforeBaseline }
        if (row.senderHandle ?? "").isEmpty { return .noSender }
        if (row.chatGuid ?? "").isEmpty { return .noChat }
        if cleanText(row.text).isEmpty && row.attachmentCount == 0 { return .noContent }
        return nil
    }

    // MARK: envelopes

    static func base(_ scope: Scope, type: String, key: String, extId: String?, thread: String?,
                     occurredAt: Date, observedAt: Date, payload: JSONValue) -> JSONValue {
        [
            "schema_version": "connector_event.v1",
            "event_id": .string(eventId(userId: scope.userId, type: type, key: key)),
            "type": .string(type),
            "platform": .string(platform),
            "connection_id": .string(scope.connectionId),
            "user_id": .string(scope.userId),
            "external_message_id": extId.json,
            "external_thread_id": thread.json,
            "occurred_at": .string(TimeConv.contractTimestamp(occurredAt)),
            "observed_at": .string(TimeConv.contractTimestamp(observedAt)),
            "payload": payload,
        ]
    }

    /// `message.received`. The caller has checked `classify(row) == nil`. Returns `nil` for
    /// a group under `GroupPolicy.skip`, or a 1:1 GUID with no address.
    public static func received(_ row: MessageRow, scope: Scope, observedAt: Date,
                                groupPolicy: GroupPolicy = .notice) -> JSONValue? {
        guard let chatGuid = row.chatGuid, let handle = row.senderHandle, let occurred = row.occurredAt else {
            return nil
        }
        let extId = externalMessageId(row.guid)
        var payload: [String: JSONValue] = [
            "text": .string(cleanText(row.text)),
            "kind": "chat_message",
            "source": "companion",
        ]
        if row.isGroup {
            guard groupPolicy == .notice else { return nil }
            // The member who wrote it; never merged into a person (ambiguous -> notice).
            payload["sender"] = ["external_contact_id": .string("\(platform):\(normalizeHandle(handle))"),
                                 "display_name": .null]
            payload["identity"] = "ambiguous"
        } else {
            guard let address = oneToOneAddress(chatGuid) else { return nil }
            // Contacts access would be another TCC grant: names come from the backend.
            payload["sender"] = ["external_contact_id": .string("\(platform):\(normalizeHandle(address))"),
                                 "display_name": .null]
            payload["identity"] = "linked"
        }
        if row.attachmentCount > 0 { payload["attachment_count"] = .int(row.attachmentCount) }
        return base(scope, type: "message.received", key: extId, extId: extId,
                    thread: externalThreadId(chatGuid), occurredAt: occurred, observedAt: observedAt,
                    payload: .object(payload))
    }

    public static func sendAcknowledged(scope: Scope, sentGuid: String, chatGuid: String,
                                        outboundMessageId: String, idempotencyKey: String,
                                        occurredAt: Date, observedAt: Date) -> JSONValue {
        base(scope, type: "message.send_acknowledged", key: idempotencyKey,
             extId: externalMessageId(sentGuid), thread: externalThreadId(chatGuid),
             occurredAt: occurredAt, observedAt: observedAt,
             payload: ["outbound_message_id": .string(outboundMessageId),
                       "idempotency_key": .string(idempotencyKey)])
    }

    public static func sendFailed(scope: Scope, chatGuid: String?, outboundMessageId: String,
                                  idempotencyKey: String, code: FailureCode, retryable: Bool,
                                  detail: String?, occurredAt: Date, observedAt: Date) -> JSONValue {
        base(scope, type: "message.send_failed", key: idempotencyKey, extId: nil,
             thread: chatGuid.map(externalThreadId), occurredAt: occurredAt, observedAt: observedAt,
             payload: [
                "outbound_message_id": .string(outboundMessageId),
                "idempotency_key": .string(idempotencyKey),
                "failure": ["code": .string(code.rawValue), "retryable": .bool(retryable), "detail": detail.json],
             ])
    }

    public static func deliveryReceipt(scope: Scope, sentGuid: String, chatGuid: String,
                                       outboundMessageId: String, occurredAt: Date, observedAt: Date) -> JSONValue {
        let extId = externalMessageId(sentGuid)
        return base(scope, type: "message.delivery_receipt", key: extId, extId: extId,
                    thread: externalThreadId(chatGuid), occurredAt: occurredAt, observedAt: observedAt,
                    payload: ["outbound_message_id": .string(outboundMessageId)])
    }

    /// `connection.capabilities_changed`, e.g. after Full Disk Access or Automation was revoked.
    public static func capabilitiesChanged(scope: Scope, document: JSONValue, reason: String, at: Date) -> JSONValue {
        base(scope, type: "connection.capabilities_changed",
             key: "caps:\(reason):\(TimeConv.contractTimestamp(at))", extId: nil, thread: nil,
             occurredAt: at, observedAt: at, payload: ["capabilities": document])
    }
}

public struct Scope: Sendable, Equatable, Codable {
    public var userId: String
    public var connectionId: String
    public init(userId: String, connectionId: String) {
        self.userId = userId
        self.connectionId = connectionId
    }
}

/// What happens to messages in group chats (see `Envelope`).
public enum GroupPolicy: String, Sendable, Codable {
    /// Uploaded as `identity: ambiguous`: shown as an inbound notice, never replied to.
    case notice
    /// Not uploaded at all.
    case skip
}

/// `send_failed.failure.code` values the companion uses (contract `SendFailure.code`).
public enum FailureCode: String, Sendable, Codable {
    case capabilityUnsupported = "capability_unsupported"
    case permissionDenied = "permission_denied"
    case rejectedByPlatform = "rejected_by_platform"
    case notConnected = "not_connected"
    case timeout
    case transportError = "transport_error"
}
