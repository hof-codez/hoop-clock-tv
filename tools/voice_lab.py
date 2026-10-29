"""Voice lab: render sample lines with Kokoro male voices and time them."""
import os, sys, time, json, wave, subprocess, glob
import numpy as np
import sherpa_onnx

MODEL_DIR = sys.argv[1]
OUT = sys.argv[2]
ARCH = sys.argv[3]
os.makedirs(OUT, exist_ok=True)

RECAP = ("Welcome back to the Hoop Clock recap. Jordell outlasted Dad in overtime, nine to eight. "
         "This one was a heavyweight fight. The lead changed hands three times. "
         "Jordell erased a four point deficit. Never count him out. "
         "The dagger. Jordell took the lead for good with forty seconds left in overtime.")
CALL = "Jordell three, Away team two. You have four minutes left."
VOICES = {11: "am_adam", 12: "am_echo", 13: "am_eric", 14: "am_fenrir", 15: "am_liam",
          16: "am_michael", 17: "am_onyx", 18: "am_puck", 24: "bm_daniel", 25: "bm_fable",
          26: "bm_george", 27: "bm_lewis"}

def build(threads):
    files = os.listdir(MODEL_DIR)
    lex = ",".join(os.path.join(MODEL_DIR, f) for f in ["lexicon-us-en.txt", "lexicon-zh.txt"] if f in files)
    kw = dict(model=os.path.join(MODEL_DIR, "model.onnx"), voices=os.path.join(MODEL_DIR, "voices.bin"),
              tokens=os.path.join(MODEL_DIR, "tokens.txt"), data_dir=os.path.join(MODEL_DIR, "espeak-ng-data"))
    if lex: kw["lexicon"] = lex
    if "dict" in files: kw["dict_dir"] = os.path.join(MODEL_DIR, "dict")
    kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(**kw)
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro, num_threads=threads, provider="cpu"))
    return sherpa_onnx.OfflineTts(cfg)

def save(audio, path):
    s = np.clip(np.array(audio.samples), -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(audio.sample_rate)
        w.writeframes((s * 32767).astype(np.int16).tobytes())

report = {"arch": ARCH, "files": sorted(os.listdir(MODEL_DIR)), "timing": []}
for threads in (1, 2):
    tts = build(threads)
    t = time.time(); a = tts.generate(RECAP, sid=16, speed=1.0); dt = time.time() - t
    dur = len(a.samples) / a.sample_rate
    report["timing"].append({"threads": threads, "audio_sec": round(dur, 2), "compute_sec": round(dt, 2), "rtf": round(dt / dur, 3)})
tts = build(2)
for sid, name in VOICES.items():
    a = tts.generate(RECAP, sid=sid, speed=1.0)
    save(a, f"{OUT}/{name}_recap.wav")
    a = tts.generate(CALL, sid=sid, speed=1.05)
    save(a, f"{OUT}/{name}_call.wav")
for wav in glob.glob(f"{OUT}/*.wav"):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-b:a", "96k", wav[:-4] + ".mp3"], check=True)
    os.remove(wav)
json.dump(report, open(f"{OUT}/report.json", "w"), indent=1)
print(json.dumps(report, indent=1))
