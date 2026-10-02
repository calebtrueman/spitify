import AVFoundation
import CryptoKit
import MediaPlayer
import UIKit

/// Makes Apple's portrait artwork asset locally, without changing the music file.
@available(iOS 26.0, *)
enum LockScreenArtwork {
    private static let work = DispatchQueue(label: "spitify.lock-screen-art", qos: .utility)

    static func artwork(image: UIImage) -> MPMediaItemAnimatedArtwork? {
        guard let data = image.jpegData(compressionQuality: 0.9) else { return nil }
        let key = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let size = CGSize(width: 720, height: 960)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let poster = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.black.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            let fill = max(size.width / image.size.width, size.height / image.size.height)
            let background = CGSize(width: image.size.width * fill, height: image.size.height * fill)
            image.draw(in: CGRect(x: (size.width-background.width)/2, y: (size.height-background.height)/2, width: background.width, height: background.height))
            UIColor.black.withAlphaComponent(0.55).setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            let fit = min(size.width / image.size.width, size.height / image.size.height)
            let cover = CGSize(width: image.size.width * fit, height: image.size.height * fit)
            image.draw(in: CGRect(x: (size.width-cover.width)/2, y: (size.height-cover.height)/2, width: cover.width, height: cover.height))
        }
        return MPMediaItemAnimatedArtwork(artworkID: "portrait-v1-" + key, previewImageRequestHandler: { _, completion in
            completion(poster)
        }, videoAssetFileURLRequestHandler: { _, completion in
            work.async { completion(makeVideo(poster, key: key)) }
        })
    }

    static func makeVideo(_ image: UIImage, key: String) -> URL? {
        let fm = FileManager.default
        let folder = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("LockScreenArt", isDirectory: true)
        do {
            try fm.createDirectory(at: folder, withIntermediateDirectories: true)
            let url = folder.appendingPathComponent(key + ".mp4")
            if fm.fileExists(atPath: url.path) { return url }
            // Keep recent covers. The active asset stays alive for the system's requests.
            let old = (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
            for file in old {
                if let date = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                   date < Date().addingTimeInterval(-7 * 86400) { try? fm.removeItem(at: file) }
            }
            let temporary = folder.appendingPathComponent(UUID().uuidString + ".mp4")
            defer { try? fm.removeItem(at: temporary) }
            let writer = try AVAssetWriter(outputURL: temporary, fileType: .mp4)
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 720, AVVideoHeightKey: 960])
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB, kCVPixelBufferWidthKey as String: 720, kCVPixelBufferHeightKey as String: 960])
            guard writer.canAdd(input) else { return nil }
            writer.add(input)
            guard writer.startWriting() else { return nil }
            writer.startSession(atSourceTime: .zero)
            var buffer: CVPixelBuffer?
            guard CVPixelBufferCreate(kCFAllocatorDefault, 720, 960, kCVPixelFormatType_32ARGB, [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary, &buffer) == kCVReturnSuccess,
                  let buffer, let cg = image.cgImage else { writer.cancelWriting(); return nil }
            CVPixelBufferLockBaseAddress(buffer, [])
            guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: 720, height: 960, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue) else {
                CVPixelBufferUnlockBaseAddress(buffer, []); writer.cancelWriting(); return nil
            }
            context.draw(cg, in: CGRect(x: 0, y: 0, width: 720, height: 960))
            CVPixelBufferUnlockBaseAddress(buffer, [])
            let deadline = Date().addingTimeInterval(8)
            for frame in 0..<60 {
                while !input.isReadyForMoreMediaData && writer.status == .writing && Date() < deadline { Thread.sleep(forTimeInterval: 0.005) }
                guard input.isReadyForMoreMediaData, adaptor.append(buffer, withPresentationTime: CMTime(value: Int64(frame), timescale: 30)) else { writer.cancelWriting(); return nil }
            }
            input.markAsFinished()
            let done = DispatchSemaphore(value: 0)
            writer.finishWriting { done.signal() }
            guard done.wait(timeout: .now() + 10) == .success, writer.status == .completed else { writer.cancelWriting(); return nil }
            try fm.moveItem(at: temporary, to: url)
            return url
        } catch { return nil }
    }
}
