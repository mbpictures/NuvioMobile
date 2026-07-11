import Foundation
import CryptoKit
import ComposeApp

/// Registers the CryptoKit-backed AES-GCM bridge with the Kotlin framework.
///
/// CommonCrypto's AES-GCM symbols (`CCCryptorGCMEncrypt`/`Decrypt`/`Final` and the one-shot
/// `CCCryptorGCMOneshotEncrypt`/`Decrypt`) are SPI and get rejected by App Store static analysis
/// (ITMS-90338). CryptoKit's `AES.GCM` is public API, so the Kotlin plugin crypto delegates GCM here.
/// See `NuvioCryptoBridge` on the Kotlin side.
enum NuvioCryptoRegistration {
    static func register() {
        NuvioCryptoBridgeFactory.shared.registerFactory(creator: NuvioCryptoBridgeCreatorImpl())
    }
}

final class NuvioCryptoBridgeCreatorImpl: NSObject, NuvioCryptoBridgeCreator {
    func createBridge() -> any NuvioCryptoBridge {
        return NuvioCryptoBridgeImpl()
    }
}

final class NuvioCryptoBridgeImpl: NSObject, NuvioCryptoBridge {

    /// AES-GCM seal. Inputs/outputs are lowercase hex. Returns `ciphertext || 16-byte tag` as hex,
    /// or nil on bad key/nonce size or any CryptoKit error.
    func aesGcmEncryptHex(keyHex: String, ivHex: String, dataHex: String) -> String? {
        guard let keyData = Data(nuvioHex: keyHex),
              let nonceData = Data(nuvioHex: ivHex),
              let plaintext = Data(nuvioHex: dataHex) else { return nil }
        do {
            let key = SymmetricKey(data: keyData)
            let nonce = try AES.GCM.Nonce(data: nonceData)
            let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
            // WebCrypto / JCE wire format: ciphertext followed by the 16-byte auth tag.
            return (sealed.ciphertext + sealed.tag).nuvioHexString
        } catch {
            return nil
        }
    }

    /// AES-GCM open. `dataHex` is `ciphertext || 16-byte tag` as hex. Returns plaintext hex, or nil on
    /// authentication failure / malformed input. CryptoKit verifies the tag internally.
    func aesGcmDecryptHex(keyHex: String, ivHex: String, dataHex: String) -> String? {
        guard let keyData = Data(nuvioHex: keyHex),
              let nonceData = Data(nuvioHex: ivHex),
              let combined = Data(nuvioHex: dataHex),
              combined.count >= 16 else { return nil }
        let tag = combined.suffix(16)
        let ciphertext = combined.prefix(combined.count - 16)
        do {
            let key = SymmetricKey(data: keyData)
            let nonce = try AES.GCM.Nonce(data: nonceData)
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(box, using: key).nuvioHexString
        } catch {
            return nil
        }
    }
}

private extension Data {
    /// Parses a hex string (optionally `0x`-prefixed, either case) into bytes; nil if malformed.
    init?(nuvioHex hex: String) {
        var cleaned = hex
        if cleaned.hasPrefix("0x") || cleaned.hasPrefix("0X") {
            cleaned = String(cleaned.dropFirst(2))
        }
        guard cleaned.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(cleaned.count / 2)
        var index = cleaned.startIndex
        while index < cleaned.endIndex {
            let next = cleaned.index(index, offsetBy: 2)
            guard let byte = UInt8(cleaned[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }

    var nuvioHexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
