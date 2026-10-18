// Folio Pico bridge. ONNX Runtime headers: Microsoft, MIT (see include notices).
#include <jni.h>
#include "onnxruntime_cxx_api.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <fstream>
#include <functional>
#include <memory>
#include <mutex>
#include <numeric>
#include <random>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
std::mutex engines_mutex;
std::atomic<jlong> next_id{1};
struct Engine {
    const bool plapre3;
    const size_t hidden, vocabulary, cache_count, kv_heads;
    Ort::Env environment{ORT_LOGGING_LEVEL_WARNING, "Folio Pico"};
    Ort::SessionOptions options;
    Ort::RunOptions run_options;
    std::unique_ptr<Ort::Session> language, kanade, hift;
    std::vector<float> embeddings;
    std::vector<std::string> input_names, output_names;
    std::vector<const char*> inputs, outputs;
    std::atomic<bool> cancelled{false};
    std::mutex profile_mutex;
    std::array<jlong,7> last_profile{}; // prefill, decode, sampling, Kanade, HiFT, total ns; code count.
    Engine(const std::string& folder, int threads, bool larger=false)
        : plapre3(larger),hidden(larger?960:576),vocabulary(larger?21224:20802),
          cache_count(larger?64:60),kv_heads(larger?5:3) {
        options.SetIntraOpNumThreads(threads);
        options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        // Independent idle pools must not busy-spin while another synthesis stage runs.
        if(plapre3) options.AddConfigEntry("session.intra_op.allow_spinning","0");
        language = std::make_unique<Ort::Session>(environment, (folder+(plapre3?"/plapre3.onnx":"/pico.onnx")).c_str(), options);
        kanade = std::make_unique<Ort::Session>(environment, (folder+"/kanade.onnx").c_str(), options);
        hift = std::make_unique<Ort::Session>(environment, (folder+"/hift.onnx").c_str(), options);
        std::ifstream file(folder+"/embeddings.bin", std::ios::binary|std::ios::ate);
        if (!file || file.tellg() != static_cast<std::streamoff>(vocabulary*hidden*sizeof(float)))
            throw std::runtime_error("Pico embedding table has an unexpected size.");
        embeddings.resize(vocabulary*hidden);
        file.seekg(0); file.read(reinterpret_cast<char*>(embeddings.data()), embeddings.size()*sizeof(float));
        if (!file) throw std::runtime_error("Pico embedding table could not be read.");
        if (language->GetInputCount()!=cache_count+3 || language->GetOutputCount()!=cache_count+1)
            throw std::runtime_error("Pico model signature does not match this app.");
        Ort::AllocatorWithDefaultOptions allocator;
        for(size_t i=0;i<cache_count+3;i++) input_names.emplace_back(language->GetInputNameAllocated(i,allocator).get());
        for(size_t i=0;i<cache_count+1;i++) output_names.emplace_back(language->GetOutputNameAllocated(i,allocator).get());
        for(auto& name:input_names) inputs.push_back(name.c_str());
        for(auto& name:output_names) outputs.push_back(name.c_str());
    }
    void check() {
        if(cancelled.load()) throw std::runtime_error("Pico generation cancelled.");
    }
    std::vector<float> generate(const std::vector<int>& prompt, std::vector<float>& prefix,
                                std::vector<float>& voice, int eos, int audio_start, uint32_t seed,
                                const std::function<bool()>& externally_cancelled) {
        cancelled.store(false); run_options.UnsetTerminate();
        auto check=[&] { if(cancelled.load() || externally_cancelled()) throw std::runtime_error("Pico generation cancelled."); };
        check();
        auto beginning=std::chrono::steady_clock::now();
        auto nanos=[](auto start) {return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();};
        std::array<jlong,7> timing{};
        auto memory=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
        std::vector<Ort::Value> cache;
        std::array<int64_t,4> empty_shape{1,static_cast<int64_t>(kv_heads),0,64}; float dummy=0;
        for(size_t i=0;i<cache_count;i++) cache.push_back(Ort::Value::CreateTensor<float>(memory,&dummy,0,empty_shape.data(),4));
        int64_t position=0;
        auto step=[&](float* embedding, size_t sequence=1) {
            check();
            std::array<int64_t,3> embed_shape{1,static_cast<int64_t>(sequence),static_cast<int64_t>(hidden)};
            std::array<int64_t,2> mask_shape{1,position+static_cast<int64_t>(sequence)};
            std::array<int64_t,1> pos_shape{static_cast<int64_t>(sequence)};
            std::vector<int64_t> mask(static_cast<size_t>(position)+sequence,1),positions(sequence);
            std::iota(positions.begin(),positions.end(),position);
            std::vector<Ort::Value> values;
            values.reserve(cache_count+3);
            values.push_back(Ort::Value::CreateTensor<float>(memory,embedding,hidden*sequence,embed_shape.data(),3));
            values.push_back(Ort::Value::CreateTensor<int64_t>(memory,mask.data(),mask.size(),mask_shape.data(),2));
            values.push_back(Ort::Value::CreateTensor<int64_t>(memory,positions.data(),positions.size(),pos_shape.data(),1));
            for(auto& value:cache) values.push_back(std::move(value));
            auto result=language->Run(run_options,inputs.data(),values.data(),values.size(),outputs.data(),outputs.size());
            const float* last=result[0].GetTensorData<float>()+(sequence-1)*vocabulary;
            std::vector<float> logits(last,last+vocabulary);
            cache.clear();
            for(size_t i=1;i<result.size();i++) cache.push_back(std::move(result[i]));
            position+=static_cast<int64_t>(sequence);
            return logits;
        };
        std::vector<float> logits;
        if(plapre3) {
            // A whole-prompt prefill avoids one native inference call per text token.
            std::vector<float> prefill(prefix);
            for(auto token:prompt) {
                if(token<0 || static_cast<size_t>(token)>=vocabulary) throw std::runtime_error("Invalid Plapre 3 prompt token.");
                auto* begin=embeddings.data()+static_cast<size_t>(token)*hidden;
                prefill.insert(prefill.end(),begin,begin+hidden);
            }
            logits=step(prefill.data(),prompt.size()+1);
        } else {
        logits=step(prefix.data());
        for(auto token:prompt) {
            if(token<0 || static_cast<size_t>(token)>=vocabulary) throw std::runtime_error("Invalid Pico prompt token.");
            logits=step(embeddings.data()+static_cast<size_t>(token)*hidden);
        }
        }
        timing[0]=nanos(beginning);
        std::mt19937 random(seed);
        std::vector<int64_t> codes;
        std::vector<size_t> indices(vocabulary); std::iota(indices.begin(),indices.end(),0);
        bool stopped=false;
        auto started=std::chrono::steady_clock::now();
        for(int iteration=0;iteration<500;iteration++) {
            check();
            if(std::chrono::steady_clock::now()-started > std::chrono::seconds(120))
                throw std::runtime_error("Pico is too slow for this passage. Try a shorter passage or another voice engine.");
            auto sampling_started=std::chrono::steady_clock::now();
            std::partial_sort(indices.begin(),indices.begin()+50,indices.end(),[&](size_t a,size_t b){return logits[a]==logits[b] ? a<b : logits[a]>logits[b];});
            std::vector<double> probabilities;
            double total=0;
            for(size_t i=0;i<50;i++) {
                double probability=std::exp((logits[indices[i]]-logits[indices[0]])/0.7);
                if(!std::isfinite(probability)) throw std::runtime_error("Pico returned invalid probabilities.");
                probabilities.push_back(probability); total+=probability;
            }
            double cumulative=0; size_t keep=0;
            do {cumulative+=probabilities[keep++];} while(keep<50 && cumulative<0.95*total);
            probabilities.resize(keep);
            std::discrete_distribution<size_t> sample(probabilities.begin(),probabilities.end());
            int token=static_cast<int>(indices[sample(random)]);
            timing[2]+=nanos(sampling_started);
            if(token==eos || (plapre3 && token==21223)) {stopped=true;break;}
            if(token<audio_start || token>=audio_start+12800) throw std::runtime_error("Pico returned an invalid audio token.");
            codes.push_back(token-audio_start);
            logits=step(embeddings.data()+static_cast<size_t>(token)*hidden);
        }
        timing[1]=nanos(started)-timing[2];
        // Exhausting a safety bound is never evidence that the sentence finished.
        if(!stopped || codes.empty()) throw std::runtime_error("Pico did not finish this passage cleanly. Your listening position is kept.");
        cache.clear(); check();
        std::array<int64_t,1> tokens_shape{static_cast<int64_t>(codes.size())};
        std::array<int64_t,2> speaker_shape{1,128};
        std::array<Ort::Value,2> acoustic{
            Ort::Value::CreateTensor<int64_t>(memory,codes.data(),codes.size(),tokens_shape.data(),1),
            Ort::Value::CreateTensor<float>(memory,voice.data(),voice.size(),speaker_shape.data(),2)};
        const char* kanade_inputs[]{"tokens","speaker"}; const char* kanade_outputs[]{"mel"};
        auto acoustic_started=std::chrono::steady_clock::now();
        auto mel=kanade->Run(run_options,kanade_inputs,acoustic.data(),2,kanade_outputs,1);
        timing[3]=nanos(acoustic_started);
        check();
        auto waveform_started=std::chrono::steady_clock::now();
        size_t samples=codes.size()*960;
        std::vector<float> noise(samples*9), initial(9);
        std::normal_distribution<float> gaussian(0,1); std::uniform_real_distribution<float> uniform(0,1);
        for(auto& value:initial) value=uniform(random); initial[0]=0;
        for(auto& value:noise) value=gaussian(random);
        std::array<int64_t,3> noise_shape{1,static_cast<int64_t>(samples),9};
        std::array<int64_t,2> initial_shape{1,9};
        std::array<Ort::Value,3> waveform_inputs{
            std::move(mel[0]),
            Ort::Value::CreateTensor<float>(memory,noise.data(),noise.size(),noise_shape.data(),3),
            Ort::Value::CreateTensor<float>(memory,initial.data(),initial.size(),initial_shape.data(),2)};
        const char* wave_names[]{"mel","noise","initial_phase"}; const char* wave_outputs[]{"waveform"};
        auto wave=hift->Run(run_options,wave_names,waveform_inputs.data(),3,wave_outputs,1);
        timing[4]=nanos(waveform_started);
        check();
        if(wave[0].GetTensorTypeAndShapeInfo().GetElementCount()!=samples)
            throw std::runtime_error("Pico waveform duration is inconsistent.");
        const auto* data=wave[0].GetTensorData<float>();
        timing[5]=nanos(beginning); timing[6]=codes.size();
        {std::lock_guard<std::mutex> lock(profile_mutex);last_profile=timing;}
        return {data,data+samples};
    }
};
std::unordered_map<jlong,std::shared_ptr<Engine>> engines;
std::shared_ptr<Engine> get(jlong id) {
    std::lock_guard<std::mutex> lock(engines_mutex);
    auto found=engines.find(id);
    if(found==engines.end()) throw std::runtime_error("Pico engine is closed.");
    return found->second;
}
void error(JNIEnv* env,const std::exception& e) {env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
}

