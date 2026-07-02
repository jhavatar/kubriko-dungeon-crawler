# Visual Rendering Checklist

Run these checks after any change to `DungeonRendererManager`, `DungeonViewActor`, `DrawCommand`, or related rendering code. Each check targets a specific geometric case that has historically been easy to break silently.

---

## Setup

Start the app: `./gradlew :app:run`

Switch between render modes by editing `App.kt` and re-running. All checks below apply to **TEXTURED** mode unless noted otherwise.

---

## Side-wall checks

These are the most fragile — the perspective homography has multiple special cases.

### SW-1 — sideDepth=0 outer strip

**Position:** stand in an open corridor facing straight ahead.  
**What to see:** thin vertical strips on the far left and far right of the viewport. These are the walls immediately beside the player (sideDepth=0).  
**Pass:** each strip shows a narrow *slice* of the tile — not the full tile compressed into a thin band, and not a stretched/smeared version. The visible slice should be the *far* edge of the tile (depth-1 end), not the near edge.  
**Fail signals:** full tile squeezed into the strip; or only a single pixel-wide line of colour.

### SW-2 — sideDepth=1 full face

**Position:** same corridor, same direction.  
**What to see:** the next side-wall trapezoids inward (sideDepth=1). These are the widest side strips normally visible.  
**Pass:** the full tile is shown end-to-end with no truncation. Tile pattern should match what you see in the atlas.  
**Fail signals:** only half (or some fraction) of the tile is shown; texture appears stretched to fill the full width.

### SW-3 — tile symmetry left/right

**Position:** stand in open space facing north, walls equidistant on left and right.  
**Pass:** left and right side-wall strips at the same depth are mirror images — same tile region, same proportions.  
**Fail signals:** one side shows more tile than the other.

### SW-4 — tiling continuity across depth

**Position:** look along a long wall from a parallel corridor (strafe until a wall runs the full depth of the view).  
**Pass:** each successive wall face tiles the texture continuously — no jump or reset at depth boundaries.  
**Fail signals:** tile restarts abruptly at each cell boundary; or the same tile slice is repeated instead of advancing.

---

## Front-wall checks

### FW-1 — full tile coverage

**Position:** face a wall directly (walk up close).  
**Pass:** the full tile fills the wall face, centred. No cutoff top/bottom.  
**Fail signals:** partial tile; wrong tile from atlas.

### FW-2 — brightness gradient

**Position:** same as FW-1, but also look at walls at different distances.  
**Pass:** closer walls are brighter than distant walls. Very close walls do not blow out to pure white.  
**Fail signals:** all walls same brightness; near walls pure white (brightness > 1 overflow).

---

## Floor / ceiling checks

### FC-1 — tile continuity at depth seams

**Position:** look at the floor while moving forward one step at a time.  
**Pass:** the tile tiling is continuous across the depth-band seam — no sudden jump or colour shift at the seam line.  
**Fail signals:** visible seam line where adjacent bands meet.

### FC-2 — near-band proportions

**Position:** look at the floor from any position.  
**Pass:** the nearest floor band (closest to the bottom of the viewport) covers approximately the same tile V fraction as the next band out. It should *not* be a full tile — only the partial depth-0→1 slice is visible.  
**Fail signals:** near band is identically sized to deeper bands (implies `vNearFraction=1` incorrectly applied to near band).

### FC-3 — ceiling mirrors floor

**Position:** any position with open space above and below.  
**Pass:** ceiling tile matches floor tile geometry exactly (mirrored vertically). Brightness gradient is symmetric.  
**Fail signals:** ceiling shows the wrong tile (e.g. wall tile); ceiling and floor proportions differ.

---

## Monster checks

First-pass monsters render as a flat placeholder-coloured rect (no `MonsterAtlas` is wired up yet — see `docs/MonsterImplementationPlan.md`), so MON-4 and MON-5 below can't be fully verified until real directional sprite art exists. MON-1/2/3 are checkable today against the placeholder.

### MON-1 — visible at correct depth/size

**Position:** face down a corridor the monster is patrolling in.
**Pass:** the placeholder rect sits on the floor line at its cell's depth, scaled consistent with `spriteWidthFraction`/`spriteHeightFraction` (narrower/shorter than a full wall face), shrinking as depth increases.
**Fail signals:** rect fills the full cell (wall-to-wall); size doesn't change with depth; rect floats above or below the floor line.

### MON-2 — occluded by nearer wall

**Position:** stand so a wall is between you and the monster.
**Pass:** the monster is not drawn at all.
**Fail signals:** monster renders through a wall.

### MON-3 — nearer monster occludes farther monster/background

**Position:** (requires 2+ monsters in the same lat column at different depths — not in the first-pass single-monster wiring in `App.kt`, add a second monster temporarily to check this.)
**Pass:** the nearer monster's rect is drawn on top of the farther one and of any background geometry visible around its silhouette.
**Fail signals:** farther monster or background painted over the nearer monster.

### MON-4 — partial occlusion around a corner (blocked until real art)

**Position:** round a corner so only part of a monster's cell is visible.
**Pass (once `MonsterAtlas` + real art exist):** the visible slice of the sprite is a clipped crop, not the whole sprite squashed into the visible width.
**Note:** with the placeholder flat-colour rect, a clipped rect and a squashed rect look identical — this check needs real texture art to be meaningful.

### MON-5 — correct front/back/left/right sprite (blocked until directional art)

**Position:** walk a full circle around a stationary monster.
**Pass (once directional art exists):** the sprite shown changes between the monster's front/back/left/right tiles as your relative position changes, matching `DungeonRendererManager.viewDirectionOf`.
**Note:** first-pass `MonsterAnimation.single(...)` points all 4 directions at the same tile, so this is not yet visually distinguishable.

## Cross-mode checks (run in all three modes)

### CM-1 — mode switch produces no crash

Switch `renderMode` in `App.kt` between `Wireframe`, `Solid`, and `Textured`. Rebuild and run each.  
**Pass:** each mode renders without exception.

### CM-2 — wireframe brightness is unaffected by `sideWallShadow`

In `Wireframe` mode, rotate to see side walls.  
**Pass:** side walls are the same brightness as front walls (no darkening from `sideWallShadow`).

---

## Quick sanity matrix

| Check | Targets |
|---|---|
| SW-1 | `tileUNearFraction` for sideDepth=0 |
| SW-2 | `ts_u = ts` for sideDepth>0 |
| SW-3 | Sign symmetry in `fillPerspectiveMatrix` |
| SW-4 | `tileLeft` offset per tile index |
| FW-1 | `FrontStrip` texture sampling |
| FW-2 | `torchBrightness` clamping, L1 fix |
| FC-1 | `vNearFraction` and depth-band continuity |
| FC-2 | Near-band `vNearFraction` < 1 |
| FC-3 | `ceilTile` default, M4 fix |
| CM-2 | Wireframe `sideWallShadow` guard, L2 fix |
