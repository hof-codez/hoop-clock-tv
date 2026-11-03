import sys,json,sherpa_onnx
D=sys.argv[1]
kw=dict(model=f"{D}/model.onnx",voices=f"{D}/voices.bin",tokens=f"{D}/tokens.txt",data_dir=f"{D}/espeak-ng-data",lexicon=f"{D}/lexicon-us-en.txt,{D}/lexicon-zh.txt",dict_dir=f"{D}/dict")
tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(**kw),num_threads=4)))
T=["You have two left.","You have two minutes left.","You have two minits left.","You have two Minutes left.","Two minutes remaining.","Two minutes to go.","minutes","Minutes","minits","minute","One minute left."]
out={t:round(len(tts.generate(t,sid=16).samples)/24000,2) for t in T}
print(json.dumps(out,indent=1));json.dump(out,open("out/minutes.json","w"),indent=1)
