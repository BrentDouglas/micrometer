/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.datadog;

import io.micrometer.core.Issue;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.server.HttpServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DatadogMeterRegistryTest {

    @Issue("#463")
    @Test
    void encodeMetricName() throws InterruptedException {
        CountDownLatch metadataRequests = new CountDownLatch(1);
        AtomicReference<String> metadataMetricName = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        Pattern p = Pattern.compile("/api/v1/metrics/([^?]+)\\?.*");

        NettyContext server = HttpServer.create(0)
                .newHandler((req, resp) -> {
                    Matcher matcher = p.matcher(req.uri());
                    if (matcher.matches()) {
                        metadataMetricName.set(matcher.group(1));
                        metadataRequests.countDown();
                    }
                    requestBody.set(req.receive().asString().blockFirst());
                    return req.receive().then(resp.status(200).send());
                }).block();

        DatadogMeterRegistry registry = new DatadogMeterRegistry(new DatadogConfig() {
            @Override
            public String uri() {
                return "http://localhost:" + server.address().getPort();
            }

            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String apiKey() {
                return "fake";
            }

            @Override
            public String applicationKey() {
                return "fake";
            }

            @Override
            public boolean enabled() {
                return false;
            }
        }, Clock.SYSTEM);

        try {
            Counter.builder("my.counter#abc")
                .baseUnit(TimeUnit.MICROSECONDS.toString().toLowerCase())
                .register(registry)
                .increment(Math.PI);
            registry.publish();

            metadataRequests.await(10, TimeUnit.SECONDS);
            assertThat(metadataMetricName.get()).isEqualTo("my.counter%23abc");
            assertThat(requestBody.get()).isEqualTo("{\"type\":\"count\",\"unit\":\"microsecond\"}");
        } finally {
            server.dispose();
            registry.close();
        }
    }
}