# Offline Talks 2.0.1 — corrected GitHub build package

## GitHub upload
Upload the **contents of this folder** to the repository root. Do not upload the enclosing folder or ZIP itself.

The repository root must contain:
- `.github/workflows/build-apk.yml`
- `app/build.gradle.kts`
- `app/src/main/...`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`

Keep the two Gradle build files in their exact locations:
- Root: `build.gradle.kts`
- App module: `app/build.gradle.kts`

## Build
Open GitHub Actions → Build Offline Talks APK → Run workflow.
The APK is uploaded as the `OfflineTalks-debug-apk` artifact.

## Current implementation status
This is a development build foundation, not a finished production messenger. The project includes Nearby Connections peer discovery/transport and the current chunked-text/delivery-ack work. Voice calling audio, durable message storage, end-to-end encryption, and fully validated media/group workflows are not complete. Build success does not imply those features are implemented.
