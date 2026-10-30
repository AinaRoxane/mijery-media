# mijery-media — Self-hosted media service

Self-hosted replacement for Cloudinary. Handles image (and later audio) upload,
validation, processing, variant generation, watermarking, and signed-URL delivery.

## Quick start

1. Create the PostgreSQL database:
   ```sql
   CREATE DATABASE mijery_media;
   ```

2. Install libvips:
   ```bash
   sudo apt install libvips-tools
   ```

3. Configure via env vars (or `src/main/resources/.env.properties`):
   | Variable | Default | Purpose |
   |---|---|---|
   | `PORT` | `8090` | HTTP port |
   | `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PWD` | localhost/5432/mijery_media/postgres/postgres | Database |
   | `MIJERY_MEDIA_STORAGE_DIR` | `./media-storage` | Root directory for stored files |
   | `MIJERY_MEDIA_VIPS_BIN` | `vips` | libvips binary path |
   | `MIJERY_MEDIA_SIGNING_SECRET` | *(change in prod)* | HMAC secret for signed URLs |
   | `MIJERY_MEDIA_SIGNED_URL_TTL` | `600` | Signed URL TTL (seconds) |
   | `MIJERY_MEDIA_WATERMARK_PATH` | *(empty)* | Path to watermark overlay image |
   | `MIJERY_MEDIA_WATERMARK_OPACITY` | `35` | Watermark opacity (0-100) |

4. Run:
   ```bash
   mvn spring-boot:run
   ```

## Key endpoints

| Endpoint | Purpose |
|---|---|
| `POST /api/assets` | Upload a new asset (multipart: file, visibility, ownerSub) |
| `GET /api/assets/{id}` | Get asset metadata |
| `DELETE /api/assets/{id}` | Delete asset and all variants |
| `GET /media/{assetId}/{filename}` | Serve a variant (signed URL required for protected) |

## Upload flow

```
Client → POST /api/assets (multipart)
  → Validate MIME (real content type) + size cap
  → Store original (private)
  → Re-encode (strip EXIF, normalize)
  → Generate variants (320/640/1080/1600 WebP via libvips)
  → Generate watermarked variants (for protected assets)
  → Save metadata + variant records to PostgreSQL
  → Return { assetId, variants: { 320, 640, 1080, 1600 }, watermarkedVariant }
```

## Integration with chronicles

### chronicles-api
Replace `MediaService` (Cloudinary) with calls to mijery-media:

```java
// Before (Cloudinary):
ImageUploadResponse response = mediaService.uploadImage(file, "avatars");

// After (mijery-media):
// POST /api/assets with file + visibility=PUBLIC + ownerSub
// Returns { assetId, variants: { 320, 640, 1080, 1600 } }
```

Store `assetId` in DB instead of `secure_url`/`public_id`.

### chronicles-front
Replace `cloudinaryUrl()` with `mediaUrl()`:

```typescript
// Before:
src={cloudinaryUrl(heroImage, { w: 1600, watermark: !isOwner })}

// After:
src={mediaUrl(assetId, 1600, { watermarked: !isOwner })}
// => "/media/{assetId}/1600.webp" or "/media/{assetId}/1600-wm.webp"
```

### Protected assets (paid panels)
1. API checks reader entitlement.
2. API calls mijery-media to get a signed URL: `GET /api/assets/{id}/signed-url?width=1080&watermarked=true`
3. Returns signed URL to front: `/media/{assetId}/1080-wm.webp?md5=...&expires=...`
4. Nginx verifies signature + expiry before serving (cached).

## Architecture

```
Browser → chronicles-front → chronicles-api → mijery-media → libvips → filesystem
                                    ↑
                              Nginx (signed URL verify + cache + serve)
```

## Roadmap

- [x] Scaffold project structure
- [x] Domain model (MediaAsset, MediaVariant)
- [x] Upload endpoint with validation
- [x] Storage service (local filesystem)
- [x] Image processing (libvips variants)
- [x] Signed URL service (HMAC)
- [x] Serve endpoint (public + protected)
- [x] Liquibase schema
- [x] Nginx config
- [ ] Real MIME detection (magic bytes, not just content-type)
- [ ] EXIF stripping in re-encode step
- [ ] Watermark positioning refinement (vips composite)
- [ ] S3-compatible storage backend
- [ ] Audio support (FFmpeg transcoding)
- [ ] chronicles-api MediaService migration
- [ ] chronicles-front cloudinaryUrl → mediaUrl migration
- [ ] Migration script for existing Cloudinary URLs
