# brrBoard receiver cold-start compatibility

## 2026-11-19 — brrBoard cold-process launch fix (CI PENDING)

Owner's Oppo/ColorOS test confirmed final-text handoff works only when brrSpeak's process is already open. Instead of persisting a background service, a narrow exported `BoardAudioWakeProvider` permits exactly one UID-verified `ContentResolver.call("prepare-recorded-audio")` from `com.mangoloads.board`; all query/insert/update/delete/MIME methods are denied and no user/audio data is accepted or returned. The provider cold-starts the brrSpeak process, then brrBoard binds the pre-existing `BoardRecordedAudioService` with its verified Messenger caller and finished PCM. No activity, microphone, history, clipboard, server duplication, or new permission. Board's minimum receiver version becomes 142009. CI and real OEM cold-start must still be verified. Persistent signing remains absent; the owner accepted a brrSpeak reinstall, with provider settings backup first.

