package ug.co.smsone.files.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.URL;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Object storage over the {@link FileStorageProvider}. Uploads are namespaced per caller
 * ({@code u/<sub>/...}); a caller may only read/delete/presign keys under their own namespace. A
 * platform operator may reach across namespaces, tiered by blast radius — support to read, admin to
 * delete. Download is a 302 to a short-lived presigned URL, so bytes stream straight from storage
 * with the stored content-type — the app never proxies the payload.
 */
@RestController
@RequestMapping("/api/v1/files")
class FileController {

    private static final String RESOURCE_TYPE = "file";
    private static final String PRESIGN_TYPE = "file-presign";

    private final FileStorageProvider storage;
    private final StorageProperties properties; // presign TTL + multipart threshold live there, with their why

    FileController(FileStorageProvider storage, StorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    record FileAttributes(String key, long size, String contentType) {
    }

    record PresignAttributes(String key, String operation, String url, long expiresInSeconds) {
    }

    record PresignRequest(@NotBlank String operation, String key, String contentType) {
    }

    @PostMapping
    @Operation(summary = "Upload a file",
            description = """
                    Sent as `multipart/form-data` under the field name `file`. The object key is minted \
                    server-side under the caller's own namespace (`u/<subject>/…`) — the caller never \
                    chooses it, so an upload can never overwrite an existing object. The returned `key` \
                    is what every later download, presign and delete addresses.""")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject upload(@RequestParam("file") MultipartFile file, CurrentUser user) {
        if (file.isEmpty()) {
            throw new ValidationException("Uploaded file is empty.", ApiSource.parameter("file"));
        }
        String key = newKey(user.subject(), file.getOriginalFilename());
        // Stored and replayed verbatim on download — acceptable because downloads 302 to the storage
        // origin, never this API's origin; add an allowlist before ever proxying bytes through here.
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try {
            if (file.getSize() > properties.multipartThreshold().toBytes()) {
                storage.putLarge(key, file.getInputStream(), file.getSize(), contentType);
            } else {
                storage.put(key, file.getInputStream(), file.getSize(), contentType);
            }
        } catch (java.io.IOException ex) {
            throw new ValidationException("Could not read the uploaded file.", ApiSource.parameter("file"));
        }
        return new ResourceObject(key, RESOURCE_TYPE, new FileAttributes(key, file.getSize(), contentType));
    }

    @GetMapping("/{*key}")
    @Operation(summary = "Download a file by key",
            description = """
                    Answers 302 with a `Location` header naming a short-lived presigned URL; the bytes \
                    stream from object storage and never through this API, so the client must follow \
                    the redirect. No response body is returned here.""")
    ResponseEntity<Void> download(@PathVariable String key, CurrentUser user) {
        String objectKey = normalize(key);
        requireOwnerOr(objectKey, user, PlatformRole.SUPPORT);
        if (!storage.exists(objectKey)) {
            throw new NotFoundException("File not found.");
        }
        URL url = storage.presignGet(objectKey, properties.presignTtl());
        return ResponseEntity.status(HttpStatus.FOUND).location(toUri(url)).build();
    }

    @DeleteMapping("/{*key}")
    @Operation(summary = "Delete a file by key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key, CurrentUser user) {
        String objectKey = normalize(key);
        requireOwnerOr(objectKey, user, PlatformRole.ADMIN);
        storage.delete(objectKey);
    }

    @PostMapping("/presign")
    @Operation(summary = "Mint a presigned upload or download URL",
            description = """
                    `operation: PUT` always mints a NEW key under the caller's own namespace — the \
                    supplied `key` is treated as a filename hint, never as a target, so a presigned \
                    upload can never replace an existing object. `operation: GET` presigns the existing \
                    `key`, which must be given. Both URLs are short-lived; `expiresInSeconds` states how \
                    long.""")
    ResourceObject presign(@Valid @RequestBody PresignRequest request, CurrentUser user) {
        String operation = request.operation().trim().toUpperCase(java.util.Locale.ROOT);
        return switch (operation) {
            case "PUT" -> {
                String key = newKey(user.subject(), request.key()); // request.key() is treated as a filename hint
                String contentType = request.contentType() == null ? "application/octet-stream" : request.contentType();
                URL url = storage.presignPut(key, contentType, properties.presignTtl());
                yield presignResource(key, "PUT", url);
            }
            case "GET" -> {
                String key = normalize(request.key());
                if (key.isBlank()) {
                    throw new ValidationException("A key is required to presign a download.",
                            ApiSource.pointer("/data/attributes/key"));
                }
                requireOwnerOr(key, user, PlatformRole.SUPPORT);
                if (!storage.exists(key)) {
                    throw new NotFoundException("File not found.");
                }
                yield presignResource(key, "GET", storage.presignGet(key, properties.presignTtl()));
            }
            default -> throw new ValidationException("operation must be GET or PUT.",
                    ApiSource.pointer("/data/attributes/operation"));
        };
    }

    private ResourceObject presignResource(String key, String operation, URL url) {
        return new ResourceObject(key, PRESIGN_TYPE,
                new PresignAttributes(key, operation, url.toString(), properties.presignTtl().toSeconds()));
    }

    /** {@code u/<sub>/<uuid>/<sanitized-filename>} — namespaced by owner, collision-free. */
    private static String newKey(String subject, String filenameHint) {
        String name = filenameHint == null || filenameHint.isBlank() ? "file" : filenameHint;
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return "u/" + subject + "/" + UUID.randomUUID() + "/" + safe;
    }

    private static String normalize(String key) {
        return key == null ? "" : key.startsWith("/") ? key.substring(1) : key;
    }

    /**
     * Within their own namespace a caller is unrestricted. Reaching into someone else's is a platform
     * action, tiered by blast radius: support may READ another user's object (fetching a user's upload
     * is the support job), but destroying tenant data takes admin. There is no cross-namespace write
     * path to tier — an upload presign always mints a key under the caller's own subject.
     */
    private static void requireOwnerOr(String key, CurrentUser user, String platformTier) {
        if (key.startsWith("u/" + user.subject() + "/") || user.hasRole(platformTier)) {
            return;
        }
        throw new ForbiddenException("You can only access files in your own namespace.");
    }

    private static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException ex) {
            throw new IllegalStateException("Storage returned a malformed presigned URL", ex);
        }
    }
}
