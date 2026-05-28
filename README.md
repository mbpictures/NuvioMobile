<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A modern media hub for Android and iOS built with Kotlin Multiplatform and Compose Multiplatform.
    <br />
    Stremio addon ecosystem • Cross-platform
  </p>

</div>

> **Unofficial Fork Notice**
>
> This is an unofficial fork of [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile), maintained independently from the upstream project.

## About

Nuvio is the current Kotlin Multiplatform rewrite of the original React Native app. It delivers a shared Compose UI for Android and iOS while keeping the playback-focused experience, collection tools, watch progress flows, downloads, and Stremio addon ecosystem integration that shaped the earlier app.

The mobile app is built from a single shared codebase in [composeApp](./composeApp), with native platform entry points for Android and iOS.

## Installation

### Android

Download the latest Android build from [GitHub Releases](https://github.com/mbpictures/NuvioMobile/releases/latest).

### iOS

Download the latest iOS build from [GitHub Releases](https://github.com/mbpictures/NuvioMobile/releases/latest) and sideload it to your device, e.g. by using [sidestore](https://sidestore.io/).

## Development

```bash
git clone https://github.com/mbpictures/NuvioMobile.git
cd NuvioMobile
./scripts/run-mobile.sh android
# or
./scripts/run-mobile.sh ios
```

### Project Structure

- `composeApp/` contains the shared Kotlin Multiplatform and Compose Multiplatform app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/androidMain/` contains Android-specific integrations.
- `composeApp/src/iosMain/` contains iOS-specific integrations.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `iosApp/` contains the native Xcode project and iOS entry point.

Useful commands:

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./scripts/build-distribution.sh
```

Versioning is driven from `iosApp/Configuration/Version.xcconfig`, which is used as the shared source of truth for both iOS and Android builds.

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- AndroidX Media3
- AVFoundation and native iOS integrations

## Star History

<a href="https://www.star-history.com/#mbpictures/NuvioMobile&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=mbpictures/NuvioMobile&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=mbpictures/NuvioMobile&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=mbpictures/NuvioMobile&type=date&legend=top-left" />
 </picture>
</a>

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/mbpictures/NuvioMobile.svg?style=for-the-badge
[contributors-url]: https://github.com/mbpictures/NuvioMobile/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/mbpictures/NuvioMobile.svg?style=for-the-badge
[forks-url]: https://github.com/mbpictures/NuvioMobile/network/members
[stars-shield]: https://img.shields.io/github/stars/mbpictures/NuvioMobile.svg?style=for-the-badge
[stars-url]: https://github.com/mbpictures/NuvioMobile/stargazers
[issues-shield]: https://img.shields.io/github/issues/mbpictures/NuvioMobile.svg?style=for-the-badge
[issues-url]: https://github.com/mbpictures/NuvioMobile/issues
[license-shield]: https://img.shields.io/github/license/mbpictures/NuvioMobile.svg?style=for-the-badge
[license-url]: https://github.com/mbpictures/NuvioMobile/blob/main/LICENSE