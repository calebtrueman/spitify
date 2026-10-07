import SwiftUI

private func platformIcon(_ platform: String) -> String {
    switch platform {
    case "android", "ios": "iphone"
    case "macos": "laptopcomputer"
    default: "desktopcomputer"
    }
}

/// Above the mini player: "Continue from <device>" after coming back to the app, or a thin
/// "Playing on <device>" bar while another linked device plays and this one doesn't.
struct DeviceStrip: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var showing: String?

    var body: some View {
        let devices = app.devices
        TimelineView(.periodic(from: .now, by: 15)) { _ in
            if let offer = devices.continueOffer, !app.player.isPlaying, let song = offer.current {
                continueCard(offer, song)
            } else if !app.player.isPlaying, let remote = devices.active, let song = remote.current {
                bar(remote, song)
            }
        }
        .sheet(item: Binding(get: { showing.map(RemoteID.init) }, set: { showing = $0?.id })) { id in
            RemoteDeviceSheet(device: id.id).environment(app)
        }
    }

    private struct RemoteID: Identifiable { var id: String }

    private func bar(_ remote: DevicePlayback, _ song: SharedTrack) -> some View {
        Button { showing = remote.device } label: {
            HStack(spacing: 8) {
                Image(systemName: "hifispeaker.and.homepod.fill").font(.system(size: 13, weight: .semibold))
                Text("Playing on \(remote.name) · \(song.title) — \(song.artist)").text(.label).lineLimit(1)
                Spacer(minLength: 4)
                Button { Haptics.tap(); Task { await app.devices.control(remote.device, remote.playing ? "pause" : "play") } } label: {
                    Image(systemName: remote.playing ? "pause.fill" : "play.fill").font(.system(size: 14, weight: .bold)).frame(width: 32, height: 28).contentShape(Rectangle())
                }
                .accessibilityLabel(remote.playing ? "Pause on \(remote.name)" : "Play on \(remote.name)")
            }
            .foregroundStyle(p.onAccent)
            .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 3)
            .background(p.accent, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.pressable(0.98))
        .padding(.horizontal, 8).padding(.bottom, 4)
        .accessibilityIdentifier("devicePlayingBar")
    }

    private func continueCard(_ offer: DevicePlayback, _ song: SharedTrack) -> some View {
        HStack(spacing: 10) {
            SharedTrackCover(track: song).frame(width: 40, height: 40).clipShape(RoundedRectangle(cornerRadius: 5))
            VStack(alignment: .leading, spacing: 1) {
                Text("Continue from \(offer.name)").text(.titleS).foregroundStyle(p.text).lineLimit(1)
                Text("\(song.title) · \(song.artist)").text(.caption).foregroundStyle(p.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Button { Haptics.tap(); Task { await app.devices.listenHere(offer) } } label: {
                Text("Play").text(.label).foregroundStyle(p.onAccent).padding(.horizontal, 14).padding(.vertical, 8).background(p.accent, in: Capsule())
            }
            Button { app.devices.continueOffer = nil } label: {
                Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(p.secondary).frame(width: 32, height: 32).contentShape(Rectangle())
            }
            .accessibilityLabel("Dismiss")
        }
        .padding(.horizontal, 8).padding(.vertical, 7)
        .background(p.surfaceHigh, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, 8).padding(.bottom, 4)
        .accessibilityIdentifier("deviceContinueCard")
    }
}

/// What another linked device is playing, with remote controls and "Listen here".
struct RemoteDeviceSheet: View {
    var device: String
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let devices = app.devices
        VStack(spacing: 18) {
            if let remote = devices.playback(device), let song = remote.current {
                Label("Playing on \(remote.name)", systemImage: platformIcon(remote.platform)).text(.label).foregroundStyle(p.accent).padding(.top, 24)
                SharedTrackCover(track: song).aspectRatio(1, contentMode: .fit).frame(maxWidth: 260).clipShape(RoundedRectangle(cornerRadius: 10))
                VStack(spacing: 4) {
                    Text(song.title).text(.headline).foregroundStyle(p.text).lineLimit(2).multilineTextAlignment(.center)
                    Text(song.artist).text(.body).foregroundStyle(p.secondary).lineLimit(1)
                }
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    let position = devices.expectedPosition(remote)
                    let duration = max(song.durationMs, 1)
                    VStack(spacing: 4) {
                        ProgressView(value: Double(min(position, duration)), total: Double(duration)).tint(p.accent)
                        HStack {
                            Text(position.formattedDuration)
                            Spacer()
                            if song.durationMs > 0 { Text(song.durationMs.formattedDuration) }
                        }.text(.caption).foregroundStyle(p.secondary)
                    }
                }
                HStack(spacing: 36) {
                    Button { Haptics.tap(); Task { await devices.control(device, "previous") } } label: { Image(systemName: "backward.fill").font(.system(size: 26)) }
                        .accessibilityLabel("Previous")
                    Button { Haptics.tap(); Task { await devices.control(device, remote.playing ? "pause" : "play") } } label: {
                        Image(systemName: remote.playing ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 60))
                    }.accessibilityLabel(remote.playing ? "Pause" : "Play")
                    Button { Haptics.tap(); Task { await devices.control(device, "next") } } label: { Image(systemName: "forward.fill").font(.system(size: 26)) }
                        .accessibilityLabel("Next")
                }
                .foregroundStyle(p.text)
                if remote.spoken {
                    Text("Episodes and audiobooks continue on the device that played them.").text(.caption).foregroundStyle(p.secondary).multilineTextAlignment(.center)
                } else {
                    Button { Haptics.tap(); dismiss(); Task { await devices.listenHere(remote) } } label: {
                        Label("Listen here", systemImage: "iphone").text(.titleS).foregroundStyle(p.onAccent).frame(maxWidth: .infinity).padding(.vertical, 14).background(p.accent, in: Capsule())
                    }
                }
                Spacer(minLength: 0)
            } else {
                Text("Nothing is playing on that device.").text(.body).foregroundStyle(p.secondary).padding(.top, 40)
                Spacer()
            }
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(p.background)
        .presentationDetents([.large])
    }
}

