"""Correct the public ONNX export's rotary base to the model's explicit config.

The source graph bakes in legacy rope_theta=10000, but rope_parameters specifies
100000 (also used by the independently published CoreML conversion). Keep the
upstream artifacts intact and write derived, checksummed deployment files.
"""
import json
import numpy as np
import onnx
from onnx import numpy_helper
from probe_lm import ROOT

config=json.loads((ROOT/"artifacts/pico/config.json").read_text())
theta=config["rope_parameters"]["rope_theta"]
assert theta==100000
target=ROOT/"export";target.mkdir(exist_ok=True)
for precision,name in [("int8","model_decode_int8.onnx"),("fp32","model_decode.onnx")]:
    model=onnx.load(str(ROOT/"artifacts/pico/onnx"/name))
    candidates=[i for i in model.graph.initializer if i.name=="expand_2"]
    assert len(candidates)==1
    old=numpy_helper.to_array(candidates[0])
    legacy=(10000.0**(-np.arange(0,64,2,dtype=np.float32)/64)).reshape(1,32,1)
    np.testing.assert_allclose(old,legacy,rtol=1e-6)
    corrected=(float(theta)**(-np.arange(0,64,2,dtype=np.float32)/64)).astype(np.float32).reshape(1,32,1)
    candidates[0].CopyFrom(numpy_helper.from_array(corrected,name="expand_2"))
    output=target/("pico.onnx" if precision=="int8" else "pico-fp32.onnx")
    onnx.save(model,str(output))
    print(f"Corrected {precision} RoPE: {output.stat().st_size} bytes",flush=True)
