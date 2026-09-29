#!/usr/bin/env python3
# Copyright (C) 2026 DevEmperor (Dictate)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
"""Ask xAI's speech-to-text endpoints what the documentation leaves open (issue #435).

The app's xAI support was built from the docs plus two outside captures of the streaming protocol. Three
things were left for a key of our own to settle, and this answers each with the request shape the app
actually sends:

  models   Does `GET /v1/models` list the transcription models at all? (The picker falls back to the
           curated id if it does not.)
  batch    `POST /v1/stt` with the app's multipart — every field before the file, `language` + `format`
           together, the glossary as repeated `keyterm`. With --formats, the same clip in every container
           the app knows, to check the accepted list in ProviderRegistry.XAI (WebM and AMR are the doubts,
           and `.opus` as a file name).
  stream   `wss://api.x.ai/v1/stt` with the app's query, audio paced in real time, then `finalize` and
           `audio.done` as the app's stop sends them. Every event is printed with its time relative to the
           stop, and the end says what `transcript.done` carried — the one event nobody has pinned down.

The key is read from XAI_API_KEY or --key-file and is never printed.

Probe audio should be real speech: formant synthesis (espeak) can be discarded by a provider's voice
gate without a word of complaint (see tools/probe-gemini-live-tail.py). --say therefore speaks the text
with xAI's own TTS, which the same key pays for (fractions of a cent).

Usage:
    python3 tools/probe-xai-stt.py --key-file ~/xai.key models
    python3 tools/probe-xai-stt.py --key-file ~/xai.key --say "Hallo, das ist ein Test." --language de batch --formats
    python3 tools/probe-xai-stt.py --key-file ~/xai.key --say "Erster Satz. Zweiter Satz." --language de stream

A WAV given with --wav must be 16 kHz mono PCM16. `stream` needs the `websockets` package, --formats
needs ffmpeg.
"""

import argparse
import asyncio
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import wave

API = "https://api.x.ai/v1/"
WS = "wss://api.x.ai/v1/stt"
MODEL = "grok-voice-transcribe-2.0"
RATE = 16_000
CHUNK_MS = 100


def read_key(args):
    if args.key_file:
        with open(os.path.expanduser(args.key_file), encoding="utf-8") as f:
            return f.read().strip()
    key = os.environ.get("XAI_API_KEY", "").strip()
    if not key:
        sys.exit("No key: set XAI_API_KEY or pass --key-file.")
    return key


def http(method, url, key, body=None, content_type=None):
    """One request; returns (status, body text). Errors are answers here, not exceptions."""
    headers = {"Authorization": f"Bearer {key}"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def synthesize(text, language, key):
    """Speaks [text] with xAI's TTS as 16 kHz WAV and returns the PCM16 frames."""
    body = json.dumps({
        "text": text,
        "language": language or "auto",
        "output_format": {"codec": "wav", "sample_rate": RATE},
    }).encode()
    status, audio = http("POST", API + "tts", key, body, "application/json")
    if status != 200:
        sys.exit(f"TTS failed: HTTP {status} {audio[:300]!r}")
    if audio[:1] == b"{":  # the JSON shape the reference describes, in case it is what comes back
        import base64
        audio = base64.b64decode(json.loads(audio)["audio"])
    with wave.open(io.BytesIO(audio)) as w:
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (RATE, 1, 2):
            sys.exit(f"TTS answered {w.getframerate()} Hz / {w.getnchannels()} ch; expected 16 kHz mono.")
        return w.readframes(w.getnframes())


def load_wav(path):
    with wave.open(path) as w:
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (RATE, 1, 2):
            sys.exit("The WAV must be 16 kHz mono PCM16, which is what the app records.")
        return w.readframes(w.getnframes())


def to_wav(pcm):
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)
    return out.getvalue()


def multipart(fields, filename, data, mime):
    """The app's order: every field first, the file last — xAI may ignore fields that follow it."""
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields:
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {mime}\r\n\r\n".encode() + data + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def app_fields(args):
    fields = [("model", MODEL)]
    if args.language:
        fields += [("language", args.language), ("format", "true")]
    fields += [("keyterm", t) for t in args.keyterm]
    return fields


def cmd_models(args, key):
    status, body = http("GET", API + "models", key)
    print(f"HTTP {status}")
    if status != 200:
        print(body.decode(errors="replace"))
        return
    ids = sorted(m.get("id", "") for m in json.loads(body).get("data", []))
    for i in ids:
        print("  " + i)
    stt = [i for i in ids if "transcribe" in i or "stt" in i]
    print(f"\n{len(ids)} models; transcription ids listed: {stt or 'none'}")


