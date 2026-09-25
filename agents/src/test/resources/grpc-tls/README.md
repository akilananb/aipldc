Test-only TLS material for `GrpcAgentRunnerTest` (slice 2.6b): a CA, a `localhost`/`127.0.0.1` server
certificate and an mTLS client certificate signed by it, and an unrelated CA for the wrong-CA case.
Generated with openssl (100-year validity); these keys protect nothing and must never be used outside tests.
