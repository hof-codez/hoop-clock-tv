import os, sys, json, sherpa_onnx
D = sys.argv[1]; OUT = sys.argv[2]; os.makedirs(OUT, exist_ok=True)
def mk(lexicon, lang=""):
    kw = dict(model=f"{D}/model.onnx", voices=f"{D}/voices.bin", tokens=f"{D}/tokens.txt", lexicon=lexicon, data_dir=f"{D}/espeak-ng-data", dict_dir=f"{D}/dict")
    try: kw["lang"] = lang; k = sherpa_onnx.OfflineTtsKokoroModelConfig(**kw)
    except TypeError: kw.pop("lang"); k = sherpa_onnx.OfflineTtsKokoroModelConfig(**kw)
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=k, num_threads=4)))
words = ["Jordell", "Kamryn", "HofCodez", "Fells", "Dad", "basketball", "Ja'Kobe", "Xzavier", "the Iron Bolts"]
lex_words = set(l.split()[0].lower() for l in open(f"{D}/lexicon-us-en.txt", encoding="utf-8") if l.strip())
rep = {}
import traceback
for label, lex, lang in [("us_only", f"{D}/lexicon-us-en.txt", "en-us"), ("us_plus_zh", f"{D}/lexicon-us-en.txt,{D}/lexicon-zh.txt", "")]:
    try: tts = mk(lex, lang)
    except Exception as e: rep[label] = "load failed: " + str(e); continue
    r = {}
    for w in words:
        try:
            a = tts.generate(w, sid=16, speed=1.0); r[w] = round(len(a.samples) / a.sample_rate, 2)
        except Exception as e:
            r[w] = "error: " + str(e)[:120]
    a = tts.generate("Jordell three, Dad two.", sid=16); r["sentence: Jordell three, Dad two."] = round(len(a.samples) / a.sample_rate, 2)
    a = tts.generate("Home three, Away two.", sid=16); r["sentence: Home three, Away two."] = round(len(a.samples) / a.sample_rate, 2)
    rep[label] = r
rep["in_lexicon"] = {w: (w.lower() in lex_words) for w in words}
rep["version"]=sherpa_onnx.__version__
json.dump(rep, open(f"{OUT}/names.json", "w"), indent=1); print(json.dumps(rep, indent=1))