/// Settings › Your devices.
struct DevicesView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var name = ""
    @State private var code = ""
    @FocusState private var nameFocused: Bool

    var body: some View {
        let devices = app.devices
        AppForm {
            Section("This device") {
                TextField("Device name", text: $name).focused($nameFocused).submitLabel(.done)
                    .onSubmit { devices.rename(name) }
                    .onChange(of: nameFocused) { _, focused in if !focused { devices.rename(name) } }
            }
            if !devices.devices.isEmpty {
                Section {
                    ForEach(devices.devices) { device in
                        HStack(spacing: 12) {
                            Image(systemName: platformIcon(device.platform)).font(.system(size: 20)).foregroundStyle(p.accent).frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.name).text(.title)
                                Text(devices.status(device.id)).text(.caption).foregroundStyle(devices.status(device.id).hasPrefix("Playing") ? p.accent : p.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) { Task { await devices.remove(device.id) } } label: { Image(systemName: "minus.circle.fill").font(.system(size: 20)) }
                                .buttonStyle(.borderless).accessibilityLabel("Remove \(device.name)")
                        }
                    }
                    Button("Leave this group", role: .destructive) { Task { await devices.leaveGroup() } }
                } header: { Text("Linked devices") }
            }
            Section("Link a device") {
                switch devices.pairing {
                case .showing(let shown, let expiresAt):
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        let left = max(0, expiresAt - SocialRules.now) / 1000
                        VStack(spacing: 10) {
                            Text(DeviceSyncState.displayCode(shown)).font(.system(size: 40, weight: .bold, design: .monospaced)).foregroundStyle(p.text)
                                .textSelection(.enabled).accessibilityIdentifier("deviceCode")
                            Text(left > 0 ? String(format: "Expires in %d:%02d", left / 60, left % 60) : "This code expired.").text(.caption).foregroundStyle(p.secondary)
                            Text("On your other device, open Settings › Your devices › Enter code.").text(.body).multilineTextAlignment(.center)
                        }.frame(maxWidth: .infinity).padding(.vertical, 8)
                    }
                    Button("Stop showing code") { devices.cancelPairing() }
                default:
                    Button { Task { await devices.showCode() } } label: { Label("Show a code", systemImage: "link") }
                }
            }
            Section("Enter code") {
                TextField("Code from your other device", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced)).submitLabel(.go).onSubmit { devices.enterCode(code) }
                Button("Link") { devices.enterCode(code) }.disabled(DeviceSyncState.normalizeCode(code).count != DeviceSyncState.tokenLength || devices.pairing == .searching)
                switch devices.pairing {
                case .searching: HStack(spacing: 8) { ProgressView(); Text("Looking for that code…") }
                case .waiting(let other): HStack(spacing: 8) { ProgressView(); Text("Waiting for \(other) to allow this device…") }
                case .failed(let reason): Text(reason).foregroundStyle(p.secondary)
                case .linked(let other): Label("Linked with \(other)", systemImage: "checkmark.circle.fill").foregroundStyle(p.accent)
                default: EmptyView()
                }
            }
            Section {
                EmptyView()
            } footer: {
                Text("Devices you link see what you play and can control playback. Sent end-to-end encrypted through the same relays as friend shares.")
            }
            if let message = devices.message { Text(message).foregroundStyle(p.secondary) }
        }
        .scrollContentBackground(.hidden)
        .background(p.background)
        .navigationTitle("Your devices")
        .onAppear { name = devices.name }
    }
}
