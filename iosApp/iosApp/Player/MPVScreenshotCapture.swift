import Foundation
import CoreVideo
import Libmpv

/// Captures the current decoded video frame from an mpv instance into a CVPixelBuffer.
///
/// Uses `screenshot-raw video` which returns the post-decode frame (no OSD or subtitles)
/// as a BGRA byte array. Called from the PiP frame pump at ~15 Hz so the PiP layer can
/// keep showing actual video while the mpv Metal layer is hidden / off-screen.
///
/// Caveat: `screenshot-raw` blocks until the next decoded frame is available and allocates
/// per call, so this is intentionally driven at a sub-realtime rate.
enum MPVScreenshotCapture {

    static func capture(mpv: OpaquePointer) -> CVPixelBuffer? {
        var result = mpv_node()
        let argv = [
            strdup("screenshot-raw"),
            strdup("video"),
            UnsafeMutablePointer<CChar>(bitPattern: 0)
        ]

        defer {
            for ptr in argv where ptr != nil {
                free(UnsafeMutablePointer(mutating: ptr))
            }
            mpv_free_node_contents(&result)
        }

        let status: Int32 = argv.withUnsafeBufferPointer { buffer in
            // Cast the [UnsafeMutablePointer<CChar>?] buffer to const char**
            buffer.baseAddress!.withMemoryRebound(
                to: UnsafePointer<CChar>?.self,
                capacity: buffer.count
            ) { rebound in
                mpv_command_ret(mpv, rebound, &result)
            }
        }

        guard status >= 0 else { return nil }
        guard result.format == MPV_FORMAT_NODE_MAP else { return nil }
        guard let list = result.u.list else { return nil }

        var width: Int = 0
        var height: Int = 0
        var stride: Int = 0
        var pixelFormat: String?
        var dataPtr: UnsafeMutableRawPointer?
        var dataSize: Int = 0

        let count = Int(list.pointee.num)
        let values = list.pointee.values
        let keys = list.pointee.keys

        for i in 0..<count {
            guard let keyCStr = keys?[i] else { continue }
            let key = String(cString: keyCStr)
            let value = values![i]

            switch key {
            case "w":
                width = Int(value.u.int64)
            case "h":
                height = Int(value.u.int64)
            case "stride":
                stride = Int(value.u.int64)
            case "format":
                if let s = value.u.string {
                    pixelFormat = String(cString: s)
                }
            case "data":
                if let ba = value.u.ba {
                    dataPtr = ba.pointee.data
                    dataSize = Int(ba.pointee.size)
                }
            default:
                break
            }
        }

        guard width > 0, height > 0, stride > 0,
              let data = dataPtr, dataSize > 0 else { return nil }

        let cvFormat: OSType
        switch pixelFormat {
        case "bgr0", "bgra":
            cvFormat = kCVPixelFormatType_32BGRA
        case "rgb0", "rgba":
            cvFormat = kCVPixelFormatType_32RGBA
        default:
            cvFormat = kCVPixelFormatType_32BGRA
        }

        var pixelBuffer: CVPixelBuffer?
        let attrs: [CFString: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey: [:],
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
        ]
        let createStatus = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            cvFormat,
            attrs as CFDictionary,
            &pixelBuffer
        )

        guard createStatus == kCVReturnSuccess, let pb = pixelBuffer else { return nil }

        CVPixelBufferLockBaseAddress(pb, [])
        defer { CVPixelBufferUnlockBaseAddress(pb, []) }

        guard let dst = CVPixelBufferGetBaseAddress(pb) else { return nil }
        let dstStride = CVPixelBufferGetBytesPerRow(pb)
        let rowBytes = min(stride, dstStride)

        for row in 0..<height {
            let srcRow = data.advanced(by: row * stride)
            let dstRow = dst.advanced(by: row * dstStride)
            memcpy(dstRow, srcRow, rowBytes)
        }

        return pb
    }
}
