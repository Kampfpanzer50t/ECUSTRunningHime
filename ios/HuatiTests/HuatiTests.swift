import XCTest
@testable import Huati

@MainActor final class HuatiTests: XCTestCase {
    func testBundledBridgeAndCampusResources() throws {
        let core = try CoreBridge()
        XCTAssertFalse(core.map.isEmpty)
        XCTAssertFalse(core.graph.isEmpty)
        XCTAssertEqual(try core.number("randomDuration", [0]), 720001)
        XCTAssertEqual(try core.number("randomDuration", [0.999999999]), 959999)
        for point in AppModel.referencePoints {
            let wgs = try core.object("wgs", [point])
            XCTAssertTrue(try core.flag("contains", [wgs, core.boundary]))
        }
    }
    func testAndroidCompatibleEncryption() throws {
        let encrypted = try HuatiAPI.encrypt(["fixture": 1], iv: Data(count: 16))
        XCTAssertEqual(encrypted, "AAAAAAAAAAAAAAAAAAAAAA==:BGu00rSpvgFKuj16y9KktQ==")
        XCTAssertNotEqual(try HuatiAPI.encrypt(["fixture": 1]), encrypted)
    }
    func testManualDurationFieldsAndInvalidOriginRecovery() throws {
        let core = try CoreBridge()
        XCTAssertEqual(try core.number("manualDuration", ["15", "12", "345"]), 912345)
        XCTAssertThrowsError(try core.number("manualDuration", ["15", "60", "0"]))
        XCTAssertThrowsError(try core.number("manualDuration", ["120", "0", "1"]))
        let origin = try core.object("safeOrigin", [["lat": 31.2, "lng": 121.5], core.boundary, 91, core.graph])
        XCTAssertTrue(try core.flag("validOrigin", [origin, core.boundary]))
        let preserved = try core.object("safeOrigin", [origin, core.boundary, 2, core.graph])
        XCTAssertEqual(preserved.number("lat"), origin.number("lat"))
        XCTAssertEqual(preserved.number("lng"), origin.number("lng"))
        XCTAssertEqual(Clock.duration(912345), "15:12.345")
    }
    func testSessionHeaderValidation() throws {
        XCTAssertThrowsError(try HuatiAPI.clean(["X-Session-ID": "1111"]))
        XCTAssertThrowsError(try HuatiAPI.clean(["X-Session-ID": "test\r\nheader"]))
        XCTAssertThrowsError(try HuatiAPI.clean(["X-Session-ID": "test\rheader"]))
        XCTAssertThrowsError(try HuatiAPI.clean(["X-Session-ID": "test\nheader"]))
        let headers = try HuatiAPI.clean(["X-Session-ID": "fixture-session", "Cookie": "excluded"])
        XCTAssertEqual(headers["x-session-id"], "fixture-session")
        XCTAssertNil(headers["cookie"])
    }
    func testEncryptedStoreRoundTripAndAuthenticatedName() throws {
        let store = try SecureStore()
        let name = "unit-test-" + UUID().uuidString
        defer { try? store.remove(name) }
        try store.write(name, ["fixture": 42])
        XCTAssertEqual(try store.read(name)?.number("fixture"), 42)
    }
    func testPauseAndContinueRetainExactDuration() throws {
        let core = try CoreBridge()
        let initial: JSON = ["status": "created", "start_ms": 100000, "duration_ms": 720123, "pauses": [JSON](), "auto_submit": true]
        let paused = try core.object("pause", [initial, 200000])
        XCTAssertEqual(try core.number("remaining", [paused, 900000]), 620123)
        let resumed = try core.object("resume", [paused, 900000])
        XCTAssertEqual(resumed.number("duration_ms"), 720123)
        XCTAssertEqual(try core.number("remaining", [resumed, 1000000]), 520123)
    }
    func testBackgroundWakePreservesPreparationAndFinishMilliseconds() throws {
        let core = try CoreBridge()
        let target = DateComponents(calendar: Calendar(identifier: .gregorian), timeZone: TimeZone(secondsFromGMT: 28800), year: 2026, month: 10, day: 13, hour: 6, minute: 15).date!.timeIntervalSince1970 * 1000
        let schedule: JSON = ["enabled": true, "hour": 6, "minute": 15]
        XCTAssertEqual(try core.number("wake", [schedule, NSNull(), target - 180000]), target - 120000)
        XCTAssertTrue(try core.flag("dailyDue", [schedule, target + 3600000, false]))
        let run: JSON = ["status": "created", "auto_submit": true, "start_ms": 100000, "duration_ms": 720123, "pauses": [JSON]()]
        XCTAssertEqual(try core.number("wake", [["enabled": false], run, 200000]), 820123)
    }
    func testCompanionRequiresHTTPSAndRejectsCredentialsInURL() throws {
        let token = String(repeating: "x", count: 43)
        XCTAssertThrowsError(try CompanionClient(["endpoint": "http://example.com", "token": token]))
        XCTAssertThrowsError(try CompanionClient(["endpoint": "https://user:password@example.com", "token": token]))
        XCTAssertThrowsError(try CompanionClient(["endpoint": "https://example.com/path", "token": token]))
        XCTAssertNoThrow(try CompanionClient(["endpoint": "https://personal.example.com", "token": token]))
    }
}
