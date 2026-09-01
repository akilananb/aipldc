package ai.pdlc.adapters.inmemory;

import ai.pdlc.core.domain.CommitRef;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRepoAdapterTest {

    @Test
    void writeThenReadRoundTrips() {
        InMemoryRepoAdapter repo = new InMemoryRepoAdapter();
        repo.writeFiles("story/4413", Map.of("openspec/changes/x/proposal.md", "# Proposal"), "drafted", "po-agent");

        assertThat(repo.readFile("story/4413", "openspec/changes/x/proposal.md")).isEqualTo("# Proposal");
    }

    @Test
    void writeFilesReturnsDeterministicShaRegardlessOfMapIterationOrder() {
        InMemoryRepoAdapter repo = new InMemoryRepoAdapter();
        CommitRef a = repo.writeFiles("b1", Map.of("a.md", "1", "b.md", "2"), "m", "author");
        CommitRef b = repo.writeFiles("b2", Map.of("b.md", "2", "a.md", "1"), "m", "author");

        assertThat(a.sha()).isEqualTo(b.sha());
    }

    @Test
    void createBranchCopiesFilesFromSource() {
        InMemoryRepoAdapter repo = new InMemoryRepoAdapter();
        repo.writeFiles("main", Map.of("README.md", "hello"), "init", "author");

        repo.createBranch("main", "story/4413");

        assertThat(repo.readFile("story/4413", "README.md")).isEqualTo("hello");
    }

    @Test
    void readingMissingFileThrows() {
        InMemoryRepoAdapter repo = new InMemoryRepoAdapter();
        assertThatThrownBy(() -> repo.readFile("main", "missing.md")).isInstanceOf(IllegalArgumentException.class);
    }
}
