import SwiftUI
import AVKit

struct AudioOutputPicker: UIViewRepresentable {
    var output: String
    var accent: Color

    final class Control: UIView {
        let picker = AVRoutePickerView()
        let label = UILabel()
        let symbol = UIImageView(image: UIImage(systemName: "airplay.audio"))
        override init(frame: CGRect) {
            super.init(frame: frame)
            picker.prioritizesVideoDevices = false
            label.font = .preferredFont(forTextStyle: .caption1)
            label.lineBreakMode = .byTruncatingTail
            label.isUserInteractionEnabled = false
            symbol.contentMode = .center
            symbol.isUserInteractionEnabled = false
            // A full-width native route control owns the complete touch area.
            addSubview(picker); addSubview(symbol); addSubview(label)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
        override func layoutSubviews() {
            super.layoutSubviews()
            picker.frame = bounds
            symbol.frame = CGRect(x: 0, y: 0, width: 44, height: bounds.height)
            label.frame = CGRect(x: 46, y: 0, width: max(0, bounds.width - 46), height: bounds.height)
        }

    }
    func makeUIView(context: Context) -> Control { Control(frame: .zero) }
    func updateUIView(_ view: Control, context: Context) {
        view.label.text = "Audio: " + output
        view.label.textColor = UIColor(accent)
        view.symbol.tintColor = UIColor(accent)
        view.picker.tintColor = .clear; view.picker.activeTintColor = .clear
        view.picker.accessibilityLabel = "Audio: " + output + ". Change playback device"
    }
}
