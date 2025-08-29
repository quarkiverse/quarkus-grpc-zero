package com.example.grpc.hibernate;

import org.junit.jupiter.api.Disabled;

import io.quarkus.grpc.test.utils.VertxGRPCTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("Flaky, as soon as it compiles we are good in this separate repo")
@QuarkusTest
@TestProfile(VertxGRPCTestProfile.class)
public class VertxBlockingRawTest extends BlockingRawTestBase {
}
