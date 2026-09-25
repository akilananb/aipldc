package ai.pdlc.agents.platform;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** Backs a mocked {@link RunStore}'s conversation methods with a list, as {@code platform_run_messages} would. */
final class StoredConversation {

    final List<RunStore.StoredMessage> messages = new CopyOnWriteArrayList<>();

    static StoredConversation attach(RunStore runs) {
        StoredConversation c = new StoredConversation();
        when(runs.messages(any(UUID.class))).thenAnswer(inv -> List.copyOf(c.messages));
        doAnswer(inv -> {
            int seq = inv.getArgument(1);
            if (c.messages.stream().anyMatch(m -> m.seq() == seq)) {
                return false;
            }
            c.messages.add(new RunStore.StoredMessage(seq, inv.getArgument(2), inv.getArgument(3)));
            return true;
        }).when(runs).appendMessage(any(UUID.class), anyInt(), anyString(), anyString());
        return c;
    }

    List<String> kinds() {
        return messages.stream().map(RunStore.StoredMessage::kind).toList();
    }
}
