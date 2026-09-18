# NPC skins

The eight runtime files in `src/main/resources/assets/sbwnpc/textures/entity/` are **64 × 64 RGBA PNGs**. Existing filenames and faction bindings are unchanged.

Clothing was redesigned using the built-in imagegen tool. Generated source artwork was 1254 × 1254 and did not reliably follow Minecraft UV coordinates; selected clothing panels were therefore resampled with nearest-neighbor sampling and packed into the standard 64 × 64 atlas. The high-resolution drafts are not runtime assets.

The original head region (rows 0–15, including face and hat layer) is retained pixel-for-pixel to preserve mob identity. These are intentionally retained portions of the previous textures, not newly authored head designs. All body, arm and leg surfaces use the new clothing. Body overlay layers are empty; base-layer details work with the current HumanoidModel. Both legacy shared limb UVs and separate left limb islands are populated.

Palette families: olive green for cat/cow/creeper/pig; tan and cream for ender/sheep; graphite with muted grey/khaki for panda/wither.

## Verification

All eight files were reopened and checked for actual 64 × 64 dimensions, exact equality of the original head region, and fully opaque base body UV faces. An enlarged front/back pixel preview was inspected. The preview is a documentation image, not a skin texture. In-game rendering has not been tested.

![Enlarged front/back pixel preview](preview.png)

## Generation prompt

Built-in imagegen was used, with one original skin as the edit target per call. Common prompt:

> Use case: precise-object-edit. Edit target: supplied Minecraft classic humanoid skin texture. Create an original replacement military NPC skin for this project, retaining its mob identity and dominant palette but REDESIGN ALL CLOTHING from scratch: coherent utilitarian field jacket, distinct chest webbing and compact pouches, reinforced elbows and knees, dark boots, restrained angular camouflage with a small original two-bar unit insignia instead of national flags. Keep the original mob face visually identical and readable. Output ONLY a flat standard Minecraft 64x64 UV skin atlas, enlarged to 1024x1024 with each source pixel an exact 16x16 solid block. Preserve EXACT UV locations and scale of all six faces of head, torso, classic FOUR-pixel-wide arms, and legs. No model render, no perspective, no labels, no grid lines, no border, no shading outside pixel art. Unused UV areas transparent. Base body opaque. Head UV stays in top 16 rows, torso/right arm/right leg rows 16-31; left leg at x16-31 y48-63, left arm x32-47 y48-63. Clothing must be a new authored design, not a recolor of the reference. No weapons or extra objects. Crisp low-resolution pixel art, no antialiasing.

Per-skin palette additions:

- **cat**: Mob: pale grey CAT, yellow eyes. Palette: olive green fatigues, moss/dark green webbing, charcoal boots.
- **cow**: COW face with brown and white fur. Olive and forest green fatigues, dark olive load-bearing vest.
- **creeper**: CREEPER bright green face. Green dominant uniform, sage and forest-green angular camo, charcoal webbing.
- **ender**: ENDERMAN black face with violet eyes. Tan/brown dominant desert fatigues, dark umber vest, very restrained violet unit insignia.
- **panda**: PANDA white/black face. Dark graphite and desaturated grey dominant fatigues and charcoal webbing.
- **pig**: PIG pink face. Muted olive and moss green dominant fatigues, dark green vest.
- **sheep**: SHEEP white wool and beige/pink face. Cream/sand dominant desert fatigues, warm khaki webbing.
- **wither**: WITHER dark skull face. Charcoal dominant fatigues with subdued stone-grey/khaki panels and webbing.

