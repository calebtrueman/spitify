import UIKit

/// Keep the local photo large. Send a sharper copy plus a preview older apps can read.
enum ProfilePhotos {
    static func jpeg(_ data: Data, sides: [Int], limit: Int) -> Data? {
        let images = sides.compactMap { side in ArtCache.squareJPEG(data, side: side).flatMap(UIImage.init(data:)) }
        for quality in [0.88, 0.78, 0.68, 0.58, 0.48, 0.38] {
            for image in images {
                if let jpeg = image.jpegData(compressionQuality: quality), jpeg.count <= limit { return jpeg }
            }
        }
        return nil
    }
    static func preview(_ data: Data) -> String? { jpeg(data, sides: [96, 80], limit: 2000)?.base64EncodedString() }
    static func shared(_ data: Data) -> String? { jpeg(data, sides: [768, 640, 512, 384], limit: 18000)?.base64EncodedString() }
}
