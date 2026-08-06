package ug.co.smsone.mcp.internal.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.document.DocumentDirectory;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolDefinition.Kind;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Document tools — metadata, short-lived download URLs, delete. Uploading bytes is deliberately
 * absent: bulk data never transits MCP (plan §8); an agent that needs content follows the presigned
 * URL, and putting new content in stays a REST multipart upload.
 */
@Component
class DocumentToolManifest implements ToolManifest {

    private final DocumentDirectory documents;

    DocumentToolManifest(DocumentDirectory documents) {
        this.documents = documents;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ToolDefinition("documents_list", "List documents",
                        "List this organization's documents (name, content type, size, source).",
                        1, "documents", Kind.READ, "document:read",
                        ToolArgs.schema(ToolArgs.pageProperties()),
                        (context, args) -> documents.list(context.orgId(), ToolArgs.page(args))),

                new ToolDefinition("document_get", "Get document",
                        "Read one document's metadata. Use document_download_url for the content.",
                        1, "documents", Kind.READ, "document:read",
                        ToolArgs.schema(Map.of("document_id", ToolArgs.string("The document id.")),
                                "document_id"),
                        (context, args) -> documents.get(context.orgId(), id(args))),

                new ToolDefinition("document_download_url", "Document download URL",
                        "Mint a short-lived download URL for a document's bytes. Fetch it promptly — "
                                + "the URL expires.",
                        1, "documents", Kind.READ, "document:read",
                        ToolArgs.schema(Map.of("document_id", ToolArgs.string("The document id.")),
                                "document_id"),
                        (context, args) -> Map.of("url",
                                documents.downloadUrl(context.orgId(), id(args)))),

                new ToolDefinition("document_delete", "Delete document",
                        "Delete a document: its bytes are removed from storage immediately, the "
                                + "metadata row is soft-deleted for the retention window.",
                        1, "documents", Kind.DESTRUCTIVE, "document:manage",
                        ToolArgs.schema(Map.of("document_id", ToolArgs.string("The document id.")),
                                "document_id"),
                        (context, args) -> {
                            UUID id = id(args);
                            documents.delete(context.orgId(), id);
                            return Map.of("deleted", id.toString());
                        }));
    }

    private static UUID id(Map<String, Object> args) {
        try {
            return UUID.fromString(ToolArgs.requireString(args, "document_id"));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("'document_id' must be a UUID.", ApiSource.pointer("/document_id"));
        }
    }
}
