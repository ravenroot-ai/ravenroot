package ai.ravenroot.extensions.gitworkspace;

import java.util.Map;
import java.util.Set;

record GitWorkspaceRequest(Operation operation, String taskId, String baseRevision,
                           String issueBranch, String approvedRevision) {
    static final String CONTRACT = "git-workspace.v1";

    enum Operation { PROVISION, INTEGRATE, VERIFY }

    static GitWorkspaceRequest parse(Object payload, GitWorkspaceProfile profile) {
        if (!(payload instanceof Map<?, ?> raw) || raw.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT);
        }
        @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) raw;
        String operationName = text(values, "operation");
        Operation operation = switch (operationName) {
            case "provision" -> Operation.PROVISION;
            case "integrate" -> Operation.INTEGRATE;
            case "verify" -> Operation.VERIFY;
            default -> throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT);
        };
        Set<String> expected = switch (operation) {
            case PROVISION -> Set.of("contract", "operation", "taskId", "baseRevision", "issueBranch");
            case INTEGRATE -> Set.of("contract", "operation", "taskId", "baseRevision", "issueBranch",
                    "approvedRevision");
            case VERIFY -> Set.of("contract", "operation", "taskId", "baseRevision", "issueBranch",
                    "acceptedRevision");
        };
        if (!values.keySet().equals(expected) || !CONTRACT.equals(text(values, "contract"))) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT);
        }
        String task = text(values, "taskId");
        String base = text(values, "baseRevision");
        String branch = text(values, "issueBranch");
        String approved = operation == Operation.INTEGRATE ? text(values, "approvedRevision")
                : operation == Operation.VERIFY ? text(values, "acceptedRevision") : null;
        if (!task.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || !oid(base, profile.objectFormat())
                || approved != null && !oid(approved, profile.objectFormat())
                || !GitWorkspaceProfile.safeRef(branch) || !branch.startsWith(profile.issueRefPrefix())) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT);
        }
        return new GitWorkspaceRequest(operation, task, base, branch, approved);
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 4096
                || text.codePoints().anyMatch(cp -> cp < 0x20 || cp == 0x7f)) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT);
        }
        return text;
    }

    static boolean oid(String value, String format) {
        int length = "sha256".equals(format) ? 64 : 40;
        return value != null && value.length() == length && value.matches("[0-9a-f]+")
                && !value.chars().allMatch(character -> character == '0');
    }
}
