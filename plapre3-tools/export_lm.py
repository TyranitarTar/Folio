"""Synthesis-only FP32 Llama export with explicit GQA, RoPE and external weights.

No remote model code. Implements the pinned Llama configuration.
Weights remain FP32. Plapre 3's published Transformers 4.57.6 reads rope_theta;
the additional rope_parameters field is ignored there. Do not borrow Pico's fix.
Validate against an independent, unmodified-config Transformers reference.
"""
from pathlib import Path
import json,hashlib
import numpy as np
import onnx
from onnx import helper as H,TensorProto as T,numpy_helper as N
from safetensors import safe_open

ROOT=Path(__file__).resolve().parent
OUT=ROOT/'export';OUT.mkdir(exist_ok=True)
config=json.loads((ROOT/'artifacts/config.json').read_text())
assert (config['hidden_size'],config['num_hidden_layers'],config['num_attention_heads'],config['num_key_value_heads'],config['vocab_size'])==(960,32,15,5,21224)
nodes=[];initializers=[];serial=0
def op(kind,*args,**attrs):
    global serial
    serial+=1;name=f'n{serial}'
    nodes.append(H.make_node(kind,list(args),[name],name=name,**attrs));return name
def constant(name,value,dtype=None):
    initializers.append(N.from_array(np.asarray(value,dtype=dtype),name=name));return name
def shape(*values):return constant(f'shape{len(initializers)}',values,np.int64)
def reshape(value,*values):return op('Reshape',value,shape(*values))
def unsqueeze(value,*axes):return op('Unsqueeze',value,shape(*axes))
def slice_(value,start,end,axis):return op('Slice',value,shape(start),shape(end),shape(axis))

