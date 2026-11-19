# brrSpeak external PCM privacy checkpoint — 2026-11-19

## Owner request

Start implementation of the brrBoard + brrSpeak offline/dual-engine speech plan.
Keep the keyboard itself offline and send only PCM audio to the user-selected provider
through brrSpeak; sensitive text must never be retained by the external voice-input path.

## Source changes in this batch

- `recognition/RecognitionSession.kt`: external PCM is treated as a sensitive request even
  though Android does not disclose the remote editor's password/incognito state.
- `recognition/ExternalAudioPrivacy.kt`: pure policy functions make that default testable.
- `DictateController.kt`: the sensitive speech-service path cannot retain failed audio
  even when the generic resend or force-export options are enabled. The external source
  no longer registers the regular screen-off stashing receiver.
- `recognition/ExternalAudioPrivacyTest.kt`: verifies external-audio history suppression
  and no failed-audio retry retention.
- `.github/workflows/brrspeak-experiment-apk.yml`: unit tests must pass before its
  experimental APK is published.
- `PRIVACY_POLICY.md`: distinguishes the fork's app identity from the upstream developer,
  and discloses temporary WAV processing and provider uploads.

## Verification and limitations

- Local YAML parse: passed.
- `git diff --check`: passed before this handoff.
- Local `./gradlew --offline :app:testExperimentUnitTest`: **not run** because the Gradle
  9.4.1 distribution is absent and outbound download is blocked in this workspace.
- CI compile/unit/publication: **pending a GitHub Actions run after commit**.
- First pushed commit `ae4d7ce` compiled the experiment APK successfully but its new
  `:app:testExperimentUnitTest` step failed task selection: this AGP configuration has
  no such test task. Corrective batch uses `:app:testDebugUnitTest` on the same
  `src/main` implementation and switches its tests to the existing Kotlin test/JUnit 5
  convention. Re-run CI; no APK was published by that failed workflow.
- Real cross-UID PCM from brrBoard on Android 15/ColorOS: **not proven**.
- Native cached WAV data is still used temporarily during network transcription; the
  separate private brrBoard offline path must never use this brrSpeak code.
- brrSpeak's experimental signer remains optional. Verify signing identity before telling
  the owner to update the installed fork; never recommend uninstalling merely to force it.

## Next batch

Complete brrBoard's one-mic PCM fanout, bundled offline Parakeet 110M INT8 runtime,
hold-to-speak blue status, encrypted opt-in personal word corrections, backup chip,
one-time special signed APK, regular-APK size gate and Oppo physical test matrix.

Do not equate a green GitHub build with successful Android 15 cross-app audio or actual
spoken Indian English accuracy. Do not store voice transcripts or raw audio in GitHub.
