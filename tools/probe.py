"""Quantize the Kokoro v1.0 model to int8 and time it against the full model."""
import os, sys, time, json, wave, subprocess
import numpy as np, sherpa_onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
D = sys.argv[1]; OUT = sys.argv[2]; os.makedirs(OUT, exist_ok=True)
quantize_dynamic(f"{D}/model.onnx", f"{D}/model.int8.onnx", weight_type=QuantType.QUInt8)
TEXT = ("Welcome back to the Hoop Clock recap. Jordell outlasted Dad in overtime, nine to eight. "
        "The dagger. Jordell took the lead for good with forty seconds left in overtime.")
rep = {"sizes_mb": {f: round(os.path.getsize(f"{D}/{f}") / 1e6, 1) for f in os.listdir(D) if os.path.isfile(f"{D}/{f}")}}
for name in ["model.onnx", "model.int8.onnx"]:
    kw = dict(model=f"{D}/{name}", voices=f"{D}/voices.bin", tokens=f"{D}/tokens.txt", data_dir=f"{D}/espeak-ng-data",
              lexicon=f"{D}/lexicon-us-en.txt,{D}/lexicon-zh.txt", dict_dir=f"{D}/dict")
    for threads in (1, 4):
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(**kw), num_threads=threads, provider="cpu")))
        t = time.time(); a = tts.generate(TEXT, sid=16, speed=1.0); dt = time.time() - t
        dur = len(a.samples) / a.sample_rate
        rep[f"{name} x{threads}"] = {"audio_sec": round(dur, 2), "compute_sec": round(dt, 2), "rtf": round(dt / dur, 3)}
    s = np.clip(np.array(a.samples), -1, 1)
    with wave.open(f"{OUT}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate); w.writeframes((s * 32767).astype(np.int16).tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"{OUT}/{name}.wav", "-b:a", "96k", f"{OUT}/{name}.mp3"], check=True)
    os.remove(f"{OUT}/{name}.wav")
json.dump(rep, open(f"{OUT}/probe.json", "w"), indent=1); print(json.dumps(rep, indent=1))