# Every container the app can hand over (AudioContainer), made from the same clip.
FORMATS = [
    ("wav", "audio/wav", None),
    ("mp3", "audio/mpeg", ["-c:a", "libmp3lame", "-b:a", "64k"]),
    ("m4a", "audio/mp4", ["-c:a", "aac", "-b:a", "64k"]),
    ("aac", "audio/aac", ["-c:a", "aac", "-b:a", "64k", "-f", "adts"]),
    ("ogg", "audio/ogg", ["-c:a", "libopus", "-b:a", "32k"]),
    ("opus", "audio/ogg", ["-c:a", "libopus", "-b:a", "32k", "-f", "ogg"]),  # the same Ogg, named .opus
    ("flac", "audio/flac", ["-c:a", "flac"]),
    ("webm", "audio/webm", ["-c:a", "libopus", "-b:a", "32k"]),
    ("amr", "audio/amr", ["-ar", "8000", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"]),
]


def cmd_batch(args, key, pcm):
    wav = to_wav(pcm)
    targets = FORMATS if args.formats else FORMATS[:1]
    if args.formats and not shutil.which("ffmpeg"):
        sys.exit("--formats needs ffmpeg.")
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "clip.wav")
        with open(src, "wb") as f:
            f.write(wav)
        for ext, mime, codec in targets:
            if codec is None:
                data = wav
            else:
                dst = os.path.join(tmp, f"clip.{ext}")
                done = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, *codec, dst])
                if done.returncode != 0:
                    print(f"{ext:5} (ffmpeg could not make it)")
                    continue
                with open(dst, "rb") as f:
                    data = f.read()
            body, ctype = multipart(app_fields(args), f"clip.{ext}", data, mime)
            started = time.monotonic()
            status, answer = http("POST", API + "stt", key, body, ctype)
            took = time.monotonic() - started
            text = answer.decode(errors="replace")
            if status == 200:
                text = json.loads(text).get("text", "")
            print(f"{ext:5} HTTP {status}  {took:4.1f} s  {text[:200]}")


async def cmd_stream(args, key, pcm):
    import websockets

    query = f"sample_rate={RATE}&encoding=pcm&interim_results=true&model={MODEL}"
    if args.language:
        query += f"&language={args.language}"
    chunk = RATE * 2 * CHUNK_MS // 1000
    events, stop_at = [], None
    async with websockets.connect(f"{WS}?{query}", additional_headers={"Authorization": f"Bearer {key}"}) as ws:
        first = json.loads(await ws.recv())
        print(f"ready: {first}")

        async def send():
            nonlocal stop_at
            for i in range(0, len(pcm), chunk):
                await ws.send(pcm[i:i + chunk])
                await asyncio.sleep(CHUNK_MS / 1000)
            stop_at = time.monotonic()
            await ws.send(json.dumps({"type": "finalize"}))
            await ws.send(json.dumps({"type": "audio.done"}))

        sender = asyncio.create_task(send())
        try:
            async for raw in ws:
                event = json.loads(raw)
                now = time.monotonic()
                events.append(event)
                at = f"{now - stop_at:+6.2f} s" if stop_at else " before"
                kind = event.get("type")
                if kind == "transcript.partial":
                    flags = ("utterance" if event.get("speech_final") else "chunk") if event.get("is_final") else "interim"
                    print(f"{at}  {flags:9}  {event.get('text')!r}")
                else:
                    print(f"{at}  {kind:9}  {json.dumps(event, ensure_ascii=False)[:300]}")
                if kind in ("transcript.done", "error"):
                    break
        finally:
            await sender
    utterances = [e.get("text", "") for e in events
                  if e.get("type") == "transcript.partial" and e.get("is_final") and e.get("speech_final")]
    done = next((e.get("text", "") for e in events if e.get("type") == "transcript.done"), None)
    print("\nutterance finals:", utterances)
    print("transcript.done:  ", repr(done))
    if done is None:
        print("-> no transcript.done arrived")
    elif not done.strip():
        print("-> empty: everything had already been settled by utterance finals")
    elif done.strip() == " ".join(u.strip() for u in utterances).strip():
        print("-> the whole session again: ignoring it once something is settled is right")
    elif utterances and done.strip() == utterances[-1].strip():
        print("-> the last utterance again")
    else:
        print("-> something else: compare with the utterance finals above")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key-file", help="file holding the key (else XAI_API_KEY)")
    parser.add_argument("--wav", help="16 kHz mono PCM16 clip to send")
    parser.add_argument("--say", help="text to speak with xAI's TTS instead of a WAV")
    parser.add_argument("--language", help="pinned language, as the app sends it (omit for auto-detect)")
    parser.add_argument("--keyterm", action="append", default=[], help="a glossary term (repeatable)")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("models")
    batch = sub.add_parser("batch")
    batch.add_argument("--formats", action="store_true", help="try every container the app knows")
    sub.add_parser("stream")
    args = parser.parse_args()

    key = read_key(args)
    if args.cmd == "models":
        cmd_models(args, key)
        return
    if args.say:
        pcm = synthesize(args.say, args.language, key)
    elif args.wav:
        pcm = load_wav(args.wav)
    else:
        sys.exit("Give --wav or --say.")
    if args.cmd == "batch":
        cmd_batch(args, key, pcm)
    else:
        asyncio.run(cmd_stream(args, key, pcm))


if __name__ == "__main__":
    main()
