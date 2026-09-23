# Research — vision auto-verify loop (Phase E item 2)

Date: 2026-09-23. Decision: **ship as opt-in `build.verify-vision` (default OFF), 1 repair pass, never rewrite a pasted JSON spec.**

## What "verify" can actually see

GHBot already has a **server-side isometric PNG** (`BuildPreviewImage.render`) — the previous
note that "no rasterizer exists" was wrong; viewer.html is the 3D client, the PNG is the
thing we can send to a vision model without a player screenshot.

Asking vision to *reconstruct* a full jsonspec from a ~200 px isometric is lossy and
fights the v0.21.39 contract ("pasted spec = truth"). So verify is a **checklist**, not
a redesign:

```
{"ok": true|false, "reason": "one sentence", "notes": ["…"]}
```

`ok=true` if the preview reasonably matches name / palette / scale. `ok=false` only for
important misses (roof, door, palette, size). If the model cannot see the image →
`ok=true` with reason `"cannot see image"` so we never "repair" blindly.

## Provider reality (owner's box)

| Provider | Transport | Usable for verify? |
|---|---|---|
| Gemini 2.5-flash | `inline_data` base64 | **Yes** when enabled |
| Ollama | `/api/chat` `images:[]` | **Only** multimodal models (`llava`, `qwen2-vl`, `minicpm`, `minimax`, …). `qwen2.5:0.5b` is text-only — we skip it even if the transport exists |
| Pollinations / extras | OpenAI chat, no image field in our client | **No** |
| fallback | none | **No** |

Owner's chat works on ollama and emits `⟦tool:…⟧`. That does **not** mean the model can
see PNGs. Flag default OFF; when ON with no usable vision provider the admin gets an
honest skip, not a fake PASS.

## Repair policy (1 pass, bounded)

- **Pasted JSON spec (the contract):** notes only. Never restage. The spec is the truth.
- **AI-generated build + `ok=false`:** append vision notes to the original prompt,
  `generateSpec` once, restage. Memory flag `vision-verify-done` kills a second loop.
- **`--direct`:** skip (already placing; no ghost to restage).
- Malformed / empty vision reply → `parsed=false` → **no repair** (don't make it worse).

## Why not world re-scan?

A post-place rescan compares voxels we ourselves just wrote — it cannot catch "the AI
forgot the roof". Vision-on-preview catches *design* drift before approve. World
fingerprint already exists (undo drift-guard). Different job.

## Not in this ship

- Reviving shelved `image` command (freeze-policy).
- FAWE / screenshot-from-client.
- Multi-pass repair, or letting vision overwrite a contract spec.
