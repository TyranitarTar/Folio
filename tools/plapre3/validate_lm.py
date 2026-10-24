"""Independent Transformers vs ONNX FP32 checks, prefill and cached decoding."""
from pathlib import Path
import sys,json
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'reference-deps'))
import numpy as np
import torch,onnxruntime as ort
from safetensors.torch import load_file
from transformers import LlamaConfig,LlamaForCausalLM
from transformers.models.llama.modeling_llama import LlamaRotaryEmbedding
from tokenizers import Tokenizer
torch.set_num_threads(4)
config=json.loads((ROOT/'artifacts/config.json').read_text())
cfg=LlamaConfig.from_dict(config);cfg._attn_implementation='eager'
with torch.device('meta'): reference=LlamaForCausalLM(cfg)
state=load_file(str(ROOT/'artifacts/model.safetensors'))
state['lm_head.weight']=state['model.embed_tokens.weight']
reference.load_state_dict(state,assign=True,strict=True);reference.eval()
reference.model.rotary_emb=LlamaRotaryEmbedding(cfg,device='cpu')
options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_DISABLE_ALL
options.add_session_config_entry('session.intra_op.allow_spinning','0')
session=ort.InferenceSession(str(ROOT/'export/plapre3.onnx'),options,providers=['CPUExecutionProvider'])
tokenizer=Tokenizer.from_file(str(ROOT/'artifacts/tokenizer.json'))
voices=json.loads((ROOT/'reference/plapre/speakers.json').read_text())
projection=torch.load(ROOT/'artifacts/speaker_proj.pt',map_location='cpu',weights_only=True)
voice=torch.tensor(voices['ida'],dtype=torch.float32)
prefix=torch.nn.functional.linear(voice,projection['weight'],projection['bias'])
ids=[8000]+tokenizer.encode('Hun åbner sin bog og læser videre.',add_special_tokens=False).ids+[8001]
embeddings=state['model.embed_tokens.weight']
prompt=torch.cat([prefix[None],embeddings[ids]],0)[None]
cache=[np.zeros((1,5,0,64),np.float32) for _ in range(64)]
past=None;position=0;errors=[]
with torch.inference_mode():
 for iteration in range(4):
    size=prompt.shape[1]
    positions=np.arange(position,position+size,dtype=np.int64)
    mask=np.ones((1,position+size),np.int64)
    feed={'inputs_embeds':prompt.numpy(),'attention_mask':mask,'cache_position':positions}
    feed.update(zip([i.name for i in session.get_inputs()][3:],cache))
    actual=session.run(None,feed);cache=actual[1:]
    expected=reference(inputs_embeds=prompt,attention_mask=torch.from_numpy(mask),
        position_ids=torch.from_numpy(positions[None]),past_key_values=past,use_cache=True)
    past=expected.past_key_values
    delta=float(np.max(np.abs(expected.logits.numpy()-actual[0])))
    assert delta<0.001,(iteration,delta)
    for index,tensor in enumerate(past.to_legacy_cache()):
        for side in range(2):
            np.testing.assert_allclose(cache[index*2+side],tensor[side].numpy(),rtol=.001,atol=.001)
    errors.append(dict(step=iteration,sequence=size,maxAbsoluteLogitError=delta,
                       sameTopToken=int(expected.logits[0,-1].argmax())==int(actual[0][0,-1].argmax())))
    assert errors[-1]['sameTopToken']
    position+=size;prompt=embeddings[8002+iteration*127][None,None]
report=dict(passed=True,reference='Transformers 4.57.6 eager Llama, pinned original FP32 weights',
            ropeTheta=cfg.rope_theta,prefillAndCachedDecode=errors)
(ROOT/'results').mkdir(exist_ok=True)
(ROOT/'results/lm-reference.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2),flush=True)
