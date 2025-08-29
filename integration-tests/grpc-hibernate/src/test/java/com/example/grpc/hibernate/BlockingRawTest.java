package com.example.grpc.hibernate;

import org.junit.jupiter.api.Disabled;

import io.quarkus.test.junit.QuarkusTest;

@Disabled("Flaky, as soon as it compiles we are good in this separate repo")
@QuarkusTest
public class BlockingRawTest extends BlockingRawTestBase {
}
