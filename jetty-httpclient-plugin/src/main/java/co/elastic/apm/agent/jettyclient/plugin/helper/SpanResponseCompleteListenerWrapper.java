package co.elastic.apm.agent.jettyclient.plugin.helper;

import co.elastic.apm.api.Outcome;
import co.elastic.apm.api.Span;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

public class SpanResponseCompleteListenerWrapper implements Response.CompleteListener {

    private final Span span;

    public SpanResponseCompleteListenerWrapper(Span span) {
        this.span = span;
    }

    @Override
    public void onComplete(Result result) {
        if (span != null) {
            try {
                Response response = result.getResponse();
                Throwable t = result.getFailure();
                if (t != null) {
                    span.setOutcome(Outcome.FAILURE);
                }
                if (response != null && t == null) {
                    span.setOutcome(Outcome.SUCCESS);
                }
                span.captureException(t);
            } finally {
                span.end();
            }
        }
    }
}
