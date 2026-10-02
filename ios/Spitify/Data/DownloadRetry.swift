import Foundation

enum DownloadRetry {
    static func delay(_ attempt: Int) -> TimeInterval {
        switch attempt { case ...1: return 2; case 2: return 5; case 3: return 15; case 4: return 60; default: return 300 }
    }
    static func isTemporary(_ error: Error) -> Bool {
        if case MusicSourceError.http(let status) = error { return status == 408 || status == 429 || status >= 500 }
        let e = error as NSError
        return e.domain == NSURLErrorDomain && [NSURLErrorTimedOut, NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost,
            NSURLErrorNetworkConnectionLost, NSURLErrorDNSLookupFailed, NSURLErrorNotConnectedToInternet,
            NSURLErrorBadServerResponse, NSURLErrorCannotParseResponse, NSURLErrorCannotDecodeRawData,
            NSURLErrorCannotDecodeContentData, NSURLErrorInternationalRoamingOff, NSURLErrorDataNotAllowed].contains(e.code)
    }
    static func legacyTemporaryFailure(_ text: String?) -> Bool {
        guard let text else { return false }
        return text == "Could not download this song. Please try again later." || text == "cannot parse response" ||
            text.contains("(521)") || text.contains("(502)") || text.contains("(503)") || text.contains("(504)")
    }
}
