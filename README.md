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
   | `MIJERY_MEDIA_STORAGE_DIR` | `./media-storage` | Root for per-product originals and variants |
   | `MIJERY_MEDIA_LOGO_DIR` | `./logo-storage` | Root for per-product logos and watermark overlays |
   | `MIJERY_MEDIA_SERVICE_TOKEN` | *(empty in dev)* | Shared `X-Mijery-Service-Token`; **required** for `/api/**` in production |
   | `MIJERY_MEDIA_MAX_LOGO_BYTES` | `2097152` | Product logo upload cap (2 MB) |
   | `MIJERY_MEDIA_VIPS_BIN` | `vips` | libvips binary path (`vipsheader` is resolved next to it) |
   | `MIJERY_MEDIA_SIGNING_SECRET` | *(change in prod)* | HMAC secret for signed URLs |
   | `MIJERY_MEDIA_SIGNED_URL_TTL` | `600` | Signed URL TTL (seconds) |
   | `MIJERY_MEDIA_WATERMARK_OPACITY` | `35` | Alpha of the product logo when used as a watermark (0-100) |

4. Run:
   ```bash
   mvn spring-boot:run
   ```

## Key endpoints

All `/api/**` calls must send `X-Mijery-Service-Token`. `GET /media/**` and
`GET /logo/**` are public: protected assets still require the signed URL verified by
`ServeController`.

| Endpoint | Purpose |
|---|---|
| `POST /api/assets` | Upload an asset (multipart: `file`, `visibility`, `ownerSub`, **`clientId`**) |
| `GET /api/assets/{id}` | Get asset metadata |
| `DELETE /api/assets/{id}` | Delete asset and all variants |
| `PUT /api/products/{clientId}/logo` | Upload/replace a product's logo (multipart `file`) |
| `GET /api/products/{clientId}/logo` | Logo descriptor, or 404 |
| `GET /api/products/logos?clientIds=a,b` | Batch lookup for one console page |
| `DELETE /api/products/{clientId}/logo` | Remove a product's logo |
| `GET /logo/{clientId}` | Public logo bytes, versioned by `ETag` |
| `GET /media/{assetId}/{filename}` | Serve a variant (signed URL required for protected) |

`clientId` is **mandatory** on upload. A request without it (or with an id outside the
registered-client alphabet) is rejected with `400` before a row or file is created.

## Storage layout

```
media-storage/{clientSlug}/originals/{assetId}.{ext}
media-storage/{clientSlug}/variants/{assetId}/{width}[-wm].webp
logo-storage/{clientSlug}/logo.webp        # normalized display logo
logo-storage/{clientSlug}/watermark.webp   # alpha pre-scaled for compositing
```

`{clientSlug}` is derived from the immutable client id (`mdg-chronicles` →
`mdg_chronicles`); see `TenantPaths`. There is deliberately no unscoped directory.

Logos should have a transparent background (PNG/WebP). A JPEG is accepted, but its
background is part of the overlay, so it watermarks as a translucent block.

## Upload flow

```
Client → POST /api/assets (multipart + service token)
  → Require clientId; validate MIME (real content type) + size cap
  → Store original under media-storage/{clientSlug}/originals
  → Re-encode (strip EXIF, normalize)
  → Generate variants (320/640/1080/1600 WebP via libvips)
  → If the product has a logo, generate watermarked variants from
    logo-storage/{clientSlug}/watermark.webp (bottom-right, ~12% width)
  → Save metadata + variant records to PostgreSQL
  → Return { assetId, variants: { 320, 640, 1080, 1600 }, watermarkedVariant }
```

`watermarkedVariant` is `null` when the product has no logo or libvips is unavailable —
a plain copy is never labelled as watermarked.

## Integration with chronicles

### chronicles-api
Replace `MediaService` (Cloudinary) with calls to mijery-media:

```java
// Before (Cloudinary):
ImageUploadResponse response = mediaService.uploadImage(file, "avatars");

// After (mijery-media):
// POST /api/assets with file + visibility=PUBLIC + ownerSub + clientId,
// carrying X-Mijery-Service-Token
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
- [x] Watermark positioning refinement (vips composite)
- [x] Per-product storage (`media-storage/{clientSlug}`) and product logo watermarks
- [ ] S3-compatible storage backend
- [ ] Audio support (FFmpeg transcoding)
- [ ] chronicles-api MediaService migration
- [ ] chronicles-front cloudinaryUrl → mediaUrl migration
- [ ] Migration script for existing Cloudinary URLs
