import Foundation

/// The one Apple-event send the companion can make (port of `applescript.py`).
///
/// **No message content is ever spliced into AppleScript source.** The script is a
/// constant, compiled once by the app (`NSAppleScript`, in-process, so the Automation grant
/// belongs to the app's own signing identity). The chat GUID and the text reach its
/// `on run argv` handler only as a list descriptor of two strings. Neither a shell nor the
/// AppleScript compiler ever parses user text.
///
/// Only sending into an EXISTING chat (`chat id`) is supported. Whether Messages accepts it
/// on a given macOS version is UNVERIFIED (manual M8 to M12).
public enum AppleScriptCommand {
    public static let maxTextChars = 4000  // OutboxInstruction.text maxLength

    /// Constant source. Do not format user data into it.
    public static let sendToChatSource = """
        on run argv
        if (count of argv) is not 2 then error "wingman: expected 2 arguments" number 1001
        set chatGuid to item 1 of argv
        set messageText to item 2 of argv
        tell application "Messages"
        with timeout of 30 seconds
        set targetChat to chat id chatGuid
        send messageText to targetChat
        end timeout
        end tell
        return "ok"
        end run
        """

    public enum ValidationError: Error, Equatable, Sendable {
        case badChatGuid, emptyText, textTooLong, nulByte, controlCharacter, loneSurrogate
    }

    /// `<service>;-;<address>` (1:1) or `<service>;+;<id>` (group); `any` is reported for macOS 26.
    public static func validateChatGuid(_ guid: String) throws -> String {
        let parts = guid.split(separator: ";", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3,
              ["iMessage", "SMS", "RCS", "any"].contains(String(parts[0])),
              parts[1] == "-" || parts[1] == "+"
        else { throw ValidationError.badChatGuid }
        let rest = parts[2].unicodeScalars
        let allowed = "@._+%:-"
        guard (1...200).contains(rest.count),
              rest.allSatisfy({ ($0.isASCII && (CharacterSet.alphanumerics.contains($0))) || allowed.unicodeScalars.contains($0) })
        else { throw ValidationError.badChatGuid }
        return guid
    }

    public static func validateText(_ text: String) throws -> String {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw ValidationError.emptyText }
        // Python counts code points; so do we (the contract's maxLength is JSON Schema code points).
        if text.unicodeScalars.count > maxTextChars { throw ValidationError.textTooLong }
        for s in text.unicodeScalars {
            if s.value == 0 { throw ValidationError.nulByte }
            if (s.value < 0x20 || s.value == 0x7F) && !["\n", "\r", "\t"].contains(Character(s)) {
                throw ValidationError.controlCharacter
            }
        }
        // A Swift String cannot hold a lone surrogate (it is repaired on decode); the JSON
        // decoder already replaced any. Kept as a case for parity with the spike.
        return text
    }

    /// A validated send. `describe()` is the only form that may be logged.
    public struct SendCommand: Sendable, Equatable {
        public let chatGuid: String
        public let text: String
        public var isGroup: Bool { chatGuid.contains(";+;") }
        /// The run handler's argv, in order.
        public var arguments: [String] { [chatGuid, text] }

        /// Redacted: never the text or the address.
        public func describe() -> String {
            let service = chatGuid.split(separator: ";").first.map(String.init) ?? "?"
            return "applescript send (\(service), \(isGroup ? "group" : "1:1"), \(text.unicodeScalars.count) chars)"
        }
    }

    public static func build(chatGuid: String, text: String) throws -> SendCommand {
        SendCommand(chatGuid: try validateChatGuid(chatGuid), text: try validateText(text))
    }

    /// Text literal for the MANUAL "paste into Script Editor" test only.
    public static func stringLiteral(_ value: String) throws -> String {
        var out = "\""
        for s in value.unicodeScalars {
            switch s {
            case "\\": out += "\\\\"
            case "\"": out += "\\\""
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if s.value < 0x20 || s.value == 0x7F { throw ValidationError.controlCharacter }
                out.unicodeScalars.append(s)
            }
        }
        return out + "\""
    }
}

/// Result of one Apple-event send, as the app's transport reports it.
public struct ExecResult: Sendable, Equatable {
    /// 0 when the script returned normally ("script success": NOT an acknowledgement).
    public var exitCode: Int
    /// The AppleScript/Apple-event error number (`NSAppleScript.errorNumber`).
    public var appleEventError: Int?
    public init(exitCode: Int, appleEventError: Int? = nil) {
        self.exitCode = exitCode
        self.appleEventError = appleEventError
    }
}

/// The Mac side: runs the constant script with the command's arguments. Implemented by the
/// app with `NSAppleScript`; tests use a fake that writes the "sent" row into a fixture.
public protocol MessagesTransport: Sendable {
    func send(_ command: AppleScriptCommand.SendCommand) async throws -> ExecResult
}
