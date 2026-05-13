# Changelog

## 3.1.0

Changes since `3.0.0`.

### Added

- Added an optional, config-gated `/locate feature <placed_feature_id>` command for finding nearby biome regions that can generate a requested placed feature.

### Changed

- Moved custom registered structure lookup to `/xlocate customstructure <id>` to avoid conflicts with vanilla locate behavior, and updated the related docs.
- Improved locate and teleport feedback with clearer start, progress, completion, chunk-preload, and teleport-target status messages.
- Refined teleport destination handling with safer nearby-position checks, better underground and surface fallbacks, and wider destination chunk preloading.
- Updated build/runtime dependencies from NeoForge `21.1.219` to `21.1.227` and ModDevGradle `2.0.140` to `2.0.141`.

### Fixed

- Prevented structure locate cache collisions between different structure queries.
- Validated located structure candidates against the originally requested structure set before reporting a result.
- Preserved teleport preloading for locate-generated `/tp` coordinates when vanilla supplies an implicit rotation object.
- Restored placed-feature ID suggestions for the new feature locate command.
- Tightened several teleport target and locate-result paths so reported coordinates and prepared teleport targets stay aligned.
