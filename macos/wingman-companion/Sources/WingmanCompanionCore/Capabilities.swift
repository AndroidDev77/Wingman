import Foundation

/// The `connector_capabilities.v1` document the companion claims at pairing and in
/// `connection.capabilities_changed`. Nothing is VERIFIED: no Mac has run this code, and the
/// server stores a client `VERIFIED` as `UNVERIFIED` anyway (ADR 0007). Only the operator
/// CLI, with a recorded real-Mac run as evidence, can verify a capability.
public enum Capabilities {
    public static let connectorVersion = "0.1.0"

    /// - Parameters:
    ///   - fullDiskAccess: `false` once opening chat.db failed (read -> UNSUPPORTED).
    ///   - automationGranted: `false` after an Apple event returned -1743 (send -> UNSUPPORTED).
    ///   - draftOnly: the user (or the D-024 NO-GO for send) chose draft-only mode: send is
    ///     UNSUPPORTED, so the backend produces assisted deliveries instead of instructions.
    public static func document(assessedAt: Date, fullDiskAccess: Bool? = nil,
                                automationGranted: Bool? = nil, draftOnly: Bool = false) -> JSONValue {
        var read: JSONValue = [
            "status": "UNVERIFIED",
            "notes": "Reads ~/Library/Messages/chat.db read-only; needs Full Disk Access. Not tested on a Mac (WING-030).",
        ]
        var send: JSONValue = [
            "status": "UNVERIFIED",
            "reports_delivery_receipts": false,
            "notes": "Messages AppleScript 'send' into an existing 1:1 chat; needs Automation consent. Ack only after the sent row appears in chat.db. Not tested on a Mac (WING-030).",
        ]
        if fullDiskAccess == false {
            read = ["status": "UNSUPPORTED", "notes": "Full Disk Access not granted."]
        }
        if automationGranted == false {
            send = ["status": "UNSUPPORTED", "notes": "Automation permission for Messages denied."]
        } else if draftOnly {
            send = ["status": "UNSUPPORTED", "notes": "Draft-only mode: the user sends by hand (assisted delivery)."]
        }
        return [
            "schema_version": "connector_capabilities.v1",
            "platform": .string(Envelope.platform),
            "connector_version": .string(connectorVersion),
            "capabilities": [
                "read": read,
                "draft": ["status": "UNSUPPORTED",
                          "notes": "Messages has no scriptable compose field; GUI scripting is out of scope."],
                "send": send,
                "swipe": ["status": "UNSUPPORTED"],
                "calendar_write": ["status": "UNSUPPORTED"],
                "profile_read": ["status": "UNSUPPORTED"],
            ],
            "assessed_at": .string(TimeConv.contractTimestamp(assessedAt)),
        ]
    }
}
