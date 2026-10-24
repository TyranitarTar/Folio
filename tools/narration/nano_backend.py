"""Low-memory Nano/Røst loader for the pinned, isolated Chatterbox 0.1.7 runtime.

Uses the publisher's GPT2-small architecture and strict checkpoint loading.
The installed runtime/classes on disk are unchanged. No remote model code runs.
Reference: resemble-ai/chatterbox (MIT), tts_turbo.py and llama_configs.py.
"""
from pathlib import Path
import json
from .core import NarrationError


def load_nano(model_dir):
    return load_turbo_weights(model_dir, nano=True)


def load_roest(model_dir):
    return load_turbo_weights(model_dir, nano=False)


def load_turbo_weights(model_dir, nano):
    import torch
    from safetensors.torch import load_file
    from transformers import AutoTokenizer
    from chatterbox.tts_turbo import ChatterboxTurboTTS,Conditionals
    from chatterbox.models.voice_encoder import VoiceEncoder
    from chatterbox.models.s3gen import S3Gen
    from chatterbox.models.t3 import T3
    from chatterbox.models.t3.modules.t3_config import T3Config
    from chatterbox.models.t3.llama_configs import LLAMA_CONFIGS,GPT2_MEDIUM_CONFIG
    folder=Path(model_dir)
    if nano:
        index=json.loads((folder/'folio-component-index.json').read_text(encoding='utf-8'))
        if index.get('engine')!='chatterbox-nano':raise NarrationError('Not a Nano component index')
        files={f['name']:Path(f['path']) for f in index['files']}
    else:
        files={name:folder/name for name in ('t3_turbo_v1.safetensors','s3gen_meanflow.safetensors','ve.safetensors','conds.pt')}
    # GPT2 small differs from the already-supported medium configuration only
    # in width, head count and depth. This is the upstream Nano definition.
    LLAMA_CONFIGS.setdefault('GPT2_small',dict(GPT2_MEDIUM_CONFIG,n_embd=768,hidden_size=768,n_head=12,n_layer=12))
    hp=T3Config(text_tokens_dict_size=50276)
    hp.llama_config_name='GPT2_small' if nano else 'GPT2_medium';hp.speech_tokens_dict_size=6563
    hp.input_pos_emb=None;hp.speech_cond_prompt_len=375;hp.use_perceiver_resampler=False;hp.emotion_adv=False

    def materialize(factory,name):
        # Avoid initializing a second full copy of all weights in scarce RAM.
        with torch.device('meta'):module=factory()
        weights=load_file(str(files[name]),device='cpu')
        module.load_state_dict(weights,strict=True,assign=True)
        del weights
        if hasattr(module,'trim_fade') and module.trim_fade.is_meta:
            n_trim=len(module.trim_fade)//2
            module.trim_fade=torch.zeros(2*n_trim,device='cpu')
            module.trim_fade[n_trim:]=(torch.cos(torch.linspace(torch.pi,0,n_trim,device='cpu'))+1)/2
        # Positional encodings are derived plain attributes, not checkpoint
        # parameters/buffers. Rebuild them on CPU after meta initialization.
        from chatterbox.models.s3gen.transformer.embedding import EspnetRelPositionalEncoding,PositionalEncoding
        for child in module.modules():
            pe=getattr(child,'pe',None)
            if isinstance(pe,torch.Tensor) and pe.is_meta:
                if isinstance(child,EspnetRelPositionalEncoding):
                    length=(pe.shape[1]+1)//2;child.pe=None
                    child.extend_pe(torch.zeros(1,length,device='cpu'))
                elif type(child) is PositionalEncoding:
                    child.__init__(child.d_model,child.dropout.p,child.max_len)
                else:raise NarrationError('Unmaterialized positional encoding in '+type(child).__name__)
            freqs=getattr(child,'freqs_cis',None)
            if isinstance(freqs,torch.Tensor) and freqs.is_meta:
                if type(child).__module__!='s3tokenizer.model_v2':
                    raise NarrationError('Unknown derived rotary frequencies in '+type(child).__name__)
                from s3tokenizer.model_v2 import precompute_freqs_cis
                child.freqs_cis=precompute_freqs_cis(freqs.shape[-1],freqs.shape[0])
            for attribute,value in vars(child).items():
                if isinstance(value,torch.Tensor) and value.is_meta:
                    raise NarrationError('Unmaterialized derived tensor: '+type(child).__name__+'.'+attribute)
        missing=[key for key,value in list(module.named_parameters())+list(module.named_buffers()) if value.is_meta]
        if missing:raise NarrationError('Unmaterialized model parameter or buffer: '+','.join(missing))
        return module.eval()

    ve=materialize(VoiceEncoder,'ve.safetensors')
    t3=materialize(lambda:T3(hp),'t3_nano_v1.safetensors' if nano else 't3_turbo_v1.safetensors')
    del t3.tfmr.wte
    s3gen=materialize(lambda:S3Gen(meanflow=True),'s3gen_meanflow.safetensors')
    tokenizer=AutoTokenizer.from_pretrained(str(folder),local_files_only=True)
    if tokenizer.pad_token is None:tokenizer.pad_token=tokenizer.eos_token
    if len(tokenizer)!=50276:raise NarrationError('Unexpected Turbo text-tokenizer vocabulary')
    conds=Conditionals.load(files['conds.pt'],map_location='cpu').to('cpu')
    return ChatterboxTurboTTS(t3,s3gen,ve,tokenizer,'cpu',conds=conds)
