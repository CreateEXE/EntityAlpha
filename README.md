# Project.EXE — Android Native

Fully offline AI companion running llama.cpp on-device via JNI.
Dual-LLM soul pipeline (P→F→P→F→P) adapted for single-model mobile deployment.

## Quick Start

### 1. Clone with submodules
```bash
git clone --recurse-submodules https://github.com/yourname/ProjectEXE.git
```

### 2. Add a GGUF model
```bash
adb push qwen2.5-1.5b-instruct-q4_k_m.gguf \
  /sdcard/Android/data/com.projectexe/files/models/
```
Recommended for Revvl 7: `qwen2.5-1.5b-instruct-q4_k_m.gguf` (~1GB, ~20 tok/s)
Download from: https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF

### 3. Build & install
```bash
./gradlew installDebug
```

## GitHub Actions

| Workflow | Trigger | Output |
|----------|---------|--------|
| `ci.yml` | Push / PR | Lint report + debug APK |
| `release.yml` | `v*` tag | Signed APK + AAB + GitHub Release |
| `nightly.yml` | 3 AM UTC | Fresh build + llama.cpp update check |

### Secrets required for release workflow
| Secret | Value |
|--------|-------|
| `ANDROID_KEYSTORE` | `base64 -w0 your.jks` |
| `KEY_ALIAS` | Keystore alias |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

## Architecture

- **Phase 1 (Nodes 1-3):** Ingestion → Security Guardian → Sanitized payload
- **Phase 2 (Nodes 4-8):** Parallel: semantic recall, mood, factual flags, aesthetic embedding, relational weight
- **Phase 3 (Nodes 9-32):** Integration hub + internal monologue (stub → plug in your ML models)
- **Phase 4 (Nodes 33-36):** Dual-hemisphere P1→F1→P2→F2→P3 soul pipeline
- **Phase 5 (Nodes 37-45):** Avatar/voice mapping, memory sync, diagnostics

## Pipeline Modes
| Mode | Stages | ~Time (3B model) |
|------|--------|------------------|
| FULL | P1→F1→P2→F2→P3 | 40–60s |
| PERSONA_ONLY | P1→P3 | 12–18s |
| QUICK | P1 only | 5–8s |

## Personality Presets (Debug tab)
- **EXE Default** — canonical chaotic companion
- **Cold Logic** — maximum factual, minimal persona (stress-tests F1/F2)
- **Unhinged Creative** — maximum temperature (stress-tests hallucination + correction)
- **Warm Companion** — high empathy, low chaos
- **Terse Debug** — 1-3 sentence max (rapid iteration)
- **Gothic Verbose** — maximum verbosity + gothic aesthetic

## Hardware
Optimized for: T-Mobile Revvl 7 (Wingtech) — Snapdragon 6 Gen 1, Cortex-A78, 6GB RAM
ARM flags: `-march=armv8.2-a+dotprod+i8mm+fp16`
GPU backend: CPU-only (Adreno 710 Vulkan too unstable with llama.cpp)
