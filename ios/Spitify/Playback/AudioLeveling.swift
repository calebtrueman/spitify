import AVFoundation
import MediaToolbox

/// Attenuates loud recordings. Quiet recordings and the phone's volume setting are never boosted.
enum AudioLeveling {
    static let targetRMS: Double = 0.126 // About -18 dBFS; deliberately leave room for peaks.
    static func gain(rms: Double, peak: Double) -> Float {
        guard rms.isFinite, peak.isFinite, rms > 0, peak > 0 else { return 1 }
        return Float(max(0, min(1, targetRMS / rms, 0.98 / peak)))
    }

    static func fileGain(_ source: AVAudioFile) -> Float {
        guard let file = try? AVAudioFile(forReading: source.url) else { return 1 }
        let format = file.processingFormat
        guard format.commonFormat == .pcmFormatFloat32, format.sampleRate > 0, file.length > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 16_384) else { return 1 }
        let originalFrame = file.framePosition
        defer { file.framePosition = originalFrame }
        let window = min(file.length, AVAudioFramePosition(format.sampleRate * 6))
        let offsets = Set([AVAudioFramePosition(0), max(0, file.length / 2 - window / 2), max(0, file.length - window)]).sorted()
        var total = 0.0, peak = 0.0, samples = 0
        do {
            for offset in offsets {
                file.framePosition = offset
                var left = window
                while left > 0 {
                    try file.read(into: buffer, frameCount: AVAudioFrameCount(min(left, 16_384)))
                    guard buffer.frameLength > 0, let channels = buffer.floatChannelData else { break }
                    let frames = Int(buffer.frameLength), channelCount = Int(format.channelCount)
                    if format.isInterleaved {
                        for i in 0..<(frames * channelCount) { let v = Double(channels[0][i]); guard v.isFinite else { continue }; total += v * v; peak = max(peak, abs(v)); samples += 1 }
                    } else {
                        for channel in 0..<channelCount {
                            for i in 0..<frames { let v = Double(channels[channel][i]); guard v.isFinite else { continue }; total += v * v; peak = max(peak, abs(v)); samples += 1 }
                        }
                    }
                    left -= AVAudioFramePosition(frames)
                }
            }
        } catch { return 1 }
        return samples > 0 ? gain(rms: sqrt(total / Double(samples)), peak: peak) : 1
    }
}

/// AVPlayer's decoded samples pass through this tap before reaching any output device.
/// Gain only falls within a song, so a quiet passage cannot cause a sudden volume rise.
final class StreamLeveling {
    private var supported = false
    private var gain: Float = 1
    private var target: Float = 1
    private var energy = 0.0
    private var samples: Int64 = 0
    private var sampleRate = 44_100.0

    static func makeTap() -> MTAudioProcessingTap? {
        let state = Unmanaged.passRetained(StreamLeveling())
        var callbacks = MTAudioProcessingTapCallbacks(version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: state.toOpaque(), init: { _, info, storage in storage.pointee = info },
            finalize: { tap in Unmanaged<StreamLeveling>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release() },
            prepare: { tap, _, format in
                let state = Unmanaged<StreamLeveling>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
                let f = format.pointee
                state.supported = f.mFormatID == kAudioFormatLinearPCM && f.mFormatFlags & kAudioFormatFlagIsFloat != 0 && f.mBitsPerChannel == 32
                state.sampleRate = max(1, f.mSampleRate)
            }, unprepare: { _ in }, process: { tap, frames, _, list, count, flags in
                let status = MTAudioProcessingTapGetSourceAudio(tap, frames, list, flags, nil, count)
                guard status == noErr else { count.pointee = 0; return }
                let state = Unmanaged<StreamLeveling>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
                state.process(list)
            })
        var tap: MTAudioProcessingTap?
        let result = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap)
        guard result == noErr else { state.release(); return nil }
        return tap
    }

    private func process(_ list: UnsafeMutablePointer<AudioBufferList>) {
        guard supported else { return }
        var blockEnergy = 0.0, peak = 0.0, count = 0
        for buffer in UnsafeMutableAudioBufferListPointer(list) {
            guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let size = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
            for i in 0..<size {
                let value = data[i].isFinite ? Double(data[i]) : 0
                blockEnergy += value * value; peak = max(peak, abs(value)); count += 1
            }
        }
        guard count > 0 else { return }
        // Skip silence while measuring a song's loudness.
        if blockEnergy / Double(count) > 0.000_001 {
            energy += blockEnergy; samples += Int64(count)
            target = min(target, AudioLeveling.gain(rms: sqrt(energy / Double(samples)), peak: peak))
        }
        let startingGain = gain
        let step = Float(1 / max(1, sampleRate * 0.02))
        for buffer in UnsafeMutableAudioBufferListPointer(list) {
            guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let size = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
            var channelGain = startingGain
            let channels = max(1, Int(buffer.mNumberChannels))
            for i in 0..<size {
                if i % channels == 0 { channelGain = max(target, channelGain - step) }
                let value = data[i].isFinite ? data[i] * channelGain : 0
                data[i] = min(0.98, max(-0.98, value))
            }
            gain = channelGain
        }
    }
}
