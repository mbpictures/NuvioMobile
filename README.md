<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio Desktop" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    Nuvio Desktop is a modern desktop media hub built with Kotlin Multiplatform and Compose Multiplatform.
    <br />
    macOS in development • Windows coming later • Stremio addon ecosystem
  </p>

</div>

> **Unofficial Fork Notice**
>
> This is an unofficial fork of [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile), maintained independently from the upstream project.

## About

Nuvio Desktop brings the Nuvio media experience to desktop with a Compose Multiplatform interface, profile-aware library flows, watch progress, collection tools, and Stremio addon ecosystem integration.

The current desktop version is focused on macOS and is still in development. Windows support is planned for a later stage.

## Status

- macOS: active development.
- Windows: planned, not available yet.

## Installation

There is no stable public desktop release yet. macOS builds are currently intended for development and testing from source.

## Development

```bash
git clone https://github.com/NuvioMedia/NuvioDesktop.git
cd NuvioDesktop
./gradlew :composeApp:desktopRun
```

### Project Structure

- `composeApp/` contains the shared Kotlin Multiplatform and Compose Multiplatform app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `MPVKit/` contains the macOS native playback bridge built on MPVKit and libmpv.
- `iosApp/` remains in the tree for shared project history and configuration used by the codebase.

Useful commands:

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:desktopRun
./gradlew :composeApp:packageReleaseDmg
```

The macOS player bridge is built automatically for desktop run tasks. To build it directly:

```bash
cd MPVKit
swift build -c release --product DesktopMPVBridge
```

Versioning is currently driven from `iosApp/Configuration/Version.xcconfig`, which is used as the shared source of truth by the build.

## Legal & DMCA

Nuvio Desktop functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio Desktop is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- Swift
- MPVKit, libmpv, and FFmpeg

## Star History

<a href="https://www.star-history.com/#tapframe/NuvioDesktop&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=tapframe/NuvioDesktop&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=tapframe/NuvioDesktop&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=tapframe/NuvioDesktop&type=date&legend=top-left" />
 </picture>
</a>

[contributors-shield]: https://img.shields.io/github/contributors/tapframe/NuvioDesktop.svg?style=for-the-badge
[contributors-url]: https://github.com/tapframe/NuvioDesktop/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/tapframe/NuvioDesktop.svg?style=for-the-badge
[forks-url]: https://github.com/tapframe/NuvioDesktop/network/members
[stars-shield]: https://img.shields.io/github/stars/tapframe/NuvioDesktop.svg?style=for-the-badge
[stars-url]: https://github.com/tapframe/NuvioDesktop/stargazers
[issues-shield]: https://img.shields.io/github/issues/tapframe/NuvioDesktop.svg?style=for-the-badge
[issues-url]: https://github.com/tapframe/NuvioDesktop/issues
[license-shield]: https://img.shields.io/github/license/tapframe/NuvioDesktop.svg?style=for-the-badge
[license-url]: https://github.com/tapframe/NuvioDesktop/blob/main/LICENSE
