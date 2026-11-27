// Minimal single-threaded Dispatch compatibility shim for wasm32-unknown-wasip1.
import Foundation

struct DispatchQoS: Sendable {
    static let background = DispatchQoS()
    static let utility = DispatchQoS()
    static let `default` = DispatchQoS()
    static let userInitiated = DispatchQoS()
    static let userInteractive = DispatchQoS()
    static let unspecified = DispatchQoS()
}

enum DispatchTimeInterval: Sendable {
    case seconds(Int)
    case milliseconds(Int)
    case microseconds(Int)
    case nanoseconds(Int)
    case never

    var nanosecondsValue: UInt64 {
        switch self {
        case .seconds(let v): return UInt64(max(0, v)) * 1_000_000_000
        case .milliseconds(let v): return UInt64(max(0, v)) * 1_000_000
        case .microseconds(let v): return UInt64(max(0, v)) * 1_000
        case .nanoseconds(let v): return UInt64(max(0, v))
        case .never: return UInt64.max
        }
    }
}

struct DispatchTime: Sendable {
    let uptimeNanoseconds: UInt64

    static func now() -> DispatchTime {
        let seconds = Date().timeIntervalSinceReferenceDate
        let ns = seconds > 0 ? UInt64(seconds * 1_000_000_000.0) : 0
        return DispatchTime(uptimeNanoseconds: ns)
    }

    static func + (lhs: DispatchTime, rhs: DispatchTimeInterval) -> DispatchTime {
        DispatchTime(uptimeNanoseconds: lhs.uptimeNanoseconds &+ rhs.nanosecondsValue)
    }
}

final class DispatchWorkItem: @unchecked Sendable {
    private var cancelled = false
    private let block: () -> Void

    init(block: @escaping () -> Void) {
        self.block = block
    }

    func cancel() {
        cancelled = true
    }

    func perform() {
        guard !cancelled else { return }
        block()
    }
}

final class DispatchQueue: @unchecked Sendable {
    init(label: String, qos: DispatchQoS = .default) {}

    func async(execute work: @escaping @Sendable () -> Void) {
        work()
    }

    func asyncAfter(deadline: DispatchTime, execute work: DispatchWorkItem) {
        work.perform()
    }
}

final class DispatchSemaphore: @unchecked Sendable {
    init(value: Int) {}

    @discardableResult
    func signal() -> Int { 0 }

    func wait() {}
}
