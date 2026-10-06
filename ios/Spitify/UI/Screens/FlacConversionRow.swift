import SwiftUI

/// Settings › Library: convert existing FLAC files to AAC (asks first; nothing happens on its own).
struct FlacConversionRow: View {
    @Environment(AppModel.self) private var app
    @State private var confirming = false

    var body: some View {
        let conversion = app.flacConversion
        let flacs = conversion.candidates(app)
        let saving = ByteCountFormatter.string(fromByteCount: conversion.estimatedSaving(flacs), countStyle: .file)
        switch conversion.state {
        case .converting(let done, let total, let current):
            VStack(alignment: .leading, spacing: 6) {
                Text("Converting FLAC to AAC")
                Text("\(done + 1) of \(total): \(current)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                ProgressView(value: Double(done), total: Double(max(total, 1)))
                Button("Stop", role: .cancel) { conversion.cancel() }
            }
        default:
            Button {
                if !flacs.isEmpty { confirming = true }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Convert FLAC to AAC")
                    Text(subtitle(flacs.count, saving)).font(.caption).foregroundStyle(.secondary)
                }
            }
            .disabled(flacs.isEmpty)
            .confirmationDialog("Convert \(flacs.count) FLAC \(flacs.count == 1 ? "song" : "songs")?", isPresented: $confirming, titleVisibility: .visible) {
                Button("Convert and delete FLAC", role: .destructive) { conversion.start(app) }
            } message: {
                Text("Each one is re-encoded as AAC 256 kbps (.m4a), which frees about \(saving). The FLAC files are then deleted and can't be recovered. Likes, playlists, play counts and lyrics carry over. The song that's playing is converted next time.")
            }
        }
    }

    private func subtitle(_ count: Int, _ saving: String) -> String {
        if count > 0 { return "\(count) FLAC \(count == 1 ? "song" : "songs") • frees about \(saving)" }
        if case .finished(let converted, let skipped, let saved) = app.flacConversion.state, converted > 0 {
            return "Done: \(converted) converted, \(ByteCountFormatter.string(fromByteCount: saved, countStyle: .file)) freed" + (skipped > 0 ? " • \(skipped) kept as FLAC" : "")
        }
        return "No FLAC files in Spitify's folder"
    }
}
