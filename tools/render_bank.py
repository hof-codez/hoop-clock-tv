"""Render the Hoop Clock clip bank in one Kokoro voice."""
import os, sys, json, base64, subprocess, wave
import numpy as np
import sherpa_onnx
MODEL_DIR, LINES, SID, OUT = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
os.makedirs(OUT, exist_ok=True)
f = os.listdir(MODEL_DIR)
kw = dict(model=f"{MODEL_DIR}/model.onnx", voices=f"{MODEL_DIR}/voices.bin", tokens=f"{MODEL_DIR}/tokens.txt", data_dir=f"{MODEL_DIR}/espeak-ng-data")
lex = ",".join(f"{MODEL_DIR}/{x}" for x in ["lexicon-us-en.txt", "lexicon-zh.txt"] if x in f)
if lex: kw["lexicon"] = lex
if "dict" in f: kw["dict_dir"] = f"{MODEL_DIR}/dict"
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(**kw), num_threads=4, provider="cpu")))
lines = json.load(open(LINES)); out = {}
for k, text in lines.items():
    fast = k.startswith("n") and k[1:].isdigit()
    a = tts.generate(text, sid=SID, speed=1.12 if fast else 1.0)
    x = np.array(a.samples, dtype=np.float32); sr = a.sample_rate
    # trim silence at both ends
    env = np.abs(x) > 0.01
    if env.any():
        i0 = max(0, np.argmax(env) - int(0.03 * sr)); i1 = min(len(x), len(x) - np.argmax(env[::-1]) + int(0.06 * sr)); x = x[i0:i1]
    # level: speech RMS to -20 dBFS, peaks capped at -4 dBFS
    hop = int(sr * 0.02); n = len(x) // hop
    fr = np.sqrt(np.mean(x[:n * hop].reshape(n, hop) ** 2, axis=1) + 1e-12) if n else np.array([1e-3])
    act = fr[fr > np.percentile(fr, 40)]; rms = np.sqrt(np.mean(act ** 2))
    x = x * min(10 ** (-20 / 20) / rms, 10 ** (-4 / 20) / (np.max(np.abs(x)) + 1e-9))
    fi, fo = int(sr * 0.01), int(sr * 0.04)
    x[:fi] *= np.linspace(0, 1, fi); x[-fo:] *= np.linspace(1, 0, fo)
    with wave.open("t.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "t.wav", "-af", "highpass=f=70", "-c:a", "libmp3lame", "-b:a", "96k", f"{OUT}/{k}.mp3"], check=True)
    out[k] = base64.b64encode(open(f"{OUT}/{k}.mp3", "rb").read()).decode()
json.dump(out, open(f"{OUT}/vox_kokoro.json", "w"))
print(len(out), "clips", sum(len(v) for v in out.values()) // 1024, "KB base64")
