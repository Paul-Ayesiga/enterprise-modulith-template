package ug.co.smsone.files.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
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
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Object storage over the {@link FileStorageProvider}. Uploads are namespaced per caller
 * ({@code u/<sub>/...}); a caller may only read/delete/presign keys under their own namespace (ADMIN
 * bypasses). Download is a 302 to a short-lived presigned URL, so bytes stream straight from storage
 * with the stored content-type — the app never proxies the payload.
 */
@RestController
@RequestMapping("/api/v1/files")
class FileController {

    private static final String RESOURCE_TYPE = "file";
    private static final long MULTIPART_THRESHOLD_BYTES = 5L * 1024 * 1024; // switch to multipart beyond 5 MB
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

    private final FileStorageProvider storage;

    FileController(FileStorageProvider storage) {
        this.storage = storage;
    }

    record FileAttributes(String key, long size, String contentType) {
    }

    record PresignAttributes(String key, String operation, String url, long expiresInSeconds) {
    }

    record PresignRequest(@NotBlank String operation, String key, String contentType) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject upload(@RequestParam("file") MultipartFile file, CurrentUser user) {
        if (file.isEmpty()) {
            throw new ValidationException("Uploaded file is empty.", ApiSource.parameter("file"));
        }
        String key = newKey(user.subject(), file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try {
            if (file.getSize() > MULTIPART_THRESHOLD_BYTES) {
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
    ResponseEntity<Void> download(@PathVariable String key, CurrentUser user) {
        String objectKey = normalize(key);
        requireOwner(objectKey, user);
        if (!storage.exists(objectKey)) {
            throw new NotFoundException("File not found.");
        }
        URL url = storage.presignGet(objectKey, PRESIGN_TTL);
        return ResponseEntity.status(HttpStatus.FOUND).location(toUri(url)).build();
    }

    @DeleteMapping("/{*key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key, CurrentUser user) {
        String objectKey = normalize(key);
        requireOwner(objectKey, user);
        storage.delete(objectKey);
    }

    @PostMapping("/presign")
    ResourceObject presign(@Valid @RequestBody PresignRequest request, CurrentUser user) {
        String operation = request.operation().trim().toUpperCase(java.util.Locale.ROOT);
        return switch (operation) {
            case "PUT" -> {
                String key = newKey(user.subject(), request.key()); // request.key() is treated as a filename hint
                String contentType = request.contentType() == null ? "application/octet-stream" : request.contentType();
                URL url = storage.presignPut(key, contentType, PRESIGN_TTL);
                yield presignResource(key, "PUT", url);
            }
            case "GET" -> {
                String key = normalize(request.key());
                if (key.isBlank()) {
                    throw new ValidationException("A key is required to presign a download.",
                            ApiSource.pointer("/data/attributes/key"));
                }
                requireOwner(key, user);
                if (!storage.exists(key)) {
                    throw new NotFoundException("File not found.");
                }
                yield presignResource(key, "GET", storage.presignGet(key, PRESIGN_TTL));
            }
            default -> throw new ValidationException("operation must be GET or PUT.",
                    ApiSource.pointer("/data/attributes/operation"));
        };
    }

    private ResourceObject presignResource(String key, String operation, URL url) {
        return new ResourceObject(key, "file-presign",
                new PresignAttributes(key, operation, url.toString(), PRESIGN_TTL.toSeconds()));
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

    private static void requireOwner(String key, CurrentUser user) {
        if (user.hasRole("ADMIN")) {
            return;
        }
        if (!key.startsWith("u/" + user.subject() + "/")) {
            throw new ForbiddenException("You can only access files in your own namespace.");
        }
    }

    private static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException ex) {
            throw new IllegalStateException("Storage returned a malformed presigned URL", ex);
        }
    }
}
