package co.elastic.apm.agent.jettyclient.plugin;

import co.elastic.apm.agent.jettyclient.plugin.helper.SpanResponseCompleteListenerWrapper;
import co.elastic.apm.agent.sdk.ElasticApmInstrumentation;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

public class JettyHttpClientInstrumentation extends ElasticApmInstrumentation {
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
        return "co.elastic.apm.agent.jettyclient.plugin.JettyHttpClientInstrumentation$JettyHttpClientAdvice";
    }

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Arrays.asList("http-client", "jetty-client");
    }

    public static class JettyHttpClientAdvice {
        @Nullable
        @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
        public static Object onBeforeSend(@Advice.Argument(0) Request request,
                                          @Advice.Argument(1) List<Response.ResponseListener> responseListeners) {
            Transaction parent = ElasticApm.currentTransaction();
            if (parent.getId().isEmpty() || request == null) {
                return null;
            }
            Span ret = parent.startExitSpan("external", "http", "");
            String requestHost = request.getHost();
            if (requestHost == null) {
                URI uri = request.getURI();
                requestHost = uri.getHost();
            }
            ret.setName(request.getMethod() + " " + requestHost);
            ret.setDestinationAddress(requestHost, request.getPort());
            ret.injectTraceHeaders((headerName, headerValue) -> request.header(headerName, headerValue));
            responseListeners.add(new SpanResponseCompleteListenerWrapper(ret));
            ret.activate();
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
