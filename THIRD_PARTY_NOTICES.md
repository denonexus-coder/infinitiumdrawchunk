# Third-Party Notices

## Sodium

Portions of this repository are derived from **Sodium**
(<https://github.com/CaffeineMC/sodium>), Copyright (c) 2020 JellySquid et al.

Those portions are distributed under the **PolyForm Shield License 1.0.0**,
whose full text is in [`LICENSE.md`](LICENSE.md) (also at
<https://polyformproject.org/licenses/shield/1.0.0>).

Anyone who receives a copy of any part of the Software from this repository
receives the full license terms at [`LICENSE.md`](LICENSE.md), as required by
the *Notices* section of that license.

### Files derived from Sodium

| File in this repo | Derived from (Sodium) |
|---|---|
| `src/main/java/com/denonexus/infinitiumdrawchunk/merge/MultiDrawBatch.java` | `me/jellysquid/mods/sodium/client/gl/device/MultiDrawBatch.java` (verbatim; only the package line changed) |
| `src/main/java/com/denonexus/infinitiumdrawchunk/merge/Geometry.java` (method `expandToGeometryRun`) | `me/jellysquid/mods/sodium/client/render/chunk/DefaultChunkRenderer.java` |

### Not derived from Sodium (original code in this repo)

Everything else is written from scratch for this project and does not copy
Sodium source: `QuadIndexBuffer`, `MergeGuard`, `Geometry` constants/comments,
`SubDrawQueue`, `DrawEmitter`, `MergeStats`, the entrypoint, the tests, the
Gradle build and the CI workflow. They implement the *idea* of folding
contiguous sub-draws (a geometric rule), reimplemented for LWJGL only, without
Sodium's `Gl*` device layer, meshing, lighting or world generation code.

### Obligation called out for maintainers: Noncompete

PolyForm Shield 1.0.0 contains a **Noncompete** term: a permitted purpose is
*everything except providing a product that competes with the Software* (i.e.
with Sodium, or with its licensor's affiliates). Sodium is a chunk-rendering
engine; keep this project's distribution consistent with that clause — in
particular, do not market this mod as a practical substitute for Sodium.

The other real obligations are: ship the license text with any copy
(satisfied by [`LICENSE.md`](LICENSE.md)), and keep the attribution above
(satisfied by this file).

## Gradle Wrapper

`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and
`gradle-wrapper.properties` are the standard Gradle Wrapper, Apache License
2.0, Copyright Gradle Inc. <https://gradle.org>

## Minecraft / Fabric Loader / Yarn / LWJGL

Fetched as build dependencies (not vendored here): Minecraft (Mojang),
Fabric Loader and Yarn mappings (Fabric, Apache-2.0), LWJGL (Apache-2.0).
They are not redistributed by this repository's source tree.
