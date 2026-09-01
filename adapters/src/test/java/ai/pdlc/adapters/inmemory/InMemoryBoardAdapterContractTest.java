package ai.pdlc.adapters.inmemory;

import ai.pdlc.adapters.BoardPortContractTest;
import ai.pdlc.core.port.BoardPort;

class InMemoryBoardAdapterContractTest extends BoardPortContractTest {

    private final InMemoryBoardAdapter adapter = new InMemoryBoardAdapter();

    @Override
    protected BoardPort port() {
        return adapter;
    }

    @Override
    protected String profile() {
        return "local";
    }
}
