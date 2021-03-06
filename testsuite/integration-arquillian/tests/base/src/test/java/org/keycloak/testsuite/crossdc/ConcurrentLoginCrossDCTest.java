/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.crossdc;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import java.util.List;

import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.keycloak.testsuite.admin.concurrency.ConcurrentLoginTest;
import org.keycloak.testsuite.arquillian.ContainerInfo;
import org.keycloak.testsuite.arquillian.LoadBalancerController;
import org.keycloak.testsuite.arquillian.annotation.LoadBalancer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.junit.Ignore;
import org.junit.Test;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ConcurrentLoginCrossDCTest extends ConcurrentLoginTest {

    @ArquillianResource
    @LoadBalancer(value = AbstractCrossDCTest.QUALIFIER_NODE_BALANCER)
    protected LoadBalancerController loadBalancerCtrl;

    @ArquillianResource
    protected ContainerController containerController;

    private static final int INVOCATIONS_BEFORE_SIMULATING_DC_FAILURE = 10;
    private static final int LOGIN_TASK_DELAY_MS = 100;
    private static final int LOGIN_TASK_RETRIES = 15;

    @Override
    public void beforeAbstractKeycloakTestRealmImport() {
        log.debug("Initializing load balancer - enabling all started nodes across DCs");
        this.loadBalancerCtrl.disableAllBackendNodes();

        this.suiteContext.getDcAuthServerBackendsInfo().stream()
                .flatMap(List::stream)
                .filter(ContainerInfo::isStarted)
                .map(ContainerInfo::getQualifier)
                .forEach(loadBalancerCtrl::enableBackendNodeByName);
    }

    @Test
    public void concurrentLoginWithRandomDcFailures() throws Throwable {
        log.info("*********************************************");
        long start = System.currentTimeMillis();

        AtomicReference<String> userSessionId = new AtomicReference<>();
        LoginTask loginTask = null;

        try (CloseableHttpClient httpClient = HttpClientBuilder.create().setRedirectStrategy(new LaxRedirectStrategy()).build()) {
            loginTask = new LoginTask(httpClient, userSessionId, LOGIN_TASK_DELAY_MS, LOGIN_TASK_RETRIES, false, Arrays.asList(
              createHttpClientContextForUser(httpClient, "test-user@localhost", "password")
            ));
            HttpUriRequest request = handleLogin(getPageContent(oauth.getLoginFormUrl(), httpClient, HttpClientContext.create()), "test-user@localhost", "password");
            log.debug("Executing login request");
            org.junit.Assert.assertTrue(parseAndCloseResponse(httpClient.execute(request)).contains("<title>AUTH_RESPONSE</title>"));

            run(DEFAULT_THREADS, DEFAULT_CLIENTS_COUNT, loginTask, new SwapDcAvailability());
            int clientSessionsCount = testingClient.testing().getClientSessionsCountInUserSession("test", userSessionId.get());
            org.junit.Assert.assertEquals(1 + DEFAULT_CLIENTS_COUNT, clientSessionsCount);
        } finally {
            long end = System.currentTimeMillis() - start;
            log.infof("Statistics: %s", loginTask == null ? "??" : loginTask.getHistogram());
            log.info("concurrentLoginWithRandomDcFailures took " + (end/1000) + "s");
            log.info("*********************************************");
        }
    }

    private class SwapDcAvailability implements KeycloakRunnable {

        private final AtomicInteger invocationCounter = new AtomicInteger();

        @Override
        public void run(int threadIndex, Keycloak keycloak, RealmResource realm) throws Throwable {
            final int currentInvocarion = invocationCounter.getAndIncrement();
            if (currentInvocarion % INVOCATIONS_BEFORE_SIMULATING_DC_FAILURE == 0) {
                int failureIndex = currentInvocarion / INVOCATIONS_BEFORE_SIMULATING_DC_FAILURE;
                int dcToEnable = failureIndex % 2;
                int dcToDisable = (failureIndex + 1) % 2;
                suiteContext.getDcAuthServerBackendsInfo().get(dcToDisable).forEach(c -> loadBalancerCtrl.disableBackendNodeByName(c.getQualifier()));
                suiteContext.getDcAuthServerBackendsInfo().get(dcToEnable).forEach(c -> loadBalancerCtrl.enableBackendNodeByName(c.getQualifier()));
            }
        }
    }

}
