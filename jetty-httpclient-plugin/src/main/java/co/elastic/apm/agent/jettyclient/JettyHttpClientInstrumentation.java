/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.apm.agent.jettyclient;

import co.elastic.apm.agent.jettyclient.helper.SpanResponseCompleteListenerWrapper;
import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Span;
import co.elastic.apm.api.Transaction;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

public class JettyHttpClientInstrumentation extends AbstractJettyClientInstrumentation {
    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return named("org.eclipse.jetty.client.HttpClient");
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return named("send")
                .and(takesArgument(0, namedOneOf("org.eclipse.jetty.client.HttpRequest", "org.eclipse.jetty.client.api.Request"))
                        .and(takesArgument(1, List.class)));
    }

    @Override
    public String getAdviceClassName() {
        return "co.elastic.apm.agent.jettyclient.JettyHttpClientInstrumentation$JettyHttpClientAdvice";
    }

    public static class JettyHttpClientAdvice {
        @Nullable
        @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
        public static Object onBeforeSend(@Advice.Argument(0) Request request,
                                          @Advice.Argument(1) List<Response.ResponseListener> responseListeners) {
            Transaction currentTransaction = ElasticApm.currentTransaction();
            Span ret = null;
            if (currentTransaction == null || request == null) {
                return ret;
            }
            ret = currentTransaction.startExitSpan("external", "http", "");
            if (ret != null) {
                String requestHost = request.getHost();
                if (requestHost == null) {
                    URI uri = request.getURI();
                    requestHost = uri.getHost();
                }
                ret.setName(request.getMethod() + " " + requestHost);
                ret.activate();
                ret.injectTraceHeaders((headerName, headerValue) -> request.header(headerName, headerValue));
                responseListeners.add(new SpanResponseCompleteListenerWrapper(ret));
            } else {
                currentTransaction.injectTraceHeaders((headerName, headerValue) -> request.header(headerName, headerValue));
            }
            return ret;
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onAfterSend(@Advice.Thrown Throwable thrown, @Advice.Enter @Nullable Object spanObject) {
            if (spanObject instanceof Span) {
                ((Span) spanObject).captureException(thrown);
            }
        }
    }
}