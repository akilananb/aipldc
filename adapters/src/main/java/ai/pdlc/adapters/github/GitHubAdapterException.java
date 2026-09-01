package ai.pdlc.adapters.github;

public class GitHubAdapterException extends RuntimeException {
    public GitHubAdapterException(String message) {
        super(message);
    }

    public GitHubAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
