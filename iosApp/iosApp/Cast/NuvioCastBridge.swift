import Foundation
import GoogleCast
import ComposeApp

enum NuvioCastRegistration {
    private static var didInitContext = false

    static func register() {
        initializeCastContextIfNeeded()
        NuvioCastBridgeFactory.shared.registerFactory(creator: NuvioCastBridgeCreatorImpl())
    }

    static func initializeCastContextIfNeeded() {
        guard !didInitContext else { return }
        let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        GCKCastContext.setSharedInstanceWith(options)
        didInitContext = true
    }
}

final class NuvioCastBridgeCreatorImpl: NSObject, NuvioCastBridgeCreator {
    func createBridge() -> any NuvioCastBridge {
        return NuvioCastBridgeImpl()
    }
}


final class NuvioCastBridgeImpl: NSObject, NuvioCastBridge {

    private weak var listenerRef: CastBridgeListener?
    private var remoteClient: GCKRemoteMediaClient?

    private var castContext: GCKCastContext { GCKCastContext.sharedInstance() }
    private var sessionManager: GCKSessionManager { castContext.sessionManager }
    private var discoveryManager: GCKDiscoveryManager { castContext.discoveryManager }

    override init() {
        super.init()
        NuvioCastRegistration.initializeCastContextIfNeeded()
        sessionManager.add(self)
        discoveryManager.add(self)
        discoveryManager.passiveScan = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(castStateChanged),
            name: NSNotification.Name.gckCastStateDidChange,
            object: nil,
        )
        if let client = sessionManager.currentCastSession?.remoteMediaClient {
            bindRemoteClient(client)
        }
    }

    func setListener(listener: CastBridgeListener?) {
        self.listenerRef = listener
    }

    @objc private func castStateChanged() { notifyChanged() }

    private func notifyChanged() {
        DispatchQueue.main.async { [weak self] in
            self?.listenerRef?.onCastStateChanged()
        }
    }


    func startDiscovery() {
        discoveryManager.passiveScan = false
        discoveryManager.startDiscovery()
    }

    func stopDiscovery() {
        // Keep passive discovery alive so the cast button keeps reflecting availability.
        discoveryManager.passiveScan = true
    }

    func getDeviceCount() -> Int32 { Int32(discoveryManager.deviceCount) }
    func getDeviceId(at: Int32) -> String { device(at)?.deviceID ?? "" }
    func getDeviceName(at: Int32) -> String { device(at)?.friendlyName ?? "" }
    func getDeviceModel(at: Int32) -> String { device(at)?.modelName ?? "" }

    private func device(_ index: Int32) -> GCKDevice? {
        guard index >= 0, UInt(index) < discoveryManager.deviceCount else { return nil }
        return discoveryManager.device(at: UInt(index))
    }

    func connect(deviceId: String) {
        for i in 0..<discoveryManager.deviceCount {
            let candidate = discoveryManager.device(at: i)
            if candidate.deviceID == deviceId {
                sessionManager.startSession(with: candidate)
                return
            }
        }
    }

    func disconnect() {
        sessionManager.endSessionAndStopCasting(true)
    }

    func getConnectionState() -> Int32 {
        switch castContext.castState {
        case .noDevicesAvailable: return 0
        case .notConnected: return 1
        case .connecting: return 2
        case .connected: return 3
        @unknown default: return 1
        }
    }

    func getConnectedDeviceName() -> String {
        sessionManager.currentCastSession?.device.friendlyName ?? ""
    }

    func loadMedia(
        url: String,
        title: String,
        subtitle: String,
        posterUrl: String,
        contentType: String,
        startPositionMs: Int64,
    ) {
        guard let client = sessionManager.currentCastSession?.remoteMediaClient,
              let mediaURL = URL(string: url) else { return }

        let metadata = GCKMediaMetadata(metadataType: .movie)
        metadata.setString(title, forKey: kGCKMetadataKeyTitle)
        if !subtitle.isEmpty {
            metadata.setString(subtitle, forKey: kGCKMetadataKeySubtitle)
        }
        if !posterUrl.isEmpty, let posterURL = URL(string: posterUrl) {
            metadata.addImage(GCKImage(url: posterURL, width: 480, height: 720))
        }

        let infoBuilder = GCKMediaInformationBuilder(contentURL: mediaURL)
        infoBuilder.streamType = .buffered
        infoBuilder.contentType = contentType
        infoBuilder.metadata = metadata

        let requestBuilder = GCKMediaLoadRequestDataBuilder()
        requestBuilder.mediaInformation = infoBuilder.build()
        requestBuilder.autoplay = true
        requestBuilder.startTime = TimeInterval(startPositionMs) / 1000.0

        client.loadMedia(with: requestBuilder.build())
        bindRemoteClient(client)
    }

    func play() { remoteClient?.play() }
    func pause() { remoteClient?.pause() }

    func seekTo(positionMs: Int64) {
        let options = GCKMediaSeekOptions()
        options.interval = TimeInterval(positionMs) / 1000.0
        remoteClient?.seek(with: options)
    }

    func isCasting() -> Bool {
        guard sessionManager.hasConnectedCastSession() else { return false }
        return remoteClient?.mediaStatus?.mediaInformation != nil
    }

    func getPositionMs() -> Int64 {
        guard let client = remoteClient else { return 0 }
        return Int64(client.approximateStreamPosition() * 1000)
    }

    func getDurationMs() -> Int64 {
        let duration = remoteClient?.mediaStatus?.mediaInformation?.streamDuration ?? 0
        return Int64(duration * 1000)
    }

    func getIsPlaying() -> Bool {
        remoteClient?.mediaStatus?.playerState == .playing
    }

    func getIsBuffering() -> Bool {
        let state = remoteClient?.mediaStatus?.playerState
        return state == .buffering || state == .loading
    }

    func destroy() {
        sessionManager.remove(self)
        discoveryManager.remove(self)
        unbindRemoteClient()
        NotificationCenter.default.removeObserver(self)
        listenerRef = nil
    }

    private func bindRemoteClient(_ client: GCKRemoteMediaClient) {
        guard remoteClient !== client else { return }
        remoteClient?.remove(self)
        remoteClient = client
        client.add(self)
    }

    private func unbindRemoteClient() {
        remoteClient?.remove(self)
        remoteClient = nil
    }
}

extension NuvioCastBridgeImpl: GCKSessionManagerListener {
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
        handleSession(session)
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) {
        handleSession(session)
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) {
        unbindRemoteClient()
        notifyChanged()
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKSession, withError error: Error) {
        notifyChanged()
    }

    private func handleSession(_ session: GCKSession) {
        if let castSession = session as? GCKCastSession, let client = castSession.remoteMediaClient {
            bindRemoteClient(client)
        }
        notifyChanged()
    }
}

extension NuvioCastBridgeImpl: GCKDiscoveryManagerListener {
    func didUpdateDeviceList() { notifyChanged() }
}

extension NuvioCastBridgeImpl: GCKRemoteMediaClientListener {
    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
        notifyChanged()
    }
}
