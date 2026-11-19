# brrSpeak agent handoff

## 2026-11-19 — brrBoard cold-process launch fix (CI PENDING)

Owner's Oppo/ColorOS test confirmed final-text handoff works only when brrSpeak's process is already open. Instead of persisting a background service, a narrow exported `BoardAudioWakeProvider` permits exactly one UID-verified `ContentResolver.call("prepare-recorded-audio")` from `com.mangoloads.board`; all query/insert/update/delete/MIME methods are denied and no user/audio data is accepted or returned. The provider cold-starts the brrSpeak process, then brrBoard binds the pre-existing `BoardRecordedAudioService` with its verified Messenger caller and finished PCM. No activity, microphone, history, clipboard, server duplication, or new permission. Board's minimum receiver version becomes 142009. CI and real OEM cold-start must still be verified. Persistent signing remains absent; the owner accepted a brrSpeak reinstall, with provider settings backup first.


# brrBoard → brrSpeak record-first audio protocol (2026-11-19)

## Intent and privacy
- brrBoard (`com.mangoloads.board`) owns foreground `AudioRecord`, 16,000 Hz, mono, signed 16-bit raw PCM.
- It writes one cache-private file per session (maximum 120 seconds / 3,840,000 bytes), and stops before sending.
- An **explicit** `bindService` to `BoardRecordedAudioService` transfers a read-only `ParcelFileDescriptor` via `Messenger`. No FileProvider grant, external storage, clipboard, activity, or network permission is involved.
- `Message.sendingUid` is system populated and checked against `PackageManager.getPackagesForUid(uid)` for exact package `com.mangoloads.board`; an unauthenticated binder client is rejected before opening its audio descriptor. Package UID isolation is the trust boundary; the two apps are not co-signed.
- The sender includes only session ID, descriptor, byte count and fixed PCM metadata. No language hints, editor state, typed text, clipboard contents, contacts, history, prompts, suggestions, or passwords cross Binder.
- brrSpeak validates exact format, even and bounded byte size, and `ParcelFileDescriptor.statSize` before passing PCM to `RecognitionSession` → `DictateController.startRecognition(...externalAudio...)`, which already uses selected provider, key, model, language and post-processing. The external path never opens brrSpeak's microphone.
- `ExternalAudioPrivacy` marks external recognition private: no audio retry retention or history.
- Only `RESULT` (final text) or `ERROR` (integer code) returns. No partial transcript or raw audio returns.
- Keyboard drops stale results after editor replacement, IME dismissal, cancellation, timeouts or a superseding session.

## Wire contract v1
- Service: `dev.patrickgold.florisboard.dictate.recognition.BoardRecordedAudioService`, exported without intent filter (explicit binding only).
- Request Messenger.what=1; cancellation=2; result=3; error=4.
- Bundle keys: `session` (positive long), `audio` (read-only PFD), `bytes` (long), `rate=16000`, `channels=1`, `encoding=2` (AudioFormat.ENCODING_PCM_16BIT).
- Result bundle: `session`, `text` (final string only). Error bundle: `session`, `error` (integer); 1=invalid format, 2=busy, 3=settings timeout, 4=engine failure.
- Receiver owns PFD after successful Binder transfer; both sides close descriptors on terminal outcomes. The sender deletes its cache file.
- Receivers predating experimental run #8 do not implement this service; the keyboard must gate on installed versionCode >=142008.

## Verified versus unverified
- Before this protocol, brrSpeak same-app pipe probe and brrBoard cross-app SpeechRecognizer builds passed GitHub CI but ColorOS cross-UID failed in the user's physical test.
- This protocol uses a normal Android bound service, bypassing Android 15's speech framework binding restriction, and never launches a background microphone.
- Unit tests can validate format, length and caller-package policy. They cannot establish OEM binder delivery or provider behavior on the user's phone.
- **Physical acceptance still required:** install brrSpeak, configure provider, install brrBoard, tap mic, speak 5–10 seconds, tap stop, verify one final insertion in original editor; then test rapid taps, empty audio, offline provider, permission revoked, editor change, IME dismissal and repeat sessions.
- brrSpeak CI signing is ephemeral unless four BRRSPEAK secrets exist; do not claim in-place update compatibility with an older ephemeral APK.

## Handoff
- Read the latest main SHA in both repositories before editing. Keep main only, no forced updates.
- Inspect `BoardRecordedAudioService.kt`, `RecognitionSession.kt`, `ExternalAudioPrivacy.kt`, `KeyboardRecordedAudioSource.kt`, `InlineDictation.kt`.
- CI: brrSpeak `brrspeak-experiment-apk.yml` builds `:app:assembleExperiment`, tests `:app:testDebugUnitTest`, checks package/receiver and publishes `brrSpeak.apk`.
- CI: brrBoard debug first, then original-key Obtainium release; never bypass pin or network-permission gate.

