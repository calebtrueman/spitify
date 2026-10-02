import SwiftUI
import AVKit

struct AudioOutputPicker: UIViewRepresentable {
    var output: String
    var accent: Color
    final class Control: UIButton {
        let picker = AVRoutePickerView()
        override init(frame: CGRect) {
            super.init(frame: frame)
            contentHorizontalAlignment = .left
            titleLabel?.font = .preferredFont(forTextStyle: .caption1)
            titleLabel?.lineBreakMode = .byTruncatingTail
            picker.prioritizesVideoDevices = false
            picker.isUserInteractionEnabled = false
            picker.isAccessibilityElement = false
            addSubview(picker)
            addAction(UIAction { [weak self] _ in self?.openPicker() }, for: .touchUpInside)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
        override func layoutSubviews() {
            super.layoutSubviews()
            picker.frame = CGRect(x: 0, y: 0, width: 40, height: bounds.height)
            titleLabel?.frame = CGRect(x: 44, y: 0, width: max(0, bounds.width - 44), height: bounds.height)
        }
        private func openPicker() {
            func button(in view: UIView) -> UIButton? {
                if let button = view as? UIButton { return button }
                return view.subviews.compactMap { button(in: $0) }.first
            }
            button(in: picker)?.sendActions(for: .touchUpInside)
        }
    }
    func makeUIView(context: Context) -> Control { Control(frame: .zero) }
    func updateUIView(_ view: Control, context: Context) {
        view.setTitle("Audio: " + output, for: .normal)
        view.setTitleColor(UIColor(accent), for: .normal)
        view.picker.tintColor = UIColor(accent); view.picker.activeTintColor = UIColor(accent)
        view.accessibilityLabel = "Audio: " + output + ". Change playback device"
    }
}