with safe_open(ROOT/'artifacts/model.safetensors',framework='np') as weights,(OUT/'plapre3.onnx.data').open('wb') as data:
    def weight(key,transpose=False):
        value=weights.get_tensor(key)
        assert value.dtype==np.float32,(key,value.dtype)
        if transpose:value=np.ascontiguousarray(value.T)
        name=key+('.T' if transpose else '')
        tensor=T();tensor.name=name;tensor.data_type=T.FLOAT;tensor.dims.extend(value.shape)
        tensor.data_location=T.EXTERNAL
        offset=data.tell();data.write(value.tobytes())
        for key_,value_ in [('location','plapre3.onnx.data'),('offset',str(offset)),('length',str(value.nbytes))]:
            entry=tensor.external_data.add();entry.key=key_;entry.value=value_
        initializers.append(tensor);return name
    eps=constant('epsilon',1e-5,np.float32)
    one=constant('one',1,np.int64);zero=constant('zero',0,np.int64)
    scale=constant('attention_scale',1/8,np.float32)
    negative=constant('negative',np.finfo(np.float32).min,np.float32)
    def rms(x,w):
        variance=op('ReduceMean',op('Mul',x,x),axes=[-1],keepdims=1)
        return op('Mul',op('Div',x,op('Sqrt',op('Add',variance,eps))),w)
    def linear(x,key):return op('MatMul',x,weight(key,True))
    positions=op('Cast','cache_position',to=T.FLOAT)
    theta=config['rope_theta'];assert theta==10000
    inverse=constant('inv_freq',float(theta)**(-np.arange(0,64,2,dtype=np.float32)/64),np.float32)
    angles=op('Mul',unsqueeze(positions,1),unsqueeze(inverse,0))
    angles=op('Concat',angles,angles,axis=1)
    cos=unsqueeze(op('Cos',angles),0,1);sin=unsqueeze(op('Sin',angles),0,1)
    def rope(x):
        rotated=op('Concat',op('Neg',slice_(x,32,64,3)),slice_(x,0,32,3),axis=3)
        return op('Add',op('Mul',x,cos),op('Mul',rotated,sin))
    total=op('Gather',op('Shape','attention_mask'),one,axis=0)
    keys=op('Range',zero,total,one)
    valid=op('GreaterOrEqual',unsqueeze('cache_position',1),unsqueeze(keys,0))
    valid=op('And',valid,op('Cast','attention_mask',to=T.BOOL))
    valid=unsqueeze(valid,0,1)
    sequence=op('Gather',op('Shape','inputs_embeds'),one,axis=0)
    context_shape=op('Concat',shape(1),unsqueeze(sequence,0),shape(960),axis=0)
    kv_repeat_shape=op('Concat',shape(1,5,3),unsqueeze(total,0),shape(64),axis=0)
    kv_final_shape=op('Concat',shape(1,15),unsqueeze(total,0),shape(64),axis=0)
    inputs=[H.make_tensor_value_info('inputs_embeds',T.FLOAT,[1,'sequence',960]),
            H.make_tensor_value_info('attention_mask',T.INT64,[1,'total']),
            H.make_tensor_value_info('cache_position',T.INT64,['sequence'])]
    outputs=[];x='inputs_embeds'
    for layer in range(32):
        prefix=f'model.layers.{layer}.'
        residual=x;x=rms(x,weight(prefix+'input_layernorm.weight'))
        q=rope(op('Transpose',reshape(linear(x,prefix+'self_attn.q_proj.weight'),1,-1,15,64),perm=[0,2,1,3]))
        k=rope(op('Transpose',reshape(linear(x,prefix+'self_attn.k_proj.weight'),1,-1,5,64),perm=[0,2,1,3]))
        v=op('Transpose',reshape(linear(x,prefix+'self_attn.v_proj.weight'),1,-1,5,64),perm=[0,2,1,3])
        for key,value in [('key',k),('value',v)]:
            name=f'past_key_values.{layer}.{key}'
            inputs.append(H.make_tensor_value_info(name,T.FLOAT,[1,5,'past',64]))
            full=op('Concat',name,value,axis=2)
            out=f'present.{layer}.{key}';nodes.append(H.make_node('Identity',[full],[out]))
            outputs.append(H.make_tensor_value_info(out,T.FLOAT,[1,5,'total',64]))
            repeat=op('Reshape',op('Expand',unsqueeze(full,2),kv_repeat_shape),kv_final_shape)
            if key=='key':full_k=repeat
            else:full_v=repeat
        score=op('Mul',op('MatMul',q,op('Transpose',full_k,perm=[0,1,3,2])),scale)
        attended=op('MatMul',op('Softmax',op('Where',valid,score,negative),axis=-1),full_v)
        context=op('Reshape',op('Transpose',attended,perm=[0,2,1,3]),context_shape)
        x=op('Add',residual,linear(context,prefix+'self_attn.o_proj.weight'))
        residual=x;x=rms(x,weight(prefix+'post_attention_layernorm.weight'))
        gate=linear(x,prefix+'mlp.gate_proj.weight')
        x=op('Mul',op('Mul',gate,op('Sigmoid',gate)),linear(x,prefix+'mlp.up_proj.weight'))
        x=op('Add',residual,linear(x,prefix+'mlp.down_proj.weight'))
    x=rms(x,weight('model.norm.weight'))
    logits=linear(x,'model.embed_tokens.weight')
    nodes.append(H.make_node('Identity',[logits],['logits']))
    outputs.insert(0,H.make_tensor_value_info('logits',T.FLOAT,[1,'sequence',21224]))
    embeddings=weights.get_tensor('model.embed_tokens.weight')
    assert embeddings.shape==(21224,960)
    embeddings.astype('<f4').tofile(OUT/'embeddings.bin')
    model=H.make_model(H.make_graph(nodes,'Folio Plapre 3 FP32',inputs,outputs,initializers),opset_imports=[H.make_opsetid('',17)])
    model.ir_version=9
    (OUT/'plapre3.onnx').write_bytes(model.SerializeToString())
onnx.checker.check_model(str(OUT/'plapre3.onnx'))
print(json.dumps({p.name:p.stat().st_size for p in OUT.iterdir() if p.is_file()}),flush=True)
