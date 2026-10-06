import AVFoundation

/// Converts audio the iPhone can read (FLAC in practice) to AAC 256 kbit/s in an .m4a.
/// Hi-res sources are resampled to 44.1 or 48 kHz first, since AAC doesn't take higher rates.
enum AacConverter {
    static let label = "AAC 256 kbps"

    enum Failure: Error { case unsupported(String) }
    private final class Finished: @unchecked Sendable { var value = false }

    static func convert(_ source: URL, to destination: URL) async throws {
        let asset = AVURLAsset(url: source)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else { throw Failure.unsupported("No audio in this file.") }
        let description = try await track.load(.formatDescriptions).first
        let basic = description.flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee }
        let inRate = basic?.mSampleRate ?? 44_100
        let channels = Int(basic?.mChannelsPerFrame ?? 2)
        guard (1...2).contains(channels) else { throw Failure.unsupported("Only mono and stereo files are converted.") }
        let outRate: Double = inRate <= 48_000 ? inRate : (inRate.truncatingRemainder(dividingBy: 44_100) == 0 ? 44_100 : 48_000)

        try? FileManager.default.removeItem(at: destination)
        let reader = try AVAssetReader(asset: asset)
        // The reader decodes and resamples (Apple's high-quality converter) to float PCM.
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
            AVSampleRateKey: outRate, AVNumberOfChannelsKey: channels,
        ])
        reader.add(output)
        let writer = try AVAssetWriter(outputURL: destination, fileType: .m4a)
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: outRate, AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: 256_000,
        ])
        input.expectsMediaDataInRealTime = false
        writer.add(input)
        guard reader.startReading(), writer.startWriting() else { throw reader.error ?? writer.error ?? Failure.unsupported("Couldn't start converting.") }
        writer.startSession(atSourceTime: .zero)
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            let queue = DispatchQueue(label: "spitify.aac")
            let state = Finished()
            input.requestMediaDataWhenReady(on: queue) {
                while input.isReadyForMoreMediaData && !state.value {
                    if let buffer = output.copyNextSampleBuffer() { input.append(buffer) }
                    else { state.value = true; input.markAsFinished(); done.resume() }
                }
            }
        }
        if reader.status == .failed { writer.cancelWriting(); throw reader.error ?? Failure.unsupported("Couldn't read the file.") }
        await writer.finishWriting()
        guard writer.status == .completed else { throw writer.error ?? Failure.unsupported("Couldn't write the AAC file.") }
    }
}