extern "C" JNIEXPORT jlong JNICALL Java_app_folio_reader_PicoNative_open(JNIEnv* env,jobject,jstring folder,jint threads) {
    try {
        if(threads<1 || threads>8) throw std::runtime_error("Unsupported Pico thread count.");
        const char* raw=env->GetStringUTFChars(folder,nullptr);
        std::string path(raw); env->ReleaseStringUTFChars(folder,raw);
        auto engine=std::make_shared<Engine>(path,threads);
        auto id=next_id.fetch_add(1);
        std::lock_guard<std::mutex> lock(engines_mutex); engines[id]=engine; return id;
    } catch(const std::exception& e) {error(env,e);return 0;}
}
extern "C" JNIEXPORT jlong JNICALL Java_app_folio_reader_PicoNative_openPlapre3(JNIEnv* env,jobject,jstring folder,jint threads) {
    try {
        if(sizeof(void*)!=8) throw std::runtime_error("Plapre 3 needs a 64-bit Android device.");
        if(threads<1 || threads>8) throw std::runtime_error("Unsupported Plapre 3 thread count.");
        const char* raw=env->GetStringUTFChars(folder,nullptr);
        std::string path(raw);env->ReleaseStringUTFChars(folder,raw);
        auto engine=std::make_shared<Engine>(path,threads,true);
        auto id=next_id.fetch_add(1);
        std::lock_guard<std::mutex> lock(engines_mutex);engines[id]=engine;return id;
    } catch(const std::exception& e) {error(env,e);return 0;}
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_app_folio_reader_PicoNative_generate(JNIEnv* env,jobject,jlong id,jintArray prompt,jfloatArray prefix,jfloatArray speaker,jint eos,jint audio_start,jint seed,jobject cancellation,jlong expected) {
    try {
        auto engine=get(id);
        if(static_cast<size_t>(env->GetArrayLength(prefix))!=engine->hidden || env->GetArrayLength(speaker)!=128 || env->GetArrayLength(prompt)>512 || eos!=0 || audio_start!=8002)
            throw std::runtime_error("Pico input configuration is invalid.");
        std::vector<int> tokens(env->GetArrayLength(prompt)); std::vector<float> projected(engine->hidden),voice(128);
        env->GetIntArrayRegion(prompt,0,tokens.size(),tokens.data());
        env->GetFloatArrayRegion(prefix,0,engine->hidden,projected.data()); env->GetFloatArrayRegion(speaker,0,128,voice.data());
        auto counter_class=env->GetObjectClass(cancellation);
        auto counter_get=env->GetMethodID(counter_class,"get","()J");
        if(!counter_get) throw std::runtime_error("Pico cancellation counter is unavailable.");
        auto audio=engine->generate(tokens,projected,voice,eos,audio_start,static_cast<uint32_t>(seed),
            [&] {return env->CallLongMethod(cancellation,counter_get)!=expected;});
        auto result=env->NewFloatArray(audio.size());
        if(result) env->SetFloatArrayRegion(result,0,audio.size(),audio.data());
        return result;
    } catch(const std::exception& e) {error(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_app_folio_reader_PicoNative_cancel(JNIEnv*,jobject,jlong id) {
    try {auto engine=get(id);engine->cancelled.store(true);engine->run_options.SetTerminate();} catch(...) {}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_app_folio_reader_PicoNative_profile(JNIEnv* env,jobject,jlong id) {
    try {
        auto engine=get(id);std::lock_guard<std::mutex> lock(engine->profile_mutex);
        auto result=env->NewLongArray(engine->last_profile.size());
        if(result) env->SetLongArrayRegion(result,0,engine->last_profile.size(),engine->last_profile.data());
        return result;
    }catch(const std::exception& e){error(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_app_folio_reader_PicoNative_close(JNIEnv*,jobject,jlong id) {
    std::lock_guard<std::mutex> lock(engines_mutex);engines.erase(id);
}
